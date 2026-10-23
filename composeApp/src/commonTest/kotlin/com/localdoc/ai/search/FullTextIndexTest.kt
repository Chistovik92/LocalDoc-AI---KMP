package com.localdoc.ai.search

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.catalog.CatalogItem
import com.localdoc.ai.domain.NovelFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FullTextIndexTest {

    private class MemoryFiles : NovelFiles {
        val data = HashMap<String, ByteArray>()
        override fun write(name: String, bytes: ByteArray) { data[name] = bytes }
        override fun read(name: String) = data[name]
        override fun deleteAll(prefix: String) { data.keys.filter { it.startsWith(prefix) }.forEach(data::remove) }
    }

    private class Fixture {
        val files = MemoryFiles()
        var manifest: String? = null
        val books = HashMap<String, List<String>>()
        var entries = emptyList<BookEntry>()
        var catalog = emptyList<CatalogItem>()
        var loads = 0

        fun index() = FullTextIndex(files, { manifest }, { manifest = it })
        fun search(index: FullTextIndex = index()) =
            LibrarySearch(index, { entries }, { id -> loads++; books[id] }, { catalog })

        fun addBook(id: String, title: String, vararg blocks: String) {
            books[id] = blocks.toList()
            entries = entries.filterNot { it.id == id } + BookEntry(id, title, "", BookFormat.EPUB, "src-$id", blocks.size)
        }
    }

    private fun catalogItem(uri: String, title: String, author: String) =
        CatalogItem(uri, "$title.epub", 10, 1, "/books", "device", BookFormat.EPUB, title, author)

    @Test
    fun `tokenizer lowercases folds yo and drops punctuation and one letter words`() {
        assertEquals(listOf("елка", "горит", "в2"), SearchTokenizer.tokens("Ёлка, горит! А в2 я"))
        assertEquals("книг", SearchTokenizer.stem("книга"))
        assertEquals("мир", SearchTokenizer.stem("мир"))
        assertEquals("читате", SearchTokenizer.stem("читатели"))
    }

    @Test
    fun `search finds inflected forms and requires all words in one block`() = runTest {
        val f = Fixture()
        f.addBook("a", "Море", "Старик ловил рыбу в открытом море.", "Рыбаки возвращались домой.", "Море было спокойным, а рыба клевала.")
        val search = f.search()
        search.sync()

        val hits = search.search("рыбу море").books
        assertEquals(listOf(0, 2), hits.map { it.block })
        assertTrue("море" in hits.first().snippet)
        assertEquals(listOf(1), search.search("рыба домой").books.map { it.block })

        // «рыба» находит «рыбу», «рыба», «рыбаки» по основе
        assertEquals(listOf(0, 1, 2), search.search("рыба").books.map { it.block }.sorted())
        assertTrue(search.search("несуществующее").isEmpty)
        assertTrue(search.search("  ").isEmpty)
    }

    @Test
    fun `second sync reads nothing and changed or removed books are updated incrementally`() = runTest {
        val f = Fixture()
        f.addBook("a", "Первая", "Космос бесконечен.")
        f.addBook("b", "Вторая", "Океан глубок.")
        val search = f.search()
        assertEquals(2, search.sync())

        f.loads = 0
        assertEquals(0, search.sync())
        assertEquals(0, f.loads, "неизменные книги не читаются заново")

        // Книга b пополнилась — подпись изменилась, переиндексируется только она
        f.addBook("b", "Вторая", "Океан глубок.", "В океане живут киты.")
        f.loads = 0
        assertEquals(1, search.sync())
        assertEquals(1, f.loads)
        assertEquals(listOf("b"), search.search("киты").books.map { it.entry.id })

        // Повторный импорт с тем же числом блоков помечает книгу устаревшей
        f.books["a"] = listOf("Галактика бесконечна.")
        search.invalidate("a")
        assertEquals(1, search.sync())
        assertEquals(listOf("a"), search.search("галактика").books.map { it.entry.id })

        // Удалённая книга уходит из индекса вместе с файлом
        f.entries = f.entries.filterNot { it.id == "a" }
        search.sync()
        assertTrue(search.search("галактика").isEmpty)
        assertFalse(f.files.data.keys.any { it.contains("lib.a") })
    }

    @Test
    fun `index survives restart and a damaged file is rebuilt`() = runTest {
        val f = Fixture()
        f.addBook("a", "Лес", "Грибы растут в лесу.")
        f.search().sync()

        // «Перезапуск»: новый индекс читает манифест и файлы с диска
        val restarted = f.search(f.index())
        f.loads = 0
        assertEquals(0, restarted.sync())
        assertEquals(1, restarted.search("грибы").books.size)

        f.files.data.keys.filter { it.startsWith("idx.lib.a") }.forEach { f.files.data[it] = "{не json".encodeToByteArray() }
        val damaged = f.search(f.index())
        assertTrue(damaged.search("грибы").isEmpty)
        assertEquals(1, damaged.sync())
        assertEquals(1, damaged.search("грибы").books.size)
    }

    @Test
    fun `catalog is searched by metadata and reindexed only when the scan changes`() = runTest {
        val f = Fixture()
        f.catalog = listOf(catalogItem("u1", "Мастер и Маргарита", "Булгаков"), catalogItem("u2", "Белая гвардия", "Булгаков"), catalogItem("u3", "Дюна", "Герберт"))
        val search = f.search()
        assertEquals(1, search.sync())
        assertEquals(setOf("u1", "u2"), search.search("булгаков").catalog.map { it.uri }.toSet())
        assertEquals(listOf("u3"), search.search("дюна герберт").catalog.map { it.uri })

        assertEquals(0, search.sync())
        f.catalog = f.catalog + catalogItem("u4", "Собачье сердце", "Булгаков")
        assertEquals(1, search.sync())
        assertEquals(3, search.search("булгаков").catalog.size)
        assertNotNull(search.search("собачье").catalog.firstOrNull { it.uri == "u4" })

        f.catalog = emptyList()
        search.sync()
        assertTrue(search.search("булгаков").isEmpty)
    }

    @Test
    fun `books are ranked by the number of matching blocks and limited`() = runTest {
        val f = Fixture()
        f.addBook("few", "Мало", "Дракон спит.", "Тишина.")
        f.addBook("many", "Много", "Дракон летит.", "Дракон кричит.", "Снова дракон.", "Дракон устал.")
        val search = f.search()
        search.sync()

        val result = search.search("дракон", perBook = 2).books
        assertEquals(listOf("many", "many", "few"), result.map { it.entry.id })
        assertEquals(1, search.search("дракон", perBook = 5, limit = 1).books.size)
    }
}
