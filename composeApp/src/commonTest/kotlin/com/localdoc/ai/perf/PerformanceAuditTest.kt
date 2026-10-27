package com.localdoc.ai.perf

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.BookImporter
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.ChapterDetector
import com.localdoc.ai.domain.NovelFiles
import com.localdoc.ai.search.FullTextIndex
import com.localdoc.ai.search.LibrarySearch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Аудит производительности 2.0.0 на синтетических больших данных. Пределы по времени очень щедрые (в разы
 * выше измеренного), чтобы тест ловил порядки величин — квадратичный рост, а не колебания машины.
 * Измеренные значения и выводы — docs/PERFORMANCE_AUDIT.md.
 */
class PerformanceAuditTest {

    private class MemoryFiles : NovelFiles {
        val data = HashMap<String, ByteArray>()
        override fun write(name: String, bytes: ByteArray) { data[name] = bytes }
        override fun read(name: String) = data[name]
        override fun deleteAll(prefix: String) { data.keys.filter { it.startsWith(prefix) }.forEach(data::remove) }
    }

    private val words = listOf("море", "город", "ветер", "дорога", "огонь", "письмо", "ночь", "камень", "река", "лес", "книга", "дверь")

    private fun paragraph(seed: Int): String = (0 until 18).joinToString(" ") { words[(seed * 7 + it * 3) % words.size] + (seed % 97) }

    private inline fun <T> timed(label: String, block: () -> T): Pair<T, Long> {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        val ms = mark.elapsedNow().inWholeMilliseconds
        println("PERF $label: $ms мс")
        return result to ms
    }

    private class Library(count: Int, blocksPerBook: Int) {
        val saves = IntArray(1)
        val files = MemoryFiles()
        var manifest: String? = null
        val entries = (0 until count).map { BookEntry("b$it", "Книга $it", "Автор ${it % 50}", BookFormat.EPUB, "src$it", blocksPerBook) }
        val search = LibrarySearch(
            FullTextIndex(files, { manifest }, { manifest = it; saves[0]++ }),
            { entries },
            { id ->
                val n = id.drop(1).toInt()
                (0 until blocksPerBook).map { b -> (0 until 18).joinToString(" ") { w -> "слово${(n * 31 + b * 7 + w) % 400}" } }
            },
            { emptyList() }
        )
    }

    @Test
    fun `indexing a big library does not rewrite the manifest for every book`() = runTest {
        val library = Library(count = 400, blocksPerBook = 5)
        library.search.sync()
        // Квадратичная запись манифеста: при тысячах книг каждая индексация сериализовала бы весь список заново
        assertTrue(library.saves[0] <= 5, "манифест записан ${library.saves[0]} раз(а) на 400 книг")
    }

    @Test
    fun `library of 1500 books indexes and searches in bounded time`() = runTest {
        val library = Library(count = 1500, blocksPerBook = 20)
        val (updated, indexMs) = timed("индексация 1500 книг x 20 блоков") { library.search.sync() }
        assertEquals(1500, updated, "каталог пуст, обновлены только книги")
        assertTrue(indexMs < 60_000, "индексация заняла $indexMs мс")

        val (results, searchMs) = timed("поиск по 1500 книгам") { library.search.search("слово17 слово18") }
        assertTrue(results.books.isNotEmpty())
        assertTrue(searchMs < 20_000, "поиск занял $searchMs мс")

        val (again, resyncMs) = timed("повторная синхронизация без изменений") { library.search.sync() }
        assertEquals(0, again)
        assertTrue(resyncMs < 5_000, "повторная синхронизация заняла $resyncMs мс")
    }

    @Test
    fun `library list of 5000 entries serializes quickly`() {
        val entries = (0 until 5000).map {
            BookEntry("id$it", "Название книги номер $it", "Автор $it", BookFormat.FB2, "content://source/$it", 1000, progressBlock = it % 1000)
        }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val serializer = ListSerializer(BookEntry.serializer())
        val (text, encodeMs) = timed("запись списка из 5000 книг") { json.encodeToString(serializer, entries) }
        val (decoded, decodeMs) = timed("чтение списка из 5000 книг") { json.decodeFromString(serializer, text) }
        assertEquals(5000, decoded.size)
        assertTrue(encodeMs < 3_000 && decodeMs < 3_000, "запись $encodeMs мс, чтение $decodeMs мс")
    }

    @Test
    fun `a huge plain text book is parsed and paged in bounded time`() {
        val text = buildString { for (i in 0 until 60_000) append(paragraph(i)).append("\n\n") }
        val bytes = text.encodeToByteArray()
        val megabytes = bytes.size / (1024 * 1024)
        val (book, parseMs) = timed("разбор текста $megabytes МБ") { BookImporter.parseBytes(bytes, BookFormat.TEXT, "Большая", "src") }
        assertTrue(book.blocks.size >= 50_000)
        assertTrue(parseMs < 60_000, "разбор занял $parseMs мс")

        val (pager, pagerMs) = timed("разбиение на страницы ${book.blocks.size} блоков") { BookPager.build(book.blocks, book.chapters) }
        assertTrue(pager.size > 100)
        assertTrue(pagerMs < 10_000, "разбиение заняло $pagerMs мс")
    }

    @Test
    fun `chapter detection and paging scale linearly on 300k blocks`() {
        val blocks = (0 until 300_000).map { i ->
            if (i % 500 == 0) Block(BlockType.HEADING, "Глава ${i / 500 + 1}", 1) else Block(BlockType.PARAGRAPH, paragraph(i))
        }
        val (chapters, detectMs) = timed("поиск глав в 300000 блоков") { ChapterDetector.detect(blocks) }
        assertTrue(chapters.isNotEmpty())
        assertTrue(detectMs < 30_000, "поиск глав занял $detectMs мс")
        val (_, pagerMs) = timed("страницы 300000 блоков") { BookPager.build(blocks, chapters) }
        assertTrue(pagerMs < 15_000, "страницы заняли $pagerMs мс")
    }
}
