package com.localdoc.ai.domain

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.PageLink
import com.localdoc.ai.book.WebNavigation
import com.localdoc.ai.net.HttpStatusException
import com.localdoc.ai.parser.LoginRequiredException
import com.localdoc.ai.parser.WebPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryNovelFiles : NovelFiles {
    val data = HashMap<String, ByteArray>()
    override fun write(name: String, bytes: ByteArray) { data[name] = bytes }
    override fun read(name: String) = data[name]
    override fun deleteAll(prefix: String) { data.keys.filter { it.startsWith(prefix) }.forEach(data::remove) }
}

/** Сайт из памяти: главы /ch/N, оглавление /toc; считает, сколько раз открывалась каждая страница. */
class FakeSite(val chapters: Int, private val links: Map<String, List<PageLink>> = emptyMap()) : ChapterSource {
    val loads = HashMap<String, Int>()
    var failing = HashSet<String>()
    var status = HashMap<String, Int>()
    var loginRequired = false
    var tocCalls = 0
    var withNextLinks = false

    private fun chapterUrl(i: Int) = "https://site.example/ch/$i"

    override suspend fun load(url: String): WebPage {
        loads[url] = (loads[url] ?: 0) + 1
        if (loginRequired && url.startsWith("https://site.example/ch/")) throw LoginRequiredException(url, null)
        status[url]?.let { throw HttpStatusException(it, url) }
        if (url in failing) error("сеть недоступна")
        val n = url.substringAfterLast('/').toIntOrNull() ?: 0
        val next = if (withNextLinks && n < chapters) chapterUrl(n + 1) else null
        val book = Book(
            title = if (n > 0) "Моя новелла - Глава $n" else "Моя новелла",
            format = BookFormat.WEB, source = url,
            blocks = listOf(
                Block(BlockType.HEADING, "Глава $n", 1),
                Block(BlockType.PARAGRAPH, "Текст главы $n. " + "Слова. ".repeat(20)),
                Block(BlockType.PARAGRAPH, "Подписывайтесь на канал в Telegram")
            ),
            web = WebNavigation(next = next, toc = "https://site.example/toc")
        )
        return WebPage(book, links[url].orEmpty(), emptyList())
    }

    override suspend fun tableOfContents(tocUrl: String): List<PageLink> {
        tocCalls++
        return (1..chapters).map { PageLink("Глава $it", chapterUrl(it)) }
    }
}

class NovelDownloaderTest {

    private fun downloader(site: ChapterSource, files: NovelFiles = MemoryNovelFiles(), fetcher: FileFetcher = FileFetcher { null }) =
        NovelDownloader(site, fetcher, files, pause = {})

    @Test
    fun `chapters come from the table of contents without paging through the book`() = runTest {
        val site = FakeSite(6)
        val progress = ArrayList<DownloadProgress>()
        val result = downloader(site).run("https://site.example/ch/3", "https://site.example/toc", emptyList()) { progress.add(it) }

        assertEquals(1, site.tocCalls, "оглавление запрошено один раз")
        // Каждая глава открыта один раз — по списку оглавления, без обхода ссылок «Далее»
        assertEquals((1..6).map { "https://site.example/ch/$it" }.toSet() + "https://site.example/toc", site.loads.keys)
        assertTrue(site.loads.values.all { it == 1 }, site.loads.toString())

        val book = result.book
        assertEquals("Моя новелла", book.title)
        assertEquals(6, result.chapters)
        assertEquals((1..6).map { "Глава $it" }, book.chapters.map { it.title })
        assertEquals(6, book.blocks.count { it.type == BlockType.HEADING }, "заголовок главы не дублируется")
        assertTrue(book.blocks.none { it.text.contains("Telegram") }, "реклама убрана")
        assertEquals(6, progress.last { it.phase == DownloadPhase.CHAPTERS }.done)
    }

