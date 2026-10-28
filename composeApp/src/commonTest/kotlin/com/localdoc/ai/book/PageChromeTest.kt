package com.localdoc.ai.book

import com.localdoc.ai.parser.WebArticleParser
import kotlin.test.Test
import kotlin.test.assertEquals

class PageChromeTest {
    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)

    @Test
    fun `site buttons at the page edges are dropped`() {
        val blocks = listOf(
            p("Bookmark"), p("Chapters list"), Block(BlockType.HEADING, "Chapter 611", level = 1),
            p("The male owner was Mao Dayou."), p("He was a little fierce."), p("Next chapter")
        )
        assertEquals(
            listOf("Chapter 611", "The male owner was Mao Dayou.", "He was a little fierce."),
            WebArticleParser.stripPageChrome(blocks).map { it.text }
        )
    }

    @Test
    fun `the same words inside the chapter text are kept`() {
        val body = (1..20).map { p("Line $it") }.toMutableList()
        body[10] = p("Contents")
        assertEquals(20, WebArticleParser.stripPageChrome(body).size)
    }

    @Test
    fun `a page made only of labels is not emptied`() {
        val blocks = listOf(p("Bookmark"), p("Chapters list"))
        assertEquals(2, WebArticleParser.stripPageChrome(blocks).size)
    }

    @Test
    fun `russian labels are dropped too`() {
        val blocks = listOf(p("Закладка"), p("Список глав"), p("Текст главы…"))
        assertEquals(listOf("Текст главы…"), WebArticleParser.stripPageChrome(blocks).map { it.text })
    }
}
