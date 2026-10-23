package com.localdoc.ai.domain

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.BookImporter
import com.localdoc.ai.book.Chapter
import com.localdoc.ai.book.DownloadCandidate
import com.localdoc.ai.book.DownloadLinkFinder
import com.localdoc.ai.book.FormatDetector
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.withoutAds
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.parser.LoginRequiredException
import com.localdoc.ai.parser.WebPage
import com.localdoc.ai.platform.FileAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** Источник страниц для скачивания: разбор главы и списка глав. */
interface ChapterSource {
    suspend fun load(url: String): WebPage
    suspend fun tableOfContents(tocUrl: String): List<PageLink>
}

/** Папка скачанных страниц: запись, чтение и удаление по префиксу имени. */
interface NovelFiles {
    fun write(name: String, bytes: ByteArray)
    fun read(name: String): ByteArray?
    fun deleteAll(prefix: String)
}

fun FileAccess.asNovelFiles(): NovelFiles = object : NovelFiles {
    override fun write(name: String, bytes: ByteArray) = writeAppFile(name, bytes)
    override fun read(name: String) = readAppFile(name)
    override fun deleteAll(prefix: String) = deleteAppFiles(prefix)
}

/** Скачивание готового файла книги, который отдаёт сайт; null — пришло не то (страница входа, ошибка). */
fun interface FileFetcher {
    suspend fun fetch(url: String): DownloadedFile?
}

class DownloadedFile(val name: String, val bytes: ByteArray)

/** Этап скачивания книги для показа пользователю. */
enum class DownloadPhase(val label: String) {
    LOOKING("Ищем готовый файл книги"),
    FILE("Скачиваем файл книги"),
    TOC("Загружаем оглавление"),
    CHAPTERS("Скачиваем главы"),
    BUILDING("Собираем книгу"),
    EXPORTING("Готовим файл"),
    SAVING("Сохраняем"),
    DONE("Готово"),
    FAILED("Ошибка"),
    CANCELLED("Отменено")
}

data class DownloadProgress(val phase: DownloadPhase, val done: Int = 0, val total: Int = 0, val message: String? = null)

/** Сбой, который нужно показать пользователю как есть (с подсказкой, что делать дальше). */
class NovelDownloadException(message: String) : IllegalStateException(message)

/** Итог: книга целиком. [original] — исходный файл, если сайт отдал книгу готовой. */
class NovelResult(val book: Book, val chapters: Int, val missing: Int, val original: DownloadedFile?)

@Serializable
private class NovelMeta(val title: String, val tocUrl: String, val chapters: List<ChapterRef>)

@Serializable
private class ChapterRef(val title: String, val url: String)

/**
 * Скачивание произведения целиком. Порядок такой, чтобы не листать книгу страница за страницей:
 *  1. сайт отдаёт книгу готовым файлом (EPUB, FB2…) — находим ссылку на странице, в оглавлении или
 *     в объявленных альтернативах и скачиваем штатно;
 *  2. иначе запрашиваем страницу ОГЛАВЛЕНИЯ (со всеми её частями) и загружаем главы по списку;
 *  3. только если оглавления нет — идём по ссылкам «Далее» от текущей главы.
 * Каждая загруженная глава сразу пишется в папку `novels/<id>/`, поэтому после обрыва или отмены
 * скачивание продолжается с того места, где остановилось. Реклама из текста убирается.
 */
