package com.localdoc.ai.book

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mobi / AZW3 и CBZ на тестовых файлах, собранных в самих тестах. */
class MobiCbzTest {

    private val chapters = listOf(
        "Первая глава" to "Это текст первой главы. Это текст первой главы, повторённый для сжатия.",
        "Вторая глава" to "Здесь начинается вторая глава с другим текстом."
    )

    private fun mobi(spec: (ByteArray) -> TestMobi.Spec): ByteArray = TestMobi.build(spec(TestMobi.htmlWithToc(chapters)))

    // ---------- PalmDOC ----------

    @Test
    fun `palmdoc compression round trips`() {
        val text = ("Привет, мир! abc abc abc abc, кошка и собака. " + "x".repeat(40) + " the quick brown fox").encodeToByteArray()
        val out = IndexedByteBuffer()
        MobiParser.palmDocDecompress(TestMobi.palmDocCompress(text), out, Int.MAX_VALUE)
        assertContentEquals(text, out.toByteArray())
    }

    @Test
    fun `palmdoc decompress stops at the limit and survives garbage`() {
        val out = IndexedByteBuffer()
        MobiParser.palmDocDecompress(ByteArray(2000) { 0x41 }, out, 100)
        assertEquals(100, out.size)
        // Ссылка назад «в никуда» не должна падать
        MobiParser.palmDocDecompress(byteArrayOf(0x80.toByte(), 0x08, 0xBF.toByte()), IndexedByteBuffer(), 1000)
    }

    // ---------- Mobi ----------

    @Test
    fun `mobi is detected by signature and extension`() {
        val bytes = mobi { TestMobi.Spec(it) }
        assertEquals(BookFormat.MOBI, FormatDetector.resolve("книга.azw3", bytes))
        assertEquals(BookFormat.MOBI, FormatDetector.bySignature(bytes))
        assertEquals(BookFormat.MOBI, FormatDetector.byName("a.mobi"))
        assertEquals(BookFormat.MOBI, FormatDetector.byName("a.PRC"))
        assertTrue(MobiParser.isMobi(bytes))
        assertTrue(!MobiParser.isMobi(ByteArray(200)))
    }

    @Test
    fun `mobi text title author and toc are read`() {
        val book = BookImporter.parseBytes(mobi { TestMobi.Spec(it, recordSize = 300) }, BookFormat.MOBI, "файл", "src")
        assertEquals("Тестовая книга", book.title)
        assertEquals("Автор Тестов", book.author)
        assertEquals(BookFormat.MOBI, book.format)
        assertTrue(book.plainText.contains("текст первой главы"))
        assertTrue(book.plainText.contains("вторая глава с другим текстом"))
        assertEquals(listOf("Первая глава", "Вторая глава"), book.chapters.map { it.title })
        val second = book.blocks[book.chapters[1].startBlock]
        assertEquals("Вторая глава", second.text)
    }

    @Test
    fun `mobi without compression and with trailing entries`() {
        val plain = BookImporter.parseBytes(mobi { TestMobi.Spec(it, compression = 1, recordSize = 500) }, BookFormat.MOBI, "x", "s")
        assertTrue(plain.plainText.contains("первой главы"))
        val trailing = BookImporter.parseBytes(mobi { TestMobi.Spec(it, recordSize = 500, trailing = true) }, BookFormat.MOBI, "x", "s")
        assertEquals(plain.plainText, trailing.plainText)
    }

    @Test
    fun `mobi images and cover come from image records`() {
        val html = "<html><body><p>До</p><img recindex=\"0002\"/><p>После</p></body></html>".encodeToByteArray()
        val second = TestMobi.png.copyOf().also { it[20] = 99 }
        val bytes = TestMobi.build(TestMobi.Spec(html, images = listOf(TestMobi.png, second), coverIndex = 0))
        val book = BookImporter.parseBytes(bytes, BookFormat.MOBI, "x", "s")
        val image = book.blocks.single { it.isImage }
        assertContentEquals(second, book.imageData[image.image])
        assertNotNull(book.cover)
        assertContentEquals(TestMobi.png, book.imageData[book.cover])
        assertEquals(2, book.images.size)
    }

    @Test
    fun `azw3 embedded images use kindle embed links`() {
        val html = "<html><body><p>Текст</p><img src=\"kindle:embed:0001?mime=image/png\"/></body></html>".encodeToByteArray()
        val book = BookImporter.parseBytes(TestMobi.build(TestMobi.Spec(html, images = listOf(TestMobi.png))), BookFormat.MOBI, "x", "s")
        assertEquals(1, book.blocks.count { it.isImage })
    }

    @Test
    fun `drm and huffman mobi give a readable message`() {
        val html = "<html><body><p>Текст</p></body></html>".encodeToByteArray()
        assertFailsWith<UnsupportedFormatException> { MobiParser.parse(TestMobi.build(TestMobi.Spec(html, encryption = 2)), "x") }
        assertFailsWith<UnsupportedFormatException> { MobiParser.parse(TestMobi.build(TestMobi.Spec(html, compression = 17480)), "x") }
    }

    @Test
    fun `truncated and broken mobi do not crash`() {
        val bytes = mobi { TestMobi.Spec(it) }
        for (cut in listOf(10, 100, 200, bytes.size / 2)) {
            runCatching { MobiParser.parse(bytes.copyOf(cut), "x") }
        }
        val broken = bytes.copyOf().also { for (i in 100 until 130) it[i] = 0xFF.toByte() }
        runCatching { MobiParser.parse(broken, "x") }
    }

    // ---------- CBZ ----------

    private fun page(n: Int): ByteArray = TestMobi.png.copyOf().also { it[20] = n.toByte() }

    @Test
    fun `cbz pages follow natural order and first page is the cover`() {
        val zip = TestFiles.zipBytes(
            linkedMapOf(
                "pages/10.png" to page(10), "pages/2.png" to page(2), "pages/1.png" to page(1),
                "pages/03.PNG" to page(3), "__MACOSX/pages/1.png" to page(99), "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray()
            )
        )
        assertEquals(BookFormat.CBZ, FormatDetector.resolve("комикс.cbz", zip))
        val book = BookImporter.parseBytes(zip, BookFormat.CBZ, "комикс", "s")
        assertEquals(BookFormat.CBZ, book.format)
        assertEquals(4, book.blocks.size)
        assertTrue(book.blocks.all { it.isImage })
        // Порядок: 1, 2, 03, 10
        val order = book.blocks.map { book.imageData[it.image]!![20].toInt() }
        assertEquals(listOf(1, 2, 3, 10), order)
        assertEquals(book.blocks.first().image, book.cover)
    }

    @Test
    fun `archive of text documents is not mistaken for a comic`() {
        val epubLike = TestFiles.zipBytes(mapOf("a.png" to page(1), "chapter.html" to "<p>x</p>".encodeToByteArray()))
        assertFailsWith<UnsupportedFormatException> { FormatDetector.resolve("x.zip", epubLike) }
        assertNull(CbzParser.pageNames(ZipArchive(TestFiles.zip(mapOf("a.txt" to "x")))).firstOrNull())
    }

    @Test
    fun `natural order compares numbers by value`() {
        val sorted = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p02a.jpg", "p001.jpg").sortedWith(CbzParser.NaturalOrder)
        assertEquals(listOf("P1.jpg", "p001.jpg", "p2.jpg", "p02a.jpg", "p10.jpg"), sorted)
    }
}
