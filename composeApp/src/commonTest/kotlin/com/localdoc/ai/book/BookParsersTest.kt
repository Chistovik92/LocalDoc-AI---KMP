package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookParsersTest {

    @Test
    fun `utf8 text is decoded as is`() {
        assertEquals("Привет, мир", TextDecoder.decode("Привет, мир".encodeToByteArray()))
    }

    @Test
    fun `windows-1251 text is detected and decoded`() {
        // «Привет» в cp1251
        val cp1251 = byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())
        assertFalse(TextDecoder.isValidUtf8(cp1251))
        assertEquals("Привет", TextDecoder.decode(cp1251))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Текст".encodeToByteArray()
        assertEquals("Текст", TextDecoder.decode(bytes))
    }

    @Test
    fun `plain text splits paragraphs and finds chapters`() {
        val book = PlainTextParser.parse("Глава 1\n\nПервый абзац\nпродолжение.\n\nГлава 2\n\nВторой абзац.", "Книга")
        assertEquals(listOf("Глава 1", "Первый абзац продолжение.", "Глава 2", "Второй абзац."), book.blocks.map { it.text })
        assertEquals(listOf(0, 2), book.chapters.map { it.startBlock })
    }

    @Test
    fun `markdown keeps structure and strips inline markers`() {
        val md = "# Заголовок\n\nТекст с **жирным** и `кодом`.\n\n- пункт один\n- пункт два\n\n> цитата\n\n```\nval x = 1\n```"
        val book = MarkdownParser.parse(md, "файл")
        assertEquals("Заголовок", book.title)
        assertEquals(
            listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST_ITEM, BlockType.LIST_ITEM, BlockType.QUOTE, BlockType.CODE),
            book.blocks.map { it.type }
        )
        val paragraph = book.blocks[1]
        assertEquals("Текст с жирным и кодом.", paragraph.text)
        assertEquals(listOf(SpanStyleType.BOLD, SpanStyleType.CODE), paragraph.spans.map { it.style })
        assertEquals("жирным", paragraph.text.substring(paragraph.spans[0].start, paragraph.spans[0].end))
    }

    @Test
    fun `fb2 reads title author sections and skips notes`() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info>
                <author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
                <book-title>Война и мир</book-title>
              </title-info></description>
              <body>
                <section><title><p>Часть первая</p></title>
                  <p>Первый абзац.</p>
                  <section><title><p>Глава I</p></title><p>Текст главы.</p></section>
                </section>
              </body>
              <body name="notes"><section><p>Сноска</p></section></body>
            </FictionBook>
        """.trimIndent()
        val book = Fb2Parser.parse(fb2.encodeToByteArray(), "fallback")
        assertEquals("Война и мир", book.title)
        assertEquals("Лев Толстой", book.author)
        assertEquals(listOf("Часть первая", "Глава I"), book.chapters.map { it.title })
        assertTrue(book.blocks.none { it.text == "Сноска" })
        assertTrue(book.blocks.any { it.text == "Текст главы." })
    }

    @Test
    fun `epub in stored zip is parsed with toc`() {
        val zip = storedZip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package><metadata><dc:title>Тестовая книга</dc:title><dc:creator>Автор</dc:creator></metadata>
                <manifest>
                  <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap>
                  <navPoint><navLabel><text>Первая</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
                  <navPoint><navLabel><text>Вторая</text></navLabel><content src="text/ch2.xhtml#start"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "OEBPS/text/ch1.xhtml" to "<html><body><h1>Глава 1</h1><p>Раз.</p><p>Два.</p></body></html>",
            "OEBPS/text/ch2.xhtml" to "<html><body><h1>Глава 2</h1><p>Три.</p></body></html>"
        )
        assertEquals(BookFormat.EPUB, FormatDetector.bySignature(zip))
        val book = EpubParser.parse(ZipArchive(zip), "fallback")
        assertEquals("Тестовая книга", book.title)
        assertEquals("Автор", book.author)
        assertEquals(listOf("Глава 1", "Раз.", "Два.", "Глава 2", "Три."), book.blocks.map { it.text })
        assertEquals(listOf(Chapter("Первая", 0), Chapter("Вторая", 3)), book.chapters)
    }

    @Test
    fun `pdf text is reflowed into paragraphs per page`() {
        val text = "--- [Страница 1] ---\nДлинная строка текста, кото-\nрая переносится дальше по странице\nи заканчивается.\n--- [Страница 2] ---\nВторая страница."
        val book = PdfTextAdapter.toBook("Документ", text, "src")
        assertEquals(listOf("Страница 1", "Страница 2"), book.chapters.map { it.title })
        assertEquals("Длинная строка текста, которая переносится дальше по странице и заканчивается.", book.blocks[0].text)
    }

    /** ZIP без сжатия (метод Stored) — достаточно для проверки разбора контейнера. */
    private fun storedZip(vararg files: Pair<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        val central = ArrayList<Byte>()
        fun MutableList<Byte>.u16(v: Int) {
            add((v and 0xFF).toByte())
            add(((v shr 8) and 0xFF).toByte())
        }
        fun MutableList<Byte>.u32(v: Int) {
            u16(v and 0xFFFF)
            u16((v ushr 16) and 0xFFFF)
        }
        for ((name, content) in files) {
            val nameBytes = name.encodeToByteArray()
            val data = content.encodeToByteArray()
            val offset = out.size
            out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(0)
            out.u32(0); out.u32(data.size); out.u32(data.size); out.u16(nameBytes.size); out.u16(0)
            nameBytes.forEach { out.add(it) }
            data.forEach { out.add(it) }

            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0); central.u16(0)
            central.u16(0); central.u16(0); central.u32(0); central.u32(data.size); central.u32(data.size)
            central.u16(nameBytes.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(0); central.u32(offset)
            nameBytes.forEach { central.add(it) }
        }
        val centralOffset = out.size
        val centralSize = central.size
        out.addAll(central)
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(files.size); out.u16(files.size)
        out.u32(centralSize); out.u32(centralOffset); out.u16(0)
        return out.toByteArray()
    }
}
