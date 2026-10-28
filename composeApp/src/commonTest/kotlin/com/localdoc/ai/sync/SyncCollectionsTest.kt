package com.localdoc.ai.sync

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.catalog.UserShelf
import com.localdoc.ai.storage.KvAccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCollectionsTest {

    private class Memory : KvAccess {
        val values = LinkedHashMap<String, String>()
        override fun getValue(key: String) = values[key]
        override fun putValue(key: String, value: String) { values[key] = value }
        override fun removeValue(key: String) { values.remove(key) }
    }

    private fun entry(id: String, source: String, progress: Int = 0) =
        BookEntry(id, "Книга $id", "Автор", BookFormat.EPUB, source, 100, progressBlock = progress)

    private fun library(kv: Memory, vararg e: BookEntry) {
        kv.values["library_v1"] = Json.encodeToString(ListSerializer(BookEntry.serializer()), e.toList())
    }

    @Test
    fun `the same book gets the same key on every device whatever the local id is`() {
        assertEquals(bookKey(entry("a1", "content://doc/1")), bookKey(entry("zz", "content://doc/1")))
        assertFalse(bookKey(entry("a1", "content://doc/1")) == bookKey(entry("a1", "content://doc/2")))
        assertNull(bookKey(entry("a1", "")))
    }

    @Test
    fun `progress is read and applied by book key with the block clamped to the book length`() {
        val a = Memory()
        library(a, entry("a1", "src-1", progress = 40), entry("a2", ""))
        val read = ProgressCollection(a).read()
        assertEquals(1, read.size)

        val b = Memory()
        library(b, entry("b9", "src-1"))
        val key = read.keys.single()
        val pending = ProgressCollection(b).apply(mapOf(key to read.getValue(key), "ffff" to """{"block":3}"""), emptySet())
        assertEquals(setOf("ffff"), pending)
        assertEquals(mapOf(key to read.getValue(key)), ProgressCollection(b).read())

        val clamped = ProgressCollection(b).apply(mapOf(key to """{"block":5000}"""), emptySet())
        assertTrue(clamped.isEmpty())
        assertEquals("""{"block":99}""", ProgressCollection(b).read().getValue(key))
    }

    @Test
    fun `bookmarks travel between devices with different local ids and without secrets`() {
        val a = Memory()
        library(a, entry("a1", "src-1"))
        a.values["bookmarks_v1"] = """[{"id":"m1","entryId":"a1","bookTitle":"Книга a1","format":"EPUB","block":7,"excerpt":"текст"}]"""
        a.values["catalog_secret"] = "пароль сайта"
        val sent = BookLinkedCollection.bookmarks(a).read()
        assertEquals(1, sent.size)
        assertFalse(sent.values.single().contains("a1\"") && sent.values.single().contains("\"entryId\":\"a1\""))
        assertFalse(sent.toString().contains("пароль"))

        val b = Memory()
        library(b, entry("b7", "src-1"))
        val pending = BookLinkedCollection.bookmarks(b).apply(sent, emptySet())
        assertTrue(pending.isEmpty())
        val stored = b.values.getValue("bookmarks_v1")
        assertTrue("\"entryId\":\"b7\"" in stored && "\"block\":7" in stored)

        // удаление приходит с другого устройства
        BookLinkedCollection.bookmarks(b).apply(emptyMap(), sent.keys)
        assertEquals("[]", b.values["bookmarks_v1"])
    }

    @Test
    fun `a bookmark of an unknown book is returned as pending and nothing is lost`() {
        val b = Memory()
        library(b, entry("b7", "other"))
        val pending = BookLinkedCollection.bookmarks(b).apply(
            mapOf("deadbeef/m1" to """{"id":"m1","entryId":"","bookTitle":"X","format":"EPUB","block":1,"excerpt":""}"""), emptySet()
        )
        assertEquals(setOf("deadbeef/m1"), pending)
        assertEquals("[]", b.values["bookmarks_v1"])
    }

    @Test
    fun `shelves carry name and library books but keep device-only files local`() {
        val key = bookKey(entry("a1", "src-1"))!!
        var shelves = listOf(UserShelf("s1", "Любимое", listOf("uri://local-only")))
        val items = listOf(item("uri://1", "a1"))
        val shelf = ShelfCollection({ shelves }, { shelves = it }, { items }, { listOf(entry("a1", "src-1")) })
        assertEquals("""{"name":"Любимое","books":[]}""", shelf.read().getValue("s1"))

        shelf.apply(mapOf("s1" to """{"name":"Любимое","books":["$key"]}"""), emptySet())
        assertEquals(listOf("uri://1", "uri://local-only"), shelves.single().uris)

        val waiting = shelf.apply(mapOf("s2" to """{"name":"Ждёт","books":["ffff"]}"""), emptySet())
        assertEquals(setOf("s2"), waiting)
        assertNotNull(shelves.firstOrNull { it.id == "s1" })
        assertNull(shelves.firstOrNull { it.id == "s2" })
    }

    @Test
    fun `a shelf waits when a library book has no catalog item yet`() {
        val key = bookKey(entry("a1", "src-1"))!!
        var shelves = listOf(UserShelf("s1", "Старое", listOf("uri://local-only")))
        // Книга в библиотеке есть (например, после восстановления копии), а каталожного элемента для неё пока нет
        val shelf = ShelfCollection({ shelves }, { shelves = it }, { emptyList() }, { listOf(entry("a1", "src-1")) })

        val pending = shelf.apply(mapOf("s2" to """{"name":"Из облака","books":["$key"]}"""), emptySet())

        assertEquals(setOf("s2"), pending, "подборка ждёт каталожного элемента, а не теряет книгу")
        assertNull(shelves.firstOrNull { it.id == "s2" })
        assertEquals(listOf("uri://local-only"), shelves.single().uris)
    }

    private fun item(uri: String, libraryId: String) = com.localdoc.ai.catalog.CatalogItem(
        uri = uri, name = "n", size = 1, modified = 1, folder = "", root = "device", format = BookFormat.EPUB, title = "t",
        libraryId = libraryId
    )
}