    @Test
    fun `download resumes from saved chapters after a failure`() = runTest {
        val files = MemoryNovelFiles()
        val site = FakeSite(12)
        // Главы 4–12 недоступны: 6 неудач подряд — сайт «упал», загрузка останавливается с подсказкой
        (4..12).forEach { site.failing.add("https://site.example/ch/$it") }
        val error = assertFailsWith<NovelDownloadException> {
            downloader(site, files).run("https://site.example/ch/1", "https://site.example/toc", emptyList()) {}
        }
        assertTrue("продолжится" in error.message.orEmpty(), error.message)
        val saved = files.data.keys.count { it.endsWith(".json") && !it.endsWith("meta.json") }
        assertEquals(3, saved, "главы 1–3 уже на диске")

        site.failing.clear()
        site.loads.clear()
        val result = downloader(site, files).run("https://site.example/ch/1", "https://site.example/toc", emptyList()) {}
        assertEquals(12, result.chapters)
        assertNull(site.loads["https://site.example/ch/2"], "сохранённые главы не скачиваются повторно")
        assertNotNull(site.loads["https://site.example/ch/4"])
    }

    @Test
    fun `a ready made file offered by the site is preferred over crawling`() = runTest {
        val text = ("Абзац книги. ".repeat(40) + "\n\n").repeat(10).encodeToByteArray()
        val link = PageLink("Скачать книгу (txt)", "https://site.example/files/book.txt")
        val site = FakeSite(4, mapOf("https://site.example/ch/2" to listOf(link)))
        var fetched = 0
        val result = downloader(site, fetcher = { url ->
            fetched++
            assertEquals("https://site.example/files/book.txt", url)
            DownloadedFile("book.txt", text)
        }).run("https://site.example/ch/2", "https://site.example/toc", emptyList()) {}

        assertEquals(1, fetched)
        assertNotNull(result.original)
        assertEquals(0, site.tocCalls, "оглавление не нужно, если есть готовый файл")
        assertTrue(result.book.plainText.contains("Абзац книги"))
    }

    @Test
    fun `a stub instead of a book falls back to chapters`() = runTest {
        val link = PageLink("Скачать", "https://site.example/download/epub")
        val site = FakeSite(3, mapOf("https://site.example/ch/1" to listOf(link)))
        val result = downloader(site, fetcher = { DownloadedFile("stub.txt", "Купите полную версию".encodeToByteArray()) })
            .run("https://site.example/ch/1", "https://site.example/toc", emptyList()) {}
        assertEquals(3, result.chapters)
        assertNull(result.original)
    }

    @Test
    fun `without a table of contents the next links are followed`() = runTest {
        val site = FakeSite(4).also { it.withNextLinks = true }
        val result = downloader(site).run("https://site.example/ch/1", null, emptyList()) {}
        assertEquals(4, result.chapters)
        assertEquals(0, site.tocCalls)
    }

    @Test
    fun `newest first table of contents is reversed and missing chapters are marked`() = runTest {
        val site = FakeSite(3)
        site.status["https://site.example/ch/2"] = 404
        val toc = (3 downTo 1).map { PageLink("Глава $it", "https://site.example/ch/$it") }
        val result = downloader(site).run("https://site.example/ch/1", "https://site.example/toc", toc) {}
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3"), result.book.chapters.map { it.title })
        assertEquals(1, result.missing)
        assertTrue(result.book.blocks.any { it.text.contains("не удалось скачать") })
    }

    @Test
    fun `login required is reported with a hint`() = runTest {
        val site = FakeSite(3)
        val start = site.load("https://site.example/ch/1")
        site.loginRequired = true
        val wrapper = object : ChapterSource {
            override suspend fun load(url: String) = if (url.endsWith("/ch/1")) start else site.load(url)
            override suspend fun tableOfContents(tocUrl: String) = site.tableOfContents(tocUrl)
        }
        val error = assertFailsWith<NovelDownloadException> {
            downloader(wrapper).run("https://site.example/ch/1", "https://site.example/toc", emptyList()) {}
        }
        assertTrue("вход" in error.message.orEmpty())
    }

    @Test
    fun `titles are guessed without chapter tails`() {
        assertEquals("Моя новелла", NovelTitles.guess("Моя новелла - Глава 12", "u"))
        assertEquals("Моя новелла", NovelTitles.guess("Глава 5. Встреча - Моя новелла", "u"))
        assertEquals("Просто книга", NovelTitles.guess("Просто книга", "u"))
        assertEquals(12, NovelTitles.number("Глава 12: старт"))
    }
}
