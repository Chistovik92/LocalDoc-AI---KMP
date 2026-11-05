package com.localdoc.ai.book

import com.localdoc.ai.book.export.BookExporter
import com.localdoc.ai.book.export.Crc32
import com.localdoc.ai.book.export.ExportFormat
import com.localdoc.ai.book.export.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Круговые преобразования: Book → файл формата → Book. */
class ExportRoundTripTest {

    private fun png(seed: Int) = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
        'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 0, 40, 0, 0, 0, 30, 8, 2, 0, 0, 0, seed.toByte(), 1, 2, 3, 4
    )

    private val styled = "Обычный текст с жирным и курсивом и кодом."

    private fun span(word: String, style: SpanStyleType): Span {
        val i = styled.indexOf(word)
        return Span(i, i + word.length, style)
    }

    private fun fixture() = Book(
        title = "Моя книга",
        author = "Иван Петров",
        format = BookFormat.EPUB,
        blocks = listOf(
            Block(BlockType.HEADING, "Моя книга", 1),
            Block(BlockType.PARAGRAPH, styled, spans = listOf(span("жирным", SpanStyleType.BOLD), span("курсивом", SpanStyleType.ITALIC), span("кодом", SpanStyleType.CODE))),
            Block(BlockType.LIST_ITEM, "пункт", 0),
            Block(BlockType.LIST_ITEM, "вложенный", 1),
            Block(BlockType.HEADING, "Вторая глава", 1),
            Block(BlockType.QUOTE, "Цитата"),
            Block(BlockType.CODE, "val x = 1"),
            Block(BlockType.IMAGE, "Рисунок", image = "im0"),
            Block(BlockType.PARAGRAPH, "Финал & <спецсимволы> \"кавычки\" — тире")
        ),
        chapters = listOf(Chapter("Моя книга", 0), Chapter("Вторая глава", 4)),
        cover = "im1",
        images = listOf("im0", "im1"),
        imageData = mapOf("im0" to png(1), "im1" to png(2))
    )

    private fun roundTrip(format: ExportFormat, book: Book = fixture()): Book {
        val bytes = BookExporter.export(book, format)
        val detected = FormatDetector.resolve("файл." + format.extension, bytes)
        assertEquals(format.importFormat, detected, "формат ${format.label} должен определяться по содержимому")
        return BookImporter.parseBytes(bytes, detected, "файл", "uri")
    }

    private fun signature(book: Book) = book.blocks.map { if (it.isImage) "IMAGE" else "${it.type}:${it.level}:${it.text}" }

    private val exact = listOf(ExportFormat.XML, ExportFormat.EPUB, ExportFormat.HTML, ExportFormat.DOCX, ExportFormat.ODT, ExportFormat.RTF, ExportFormat.MARKDOWN)

    @Test
    fun `structure survives export and import for the rich formats`() {
        val expected = signature(fixture())
        for (format in exact) {
            val back = roundTrip(format)
            assertEquals(expected, signature(back), format.label)
            assertEquals("Моя книга", back.title, format.label)
        }
    }

    @Test
    fun `inline styles survive`() {
        val expected = fixture().blocks[1].spans.map { Triple(it.style, styled.substring(it.start, it.end), true) }.toSet()
        for (format in exact) {
            val block = roundTrip(format).blocks[1]
            val actual = block.spans.map { Triple(it.style, block.text.substring(it.start, it.end), true) }.toSet()
            assertEquals(expected, actual, format.label)
        }
    }

    @Test
    fun `chapters and table of contents survive`() {
        for (format in listOf(ExportFormat.XML, ExportFormat.EPUB, ExportFormat.HTML, ExportFormat.DOCX, ExportFormat.ODT, ExportFormat.RTF, ExportFormat.MARKDOWN)) {
            assertEquals(listOf("Моя книга", "Вторая глава"), roundTrip(format).chapters.map { it.title }, format.label)
        }
        // FB2: главы — секции
        assertEquals(listOf("Моя книга", "Вторая глава"), roundTrip(ExportFormat.FB2).chapters.map { it.title })
    }

    @Test
    fun `images and cover survive in formats that carry them`() {
        for (format in listOf(ExportFormat.XML, ExportFormat.EPUB, ExportFormat.HTML, ExportFormat.DOCX, ExportFormat.ODT, ExportFormat.MARKDOWN, ExportFormat.FB2, ExportFormat.FB2_ZIP)) {
            val back = roundTrip(format)
            val image = assertNotNull(back.blocks.first { it.isImage }.image, format.label)
            assertContentEquals(png(1), back.imageData.getValue(image), format.label)
            val cover = assertNotNull(back.cover, "обложка ${format.label}")
            assertContentEquals(png(2), back.imageData.getValue(cover), format.label)
        }
        val rtf = roundTrip(ExportFormat.RTF)
        assertContentEquals(png(1), rtf.imageData.getValue(rtf.blocks.first { it.isImage }.image!!))
    }

    @Test
    fun `alt text of an image survives`() {
        for (format in listOf(ExportFormat.XML, ExportFormat.EPUB, ExportFormat.HTML, ExportFormat.DOCX, ExportFormat.ODT, ExportFormat.MARKDOWN)) {
            assertEquals("Рисунок", roundTrip(format).blocks.first { it.isImage }.text, format.label)
        }
    }

    @Test
    fun `fb2 keeps text quotes and chapters but turns lists into bullet paragraphs`() {
        val back = roundTrip(ExportFormat.FB2)
        assertEquals("Иван Петров", back.author)
        val texts = back.blocks.filterNot { it.isImage }.map { it.text.trim() }
        assertTrue("• пункт" in texts && "• вложенный" in texts)
        assertTrue(back.blocks.any { it.type == BlockType.QUOTE && it.text == "Цитата" })
        assertTrue("Финал & <спецсимволы> \"кавычки\" — тире" in texts)
        val zip = roundTrip(ExportFormat.FB2_ZIP)
        assertEquals(back.blocks.map { it.text }, zip.blocks.map { it.text })
    }

    @Test
    fun `txt keeps all text`() {
        val back = roundTrip(ExportFormat.TXT)
        val all = back.plainText
        listOf("Моя книга", "Вторая глава", "пункт", "вложенный", "Цитата", "val x = 1", "Финал & <спецсимволы>").forEach {
            assertTrue(it in all, it)
        }
    }

    @Test
    fun `every import format converts to every export format`() {
        val sources = listOf(
            MarkdownParser.parse("# Заголовок\n\nТекст **жирный**.\n\n- пункт\n\n> цитата", "t"),
            PlainTextParser.parse("Глава 1\n\nПервый абзац.\n\nГлава 2\n\nВторой абзац.", "t"),
            RtfParser.parse("{\\rtf1\\ansi Hello\\par World\\par}".encodeToByteArray(), "t")
        )
        for (source in sources) for (format in ExportFormat.entries) {
            val back = roundTrip(format, source)
            assertTrue(back.blocks.isNotEmpty(), "${source.format} → ${format.label}")
        }
    }

    @Test
    fun `chapters without headings get headings on export`() {
        val book = Book(
            title = "Новелла", format = BookFormat.WEB,
            blocks = listOf(Block(BlockType.PARAGRAPH, "Первый"), Block(BlockType.PARAGRAPH, "Второй")),
            chapters = listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 1))
        )
        val back = roundTrip(ExportFormat.EPUB, book)
        assertEquals(listOf("Глава 1", "Первый", "Глава 2", "Второй"), back.blocks.map { it.text })
        assertEquals(listOf("Глава 1", "Глава 2"), back.chapters.map { it.title })
    }

    @Test
    fun `empty book cannot be exported and names are file safe`() {
        val empty = fixture().copy(blocks = emptyList())
        kotlin.test.assertFailsWith<IllegalArgumentException> { BookExporter.export(empty, ExportFormat.EPUB) }
        val name = BookExporter.suggestedName(fixture().copy(title = "Война: и мир? <1>/2"), ExportFormat.FB2_ZIP)
        assertEquals("Война и мир 1 2.fb2.zip", name)
    }

    @Test
    fun `zip writer produces readable archives with correct crc`() {
        val writer = ZipWriter()
        writer.add("a.txt", "привет")
        writer.add("dir/b.bin", ByteArray(1000) { it.toByte() })
        val zip = ZipArchive(writer.finish())
        assertEquals("привет", zip.readText("a.txt"))
        assertEquals(1000, zip.read("dir/b.bin")!!.size)
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun `epub starts with an uncompressed mimetype entry`() {
        val bytes = BookExporter.export(fixture(), ExportFormat.EPUB)
        assertEquals("PK", bytes.decodeToString(0, 2))
        assertTrue(bytes.decodeToString(30, 38) == "mimetype")
        assertTrue("application/epub+zip" in bytes.decodeToString(0, 80))
    }
}
