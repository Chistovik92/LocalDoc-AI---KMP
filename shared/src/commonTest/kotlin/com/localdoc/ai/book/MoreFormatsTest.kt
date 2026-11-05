package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форматы 1.7.5: RTF, XML, WPS, Pages, варианты Word и сигнатуры. */
class MoreFormatsTest {

    private val png = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20
    )

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---------- имена и сигнатуры ----------

    @Test
    fun `all requested extensions are recognised`() {
        val expected = mapOf(
            "a.docx" to BookFormat.DOCX, "a.docm" to BookFormat.DOCX, "a.dotx" to BookFormat.DOCX, "a.dotm" to BookFormat.DOCX,
            "a.doc" to BookFormat.DOC, "a.pdf" to BookFormat.PDF, "a.odt" to BookFormat.ODT, "a.rtf" to BookFormat.RTF,
            "a.txt" to BookFormat.TEXT, "a.htm" to BookFormat.HTML, "a.html" to BookFormat.HTML, "a.xml" to BookFormat.XML,
            "a.wps" to BookFormat.WPS, "a.pages" to BookFormat.PAGES, "a.fb2.zip" to BookFormat.FB2, "a.epub" to BookFormat.EPUB
        )
        expected.forEach { (name, format) -> assertEquals(format, FormatDetector.byName(name), name) }
        assertNull(FormatDetector.byName("a.exe"))
    }

    @Test
    fun `docm and dotx are parsed as word documents`() {
        val bytes = TestFiles.docx("<w:p><w:r><w:t>макрос</w:t></w:r></w:p>")
        assertEquals(BookFormat.DOCX, FormatDetector.resolve("шаблон.dotx", bytes))
        assertEquals(BookFormat.DOCX, FormatDetector.resolve("макросы.docm", bytes))
    }

    @Test
    fun `xml files are told apart by content`() {
        assertEquals(BookFormat.XML, FormatDetector.resolve("a.xml", "<?xml version=\"1.0\"?><doc><p>текст</p></doc>".encodeToByteArray()))
        assertEquals(BookFormat.FB2, FormatDetector.resolve("a.xml", "<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>".encodeToByteArray()))
        assertEquals(BookFormat.XML, FormatDetector.bySignature("<?xml version=\"1.0\"?><doc/>".encodeToByteArray()))
    }

    @Test
    fun `rtf is found by signature and wps is told apart from doc`() {
        assertEquals(BookFormat.RTF, FormatDetector.resolve("письмо.txt", rtf("Привет").encodeToByteArray()))
        assertEquals(BookFormat.WPS, FormatDetector.resolve("старый.wps", TestFiles.doc(listOf("текст"))))
        assertEquals(BookFormat.DOC, FormatDetector.resolve("старый.doc", TestFiles.doc(listOf("текст"))))
    }

    @Test
    fun `broken containers under known extensions give clear errors and never crash`() {
        for (name in listOf("a.pages", "a.wps", "a.docm", "a.rtf")) {
            val error = assertFailsWith<UnsupportedFormatException>(name) {
                FormatDetector.resolve(name, "просто текст, не документ".encodeToByteArray())
            }
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    // ---------- RTF ----------

    private fun rtf(body: String, head: String = "") = "{\\rtf1\\ansi\\ansicpg1252\\deff0$head $body}"

    @Test
    fun `rtf paragraphs tabs and special characters`() {
        val book = RtfParser.parse(rtf("Hello {\\b bold} and {\\i italic}\\par Second\\tab para \\emdash  dash\\par").encodeToByteArray(), "файл")
        assertEquals(BookFormat.RTF, book.format)
        assertEquals(listOf("Hello bold and italic", "Second para — dash"), book.blocks.map { it.text })
    }

    @Test
    fun `rtf unicode escapes skip their fallback characters`() {
        val body = "\\u1055?\\u1088?\\u1080?\\u1074?\\u1077?\\u1090?\\par"
        val book = RtfParser.parse(rtf(body).encodeToByteArray(), "x")
        assertEquals("Привет", book.blocks.single().text)
    }

    @Test
    fun `rtf cp1251 hex escapes are decoded with the document code page`() {
        val data = "{\\rtf1\\ansi\\ansicpg1251\\deff0 \\'cc\\'e8\\'f0\\par}".encodeToByteArray()
        assertEquals("Мир", RtfParser.parse(data, "x").blocks.single().text)
    }

    @Test
    fun `rtf headings come from the stylesheet and lists from ls`() {
        val head = "{\\stylesheet{\\s1 heading 1;}{\\s2 heading 2;}}{\\info{\\title Моя книга}{\\author Автор А.}}"
        val body = "\\pard\\s1 Глава первая\\par \\pard Текст главы.\\par \\pard\\ls1 пункт один\\par \\pard\\s2 Подраздел\\par"
        val book = RtfParser.parse(rtf(body, head).encodeToByteArray(), "файл")
        assertEquals("Моя книга", book.title)
        assertEquals("Автор А.", book.author)
        assertEquals(
            listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST_ITEM, BlockType.HEADING),
            book.blocks.map { it.type }
        )
        assertEquals(1, book.blocks[0].level)
        assertEquals(2, book.blocks[3].level)
        assertEquals(listOf("Глава первая", "Подраздел"), book.chapters.map { it.title })
    }

    @Test
    fun `rtf bold and monospace become spans and font table is not text`() {
        val head = "{\\fonttbl{\\f0 Arial;}{\\f1\\fmodern Courier New;}}{\\colortbl;\\red0\\green0\\blue0;}"
        val book = RtfParser.parse(rtf("обычный {\\b жирный} {\\f1 код}\\par", head).encodeToByteArray(), "x")
        val block = book.blocks.single()
        assertEquals("обычный жирный код", block.text)
        assertTrue(block.spans.any { it.style == SpanStyleType.BOLD && block.text.substring(it.start, it.end) == "жирный" })
        assertTrue(block.spans.any { it.style == SpanStyleType.CODE && block.text.substring(it.start, it.end) == "код" })
    }

    @Test
    fun `rtf table rows become paragraphs and headers are skipped`() {
        val body = "{\\header колонтитул}\\trowd\\intbl ячейка 1\\cell ячейка 2\\cell\\row"
        val book = RtfParser.parse(rtf(body).encodeToByteArray(), "x")
        assertEquals("ячейка 1 | ячейка 2", book.blocks.single().text)
    }

    @Test
    fun `rtf picture becomes an image block`() {
        val body = "до\\par {\\pict\\pngblip\\picw10 ${hex(png)}}\\par после\\par"
        val book = RtfParser.parse(rtf(body).encodeToByteArray(), "x")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.IMAGE, BlockType.PARAGRAPH), book.blocks.map { it.type })
        val id = assertNotNull(book.blocks[1].image)
        assertTrue(book.imageData.getValue(id).contentEquals(png))
    }

    @Test
    fun `rtf without text and broken rtf give readable errors`() {
        assertFailsWith<IllegalArgumentException> { RtfParser.parse("{\\rtf1}".encodeToByteArray(), "x") }
        assertFailsWith<IllegalArgumentException> { RtfParser.parse("не rtf".encodeToByteArray(), "x") }
        // Оборванный файл и лишние скобки не должны зацикливаться или падать
        val truncated = rtf("текст \\par {\\b ещё {{{").encodeToByteArray()
        assertEquals("текст", RtfParser.parse(truncated, "x").blocks.first().text)
        assertEquals("a", RtfParser.parse("{\\rtf1 a}}}}".encodeToByteArray(), "x").blocks.single().text)
    }

    // ---------- XML ----------

    @Test
    fun `generic xml keeps headings lists quotes and code`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <article><title>Статья</title>
              <section><title>Раздел</title><para>Первый абзац.</para>
                <itemizedlist><listitem><para>пункт</para></listitem></itemizedlist>
                <blockquote>цитата</blockquote><programlisting>val x = 1</programlisting></section></article>"""
        val book = XmlBookParser.parse(xml.encodeToByteArray(), "файл")
        assertEquals("Статья", book.title)
        val types = book.blocks.map { it.type }
        assertTrue(BlockType.HEADING in types && BlockType.LIST_ITEM in types && BlockType.QUOTE in types && BlockType.CODE in types)
        assertEquals("Первый абзац.", book.blocks.first { it.type == BlockType.PARAGRAPH }.text)
    }

    @Test
    fun `xml in windows-1251 and word 2003 xml`() {
        val head = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><r><p>"
        val body = byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())
        val data = head.encodeToByteArray() + body + "</p></r>".encodeToByteArray()
        assertEquals("Привет", XmlBookParser.parse(data, "x").blocks.single().text)

        val word = """<?xml version="1.0"?><w:wordDocument xmlns:w="u"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Заголовок</w:t></w:r></w:p>
            <w:p><w:r><w:t>Текст </w:t></w:r><w:r><w:t>абзаца</w:t></w:r></w:p></w:body></w:wordDocument>"""
        val book = XmlBookParser.parse(word.encodeToByteArray(), "x")
        assertEquals(listOf("Заголовок", "Текст абзаца"), book.blocks.map { it.text })
        assertEquals(BlockType.HEADING, book.blocks[0].type)
    }

    @Test
    fun `xml fictionbook and empty xml`() {
        val fb2 = "<?xml version=\"1.0\"?><FictionBook><description><title-info><book-title>Книга</book-title></title-info></description>" +
            "<body><section><p>Текст</p></section></body></FictionBook>"
        assertEquals("Книга", XmlBookParser.parse(fb2.encodeToByteArray(), "x").title)
        assertFailsWith<IllegalArgumentException> { XmlBookParser.parse("<?xml version=\"1.0\"?><a/>".encodeToByteArray(), "x") }
    }

    // ---------- WPS ----------

    @Test
    fun `wps with a word document stream is read like doc`() {
        val book = WpsParser.parse(TestFiles.doc(listOf("Привет из WPS", "Второй абзац")), "документ")
        assertEquals(BookFormat.WPS, book.format)
        assertEquals(listOf("Привет из WPS", "Второй абзац"), book.blocks.map { it.text })
    }

    @Test
    fun `works wps takes printable runs and unknown wps gives a clear error`() {
        val text = "Это длинная строка из документа Microsoft Works для проверки."
        val cp1251 = ByteArray(text.length) { i ->
            val c = text[i]
            if (c in 'А'..'я') (c.code - 'А'.code + 0xC0).toByte() else c.code.toByte()
        }
        val ole = TestFiles.ole2(mapOf("CONTENTS" to byteArrayOf(0, 1, 2) + cp1251 + byteArrayOf(0, 0)))
        assertEquals(BookFormat.WPS, FormatDetector.resolve("works.wps", ole))
        assertEquals(text, WpsParser.parse(ole, "x").blocks.single().text)

        val unknown = TestFiles.ole2(mapOf("Foo" to ByteArray(10)))
        assertFailsWith<IllegalArgumentException> { WpsParser.parse(unknown, "x") }
    }

    // ---------- Pages ----------

    @Test
    fun `old pages index xml paragraphs are read`() {
        val xml = """<?xml version="1.0"?><sl:document xmlns:sl="a" xmlns:sf="b"><sf:text-body>
            <sf:p>Первый абзац</sf:p><sf:p>Второй <sf:span>абзац</sf:span></sf:p></sf:text-body></sl:document>"""
        val zip = TestFiles.zip(mapOf("index.xml" to xml, "QuickLook/Thumbnail.jpg" to "x"))
        assertEquals(BookFormat.PAGES, FormatDetector.resolve("a.pages", zip))
        val book = PagesParser.parse(ZipArchive(zip), "заметки")
        assertEquals(listOf("Первый абзац", "Второй абзац"), book.blocks.map { it.text })
    }

    @Test
    fun `new pages iwa storage text is extracted without headers`() {
        val body = IwaBuilder.storage(kind = 0, texts = listOf("Глава 1\nТекст страницы\u2029Ещё абзац\uFFFC"))
        val header = IwaBuilder.storage(kind = 1, texts = listOf("Колонтитул"))
        val iwa = IwaBuilder.iwa(listOf(2001L to header, 2001L to body))
        val zip = TestFiles.zipBytes(mapOf("Index/Document.iwa" to iwa, "Metadata/Properties.plist" to "<plist/>".encodeToByteArray()))
        assertEquals(BookFormat.PAGES, FormatDetector.resolve("a.pages", zip))
        val book = PagesParser.parse(ZipArchive(zip), "документ")
        assertEquals(listOf("Глава 1", "Текст страницы", "Ещё абзац"), book.blocks.map { it.text })
        assertEquals("Глава 1", book.chapters.single().title)
    }

    @Test
    fun `corrupted iwa does not crash`() {
        val zip = TestFiles.zipBytes(mapOf("Index/Document.iwa" to byteArrayOf(0, 5, 0, 0, 1, 2, 3, 4, 5)))
        assertFailsWith<IllegalArgumentException> { PagesParser.parse(ZipArchive(zip), "x") }
        val whole = IwaBuilder.iwa(listOf(2001L to IwaBuilder.storage(0, listOf("текст"))))
        assertFailsWith<IllegalArgumentException> {
            PagesParser.parse(ZipArchive(TestFiles.zipBytes(mapOf("Index/Document.iwa" to whole.copyOf(whole.size - 5)))), "x")
        }
    }

    @Test
    fun `snappy back references are decoded`() {
        // длина 8, литерал «abcd», копия 4 байт со смещением 4
        val block = byteArrayOf(8, (3 shl 2).toByte()) + "abcd".encodeToByteArray() + byteArrayOf(0x01, 4)
        assertEquals("abcdabcd", Snappy.decompress(block, 0, block.size).decodeToString())
    }
}

/** Сборка IWA-файла для тестов: protobuf-архивы в Snappy-блоке из одних литералов. */
internal object IwaBuilder {

    private fun varint(v: Long): ByteArray {
        val out = ArrayList<Byte>()
        var x = v
        while (x >= 0x80) { out.add(((x and 0x7F) or 0x80).toByte()); x = x shr 7 }
        out.add(x.toByte())
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray) = varint((number.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun varintField(number: Int, v: Long) = varint(number.toLong() shl 3) + varint(v)

    /** TSWP.StorageArchive: kind (1) и повторяющиеся строки text (3). */
    fun storage(kind: Int, texts: List<String>): ByteArray =
        varintField(1, kind.toLong()) + texts.fold(ByteArray(0)) { acc, t -> acc + field(3, t.encodeToByteArray()) }

    fun iwa(messages: List<Pair<Long, ByteArray>>): ByteArray {
        var raw = ByteArray(0)
        for ((type, payload) in messages) {
            val info = field(2, varintField(1, type) + varintField(3, payload.size.toLong()))
            raw += varint(info.size.toLong()) + info + payload
        }
        // Snappy: длина, затем литералы блоками до 60 байт
        var snappy = varint(raw.size.toLong())
        var p = 0
        while (p < raw.size) {
            val n = minOf(60, raw.size - p)
            snappy += byteArrayOf(((n - 1) shl 2).toByte()) + raw.copyOfRange(p, p + n)
            p += n
        }
        return byteArrayOf(0, snappy.size.toByte(), (snappy.size shr 8).toByte(), (snappy.size shr 16).toByte()) + snappy
    }
}
