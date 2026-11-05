package com.localdoc.ai.backup

import com.localdoc.ai.book.export.ZipWriter
import com.localdoc.ai.storage.StorageSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupV2Test {

    private class MemoryStorage : BackupStorage {
        val values = LinkedHashMap<String, String>()
        val files = LinkedHashMap<String, ByteArray>()
        var failWrites = false
        override fun getValue(key: String) = values[key]
        override fun putValue(key: String, value: String) { values[key] = value }
        override fun removeValue(key: String) { values.remove(key) }
        override fun readFile(name: String) = files[name]
        override fun writeFile(name: String, bytes: ByteArray) {
            if (failWrites) throw IllegalStateException("нет места")
            files[name] = bytes
        }
        override fun listFiles(prefix: String) = files.keys.filter { it.startsWith(prefix) }
        override fun deleteFiles(prefix: String) { files.keys.filter { it.startsWith(prefix) }.forEach(files::remove) }
    }

    private val bigBook = """{"title":"Книга","format":"EPUB","blocks":[""" +
        (1..400).joinToString(",") { """{"type":"PARAGRAPH","text":"Повторяющийся абзац номер $it для проверки сжатия"}""" } + "]}"

    private fun filled() = MemoryStorage().apply {
        values["library_v1"] = """[{"id":"a1","title":"Книга","format":"EPUB","source":"s","totalBlocks":400,"progressBlock":3}]"""
        values["bookmarks_v1"] = "[]"
        files["a1.json"] = bigBook.encodeToByteArray()
    }

    /** Копия формата 1, как её писали версии 1.5–1.9: ZIP без сжатия, manifest без поля schema. */
    private fun legacyBackup(library: String): ByteArray {
        val zip = ZipWriter()
        zip.add(
            "backup.json",
            """{"format":"localdoc-backup","version":1,"appVersion":"1.5.0","createdAt":1700000000000,"books":1,"keys":["library_v1","reader_settings"],"files":["a1.json"]}"""
        )
        zip.add("kv/library_v1", library)
        zip.add("kv/reader_settings", """{"fontSize":20.0,"theme":"СТАРАЯ_ТЕМА"}""")
        zip.add("files/a1.json", """{"title":"Книга","format":"EPUB","blocks":[]}""")
        return zip.finish()
    }

    @Test
    fun `new copies are deflated, readable and carry version 2 and the storage schema`() {
        val source = filled()
        val bytes = BackupService(source, "2.0.0").create()
        val stored = ZipWriter().also { it.add("a1.json", bigBook) }.finish().size
        assertTrue(bytes.size < stored / 2, "copy ${bytes.size} should be much smaller than stored $stored")

        val check = assertIs<BackupCheck.Ok>(BackupService(MemoryStorage(), "2.0.0").inspect(bytes))
        assertEquals(2, check.manifest.version)
        assertEquals(StorageSchema.CURRENT, check.manifest.schema)

        val target = MemoryStorage()
        assertIs<RestoreResult.Restored>(BackupService(target, "2.0.0").restore(bytes))
        assertEquals(bigBook, target.files.getValue("a1.json").decodeToString())
    }

    @Test
    fun `an old version 1 copy still restores and its data is migrated`() {
        val bad = legacyBackup("""[{"id":"a1","title":"Книга","format":"EPUB","totalBlocks":1},{"id":"bad","title":"x","format":"НЕТ","totalBlocks":1}]""")
        // Копия с испорченной записью библиотеки отвергается проверкой: строгий разбор защищает текущие данные
        assertIs<BackupCheck.Invalid>(BackupService(MemoryStorage(), "2.0.0").inspect(bad))

        val clean = legacyBackup("""[{"id":"a1","title":"Книга","format":"EPUB","totalBlocks":1}]""")
        val target = MemoryStorage()
        target.values[StorageSchema.KEY] = StorageSchema.CURRENT.toString()
        val check = assertIs<BackupCheck.Ok>(BackupService(target, "2.0.0").inspect(clean))
        assertEquals(1, check.manifest.version)
        assertEquals(0, check.manifest.schema)

        assertIs<RestoreResult.Restored>(BackupService(target, "2.0.0").restore(clean))
        assertEquals("""{"fontSize":20.0}""", target.values["reader_settings"])
        assertNotNull(target.values[StorageSchema.BACKUP_PREFIX + "reader_settings"])
        assertEquals(StorageSchema.CURRENT.toString(), target.values[StorageSchema.KEY])
    }

    @Test
    fun `a copy from a newer format is refused`() {
        val zip = ZipWriter()
        zip.add("backup.json", """{"format":"localdoc-backup","version":3,"appVersion":"9.0.0","keys":[],"files":[]}""")
        assertIs<BackupCheck.TooNew>(BackupService(MemoryStorage(), "2.0.0").inspect(zip.finish()))
    }

    @Test
    fun `scheduled backup runs when due, rotates old copies and survives write failures`() {
        val storage = filled()
        var now = 1_700_000_000_000L
        val day = 86_400_000L
        val schedule = ScheduledBackup(storage, storage, BackupService(storage, "2.0.0") { now }) { now }

        assertFalse(schedule.isDue())
        schedule.update(BackupScheduleSettings(enabled = true, everyDays = 2, keep = 2))
        val first = schedule.runIfDue()
        assertNotNull(first)
        assertTrue(first.startsWith("autobackup-") && first.endsWith(".ldbackup"))
        assertNull(schedule.runIfDue()) // ещё не пора

        now += day
        assertNull(schedule.runIfDue())
        repeat(3) {
            now += 2 * day
            assertNotNull(schedule.runIfDue())
        }
        assertEquals(2, schedule.existing().size)
        assertTrue(schedule.existing().first() > schedule.existing().last())
        // сама копия читается
        assertIs<BackupCheck.Ok>(BackupService(MemoryStorage(), "2.0.0").inspect(storage.files.getValue(schedule.existing().first())))

        storage.failWrites = true
        now += 2 * day
        val lastRun = schedule.settings().lastRunAt
        assertNull(schedule.runIfDue())
        assertEquals(lastRun, schedule.settings().lastRunAt)
        assertTrue(schedule.isDue())
    }
}
