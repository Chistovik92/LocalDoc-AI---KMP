package com.localdoc.ai.search

import com.localdoc.ai.domain.NovelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Разбиение текста на слова для индекса и запроса: буквы и цифры, нижний регистр, «ё» = «е». */
object SearchTokenizer {

    private const val MIN_TOKEN = 2

    fun tokens(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_TOKEN) result.add(current.toString())
            current.clear()
        }
        for (ch in text) {
            if (ch.isLetterOrDigit()) current.append(normalize(ch)) else flush()
        }
        flush()
        return result
    }

    private fun normalize(ch: Char): Char = when (val lower = ch.lowercaseChar()) {
        'ё' -> 'е'
        else -> lower
    }

    /**
     * Основа слова запроса для поиска по началу слова: у слов от четырёх букв отбрасываются 1–2 последние,
     * поэтому «книга» находит «книги», «книгой», «книгах». Короткие слова ищутся точно.
     */
    fun stem(token: String): String = when {
        token.length >= 7 -> token.dropLast(2)
        token.length >= 4 -> token.dropLast(1)
        else -> token
    }
}

/** Найденное место: документ, единица индекса (блок книги) и ссылка на элемент (для сводных документов, например каталога). */
data class IndexHit(val docId: String, val unit: Int, val ref: String?)

@Serializable
private class Shard(
    val signature: String,
    /** Слово → номера единиц, отсортированные и записанные разностями через запятую. */
    val terms: Map<String, String>,
    /** Для сводных документов (каталог): единица i — элемент refs[i]. */
    val refs: List<String> = emptyList()
)

/**
 * Полнотекстовый индекс на диске: на каждый документ (книгу) свой файл с обратным индексом «слово → блоки».
 * Обновление инкрементальное: файл пересобирается только у изменившейся книги (по подписи `signature`),
 * у удалённой — стирается. Поиск читает файлы по одному, поэтому память не растёт с размером библиотеки.
 *
 * Манифест (какие документы проиндексированы и с какой подписью) хранится строкой через [loadManifest]/[saveManifest].
 */
