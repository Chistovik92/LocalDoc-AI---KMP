package com.localdoc.ai.book

import com.localdoc.ai.book.pdf.PageCache
import com.localdoc.ai.book.pdf.PageRenderer
import com.localdoc.ai.book.pdf.PdfPageSource
import com.localdoc.ai.book.pdf.PdfPages
import com.localdoc.ai.book.pdf.PdfRenderSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Постраничный PDF: размер страницы, кэш с пределом по памяти, последовательный рендер, обложка. */
class PdfPagesTest {

    /** «Огромный PDF»: страницы по [pageBytes] байт, считает, сколько страниц рисуется одновременно. */
    private class FakeRenderer(override val pageCount: Int, private val pageBytes: Int) : PageRenderer {
        var rendered = 0
        var concurrent = 0
        var peakConcurrent = 0
        var closed = false

        override fun render(index: Int, widthPx: Int): ByteArray? {
            concurrent++
            peakConcurrent = maxOf(peakConcurrent, concurrent)
            rendered++
            val page = TestMobi.png.copyOf(pageBytes)
            page[20] = (index and 0xFF).toByte()
            concurrent--
            return page
        }

        override fun close() { closed = true }
    }

    @Test
    fun `page size keeps the width and aspect ratio of a normal page`() {
        val (w, h) = PdfRenderSize.fit(595.0, 842.0, 1080)
        assertEquals(1080, w)
        assertTrue(kotlin.math.abs(h - (842.0 / 595.0 * 1080).toInt()) <= 1)
    }

    @Test
    fun `huge pages are limited by pixels and side`() {
        // Плакат 100 × 100 м в пунктах: память под одну страницу всё равно ограничена
        val (w, h) = PdfRenderSize.fit(283_465.0, 283_465.0, 1080)
        assertTrue(w.toLong() * h <= PdfRenderSize.MAX_PIXELS)
        val (w2, h2) = PdfRenderSize.fit(100.0, 10_000.0, 4000)
        assertTrue(w2 <= PdfRenderSize.MAX_SIDE && h2 <= PdfRenderSize.MAX_SIDE)
        assertEquals(1 to 1, PdfRenderSize.fit(0.0, 0.0, 1000))
    }

    @Test
    fun `cache drops the oldest pages above the byte limit`() {
        val cache = PageCache(maxBytes = 1000)
        for (i in 0 until 10) cache.put("p$i", ByteArray(300))
        assertTrue(cache.bytes <= 1000)
        assertEquals(3, cache.size)
        assertNull(cache.get("p0"))
        assertNotNull(cache.get("p9"))
        // Страница, обращение к которой было недавним, переживает вытеснение
        cache.get("p7")
        cache.put("p10", ByteArray(300))
        assertNotNull(cache.get("p7"))
        assertNull(cache.get("p8"))
        // Страница больше всего лимита не кэшируется
        cache.put("big", ByteArray(2000))
        assertNull(cache.get("big"))
        assertTrue(cache.bytes <= 1000)
    }

    @Test
    fun `reading a very large pdf keeps memory bounded and renders one page at a time`() = runTest {
        val renderer = FakeRenderer(pageCount = 5000, pageBytes = 2 * 1024 * 1024)
        val source = PdfPageSource(renderer, Dispatchers.Default, maxCacheBytes = 8L * 1024 * 1024)
        val results = (0 until 60).map { i -> async { source.page(i * 80, 1080) } }.awaitAll()
        assertTrue(results.all { it != null })
        assertEquals(1, renderer.peakConcurrent)
        assertTrue(source.cachedBytes <= 8L * 1024 * 1024, "в кэше ${source.cachedBytes} байт")
        // Повторный запрос последней страницы берётся из кэша без нового рендера
        val before = renderer.rendered
        assertContentEquals(results.last(), source.page(59 * 80, 1080))
        assertEquals(before, renderer.rendered)
        source.close()
        assertTrue(renderer.closed)
        assertEquals(0L, source.cachedBytes)
    }

    @Test
    fun `out of range pages and render failures give null`() = runTest {
        val failing = object : PageRenderer {
            override val pageCount = 3
            override fun render(index: Int, widthPx: Int): ByteArray? = error("не рисуется")
            override fun close() = Unit
        }
        val source = PdfPageSource(failing, Dispatchers.Default)
        assertNull(source.page(0, 500))
        assertNull(source.page(3, 500))
        assertNull(source.page(-1, 500))
    }

    @Test
    fun `copy name is stable and unique per source`() {
        val a = PdfPages.fileNameFor("content://docs/1")
        assertEquals(a, PdfPages.fileNameFor("content://docs/1"))
        assertTrue(a != PdfPages.fileNameFor("content://docs/2"))
        assertTrue(a.startsWith("pdf/") && a.endsWith(".pdf"))
    }

    @Test
    fun `first page becomes the cover`() {
        val book = Book(title = "PDF", format = BookFormat.PDF, blocks = listOf(Block(BlockType.PARAGRAPH, "текст")))
        val withCover = PdfPages.withCover(book, FakeRenderer(10, 64))
        assertNotNull(withCover.cover)
        assertEquals(listOf(withCover.cover), withCover.images)
        assertEquals(0, withCover.imageData[withCover.cover]!![20].toInt())
        val failing = object : PageRenderer {
            override val pageCount = 1
            override fun render(index: Int, widthPx: Int): ByteArray? = null
            override fun close() = Unit
        }
        assertNull(PdfPages.withCover(book, failing).cover)
    }

    @Test
    fun `old library records without a pdf copy still load`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"title":"Старая","format":"PDF","blocks":[{"type":"PARAGRAPH","text":"т"}]}"""
        val book = json.decodeFromString(Book.serializer(), old)
        assertNull(book.pdfFile)
    }
}