class NovelDownloader(
    private val source: ChapterSource,
    private val fetcher: FileFetcher,
    private val files: NovelFiles,
    private val pause: suspend () -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(startUrl: String, tocUrl: String?, knownToc: List<PageLink>, progress: (DownloadProgress) -> Unit): NovelResult {
        progress(DownloadProgress(DownloadPhase.LOOKING))
        val start = source.load(startUrl)
        // Страница оглавления / описания: на ней название книги, обложка и ссылки «скачать»
        val tocPage = if (tocUrl != null && tocUrl != start.book.source) runCatching { source.load(tocUrl) }.getOrNull() else null

        directBook(start, tocPage, progress)?.let { return it }

        progress(DownloadProgress(DownloadPhase.TOC))
        val toc = knownToc.ifEmpty { tocUrl?.let { source.tableOfContents(it) }.orEmpty() }.distinctBy { it.url }
        val chapters = if (toc.size >= MIN_TOC) orderChapters(toc) else walkNextLinks(start, progress)
        if (chapters.isEmpty()) throw NovelDownloadException("Не удалось найти главы: у страницы нет оглавления и ссылки «Далее»")

        val title = tocPage?.book?.title?.trim()?.takeIf { it.isNotEmpty() } ?: NovelTitles.guess(start.book.title, start.book.source)
        val folder = "novels/" + folderId(tocUrl ?: startUrl) + "/"
        val meta = NovelMeta(title, tocUrl.orEmpty(), chapters.map { ChapterRef(it.text, it.url) })
        files.write(folder + "meta.json", json.encodeToString(NovelMeta.serializer(), meta).encodeToByteArray())

        val loaded = fetchChapters(folder, chapters, start, progress)
        progress(DownloadProgress(DownloadPhase.BUILDING, chapters.size, chapters.size))
        return assemble(title, tocUrl ?: startUrl, tocPage ?: start, chapters, loaded)
    }

    // ---------- 1. готовый файл ----------

    private suspend fun directBook(start: WebPage, tocPage: WebPage?, progress: (DownloadProgress) -> Unit): NovelResult? {
        val links = ArrayList(start.downloads + start.links)
        // Ссылка «скачать» часто лежит на странице описания / оглавления, а не на странице главы
        tocPage?.let { links.addAll(it.downloads + it.links) }
        for (candidate in DownloadLinkFinder.find(links).take(MAX_CANDIDATES)) {
            coroutineContext.ensureActive()
            progress(DownloadProgress(DownloadPhase.FILE, message = candidate.label.ifBlank { candidate.url }))
            val downloaded = tryDownload(candidate) ?: continue
            return NovelResult(downloaded.first, chapters = downloaded.first.chapters.size, missing = 0, original = downloaded.second)
        }
        return null
    }

    private suspend fun tryDownload(candidate: DownloadCandidate): Pair<Book, DownloadedFile>? {
        val file = try {
            fetcher.fetch(candidate.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val format = FormatDetector.resolve(file.name, file.bytes)
            // PDF разбирается платформой по файлу, а не по байтам — такой файл просто сохраняется как есть
            if (format == BookFormat.PDF) return null
            val book = BookImporter.parseBytes(file.bytes, format, file.name.substringBeforeLast('.'), candidate.url)
            // Заглушка вроде «купите полную версию» не считается книгой
            if (book.plainText.length < MIN_BOOK_CHARS) null else book to file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 2. главы по оглавлению ----------

    /** Оглавление, где новые главы сверху: разворачиваем по номерам. */
    private fun orderChapters(toc: List<PageLink>): List<PageLink> {
        val first = NovelTitles.number(toc.first().text)
        val last = NovelTitles.number(toc.last().text)
        return if (first != null && last != null && first > last) toc.reversed() else toc
    }

    /** Запасной путь без оглавления: по ссылкам «Далее» от текущей главы. */
    private suspend fun walkNextLinks(start: WebPage, progress: (DownloadProgress) -> Unit): List<PageLink> {
        val result = ArrayList<PageLink>()
        val seen = HashSet<String>()
        var page: WebPage? = start
        while (page != null && seen.add(page.book.source) && result.size < MAX_CHAIN) {
            coroutineContext.ensureActive()
            result.add(PageLink(page.book.title, page.book.source))
            progress(DownloadProgress(DownloadPhase.TOC, result.size, 0, "Ищем следующие главы"))
            val next = page.book.web?.next ?: break
            page = try {
                source.load(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            pause()
        }
        return result
    }

    private suspend fun fetchChapters(folder: String, chapters: List<PageLink>, start: WebPage, progress: (DownloadProgress) -> Unit): Map<Int, Book?> {
        val loaded = HashMap<Int, Book?>()
        var consecutiveFailures = 0
        // Уже скачанное в прошлый раз берём с диска — это и есть докачка
        // Файл главы назван по адресу, а не по номеру: если оглавление изменилось (главу вставили, убрали,
        // переставили), чужой текст в книгу не попадёт
        for ((i, link) in chapters.withIndex()) {
            files.read(chapterFile(folder, link.url))?.let { bytes ->
                runCatching { json.decodeFromString(Book.serializer(), bytes.decodeToString()) }.getOrNull()
                    ?.takeIf { it.source == link.url }?.let { loaded[i] = it }
            }
        }
        progress(DownloadProgress(DownloadPhase.CHAPTERS, loaded.size, chapters.size))
        val pending = chapters.indices.filter { it !in loaded }
        for (batch in pending.chunked(BATCH)) {
            coroutineContext.ensureActive()
            val results = coroutineScope {
                batch.map { i -> async { i to fetchOne(chapters[i], start) } }.awaitAll()
            }
            for ((i, outcome) in results) {
                when (outcome) {
                    is ChapterOutcome.Ok -> {
                        consecutiveFailures = 0
                        loaded[i] = outcome.book
                        files.write(chapterFile(folder, chapters[i].url), json.encodeToString(Book.serializer(), outcome.book).encodeToByteArray())
                    }
                    is ChapterOutcome.Missing -> {
                        loaded[i] = null
                        consecutiveFailures = 0
                    }
                    is ChapterOutcome.Failed -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            throw NovelDownloadException(
                                "Сайт перестал отвечать на главе «${chapters[i].text}»: ${outcome.reason}. " +
                                    "Уже скачанное сохранено — нажмите «Скачать» ещё раз, и загрузка продолжится."
                            )
                        }
                    }
                }
            }
            progress(DownloadProgress(DownloadPhase.CHAPTERS, loaded.size, chapters.size))
            pause()
        }
        return loaded
    }

    private sealed interface ChapterOutcome {
        class Ok(val book: Book) : ChapterOutcome
        data object Missing : ChapterOutcome
        class Failed(val reason: String) : ChapterOutcome
    }

    private suspend fun fetchOne(link: PageLink, start: WebPage): ChapterOutcome {
        if (link.url == start.book.source) return ChapterOutcome.Ok(start.book)
        var lastError = "нет ответа"
        repeat(ATTEMPTS) { attempt ->
            try {
                return ChapterOutcome.Ok(source.load(link.url).book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginRequiredException) {
                throw NovelDownloadException("Для скачивания нужен вход на сайт. Войдите и повторите — скачанные главы сохранены.")
            } catch (e: HttpStatusException) {
                // Глава удалена или закрыта — книга собирается без неё
                if (e.status == 404 || e.status == 410 || e.status == 403) return ChapterOutcome.Missing
                lastError = e.message.orEmpty()
            } catch (e: Exception) {
                lastError = e.message ?: "ошибка сети"
            }
            if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return ChapterOutcome.Failed(lastError)
    }

    // ---------- 3. сборка ----------

    private fun assemble(title: String, sourceUrl: String, start: WebPage, chapters: List<PageLink>, loaded: Map<Int, Book?>): NovelResult {
        val blocks = ArrayList<Block>()
        val outline = ArrayList<Chapter>()
        var missing = 0
        for ((i, link) in chapters.withIndex()) {
            val chapterTitle = link.text.ifBlank { "Глава ${i + 1}" }
            outline.add(Chapter(chapterTitle.take(120), blocks.size))
            blocks.add(Block(BlockType.HEADING, chapterTitle, 1))
            val book = loaded[i]
            if (book == null) {
                missing++
                blocks.add(Block(BlockType.PARAGRAPH, "Эту главу не удалось скачать: страница недоступна."))
                continue
            }
            // Заголовок самой страницы совпадает с названием главы — второй раз не повторяем
            blocks.addAll(book.blocks.dropWhile { it.type == BlockType.HEADING && NovelTitles.sameTitle(it.text, chapterTitle) })
        }
        val merged = Book(
            title = title,
            author = start.book.author,
            format = BookFormat.EPUB,
            source = sourceUrl,
            blocks = blocks,
            chapters = outline,
            cover = start.book.cover
        ).withoutAds()
        return NovelResult(merged, chapters.size, missing, original = null)
    }

    private fun chapterFile(folder: String, url: String) = folder + folderId(url) + ".json"

    /** Удаляет папку со скачанными страницами произведения. */
    fun forget(tocOrStartUrl: String) = files.deleteAll("novels/" + folderId(tocOrStartUrl) + "/")

    companion object {
        // Паузы между запросами к сайту выдерживает общий клиент (WebHttp / HostThrottle); главы идут по одной
        private const val RETRY_DELAY_MS = 2_000L
        private const val ATTEMPTS = 3
        private const val BATCH = 1
        private const val MAX_CONSECUTIVE_FAILURES = 6
        private const val MAX_CANDIDATES = 3
        private const val MIN_TOC = 2
        private const val MIN_BOOK_CHARS = 2000
        private const val MAX_CHAIN = 3000

        /** Имя папки произведения: 64-битный FNV-1a адреса оглавления. */
        fun folderId(url: String): String {
            var h = -0x340d631b7bdddcdbL
            for (b in url.encodeToByteArray()) h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
            return h.toULong().toString(16).padStart(16, '0')
        }
    }
}

/** Названия глав и произведений: номер главы, название книги без хвоста «— Глава 12». */
object NovelTitles {
    private val CHAPTER_TAIL = Regex("\\s*[-–—|:·]\\s*(глава|chapter|ch\\.?|часть|том)\\s*\\d+.*$", RegexOption.IGNORE_CASE)
    private val CHAPTER_HEAD = Regex("^(глава|chapter|ch\\.?)\\s*\\d+\\s*[-–—|:.]?\\s*", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("\\d+")

    fun number(text: String): Int? = NUMBER.find(text)?.value?.toIntOrNull()

    fun guess(pageTitle: String, url: String): String {
        val cleaned = pageTitle.replace(CHAPTER_TAIL, "").trim().ifBlank { pageTitle.trim() }
        // «Глава 5. Встреча — Новелла»: название книги — часть без слова «Глава»
        val parts = cleaned.split(Regex("\\s[-–—|]\\s")).map { it.trim() }.filter { it.isNotEmpty() }
        val book = parts.lastOrNull { !CHAPTER_HEAD.containsMatchIn(it) && !it.contains("Глава", ignoreCase = true) } ?: parts.firstOrNull()
        return (book ?: url.substringAfter("://").substringBefore('/')).take(120)
    }

    fun sameTitle(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val x = norm(a)
        val y = norm(b)
        return x == y || x.contains(y) || y.contains(x)
    }
}
