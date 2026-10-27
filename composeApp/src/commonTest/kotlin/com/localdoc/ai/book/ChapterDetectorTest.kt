package com.localdoc.ai.book

import com.localdoc.ai.book.export.withChapterHeadings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterDetectorTest {

    private fun paragraphs(vararg texts: String) = texts.map { Block(BlockType.PARAGRAPH, it) }

    private fun titles(vararg texts: String, keywords: ChapterKeywords = ChapterKeywords.DEFAULT) =
        ChapterDetector.detect(paragraphs(*texts), keywords).map { it.title }

    private val body = "Длинный абзац обычного текста книги, в котором герои разговаривают, идут по дороге и думают о своём. ".repeat(3)

    @Test
    fun `finds chapter parts prologue and epilogue by their words`() {
        val found = titles("Пролог", body, "Часть первая", body, "Глава 1. Начало", body, "Глава 2", body, "Эпилог", body)
        assertEquals(listOf("Пролог", "Часть первая", "Глава 1. Начало", "Глава 2", "Эпилог"), found)
    }

    @Test
    fun `chapter starts at the block with the word`() {
        val chapters = ChapterDetector.detect(paragraphs("Пролог", body, "Глава 1", body, body, "Глава 2", body))
        assertEquals(listOf(0, 2, 5), chapters.map { it.startBlock })
    }

    @Test
    fun `understands digits roman numerals and spelled out numbers`() {
        val found = titles(
            "ГЛАВА IV", body, "Глава двадцать вторая", body, "Chapter 3: The Return", body, "Part Two", body,
            "Глава № 7", body, "Книга третья. Дорога"
        )
        assertEquals(
            listOf("ГЛАВА IV", "Глава двадцать вторая", "Chapter 3: The Return", "Part Two", "Глава № 7", "Книга третья. Дорога"),
            found
        )
    }

    @Test
    fun `ordinary sentences with the same words are not chapters`() {
        val found = titles(
            "Часть пути он прошёл молча.", "Глава города отвернулась и ушла.", "Часть 2 была готова к утру.",
            "Part of the road was closed.", "Пролог был написан позже всего остального и потому казался чужим в этой книге."
        )
        assertEquals(emptyList(), found)
    }

    @Test
    fun `a long paragraph that starts with a chapter word is not a title`() {
        assertEquals(emptyList(), titles("Глава 1. $body"))
    }

    @Test
    fun `standalone words such as prologue need no number`() {
        assertEquals(listOf("Prologue", "Интерлюдия", "Послесловие"), titles("Prologue", body, "Интерлюдия", body, "Послесловие"))
    }

    @Test
    fun `numbered words alone are not chapters`() {
        assertEquals(emptyList(), titles("Глава", "Часть", "Chapter"))
    }

    @Test
    fun `headings of the top two levels are chapters even without keywords`() {
        val blocks = listOf(
            Block(BlockType.HEADING, "Дорога домой", 1), Block(BlockType.PARAGRAPH, body),
            Block(BlockType.HEADING, "Встреча", 2), Block(BlockType.PARAGRAPH, body),
            Block(BlockType.HEADING, "Мелкая подпись", 3), Block(BlockType.PARAGRAPH, body)
        )
        assertEquals(listOf("Дорога домой", "Встреча"), ChapterDetector.detect(blocks).map { it.title })
    }

    @Test
    fun `a counted run of bare numbers is a chapter list`() {
        val found = titles("1", body, "2", body, "3", body, "4", body)
        assertEquals(listOf("1", "2", "3", "4"), found)
    }

    @Test
    fun `stray numbers such as page numbers are ignored`() {
        assertEquals(emptyList(), titles("17", body, "42", body, "3", body))
        assertEquals(emptyList(), titles("1", body, "2", body))
    }

    @Test
    fun `custom keywords from the user are used`() {
        val keywords = ChapterKeywords.parse("Песнь, Сказ, Глава")
        assertEquals(listOf("Песнь третья", "Сказ"), titles("Песнь третья", body, "Сказ", body, "Глава", keywords = keywords))
    }

    @Test
    fun `keywords list ignores blanks duplicates and case`() {
        val keywords = ChapterKeywords.parse(" глава ;ГЛАВА\n\n Пролог, ")
        assertTrue("глава" in keywords.needNumber)
        assertTrue("пролог" in keywords.standalone)
        assertEquals(1, keywords.needNumber.size)
    }

    @Test
    fun `export turns a chapter line into a heading instead of repeating it`() {
        val book = Book(
            title = "Т", format = BookFormat.TEXT,
            blocks = paragraphs("Глава 1", "Текст первой главы.", "Глава 2", "Текст второй."),
            chapters = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 2))
        )
        val prepared = book.withChapterHeadings()
        assertEquals(listOf("Глава 1", "Текст первой главы.", "Глава 2", "Текст второй."), prepared.blocks.map { it.text })
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.HEADING, BlockType.PARAGRAPH), prepared.blocks.map { it.type })
        assertEquals(listOf(0, 2), prepared.chapters.map { it.startBlock })
    }
}
