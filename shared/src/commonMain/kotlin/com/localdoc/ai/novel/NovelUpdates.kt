package com.localdoc.ai.novel

import com.localdoc.ai.book.PageLink
import com.localdoc.ai.domain.ChapterSource
import com.localdoc.ai.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Сравнение списков глав: адреса приводятся к одному виду, повторы выбрасываются. */
object ChapterDiff {
    private val NOISE_PARAMS = setOf("fbclid", "gclid", "yclid", "mc_cid", "mc_eid", "ref", "ref_src")

    /** Одинаковая глава под разными адресами: `http`/`https`, `www.`, `#якорь`, `/` в конце, `utm_*`, порядок параметров. */
    fun normalizeUrl(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val rest = noFragment.substringAfter("://", noFragment)
        val authority = rest.substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase()
            .removePrefix("www.").removeSuffix(":80").removeSuffix(":443")
        val afterAuthority = rest.removePrefix(rest.substringBefore('/').substringBefore('?'))
        val path = afterAuthority.substringBefore('?').trimEnd('/')
        val query = afterAuthority.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .filterNot { p ->
                val name = p.substringBefore('=').lowercase()
                name.startsWith("utm_") || name in NOISE_PARAMS
            }
            .sorted()
            .joinToString("&")
        return "https://$authority$path" + if (query.isEmpty()) "" else "?$query"
    }

    /** Оглавление без повторов (по нормализованному адресу); остаётся первое упоминание. */
    fun dedupe(links: List<PageLink>): List<PageLink> {
        val seen = HashSet<String>()
        return links.filter { seen.add(normalizeUrl(it.url)) }
    }

    /**
     * [newChapters] — главы из [fresh], которых нет среди [known] (нормализованных адресов), в порядке оглавления.
     * [restructured] — сайт сменил адреса целиком (ни одной известной главы в новом списке): новыми это не считается,
     * иначе пользователь получил бы уведомление о «сотне новых глав».
     */
    class Diff(val newChapters: List<PageLink>, val restructured: Boolean)

    fun diff(known: Collection<String>, fresh: List<PageLink>): Diff {
        val chapters = dedupe(fresh)
        if (known.isEmpty() || chapters.isEmpty()) return Diff(emptyList(), false)
        val knownSet = known.toHashSet()
        val overlap = chapters.count { normalizeUrl(it.url) in knownSet }
        if (overlap == 0 && known.size >= MIN_FOR_RESTRUCTURE && chapters.size >= MIN_FOR_RESTRUCTURE) return Diff(emptyList(), true)
        return Diff(chapters.filter { normalizeUrl(it.url) !in knownSet }, false)
    }

    private const val MIN_FOR_RESTRUCTURE = 3
}

@Serializable
data class NewChapter(val title: String, val url: String)

/** Скачанная новелла, за которой следит автообновление. [knownUrls] — нормализованные адреса глав, о которых уже известно. */
@Serializable
data class TrackedNovel(
    val id: String,
    val title: String,
    val tocUrl: String,
    val knownUrls: List<String>,
    val autoCheck: Boolean = true,
    val lastCheckedMs: Long = 0L,
    val lastError: String? = null,
    /** Найденные, но ещё не скачанные главы. */
    val pending: List<NewChapter> = emptyList()
)

