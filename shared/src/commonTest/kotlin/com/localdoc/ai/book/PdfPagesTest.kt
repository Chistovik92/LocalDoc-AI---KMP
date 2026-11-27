package com.localdoc.ai.book

import com.localdoc.ai.book.pdf.PageCache
import com.localdoc.ai.book.pdf.PageRenderer
import com.localdoc.ai.book.pdf.PdfPageResult
import com.localdoc.ai.book.pdf.PdfPageSource
import com.localdoc.ai.book.pdf.PdfPages
import com.localdoc.ai.book.pdf.PdfRenderSize
import com.localdoc.ai.book.pdf.zoomRenderWidth
import com.localdoc.ai.book.pdf.blockOfPdfPage
import com.localdoc.ai.book.pdf.pdfPageOfBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        override fun render(index: Int, widthPx: Int, maxPixels: Long): ByteArray? {
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
        // Битая страница нулевого размера рисуется пропорцией А4, а не квадратом 1×1
        val (fw, fh) = PdfRenderSize.fit(0.0, 0.0, 1000)
        assertEquals(1000, fw)
        assertTrue(kotlin.math.abs(fh - (842.0 / 595.0 * 1000).toInt()) <= 1)
        // Повышенный бюджет пикселей (перерисовка при зуме) уважается
        val (zw, zh) = PdfRenderSize.fit(595.0, 842.0, 4000, maxPixels = 16_000_000)
        assertTrue(zw.toLong() * zh in 3_000_001..16_000_000)
        assertTrue(zw <= PdfRenderSize.MAX_SIDE && zh <= PdfRenderSize.MAX_SIDE)
    }

    @Test
    fun `zoom render width follows zoom in steps and stays bounded`() {
        val base = 1350
        // До порога страница рисуется базовой шириной — перерисовка не нужна
        assertEquals(base, zoomRenderWidth(1.0f, base, 4096))
        assertEquals(base, zoomRenderWidth(1.25f, base, 4096))
        // Ступени округляются вверх: 1,3→2×base, 2,9→3×base, 4,5→5×base
        assertEquals(base * 2, zoomRenderWidth(1.3f, base, 20_000))
        assertEquals(base * 2, zoomRenderWidth(2.0f, base, 20_000))
        assertEquals(base * 3, zoomRenderWidth(2.9f, base, 20_000))
        assertEquals(base * 5, zoomRenderWidth(4.5f, base, 20_000))
        // Потолок ширины не превышается
        assertEquals(4096, zoomRenderWidth(6.0f, base, 4096))
        // База больше потолка: не шире потолка
        assertEquals(2000, zoomRenderWidth(6.0f, 2000, 2000))
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
        assertTrue(results.all { it is PdfPageResult.Ready })
        assertEquals(1, renderer.peakConcurrent)
        assertTrue(source.cachedBytes <= 8L * 1024 * 1024, "в кэше ${source.cachedBytes} байт")
        // Повторный запрос последней страницы берётся из кэша без нового рендера
        val before = renderer.rendered
        val repeated = source.page(59 * 80, 1080)
        assertIs<PdfPageResult.Ready>(repeated)
        assertContentEquals(assertIs<PdfPageResult.Ready>(results.last()).bytes, repeated.bytes)
        assertEquals(before, renderer.rendered)
        source.close()
        assertTrue(renderer.closed)
        assertEquals(0L, source.cachedBytes)
    }

    @Test
    fun `out of range pages and render failures report a reason instead of null`() = runTest {
        val failing = object : PageRenderer {
            override val pageCount = 3
            override fun render(index: Int, widthPx: Int, maxPixels: Long): ByteArray? = error("не рисуется")
            override fun close() = Unit
        }
        val source = PdfPageSource(failing, Dispatchers.Default)
        // Сброс рендера: причина, а не молчаливый null — у экрана появляется экран «Повторить»
        val fromRender = source.page(0, 500)
        assertIs<PdfPageResult.Failed>(fromRender)
        assertEquals("не рисуется", fromRender.reason)
        // За границами документа: причина тоже внятная
        val outOfRange = source.page(3, 500)
        assertIs<PdfPageResult.Failed>(outOfRange)
        assertTrue(outOfRange.reason.contains("вне документа"))
        assertIs<PdfPageResult.Failed>(source.page(-1, 500))
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
            override fun render(index: Int, widthPx: Int, maxPixels: Long): ByteArray? = null
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

    @Test
    fun `page and block mapping works with and without page chapters`() {
        val blocks = List(30) { Block(BlockType.PARAGRAPH, "п$it") }
        val withChapters = Book(
            title = "PDF", format = BookFormat.PDF, blocks = blocks,
            chapters = List(10) { Chapter("Страница ${it + 1}", it * 3) }
        )
        assertEquals(12, withChapters.blockOfPdfPage(4, 10))
        assertEquals(4, withChapters.pdfPageOfBlock(13, 10))
        val noChapters = withChapters.copy(chapters = emptyList())
        assertEquals(15, noChapters.blockOfPdfPage(5, 10))
        assertEquals(5, noChapters.pdfPageOfBlock(15, 10))
        // Выход за границы не ломается
        assertEquals(9, noChapters.pdfPageOfBlock(10_000, 10))
        assertEquals(0, noChapters.blockOfPdfPage(0, 10))
    }
}
