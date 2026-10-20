package com.localdoc.ai.book

import com.localdoc.ai.parser.WebArticleParser
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Картинки и обложки: EPUB, FB2, DOCX, ODT, HTML, веб-страницы, Markdown и лимиты сборщика. */
class ImagesTest {

    private fun fakePng(seed: Int) = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
        seed.toByte(), 2, 3, 4, 5, 6, 7, 8
    )

    private fun fakeJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 74, 70, 73, 70, 0, 1, 1, 0)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun b64(bytes: ByteArray) = Base64.encode(bytes)

    // ---------- сборщик ----------

    @Test
    fun `collector dedupes limits and ignores non images`() {
        val c = ImageCollector(maxEach = 40, maxTotal = 50)
        val a = assertNotNull(c.add(fakePng(1)))
        assertEquals(a, c.add(fakePng(1)))
        assertNull(c.add("не картинка и не бинарь".encodeToByteArray()))
        assertNull(c.add(ByteArray(100) { 1 }))
        val b = assertNotNull(c.add(fakePng(2)))
        assertTrue(a != b)
        assertNull(c.add(fakePng(3)), "общий лимит 50 байт: третья картинка не помещается")
        assertEquals(listOf(a, b), c.ids)
    }

    @Test
    fun `image format is told by signature`() {
        assertEquals("image/png", ImageFormat.mimeOf(fakePng(1)))
        assertEquals("image/jpeg", ImageFormat.mimeOf(fakeJpeg()))
        assertNull(ImageFormat.mimeOf(ByteArray(40)))
        assertEquals("jpg", ImageFormat.extensionOf("image/jpeg"))
    }

    // ---------- EPUB ----------

    @Test
    fun `epub inline images and cover are collected`() {
        val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
        val opf = """<package><metadata><dc:title>Книга</dc:title><meta name="cover" content="cov"/></metadata>
            <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="cov" href="img/cover.png" media-type="image/png"/></manifest>
            <spine><itemref idref="c1"/></spine></package>"""
        val ch = """<html><body><h1>Глава</h1><p>Текст.</p><img src="img/pic%201.png" alt="Подпись"/><p>Дальше.</p>
            <img src="img/icon-small.png"/><img src="http://example.com/remote.png" alt="r"/></body></html>"""
        val zip = TestFiles.zipBytes(
            mapOf(
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch.encodeToByteArray(),
                "OEBPS/img/cover.png" to fakePng(9),
                "OEBPS/img/pic 1.png" to fakePng(5)
            )
        )
        val book = EpubParser.parse(ZipArchive(zip), "x")
        val images = book.blocks.filter { it.type == BlockType.IMAGE }
        assertEquals(2, images.size)
        assertEquals("Подпись", images[0].text)
        assertTrue(book.imageData.getValue(images[0].image!!).contentEquals(fakePng(5)))
        assertEquals("http://example.com/remote.png", images[1].image)
        val cover = assertNotNull(book.cover)
        assertTrue(book.imageData.getValue(cover).contentEquals(fakePng(9)))
        assertEquals(listOf("Глава"), book.chapters.map { it.title })
    }

    // ---------- FB2 ----------

    @Test
    fun `fb2 binaries become cover and image blocks`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Т</book-title>
            <coverpage><image l:href="#cover.png"/></coverpage></title-info></description>
            <body><section><title><p>Раздел</p></title><p>Текст</p><image l:href="#pic.png"/><p>Конец</p></section></body>
            <binary id="cover.png" content-type="image/png">${b64(fakePng(1))}</binary>
            <binary id="pic.png" content-type="image/png">${b64(fakePng(2))}</binary></FictionBook>"""
        val book = Fb2Parser.parse(xml.encodeToByteArray(), "x")
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.IMAGE, BlockType.PARAGRAPH), book.blocks.map { it.type })
        assertTrue(book.imageData.getValue(book.cover!!).contentEquals(fakePng(1)))
        assertTrue(book.imageData.getValue(book.blocks[2].image!!).contentEquals(fakePng(2)))
    }

    // ---------- DOCX / ODT ----------

    @Test
    fun `docx drawing images are read through relationships`() {
        val body = """<w:p><w:r><w:t>Перед</w:t></w:r></w:p>
            <w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>"""
        val zip = TestFiles.zipBytes(
            mapOf(
                "word/document.xml" to """<w:document xmlns:w="w" xmlns:a="a" xmlns:r="r"><w:body>$body</w:body></w:document>""".encodeToByteArray(),
                "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId7" Type="image" Target="media/image1.png"/></Relationships>""".encodeToByteArray(),
                "word/media/image1.png" to fakePng(4),
                "docProps/thumbnail.jpeg" to fakeJpeg()
            )
        )
        val book = DocxParser.parse(ZipArchive(zip), "x")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.IMAGE), book.blocks.map { it.type })
        assertTrue(book.imageData.getValue(book.blocks[1].image!!).contentEquals(fakePng(4)))
        assertTrue(book.imageData.getValue(book.cover!!).contentEquals(fakeJpeg()))
    }

    @Test
    fun `odt draw images are read from pictures`() {
        val content = """<office:document-content xmlns:office="o" xmlns:text="t" xmlns:draw="d" xmlns:xlink="x"><office:body><office:text>
            <text:p>Абзац</text:p>
            <text:p><draw:frame><draw:image xlink:href="Pictures/a.png"/></draw:frame></text:p></office:text></office:body></office:document-content>"""
        val zip = TestFiles.zipBytes(
            mapOf(
                "mimetype" to "application/vnd.oasis.opendocument.text".encodeToByteArray(),
                "content.xml" to content.encodeToByteArray(),
                "Pictures/a.png" to fakePng(6),
                "Thumbnails/thumbnail.png" to fakePng(7)
            )
        )
        val book = OdtParser.parse(ZipArchive(zip), "x")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.IMAGE), book.blocks.map { it.type })
        assertTrue(book.imageData.getValue(book.cover!!).contentEquals(fakePng(7)))
    }

    // ---------- HTML и веб ----------

    @Test
    fun `html keeps data uri images and remote links and skips tiny ones`() {
        val html = """<html><head><title>Стр</title></head><body><article><p>${"Длинный текст статьи. ".repeat(40)}</p>
            <img src="data:image/png;base64,${b64(fakePng(8))}" alt="встроенная"/>
            <img src="/img/photo.jpg" alt="фото" width="600"/>
            <img src="/img/dot.png" width="1" height="1"/>
            <img src="/static/logo.png"/></article></body></html>"""
        val book = HtmlFileParser.parse(html, "x", "https://site.example/a/b.html")
        val images = book.blocks.filter { it.type == BlockType.IMAGE }
        assertEquals(2, images.size)
        assertTrue(book.imageData.getValue(images[0].image!!).contentEquals(fakePng(8)))
        assertEquals("https://site.example/img/photo.jpg", images[1].image)
    }

    @Test
    fun `web page gets og image cover and lazy images`() {
        val html = """<html><head><meta property="og:image" content="/covers/novel.jpg"/><title>Новелла</title></head>
            <body><h1>Глава 1</h1><article><p>${"Текст главы. ".repeat(60)}</p>
            <img data-src="https://cdn.example/illustration.png" src="placeholder.gif" alt="Иллюстрация"/></article></body></html>"""
        val page = WebArticleParser().fromHtml(html, "https://novel.example/book/1")
        assertEquals("https://novel.example/covers/novel.jpg", page.book.cover)
        val image = page.book.blocks.first { it.type == BlockType.IMAGE }
        assertEquals("https://cdn.example/illustration.png", image.image)
        assertEquals("Иллюстрация", image.text)
    }

    @Test
    fun `markdown standalone image line becomes a remote image`() {
        val book = MarkdownParser.parse("# Заголовок\n\n![схема](https://x.example/s.png)\n\nТекст", "t")
        assertEquals(listOf(BlockType.HEADING, BlockType.IMAGE, BlockType.PARAGRAPH), book.blocks.map { it.type })
        assertEquals("схема", book.blocks[1].text)
        assertEquals("https://x.example/s.png", book.blocks[1].image)
    }

    // ---------- DOC / WPS / Pages ----------

    private fun realPng(): ByteArray {
        fun chunk(type: String, data: ByteArray) = byteArrayOf(0, 0, 0, data.size.toByte()) + type.encodeToByteArray() + data + byteArrayOf(1, 2, 3, 4)
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            chunk("IHDR", ByteArray(13)) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun `doc data stream pictures are found`() {
        val png = realPng()
        val jpeg = fakeJpeg() + byteArrayOf(5, 6, 7, 0xFF.toByte(), 0xD9.toByte())
        val data = ByteArray(10) + png + ByteArray(7) + jpeg + ByteArray(5)
        val doc = TestFiles.docWithStreams(listOf("Текст"), mapOf("Data" to data))
        val book = DocParser.parse(doc, "x")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.IMAGE, BlockType.IMAGE), book.blocks.map { it.type })
        assertTrue(book.imageData.getValue(book.blocks[1].image!!).contentEquals(png))
    }

    @Test
    fun `pages data folder images follow the text`() {
        val xml = "<?xml version=\"1.0\"?><d xmlns:sf=\"b\"><sf:p>Текст</sf:p></d>"
        val zip = TestFiles.zipBytes(mapOf("index.xml" to xml.encodeToByteArray(), "QuickLook/Thumbnail.jpg" to fakeJpeg(), "Data/pic-1.png" to fakePng(3)))
        val book = PagesParser.parse(ZipArchive(zip), "x")
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.IMAGE), book.blocks.map { it.type })
        assertNotNull(book.cover)
    }
}
