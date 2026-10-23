package com.localdoc.ai.notes

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.BookImporter
import com.localdoc.ai.book.BookmarkRepository
import com.localdoc.ai.book.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationExportTest {

    private val entry = BookEntry("b1", "Война и мир", "Толстой", BookFormat.EPUB, "uri", 30)
    private val blocks = (0 until 30).map { Block(BlockType.PARAGRAPH, "Абзац $it про мир и войну.") }
    private val chapters = listOf(Chapter("Том 1", 0), Chapter("Том 2", 15))

    private fun notes(): BookNotes {
        val repo = AnnotationRepository({ null }, { })
        repo.add(entry, TextAnchor.create(blocks, chapters, 20, 0, 20, 8), "Важная мысль")
        repo.add(entry, TextAnchor.create(blocks, chapters, 3, 0, 4, 7))
        val mark = BookmarkRepository({ null }, { }).add(entry, 7, blocks[7].text, "Том 1")
        val word = DictionaryRepository({ null }, { }).save("мир", "peace", entryId = "b1", bookTitle = entry.title)
        return BookNotes(entry.title, entry.author, repo.all.value, listOf(mark), listOf(word))
    }

    @Test
    fun `markdown groups quotes by chapter in reading order with notes under them`() {
        val md = AnnotationExport.markdown(notes())

        assertTrue(md.startsWith("# Война и мир\n*Толстой*\n"))
        assertTrue(md.indexOf("## Том 1") < md.indexOf("## Том 2"))
        assertTrue("> Абзац 3 про мир и войну.\n> Абзац 4" in md, md)
        assertTrue("> Абзац 20\n\nВажная мысль" in md, md)
        assertTrue("## Закладки\n\n- Абзац 7 про мир и войну. — Том 1" in md, md)
        assertTrue("- **мир** — peace" in md, md)
        assertTrue(md.endsWith("\n") && !md.endsWith("\n\n"))
    }

    @Test
    fun `epub export is a readable book with chapters quotes and notes`() {
        val bytes = AnnotationExport.epub(notes())
        val book = BookImporter.parseBytes(bytes, BookFormat.EPUB, "заметки", "uri")

        assertEquals("Заметки — Война и мир", book.title)
        assertEquals(listOf("Том 1", "Том 2", "Закладки", "Слова"), book.chapters.map { it.title })
        assertTrue(book.blocks.any { it.type == BlockType.QUOTE && it.text == "Абзац 20" })
        assertTrue(book.blocks.any { it.text == "Важная мысль" })
    }

    @Test
    fun `empty notes still produce valid files and are reported as empty`() {
        val empty = BookNotes("Пустая", "", emptyList())
        assertTrue(empty.isEmpty)
        assertTrue(AnnotationExport.markdown(empty).startsWith("# Пустая"))
        val book = BookImporter.parseBytes(AnnotationExport.epub(empty), BookFormat.EPUB, "п", "uri")
        assertTrue(book.blocks.any { it.text.contains("Заметок пока нет") })
        assertEquals("Заметки — Война _ мир.md", AnnotationExport.fileName(BookNotes("Война / мир", "", emptyList()), "md"))
    }

    @Test
    fun `dictionary keeps one entry per word and finds words case and yo insensitively`() {
        var stored: String? = null
        val dict = DictionaryRepository({ stored }, { stored = it })
        dict.save("Ёлка", "ель", context = "Нарядили ёлку.", entryId = "b1", bookTitle = "Сказки")
        dict.save("елка", "новогоднее дерево")

        val all = DictionaryRepository({ stored }, { }).all.value
        assertEquals(1, all.size)
        assertEquals("Ёлка", all.single().word)
        assertEquals("новогоднее дерево", all.single().meaning)
        assertEquals("Нарядили ёлку.", all.single().context)
        assertNotNull(dict.find("ЁЛКА"))

        dict.remove(all.single().id)
        assertNull(dict.find("ёлка"))
    }

    @Test
    fun `word selection trims punctuation and takes the surrounding sentence`() {
        assertEquals("мир", WordSelection.wordOf("  «мир», "))
        assertNull(WordSelection.wordOf("несколько слов\nна двух строках"))
        assertNull(WordSelection.wordOf("...."))
        assertNull(WordSelection.wordOf("а".repeat(100)))

        val text = "Первое предложение. Он любил мир во всём мире! Последнее."
        assertEquals("Он любил мир во всём мире!", WordSelection.sentenceAround(text, "мир"))
        assertEquals("Первое предложение.", WordSelection.sentenceAround(text, "Первое"))
        assertEquals(text, WordSelection.sentenceAround(text, "нет"))
    }
}
