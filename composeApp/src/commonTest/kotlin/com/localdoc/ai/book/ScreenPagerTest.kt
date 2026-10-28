package com.localdoc.ai.book

import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Фейковый измеритель: символ шириной 10 px, строка 20 px, перенос ровно по числу символов в строке. */
private class FakeMeasurer : PageMeasurer {
    override fun measure(block: Block, spec: PageSpec): BlockMeasure {
        if (block.isImage) {
            val h = min(block.level * 10f, spec.height)
            return BlockMeasure(0f, 0f, floatArrayOf(h), intArrayOf(0), atomic = true)
        }
        val perLine = (spec.width / CHAR).toInt().coerceAtLeast(1)
        val lines = ((block.text.length + perLine - 1) / perLine).coerceAtLeast(1)
        val lineHeight = if (block.type == BlockType.HEADING) 30f else LINE
        val gap = if (block.type == BlockType.HEADING) 10f else 0f
        return BlockMeasure(gap, gap, FloatArray(lines) { lineHeight }, IntArray(lines) { it * perLine })
    }

    companion object {
        const val CHAR = 10f
        const val LINE = 20f
    }
}

private fun para(lines: Int, perLine: Int = 10) = Block(BlockType.PARAGRAPH, "x".repeat(lines * perLine))
private fun heading(text: String = "Заголовок") = Block(BlockType.HEADING, text, level = 1)
private fun image(height: Int) = Block(BlockType.IMAGE, "", level = height / 10, image = "im1")

/** Страница из 10 строк по 20 px при ширине 10 символов. */
private val spec = PageSpec(width = 100f, height = 200f)

private suspend fun layout(blocks: List<Block>, chapters: List<Chapter> = emptyList(), s: PageSpec = spec) =
    ScreenPager.layoutAll(blocks, chapters, s, FakeMeasurer())

private suspend fun fullLayout(blocks: List<Block>, chapters: List<Chapter>): ScreenLayout {
    var result = ScreenLayout.empty(blocks.size, chapters)
    ScreenPager.segments(blocks.size, chapters).forEachIndexed { i, range ->
        val pages = ArrayList<ScreenPage>()
        ScreenPager.layoutSegment(blocks, range, spec, FakeMeasurer()) { pages.add(it) }
        result = result.with(i, SegmentPages(pages, true))
    }
    return result
}

class ScreenPagerTest {

    @Test
    fun `empty book has no pages`() = runTest {
        assertTrue(layout(emptyList()).isEmpty())
    }

    @Test
    fun `paragraph is split between pages by lines`() = runTest {
        val pages = layout(listOf(para(25)))
        assertEquals(3, pages.size)
        assertEquals(PagePos(0, 0), pages[0].start)
        assertEquals(PagePos(0, 100), pages[0].end) // 10 строк × 10 символов
        assertEquals(PagePos(0, 100), pages[1].start)
        assertEquals(PagePos(0, 200), pages[1].end)
        assertEquals(PagePos(1, 0), pages[2].end)
    }

