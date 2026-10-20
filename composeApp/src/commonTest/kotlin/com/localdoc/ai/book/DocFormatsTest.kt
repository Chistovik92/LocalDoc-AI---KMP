package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocFormatsTest {

    private val styles = """
        <w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
        <w:style w:styleId="Zagolovok2"><w:name w:val="heading 2"/></w:style>
        <w:style w:styleId="Quote"><w:name w:val="Quote"/></w:style>
        <w:style w:styleId="ListBullet"><w:name w:val="List Bullet"/></w:style>
    """.trimIndent()

    // ---------- DOCX ----------

    @Test
    fun `docx paragraphs headings lists and quotes keep structure`() {
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Глава первая</w:t></w:r></w:p>
            <w:p><w:r><w:t>Обычный абзац.</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Zagolovok2"/></w:pPr><w:r><w:t>Подраздел</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>пункт списка</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Quote"/></w:pPr><w:r><w:t>цитата</w:t></w:r></w:p>
            <w:p><w:r><w:t></w:t></w:r></w:p>
        """.trimIndent()
        val book = BookImporter.parseBytes(TestFiles.docx(body, styles, title = "Мой документ"), BookFormat.DOCX, "файл", "uri")

        assertEquals("Мой документ", book.title)
        assertEquals("Автор Тестов", book.author)
        assertEquals(BookFormat.DOCX, book.format)
        assertEquals(
            listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.HEADING, BlockType.LIST_ITEM, BlockType.QUOTE),
            book.blocks.map { it.type }
        )
        assertEquals(listOf(1, 0, 2, 1, 0), book.blocks.map { it.level })
        assertEquals(listOf("Глава первая", "Подраздел"), book.chapters.map { it.title })
    }

    @Test
    fun `docx runs are merged and inline styles become spans`() {
        val body = """
            <w:p>
              <w:r><w:t xml:space="preserve">Это </w:t></w:r>
              <w:r><w:rPr><w:b/></w:rPr><w:t>важное</w:t></w:r>
              <w:r><w:t xml:space="preserve"> и </w:t></w:r>
              <w:hyperlink><w:r><w:rPr><w:i/></w:rPr><w:t>наклонное</w:t></w:r></w:hyperlink>
              <w:r><w:t>.</w:t></w:r>
            </w:p>
        """.trimIndent()
        val block = BookImporter.parseBytes(TestFiles.docx(body), BookFormat.DOCX, "t", "u").blocks.single()

        assertEquals("Это важное и наклонное.", block.text)
        val bold = block.spans.single { it.style == SpanStyleType.BOLD }
        val italic = block.spans.single { it.style == SpanStyleType.ITALIC }
        assertEquals("важное", block.text.substring(bold.start, bold.end))
        assertEquals("наклонное", block.text.substring(italic.start, italic.end))
    }

    @Test
    fun `docx table rows become paragraphs and bold off is respected`() {
        val body = """
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>Имя</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Возраст</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>Анна</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>30</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>не жирный</w:t></w:r></w:p>
        """.trimIndent()
        val book = BookImporter.parseBytes(TestFiles.docx(body), BookFormat.DOCX, "t", "u")

        assertEquals(listOf("Имя | Возраст", "Анна | 30", "не жирный"), book.blocks.map { it.text })
        assertTrue(book.blocks.last().spans.isEmpty())
    }

    @Test
    fun `docx without word document xml gives a clear error`() {
        val notDocx = TestFiles.zip(mapOf("readme.txt" to "привет"))
        val error = assertFailsWith<IllegalArgumentException> { DocxParser.parse(ZipArchive(notDocx), "x") }
        assertTrue("word/document.xml" in error.message.orEmpty())
    }

    @Test
    fun `empty docx is rejected with a readable message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            BookImporter.parseBytes(TestFiles.docx("<w:p/>"), BookFormat.DOCX, "t", "u")
        }
        assertTrue("не найден текст" in error.message.orEmpty())
    }

    // ---------- DOC (OLE2) ----------

    @Test
    fun `ole2 signature is recognised and the doc text is extracted`() {
        val bytes = TestFiles.doc(listOf("Первая строка документа.", "Глава 2", "Вторая строка, уже с таблицей\u0007"))
        assertTrue(Ole2File.isOle2(bytes))
        assertEquals(BookFormat.DOC, FormatDetector.bySignature(bytes))

        val book = BookImporter.parseBytes(bytes, BookFormat.DOC, "Письмо", "uri")
        assertEquals(BookFormat.DOC, book.format)
        assertEquals(listOf("Первая строка документа.", "Глава 2", "Вторая строка, уже с таблицей"), book.blocks.map { it.text })
        assertEquals(listOf("Глава 2"), book.chapters.map { it.title })
    }

    @Test
    fun `doc with compressed pieces and mini stream table is read`() {
        val bytes = TestFiles.doc(listOf("Hello, café world"), compressed = true, miniTable = true)
        val book = BookImporter.parseBytes(bytes, BookFormat.DOC, "t", "u")
        assertEquals("Hello, café world", book.blocks.single().text)
    }

    @Test
    fun `doc fields keep only their result and control characters disappear`() {
        val field = "Страница \u0013 PAGE \u0014 5\u0015 из текста\u0001\u0008"
        val book = BookImporter.parseBytes(TestFiles.doc(listOf(field)), BookFormat.DOC, "t", "u")
        assertEquals("Страница 5 из текста", book.blocks.single().text)
    }

    @Test
    fun `encrypted doc gives a clear message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            BookImporter.parseBytes(TestFiles.doc(listOf("секрет"), encrypted = true), BookFormat.DOC, "t", "u")
        }
        assertTrue("паролем" in error.message.orEmpty())
    }

    @Test
    fun `truncated ole2 file does not crash`() {
        val bytes = TestFiles.doc(listOf("Текст документа"))
        for (cut in listOf(8, 100, 600, 2000, bytes.size / 2)) {
            assertFailsWith<IllegalArgumentException>("обрезка до $cut") {
                BookImporter.parseBytes(bytes.copyOf(cut), BookFormat.DOC, "t", "u")
            }
        }
    }

    @Test
    fun `garbage after the ole2 signature does not loop or crash`() {
        val garbage = TestFiles.doc(listOf("x")).also { for (i in 600 until 1500) it[i] = 0x37 }
        assertFailsWith<IllegalArgumentException> { BookImporter.parseBytes(garbage, BookFormat.DOC, "t", "u") }
    }

    // ---------- ODT ----------

    @Test
    fun `odt headings lists tables and inline styles are read`() {
        val content = """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="o" xmlns:text="t" xmlns:style="s" xmlns:fo="f" xmlns:table="tb">
              <office:automatic-styles>
                <style:style style:name="T1"><style:text-properties fo:font-weight="bold"/></style:style>
              </office:automatic-styles>
              <office:body><office:text>
                <text:h text:outline-level="1">Заголовок</text:h>
                <text:p>Обычный <text:span text:style-name="T1">жирный</text:span> текст<text:s text:c="2"/>с пробелами.</text:p>
                <text:list><text:list-item><text:p>пункт</text:p></text:list-item></text:list>
                <table:table><table:table-row>
                  <table:table-cell><text:p>А</text:p></table:table-cell><table:table-cell><text:p>Б</text:p></table:table-cell>
                </table:table-row></table:table>
              </office:text></office:body>
            </office:document-content>"""
        val bytes = TestFiles.zip(linkedMapOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to content,
            "meta.xml" to "<m xmlns:dc=\"d\"><dc:title>Письмо</dc:title></m>"
        ))
        assertEquals(BookFormat.ODT, FormatDetector.bySignature(bytes))
        val book = BookImporter.parseBytes(bytes, BookFormat.ODT, "файл", "uri")

        assertEquals("Письмо", book.title)
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST_ITEM, BlockType.PARAGRAPH), book.blocks.map { it.type })
        val paragraph = book.blocks[1]
        assertEquals("Обычный жирный текст с пробелами.", paragraph.text)
        val bold = paragraph.spans.single()
        assertEquals("жирный", paragraph.text.substring(bold.start, bold.end))
        assertEquals("А | Б", book.blocks.last().text)
    }

    // ---------- определение формата ----------

    @Test
    fun `extensions of word documents are known`() {
        assertEquals(BookFormat.DOCX, FormatDetector.byName("Отчёт.DOCX"))
        assertEquals(BookFormat.DOC, FormatDetector.byName("письмо.doc"))
    }

    @Test
    fun `docx zip is detected by content even without extension`() {
        val bytes = TestFiles.docx("<w:p><w:r><w:t>текст</w:t></w:r></w:p>")
        assertEquals(BookFormat.DOCX, FormatDetector.bySignature(bytes))
        assertEquals(BookFormat.DOCX, FormatDetector.resolve("загрузка", bytes))
    }

    @Test
    fun `doc with a misleading txt extension is still parsed as doc`() {
        assertEquals(BookFormat.DOC, FormatDetector.resolve("письмо.txt", TestFiles.doc(listOf("текст"))))
    }

    @Test
    fun `binary data is never accepted as text`() {
        val binary = ByteArray(4000) { (it * 31 % 256).toByte() }
        assertTrue(FormatDetector.looksBinary(binary))
        assertFailsWith<UnsupportedFormatException> { FormatDetector.bySignature(binary) }
        assertFailsWith<UnsupportedFormatException> { FormatDetector.resolve("неизвестный.bin", binary) }
        assertFailsWith<UnsupportedFormatException> { FormatDetector.resolve("книга.txt", binary) }
    }

    @Test
    fun `real text including cp1251 and utf16 is not binary`() {
        assertFalse(FormatDetector.looksBinary("Обычный текст\nс переносами\tи табуляцией".encodeToByteArray()))
        val cp1251 = byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())
        assertFalse(FormatDetector.looksBinary(cp1251))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x1F, 0x04, 0x40, 0x04)
        assertFalse(FormatDetector.looksBinary(utf16))
        assertEquals(BookFormat.TEXT, FormatDetector.bySignature("просто текст".encodeToByteArray()))
    }

    @Test
    fun `rtf is rejected with an explanation and fake epub is not parsed`() {
        val rtf = "{\\rtf1\\ansi Hello}".encodeToByteArray()
        val rtfError = assertFailsWith<UnsupportedFormatException> { FormatDetector.bySignature(rtf) }
        assertTrue("RTF" in rtfError.message.orEmpty())

        val fakeEpub = "это не архив".encodeToByteArray()
        assertFailsWith<UnsupportedFormatException> { FormatDetector.resolve("книга.epub", fakeEpub) }
        assertFailsWith<UnsupportedFormatException> { FormatDetector.resolve("архив.zip", TestFiles.zip(mapOf("a.bin" to "x"))) }
    }

    @Test
    fun `control characters are stripped from plain text`() {
        val book = PlainTextParser.parse("Строка\u0000с\u0001мусором\n\nВторой абзац", "t")
        assertEquals(listOf("Строкасмусором", "Второй абзац"), book.blocks.map { it.text })
    }
}
