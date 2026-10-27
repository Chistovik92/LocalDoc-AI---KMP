package com.localdoc.ai.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageMigrationsTest {

    private class Memory(vararg pairs: Pair<String, String>) : KvAccess {
        val values = LinkedHashMap<String, String>().apply { putAll(pairs) }
        override fun getValue(key: String) = values[key]
        override fun putValue(key: String, value: String) { values[key] = value }
        override fun removeValue(key: String) { values.remove(key) }
    }

    // Сохранённые образцы данных прошлых версий 1.x (минимальные записи без полей, добавленных позже)
    private val library10 = """[{"id":"a1","title":"Книга","format":"EPUB","totalBlocks":10}]"""
    private val library17 = """[{"id":"a1","title":"Книга","author":"Автор","format":"EPUB","source":"content://doc/1","totalBlocks":10,"progressBlock":4,"cover":"im0"}]"""
    private val bookmark15 = """[{"id":"b1","entryId":"a1","bookTitle":"Книга","format":"EPUB","block":3,"excerpt":"текст"}]"""
    private val annotation17 = """[{"id":"n1","entryId":"a1","bookTitle":"Книга","format":"EPUB","anchor":{"block":2,"quote":"фраза"},"createdAt":1700000000000}]"""

    @Test
    fun `clean upgrade from any 1_x leaves valid data byte for byte and stamps the schema`() {
        for (library in listOf(library10, library17)) {
            val kv = Memory(
                "library_v1" to library, "bookmarks_v1" to bookmark15, "annotations_v1" to annotation17,
                "reader_settings" to """{"fontSize":20.0,"paged":true}""", "voice_settings" to """{"rate":1.5}"""
            )
            val before = kv.values.toMap()
            val report = assertIs<MigrationReport.Migrated>(StorageMigrations(kv).run())
            assertEquals(0, report.from)
            assertEquals(0, report.droppedItems)
            assertTrue(report.rewrittenKeys.isEmpty())
            assertEquals(before, kv.values.filterKeys { it != StorageSchema.KEY })
            assertEquals(StorageSchema.CURRENT.toString(), kv.values[StorageSchema.KEY])
        }
    }

    @Test
    fun `an empty app just gets the schema stamp and a second run does nothing`() {
        val kv = Memory()
        assertIs<MigrationReport.Migrated>(StorageMigrations(kv).run())
        assertEquals(MigrationReport.UpToDate, StorageMigrations(kv).run())
        assertEquals(setOf(StorageSchema.KEY), kv.values.keys)
    }

    @Test
    fun `one broken entry does not wipe the whole list and the original is kept`() {
        val broken = """[{"id":"a1","title":"Книга","format":"EPUB","totalBlocks":10},{"id":"x","title":"?","format":"НЕТ_ТАКОГО","totalBlocks":1},{"junk":true}]"""
        val kv = Memory("library_v1" to broken)
        val report = assertIs<MigrationReport.Migrated>(StorageMigrations(kv).run())
        assertEquals(2, report.droppedItems)
        assertEquals(listOf("library_v1"), report.rewrittenKeys)
        assertEquals("""[{"id":"a1","title":"Книга","format":"EPUB","totalBlocks":10}]""", kv.values["library_v1"])
        assertEquals(broken, kv.values[StorageSchema.BACKUP_PREFIX + "library_v1"])
    }

    @Test
    fun `settings keep every readable field and fall back to defaults for the rest`() {
        val kv = Memory("reader_settings" to """{"fontSize":20.0,"theme":"СТАРАЯ_ТЕМА","paged":true}""")
        StorageMigrations(kv).run()
        assertEquals("""{"fontSize":20.0,"paged":true}""", kv.values["reader_settings"])
        assertTrue(StorageSchema.BACKUP_PREFIX + "reader_settings" in kv.values)
    }

    @Test
    fun `unreadable values are left alone`() {
        val kv = Memory("library_v1" to "не json", "reader_settings" to "[1,2]")
        val report = assertIs<MigrationReport.Migrated>(StorageMigrations(kv).run())
        assertTrue(report.rewrittenKeys.isEmpty())
        assertEquals("не json", kv.values["library_v1"])
        assertNull(kv.values[StorageSchema.BACKUP_PREFIX + "library_v1"])
    }

    @Test
    fun `data saved by a newer version is not touched`() {
        val kv = Memory(StorageSchema.KEY to "99", "library_v1" to """[{"junk":1}]""")
        assertEquals(MigrationReport.TooNew(99), StorageMigrations(kv).run())
        assertEquals("""[{"junk":1}]""", kv.values["library_v1"])
    }
}