    @Test
    fun `pages are contiguous and cover the book`() = runTest {
        val blocks = List(40) { para(1 + it % 7) }
        val pages = layout(blocks)
        assertEquals(PagePos(0, 0), pages.first().start)
        assertEquals(PagePos(blocks.size, 0), pages.last().end)
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun `no single line of a paragraph is left at the page bottom`() = runTest {
        // 9 строк занимают почти всю страницу, под абзац из 5 строк остаётся место лишь для одной
        val pages = layout(listOf(para(9), para(5)))
        assertEquals(PagePos(1, 0), pages[0].end)
        assertEquals(PagePos(1, 0), pages[1].start)
    }

    @Test
    fun `no single line of a paragraph moves to the next page`() = runTest {
        // Влезает 10 строк из 11: на следующую страницу уходят две, а не одна
        val pages = layout(listOf(para(11)))
        assertEquals(PagePos(0, 90), pages[0].end)
        assertEquals(PagePos(1, 0), pages[1].end)
    }

    @Test
    fun `short paragraph stays whole when it cannot satisfy both edges`() = runTest {
        val pages = layout(listOf(para(9), para(2)))
        assertEquals(PagePos(1, 0), pages[0].end)
    }

    @Test
    fun `heading is not the last block on a page`() = runTest {
        // 7 строк = 140, заголовок 10+30+10 = 50 → 190; следующий абзац не помещается — заголовок уходит с ним
        val pages = layout(listOf(para(7), heading(), para(5)))
        assertEquals(PagePos(1, 0), pages[0].end)
        assertEquals(PagePos(1, 0), pages[1].start)
    }

    @Test
    fun `heading alone on a page stays`() = runTest {
        val pages = layout(listOf(heading(), para(30)))
        assertTrue(pages.first().end >= PagePos(1, 0))
    }

    @Test
    fun `consecutive headings move together`() = runTest {
        val pages = layout(listOf(para(5), heading("A"), heading("B"), para(8)))
        assertEquals(PagePos(1, 0), pages[0].end)
    }

    @Test
    fun `chapter always starts a new page`() = runTest {
        val blocks = listOf(para(2), para(2), para(2), para(2))
        val pages = layout(blocks, listOf(Chapter("Глава 2", 2)))
        assertEquals(2, pages.size)
        assertEquals(PagePos(2, 0), pages[0].end)
        assertEquals(PagePos(2, 0), pages[1].start)
    }

    @Test
    fun `image takes its own height and does not fit next to a long block`() = runTest {
        // 40 + 120 = 160; две строки следом (40) уже не помещаются
        val pages = layout(listOf(para(2), image(120), para(3)))
        assertEquals(PagePos(2, 0), pages[0].end)
    }

    @Test
    fun `image taller than the page gets its own page`() = runTest {
        val pages = layout(listOf(para(2), image(900), para(2)))
        assertEquals(3, pages.size)
        assertEquals(PagePos(1, 0), pages[0].end)
        assertEquals(PagePos(2, 0), pages[1].end)
    }

    @Test
    fun `giant paragraph is split into many pages without gaps`() = runTest {
        val pages = layout(listOf(para(10_000)))
        assertEquals(1000, pages.size)
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertEquals(PagePos(1, 0), pages.last().end)
    }

    @Test
    fun `position lands on exactly one page after relayout with another page size`() = runTest {
        val blocks = List(60) { para(3 + it % 5) }
        val big = layout(blocks)
        val pos = big[big.size / 2].start
        val others = listOf(big, layout(blocks, s = PageSpec(100f, 120f)), layout(blocks, s = PageSpec(200f, 300f)))
        for (pages in others) {
            assertEquals(1, pages.count { pos in it }, "место должно попадать ровно на одну страницу")
        }
    }

    @Test
    fun `slices cut the first and last blocks`() = runTest {
        val blocks = listOf(para(25), para(1))
        val pages = layout(blocks)
        assertEquals(listOf(BlockSlice(0, 100, 200, first = false, last = false)), pages[1].slices(blocks))
        val last = pages[2].slices(blocks)
        assertEquals(BlockSlice(0, 200, 250, first = false, last = true), last.first())
        assertTrue(last.last().last)
    }

    @Test
    fun `layout finds page by position and navigates between chapters`() = runTest {
        val blocks = List(20) { para(6) }
        val layout = fullLayout(blocks, listOf(Chapter("2", 10)))
        assertTrue(layout.isComplete)
        val first = layout.locate(PagePos(10, 0))!!
        assertEquals(PageRef(1, 0), first)
        val before = layout.previous(first)!!
        assertEquals(0, before.segment)
        assertEquals(first, layout.next(before))
        val lastRef = layout.refAtOrdinal(layout.total - 1)!!
        assertFalse(layout.hasMoreAfter(lastRef))
        assertEquals(layout.total, layout.ordinal(lastRef).first + 1)
    }

    @Test
    fun `partial layout estimates total and marks ordinal inexact`() = runTest {
        val blocks = List(20) { para(6) }
        val chapters = listOf(Chapter("2", 10))
        val pages = ArrayList<ScreenPage>()
        ScreenPager.layoutSegment(blocks, ScreenPager.segments(blocks.size, chapters)[1], spec, FakeMeasurer()) { pages.add(it) }
        val layout = ScreenLayout.empty(blocks.size, chapters).with(1, SegmentPages(pages, true))
        assertFalse(layout.isComplete)
        assertNull(layout.locate(PagePos(0, 0)))
        val ref = layout.locate(PagePos(12, 0))!!
        assertFalse(layout.ordinal(ref).second)
        assertTrue(layout.total > pages.size)
    }
}
