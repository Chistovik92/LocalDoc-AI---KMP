package com.localdoc.ai.notes

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.book.BookmarkRepository
import com.localdoc.ai.book.Chapter
import com.localdoc.ai.book.PageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextAnchorTest {

    private fun paragraph(text: String) = Block(BlockType.PARAGRAPH, text)

    /** 60 абзацев в трёх главах; в каждом абзаце своя фраза, поэтому место определяется однозначно. */
    private fun sampleBlocks(): List<Block> = (0 until 60).map { i ->
        if (i % 20 == 0) Block(BlockType.HEADING, "Глава ${i / 20 + 1}", level = 1)
        else paragraph("Абзац номер $i. " + "Слово ".repeat(60) + "конец абзаца $i.")
    }

    private fun sampleChapters() = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 20), Chapter("Глава 3", 40))

    private fun book(blocks: List<Block>, chapters: List<Chapter>) =
        Book(title = "Тест", format = BookFormat.EPUB, blocks = blocks, chapters = chapters)

    private val entry = BookEntry("b1", "Тест", "", BookFormat.EPUB, "uri", 60)

    @Test
    fun `anchor records fragment context and chapter`() {
        val blocks = sampleBlocks()
        val text = blocks[25].text
        val at = text.indexOf("конец абзаца")
        val anchor = TextAnchor.create(blocks, sampleChapters(), 25, at, 25, at + "конец абзаца 25".length)

        assertEquals("конец абзаца 25", anchor.quote)
        assertEquals("Глава 2", anchor.chapter)
        assertEquals(5, anchor.chapterOffset)
        assertTrue(anchor.prefix.endsWith("Слово "))
        assertTrue(anchor.suffix.startsWith("."))
    }

    @Test
    fun `quote and bookmark survive any page size`() {
        val blocks = sampleBlocks()
        val chapters = sampleChapters()
        val quote = TextAnchor.create(blocks, chapters, 33, 0, 33, 12)
        val bookmark = BookmarkRepository({ null }, { }).add(entry, 33, blocks[33].text, "Глава 2", TextAnchor.ofBlock(blocks, chapters, 33))

        for (size in PageSize.entries) {
            val pager = BookPager.build(blocks, chapters, size.chars)
            val resolved = AnchorResolver.resolve(quote, blocks, chapters)
            assertEquals(33, resolved.block, "размер страницы ${size.label}")
            assertEquals(33, bookmark.resolveBlock(book(blocks, chapters)))
            // Страница под блоком цитаты содержит этот блок при любой разбивке
            assertTrue(33 in pager.page(pager.pageOfBlock(resolved.block)), "размер страницы ${size.label}")
        }
        // Разбивки действительно разные: проверка не тавтологична
        assertTrue(BookPager.build(blocks, chapters, PageSize.SMALL.chars).size > BookPager.build(blocks, chapters, PageSize.LARGE.chars).size)
    }

    @Test
    fun `anchor follows the text when the book is parsed again with shifted blocks`() {
        val blocks = sampleBlocks()
        val chapters = sampleChapters()
        val anchor = TextAnchor.create(blocks, chapters, 45, 7, 45, 40)

        // Новый разбор добавил две вводные страницы в начало, главы сдвинулись
        val preface = listOf(paragraph("Об издании."), paragraph("Предисловие."))
        val shiftedBlocks = preface + blocks
        val shiftedChapters = chapters.map { it.copy(startBlock = it.startBlock + 2) }
        val resolved = AnchorResolver.resolve(anchor, shiftedBlocks, shiftedChapters)

        assertTrue(resolved.exact)
        assertEquals(47, resolved.block)
        assertEquals(7, resolved.start)
        assertEquals(40, resolved.end)
        assertEquals(anchor.quote, shiftedBlocks[resolved.block].text.substring(resolved.start, resolved.end))
    }

    @Test
    fun `repeated phrase is told apart by surrounding text`() {
        val blocks = listOf(
            paragraph("Он сказал: «Идём домой». Потом замолчал."),
            paragraph("Она ответила: «Идём домой». И они пошли.")
        )
        val at = blocks[1].text.indexOf("Идём")
        val second = TextAnchor.create(blocks, emptyList(), 1, at, 1, at + 10)
        // В начало добавлен блок: цитата сдвигается, но берётся то вхождение, у которого то же окружение
        val reparsed = listOf(paragraph("Вступление.")) + blocks
        val resolved = AnchorResolver.resolve(second, reparsed, emptyList())

        assertEquals(2, resolved.block)
        assertTrue(resolved.exact)
    }

    @Test
    fun `edited text falls back to the chapter position and then to the old index`() {
        val blocks = sampleBlocks()
        val chapters = sampleChapters()
        val anchor = TextAnchor.create(blocks, chapters, 43, 0, 43, 15)
        // Текст абзаца изменился, главы на месте: возвращаемся на то же расстояние от начала главы
        val edited = blocks.toMutableList().also { it[43] = paragraph("Совсем другой текст.") }
        val resolved = AnchorResolver.resolve(anchor, edited, chapters)

        assertFalse(resolved.exact)
        assertEquals(43, resolved.block)

        // Книга стала короче: блок не выходит за пределы
        val short = AnchorResolver.resolve(anchor, blocks.take(10), emptyList())
        assertEquals(9, short.block)
        assertFalse(short.exact)
    }

    @Test
    fun `legacy bookmark without anchor keeps its block`() {
        val blocks = sampleBlocks()
        val legacy = BookmarkRepository({ null }, { }).add(entry, 12, blocks[12].text)
        assertNull(legacy.anchor)
        assertEquals(12, legacy.resolveBlock(book(blocks, sampleChapters())))
        assertEquals(59, legacy.copy(block = 900).resolveBlock(book(blocks, sampleChapters())))
    }

    @Test
    fun `selection text is located in page blocks including multi paragraph selection`() {
        val blocks = listOf(
            paragraph("Первый абзац про море."),
            paragraph("Второй абзац про горы и реки."),
            paragraph("Третий абзац про лес.")
        )
        val single = assertNotNull(TextAnchor.locate(blocks, emptyList(), "про горы", 0..2))
        assertEquals(1, single.block)
        assertEquals(blocks[1].text.indexOf("про горы"), single.start)

        val multi = assertNotNull(TextAnchor.locate(blocks, emptyList(), "про море.\nВторой абзац про горы", 0..2))
        assertEquals(0, multi.block)
        assertEquals(1, multi.endBlock)
        assertEquals("про море.\nВторой абзац про горы", multi.quote)

        // Абзацы пришли склеенными, без переводов строки
        val glued = assertNotNull(TextAnchor.locate(blocks, emptyList(), "про море.Второй абзац про горы и реки.Третий абзац", 0..2))
        assertEquals(0, glued.block)
        assertEquals(blocks[0].text.indexOf("про море."), glued.start)
        assertEquals(2, glued.endBlock)
        assertEquals("Третий абзац".length, glued.end)
        assertEquals("про море.\nВторой абзац про горы и реки.\nТретий абзац", glued.quote)

        assertNull(TextAnchor.locate(blocks, emptyList(), "нет такого", 0..2))
        assertNull(TextAnchor.locate(blocks, emptyList(), "   ", 0..2))
    }

    @Test
    fun `annotations are stored per book sorted by position and removed with the book`() {
        var stored: String? = null
        val repo = AnnotationRepository({ stored }, { stored = it })
        val blocks = sampleBlocks()
        val late = repo.add(entry, TextAnchor.create(blocks, emptyList(), 50, 0, 50, 10), "поздняя")
        repo.add(entry, TextAnchor.create(blocks, emptyList(), 5, 0, 5, 10))

        assertEquals(listOf(5, 50), repo.forEntry("b1").map { it.anchor.block })
        repo.updateNote(late.id, "  правка  ")
        assertEquals("правка", AnnotationRepository({ stored }, { }).forEntry("b1").last().note)

        repo.removeForEntry("b1")
        assertTrue(AnnotationRepository({ stored }, { }).all.value.isEmpty())
    }

    @Test
    fun `bookmark lookup uses the block resolved by the anchor`() {
        val blocks = sampleBlocks()
        val repo = BookmarkRepository({ null }, { })
        repo.add(entry, 10, blocks[10].text, "", TextAnchor.ofBlock(blocks, sampleChapters(), 10))
        // Книгу разобрали заново: перед текстом добавлены два блока
        val shifted = book(listOf(paragraph("а"), paragraph("б")) + blocks, sampleChapters().map { it.copy(startBlock = it.startBlock + 2) })
        val resolve = { m: com.localdoc.ai.book.Bookmark -> m.resolveBlock(shifted) }

        assertNull(repo.findIn("b1", 10..10, resolve))
        assertNotNull(repo.findIn("b1", 12..12, resolve))
        assertFalse(repo.toggle(entry, 12..12, 12, "", "", null, resolve))
        assertTrue(repo.all.value.isEmpty())
    }
}
