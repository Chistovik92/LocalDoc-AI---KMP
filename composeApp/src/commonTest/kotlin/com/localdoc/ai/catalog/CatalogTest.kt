package com.localdoc.ai.catalog

import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.TestFiles
import com.localdoc.ai.domain.MemoryNovelFiles
import com.localdoc.ai.platform.ScannedFile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {

    private val fb2 = """<?xml version="1.0" encoding="UTF-8"?><FictionBook><description><title-info>
        <genre>sf_fantasy</genre><genre>popadanec</genre><author><first-name>Иван</first-name><last-name>Петров</last-name></author>
        <book-title>Путь героя</book-title><date value="2015-03-01">2015</date><lang>ru</lang>
        <sequence name="Хроники Севера" number="2"/></title-info></description><body><section><p>Текст</p></section></body></FictionBook>"""

    // ---------- метаданные ----------

    @Test
    fun `file names give author title and year`() {
        val m = BookMetadata.fromName("Стругацкие - Пикник на обочине (1972).fb2")
        assertEquals("Пикник на обочине", m.title)
        assertEquals("Стругацкие", m.author)
        assertEquals(1972, m.year)
        val plain = BookMetadata.fromName("some_long_file_name.pdf")
        assertEquals("some long file name", plain.title)
        assertEquals("", plain.author)
    }

    @Test
    fun `fb2 header gives genres series author and year`() {
        val m = BookMetadata.read("x.fb2", fb2.encodeToByteArray())
        assertEquals("Путь героя", m.title)
        assertEquals("Иван Петров", m.author)
        assertEquals(listOf("Фэнтези", "Попаданцы"), m.genres)
        assertEquals(2015, m.year)
        assertEquals("ru", m.language)
        assertEquals("Хроники Севера", m.series)
    }

    @Test
    fun `epub docx and pdf metadata are read`() {
        val epub = TestFiles.zip(
            mapOf(
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""",
                "c.opf" to """<package><metadata><dc:title>Книга</dc:title><dc:creator>А. Автор</dc:creator><dc:subject>Фэнтези, Приключения</dc:subject>
                    <dc:date>2020-05-01</dc:date><dc:language>en-US</dc:language><meta name="calibre:series" content="Цикл"/></metadata></package>"""
            )
        )
        val e = BookMetadata.read("b.epub", epub)
        assertEquals(listOf("Книга", "А. Автор", "en", "Цикл"), listOf(e.title, e.author, e.language, e.series))
        assertEquals(2020, e.year)
        assertEquals(listOf("Фэнтези", "Приключения"), e.genres)

        val docx = BookMetadata.read("d.docx", TestFiles.docx("<w:p/>", title = "Отчёт"))
        assertEquals("Отчёт", docx.title)
        assertEquals("Автор Тестов", docx.author)

        val pdf = "%PDF-1.4\n1 0 obj << /Title (Report) /Author (Ivanov) /CreationDate (D:20190102120000) >> endobj".encodeToByteArray()
        val p = BookMetadata.read("m.pdf", pdf)
        assertEquals(listOf("Report", "Ivanov", 2019), listOf(p.title, p.author, p.year))
    }

    @Test
    fun `broken files fall back to the name`() {
        val m = BookMetadata.read("Автор - Название.epub", ByteArray(50) { 1 })
        assertEquals("Название", m.title)
        assertEquals("Автор", m.author)
    }

    // ---------- полки и фильтры ----------

    private fun item(
        uri: String, title: String, author: String = "", genres: List<String> = emptyList(), year: Int? = null,
        series: String = "", format: BookFormat = BookFormat.EPUB, folder: String = "/books", size: Long = 1000, found: Long = 0
    ) = CatalogItem(uri, "$title.epub", size, 0, folder, "device", format, title, author, genres, year, "", series, true, found)

    private val items = listOf(
        item("1", "Альфа", "Иванов", listOf("Фэнтези", "Приключения"), 2015, "Цикл А", found = 3),
        item("2", "Бета", "Иванов", listOf("Фэнтези"), 2018, "Цикл А", BookFormat.FB2, found = 1),
        item("3", "Гамма", "Петров", listOf("Детективы"), 1999, folder = "/docs", found = 2),
        item("4", "Дельта", "", emptyList(), null, format = BookFormat.PDF, size = 5000)
    )

    @Test
    fun `shelves are grouped like store shelves`() {
        assertEquals(
            listOf("Иванов" to 2, "Петров" to 1, CatalogQuery.NO_VALUE to 1),
            CatalogQuery.shelf(items, ShelfKind.AUTHOR).map { it.value to it.count }
        )
        assertEquals("Фэнтези" to 2, CatalogQuery.shelf(items, ShelfKind.GENRE).first().let { it.value to it.count })
        val years = CatalogQuery.shelf(items, ShelfKind.YEAR).map { it.value }
        assertEquals(setOf("2010-е", "1990-е", CatalogQuery.NO_VALUE), years.toSet())
        assertEquals(CatalogQuery.NO_VALUE, years.last())
        assertEquals(setOf("EPUB", "FB2", "PDF"), CatalogQuery.shelf(items, ShelfKind.FORMAT).map { it.value }.toSet())
        assertEquals("Цикл А" to 2, CatalogQuery.shelf(items, ShelfKind.SERIES).first().let { it.value to it.count })
    }

    @Test
    fun `filters combine text search shelf value and sort`() {
        fun titles(f: CatalogFilter) = CatalogQuery.filter(items, f).map { it.title }
        assertEquals(listOf("Альфа", "Бета"), titles(CatalogFilter(kind = ShelfKind.AUTHOR, value = "Иванов")))
        assertEquals(listOf("Бета", "Альфа"), titles(CatalogFilter(kind = ShelfKind.GENRE, value = "Фэнтези", sort = CatalogSort.YEAR)))
        assertEquals(listOf("Гамма"), titles(CatalogFilter(query = "гам")))
        assertEquals(listOf("Дельта"), titles(CatalogFilter(kind = ShelfKind.FORMAT, value = "PDF")))
        assertEquals(listOf("Дельта"), titles(CatalogFilter(kind = ShelfKind.AUTHOR, value = CatalogQuery.NO_VALUE)))
        assertEquals(listOf("Альфа", "Гамма", "Бета"), titles(CatalogFilter(kind = ShelfKind.RECENT)).take(3))
        assertEquals("Дельта", titles(CatalogFilter(sort = CatalogSort.SIZE)).first())
        val added = items.map { if (it.uri == "1") it.copy(libraryId = "x") else it }
        assertEquals(listOf("Бета", "Гамма", "Дельта"), CatalogQuery.filter(added, CatalogFilter(hideAdded = true)).map { it.title })
    }

    // ---------- сканирование ----------

    private class FakeSource(var files: List<ScannedFile>) : ScanSource {
        override suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int {
            files.filter { f -> extensions.any { f.name.lowercase().endsWith(".$it") } }.forEach(onFound)
            return files.size
        }
    }

    @Test
    fun `scan finds books enriches them and tracks vanished files`() = runTest {
        val source = FakeSource(
            listOf(
                ScannedFile("/b/a.fb2", "a.fb2", fb2.length.toLong(), 5, "/b"),
                ScannedFile("/b/Автор - Книга.epub", "Автор - Книга.epub", 100, 5, "/b"),
                ScannedFile("/b/log.txt", "log.txt", 10, 5, "/b")
            )
        )
        val contents = mapOf("/b/a.fb2" to fb2.encodeToByteArray())
        val files = MemoryNovelFiles()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo = CatalogRepository(source, { uri, _ -> contents[uri] }, files, scope, now = { 1000 })

        repo.scan("device", "Всё устройство", includeText = false)
        scope.advanceUntilIdle()
        assertEquals(setOf("a.fb2", "Автор - Книга.epub"), repo.items.value.map { it.name }.toSet(), ".txt по умолчанию не ищется")
        val fb = repo.items.value.first { it.name == "a.fb2" }
        assertEquals(listOf("Путь героя", "Иван Петров"), listOf(fb.title, fb.author))
        assertTrue(fb.enriched)
        assertEquals("Книга", repo.items.value.first { it.format == BookFormat.EPUB }.title)
        assertEquals(1, repo.roots.value.size)
        assertTrue(repo.state.value.message.orEmpty().contains("Найдено книг: 2"))

        // Файл исчез, появился новый, TXT включён
        source.files = listOf(source.files[0], ScannedFile("/b/new.fb2", "new.fb2", 5, 1, "/b"), source.files[2])
        repo.scan("device", "Всё устройство", includeText = true)
        scope.advanceUntilIdle()
        assertEquals(setOf("a.fb2", "new.fb2", "log.txt"), repo.items.value.map { it.name }.toSet())

        // Каталог переживает перезапуск
        val restarted = CatalogRepository(source, { _, _ -> null }, files, scope)
        assertEquals(3, restarted.items.value.size)
        assertEquals("Иван Петров", restarted.items.value.first { it.name == "a.fb2" }.author)
    }

    @Test
    fun `user shelves collect files under a name and survive restart`() = runTest {
        val files = MemoryNovelFiles()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val repo = CatalogRepository(FakeSource(emptyList()), { _, _ -> null }, files, scope)
        repo.createShelf("  На выходные ", listOf("1", "2"))
        repo.createShelf("На выходные", listOf("2", "3"))
        repo.createShelf("", listOf("9"))
        assertEquals(listOf("На выходные" to listOf("1", "2", "3")), repo.shelves.value.map { it.name to it.uris })
        assertEquals(
            listOf("1", "2", "3"),
            CatalogQuery.filter(items.take(3), CatalogFilter(kind = ShelfKind.MINE, value = "На выходные"), repo.shelves.value).map { it.uri }.sorted()
        )

        assertEquals(1, CatalogRepository(FakeSource(emptyList()), { _, _ -> null }, files, scope).shelves.value.size)
        repo.deleteShelf("На выходные")
        assertTrue(repo.shelves.value.isEmpty())
    }
}