/** Список отслеживаемых новелл в KV (JSON). Хранилище передаётся функциями — удобно для тестов. */
class NovelTracker(private val load: () -> String?, private val save: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TrackedNovel.serializer())
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<TrackedNovel>> = _all.asStateFlow()

    fun reload() {
        _all.value = read()
    }

    /** Добавляет новеллу или обновляет её запись (выбор «следить» и найденные главы сохраняются, известные главы объединяются). */
    fun register(id: String, title: String, tocUrl: String, knownUrls: List<String>) = change { list ->
        val old = list.firstOrNull { it.id == id }
        val merged = ((old?.knownUrls.orEmpty()) + knownUrls).distinct()
        val next = old?.copy(title = title, tocUrl = tocUrl, knownUrls = merged) ?: TrackedNovel(id, title, tocUrl, merged)
        list.filterNot { it.id == id } + next
    }

    fun setAutoCheck(id: String, enabled: Boolean) = update(id) { it.copy(autoCheck = enabled) }

    fun remove(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Итог проверки: новый базовый список, найденные главы и ошибка (null — проверка прошла). */
    fun recordCheck(id: String, nowMs: Long, knownUrls: List<String>, pending: List<NewChapter>, error: String?) =
        update(id) { it.copy(lastCheckedMs = nowMs, knownUrls = knownUrls, pending = pending, lastError = error) }

    fun recordError(id: String, nowMs: Long, error: String) = update(id) { it.copy(lastCheckedMs = nowMs, lastError = error) }

    /** Главы с адресами [urls] скачаны: они известны и больше не ждут. */
    fun markDownloaded(id: String, urls: Collection<String>) = update(id) { novel ->
        val done = urls.toHashSet()
        novel.copy(
            knownUrls = (novel.knownUrls + urls).distinct(),
            pending = novel.pending.filterNot { ChapterDiff.normalizeUrl(it.url) in done }
        )
    }

    private fun update(id: String, transform: (TrackedNovel) -> TrackedNovel) =
        change { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun change(transform: (List<TrackedNovel>) -> List<TrackedNovel>) {
        val next = transform(_all.value)
        _all.value = next
        save(json.encodeToString(serializer, next))
    }

    private fun read(): List<TrackedNovel> =
        load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
}

/** Новые главы одной новеллы, найденные проверкой. */
class NovelUpdate(val id: String, val title: String, val newChapters: List<PageLink>)

/**
 * Итог одной проверки. [throttledHosts] — сайты, отказавшие (429 / 503): их остальные новеллы не проверялись,
 * расписание откладывает следующую попытку. [skippedByRobots] — названия новелл, которые `robots.txt` не разрешает читать автоматически.
 */
class UpdateReport(
    val checked: Int,
    val failed: Int,
    val updates: List<NovelUpdate>,
    val throttledHosts: Set<String>,
    val skippedByRobots: List<String>
) {
    val throttled: Boolean get() = throttledHosts.isNotEmpty()
    val newChapterCount: Int get() = updates.sumOf { it.newChapters.size }
}

/**
 * Проверка новых глав у отслеживаемых новелл. Запросы идут через общий клиент, поэтому паузы и `Crawl-delay`
 * соблюдаются сами; здесь — только решение, что проверять, и разбор отказов: после 429 / 503 сайт больше не трогаем.
 */
class NovelUpdateChecker(
    private val tracker: NovelTracker,
    private val source: ChapterSource,
    private val allowed: suspend (url: String) -> Boolean = { true },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) {
    suspend fun checkAll(): UpdateReport {
        var checked = 0
        var failed = 0
        val updates = ArrayList<NovelUpdate>()
        val throttled = LinkedHashSet<String>()
        val skipped = ArrayList<String>()

        for (novel in tracker.all.value.filter { it.autoCheck }) {
            val host = hostOf(novel.tocUrl)
            if (host in throttled) continue
            if (!allowed(novel.tocUrl)) {
                skipped.add(novel.title)
                continue
            }
            val fresh = try {
                source.tableOfContents(novel.tocUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.status == 429 || e.status == 503) {
                    throttled.add(host)
                } else {
                    failed++
                    tracker.recordError(novel.id, now(), e.message ?: "Ошибка HTTP ${e.status}")
                }
                continue
            } catch (e: Exception) {
                failed++
                tracker.recordError(novel.id, now(), e.message ?: "Не удалось загрузить оглавление")
                continue
            }
            checked++
            val diff = ChapterDiff.diff(novel.knownUrls, fresh)
            val freshUrls = ChapterDiff.dedupe(fresh).map { ChapterDiff.normalizeUrl(it.url) }
            if (diff.restructured) {
                tracker.recordCheck(novel.id, now(), freshUrls, emptyList(), null)
                continue
            }
            val pendingUrls = novel.pending.map { ChapterDiff.normalizeUrl(it.url) }.toHashSet()
            val pending = novel.pending + diff.newChapters.filter { ChapterDiff.normalizeUrl(it.url) !in pendingUrls }.map { NewChapter(it.text, it.url) }
            tracker.recordCheck(novel.id, now(), (novel.knownUrls + freshUrls).distinct(), pending, null)
            if (diff.newChapters.isNotEmpty()) updates.add(NovelUpdate(novel.id, novel.title, diff.newChapters))
        }
        return UpdateReport(checked, failed, updates, throttled, skipped)
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase()
}
