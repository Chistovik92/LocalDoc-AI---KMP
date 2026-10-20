package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookPagerTest {

    private fun p(length: Int) = Block(BlockType.PARAGRAPH, "а".repeat(length))
    private fun h(text: String = "Заголовок") = Block(BlockType.HEADING, text, 1)

    @Test
    fun `blocks are grouped up to the page size and never split`() {
        val pager = BookPager.build(List(10) { p(400) }, targetChars = 1_000)

        assertEquals(listOf(0 to 2, 2 to 4, 4 to 6, 6 to 8, 8 to 10), pager.pages.map { it.startBlock to it.endBlock })
        assertTrue(pager.pages.all { it.blockCount in 1..2 })
    }

    @Test
    fun `pages cover every block exactly once`() {
        val blocks = List(137) { p(50 + it * 7 % 600) }
        val pager = BookPager.build(blocks, targetChars = 1_500)

        assertEquals(0, pager.pages.first().startBlock)
        assertEquals(blocks.size, pager.pages.last().endBlock)
        pager.pages.zipWithNext().forEach { (a, b) -> assertEquals(a.endBlock, b.startBlock) }
        blocks.indices.forEach { assertTrue(it in pager.page(pager.pageOfBlock(it))) }
    }

    @Test
    fun `an oversized block makes its own page`() {
        val pager = BookPager.build(listOf(p(100), p(50_000), p(100)), targetChars = 1_000)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), pager.pages.map { it.startBlock to it.endBlock })
    }

    @Test
    fun `chapter start always begins a new page`() {
        val blocks = listOf(p(100), p(100), p(100), p(100))
        val pager = BookPager.build(blocks, listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 2)), targetChars = 10_000)
        assertEquals(listOf(0 to 2, 2 to 4), pager.pages.map { it.startBlock to it.endBlock })
    }

    @Test
    fun `heading is not left alone at the end of a page`() {
        val blocks = listOf(p(400), p(400), h(), p(400))
        val pager = BookPager.build(blocks, targetChars = 1_000)
        // «Заголовок» уходит вместе со своим текстом на вторую страницу
        assertEquals(listOf(0 to 2, 2 to 4), pager.pages.map { it.startBlock to it.endBlock })
    }

    @Test
    fun `page lookup clamps out of range blocks and empty book has no pages`() {
        val pager = BookPager.build(List(6) { p(900) }, targetChars = 1_000)
        assertEquals(0, pager.pageOfBlock(-5))
        assertEquals(pager.size - 1, pager.pageOfBlock(10_000))
        assertEquals(0, BookPager.build(emptyList()).size)
    }

    @Test
    fun `huge book is paged quickly into bounded pages`() {
        val blocks = List(60_000) { p(300) }
        val pager = BookPager.build(blocks, targetChars = 3_000)
        assertTrue(pager.size >= 5_000)
        assertTrue(pager.pages.all { page -> page.blockCount <= 11 })
    }

    @Test
    fun `progress of page goes from zero to one`() {
        val pager = BookPager.build(List(5) { p(900) }, targetChars = 1_000)
        assertEquals(0f, pager.progressOfPage(0))
        assertEquals(1f, pager.progressOfPage(pager.size - 1))
    }
}