class FullTextIndex(
    private val files: NovelFiles,
    private val loadManifest: () -> String?,
    private val saveManifest: (String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val manifestSerializer = MapSerializer(String.serializer(), String.serializer())
    private var manifest: MutableMap<String, String> = readManifest()
    private val cache = LinkedHashMap<String, Shard>()

    private var batchDepth = 0
    private var manifestDirty = false

    /**
     * Группа изменений с одной записью манифеста в конце: без неё каждая из тысяч книг сериализовала бы весь
     * манифест заново (квадратичный рост). Обрыв посреди группы безопасен: файлы индекса без записи в манифесте
     * при следующей синхронизации просто строятся заново.
     */
    suspend fun <T> batch(block: suspend () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && manifestDirty) persistManifest()
        }
    }

    /** Запись после индексации: внутри [batch] откладывается до его конца. */
    private fun persistManifest() {
        if (batchDepth > 0) manifestDirty = true else flushManifest()
    }

    /**
     * Немедленная запись. Нужна удалению и инвалидации: если процесс оборвётся до конца группы, отложенная запись
     * потеряла бы «книга изменилась», и устаревший индекс считался бы актуальным (подпись книги не меняется).
     */
    private fun flushManifest() {
        manifestDirty = false
        saveManifest(json.encodeToString(manifestSerializer, manifest))
    }

    fun indexedDocs(): Set<String> = manifest.keys.toSet()

    fun isCurrent(docId: String, signature: String): Boolean = manifest[docId] == signature

    /** Индексирует документ, если подпись изменилась; true — индекс обновлён. [units] — тексты блоков по порядку. */
    suspend fun index(docId: String, signature: String, units: List<String>, refs: List<String> = emptyList()): Boolean {
        if (isCurrent(docId, signature) && files.read(fileName(docId)) != null) return false
        val postings = HashMap<String, MutableList<Int>>()
        units.forEachIndexed { unit, text ->
            for (token in SearchTokenizer.tokens(text).toSet()) postings.getOrPut(token) { ArrayList() }.add(unit)
            if (unit % YIELD_EVERY == 0) yield()
        }
        val shard = Shard(signature, postings.mapValues { encode(it.value) }, refs)
        files.write(fileName(docId), json.encodeToString(Shard.serializer(), shard).encodeToByteArray())
        cache[docId] = shard
        trimCache()
        manifest[docId] = signature
        persistManifest()
        return true
    }

    fun remove(docId: String) {
        files.deleteAll(fileName(docId))
        cache.remove(docId)
        if (manifest.remove(docId) != null) flushManifest()
    }

    /** Забывает документ: при следующей синхронизации он будет проиндексирован заново. */
    fun invalidate(docId: String) {
        if (manifest.remove(docId) != null) flushManifest()
        cache.remove(docId)
    }

    /** Стирает весь индекс (после восстановления из резервной копии книги другие, индекс строится заново). */
    fun clear() {
        manifest.keys.toList().forEach { files.deleteAll(fileName(it)) }
        manifest.clear()
        cache.clear()
        flushManifest()
    }

    /**
     * Ищет блоки, где есть все слова запроса (по началу слова, см. [SearchTokenizer.stem]). Результаты — по документам
     * в порядке числа совпавших блоков; в документе не больше [perDoc] мест, всего не больше [limit].
     */
    suspend fun search(query: String, docs: Collection<String> = indexedDocs(), perDoc: Int = 3, limit: Int = 60): List<IndexHit> {
        val stems = SearchTokenizer.tokens(query).map(SearchTokenizer::stem).distinct()
        if (stems.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, List<IndexHit>>>()
        for (docId in docs) {
            val shard = shard(docId) ?: continue
            val units = matchingUnits(shard, stems)
            if (units.isEmpty()) continue
            found.add(units.size to units.take(perDoc).map { IndexHit(docId, it, shard.refs.getOrNull(it)) })
            yield()
        }
        return found.sortedByDescending { it.first }.flatMap { it.second }.take(limit)
    }

    private fun matchingUnits(shard: Shard, stems: List<String>): List<Int> {
        var result: Set<Int>? = null
        for (stem in stems) {
            val units = HashSet<Int>()
            for ((term, postings) in shard.terms) if (term.startsWith(stem)) units.addAll(decode(postings))
            result = result?.intersect(units) ?: units
            if (result.isEmpty()) return emptyList()
        }
        return result.orEmpty().sorted()
    }

    private fun shard(docId: String): Shard? {
        cache.remove(docId)?.let { cache[docId] = it; return it }
        val bytes = files.read(fileName(docId)) ?: return null
        val shard = try {
            json.decodeFromString(Shard.serializer(), bytes.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Повреждённый файл индекса: документ будет проиндексирован заново
            invalidate(docId)
            return null
        }
        cache[docId] = shard
        trimCache()
        return shard
    }

    private fun trimCache() {
        while (cache.size > CACHED_SHARDS) cache.remove(cache.keys.first())
    }

    private fun readManifest(): MutableMap<String, String> =
        loadManifest()?.let { runCatching { json.decodeFromString(manifestSerializer, it) }.getOrNull() }?.toMutableMap() ?: HashMap()

    private fun fileName(docId: String) = "idx.$docId.json"

    private fun encode(units: List<Int>): String {
        var previous = 0
        return units.joinToString(",") { (it - previous).also { _ -> previous = it }.toString() }
    }

    private fun decode(postings: String): List<Int> {
        var acc = 0
        return postings.split(',').mapNotNull { it.toIntOrNull() }.map { acc += it; acc }
    }

    private companion object {
        const val YIELD_EVERY = 200
        const val CACHED_SHARDS = 6
    }
}

/** Фрагмент текста вокруг первого найденного слова — для показа в результатах поиска. */
object Snippets {

    private const val RADIUS = 70

    fun around(text: String, query: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val stems = SearchTokenizer.tokens(query).map(SearchTokenizer::stem)
        val lower = clean.lowercase().replace('ё', 'е')
        val at = stems.mapNotNull { stem -> lower.indexOf(stem).takeIf { it >= 0 } }.minOrNull() ?: 0
        val from = (at - RADIUS).coerceAtLeast(0)
        val to = (at + RADIUS).coerceAtMost(clean.length)
        return (if (from > 0) "…" else "") + clean.substring(from, to).trim() + (if (to < clean.length) "…" else "")
    }
}
