package com.localdoc.ai.backup

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.export.ZipWriter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {

    private class MemoryStorage : BackupStorage {
        val values = LinkedHashMap<String, String>()
        val files = LinkedHashMap<String, ByteArray>()
        override fun getValue(key: String) = values[key]
        override fun putValue(key: String, value: String) { values[key] = value }
        override fun removeValue(key: String) { values.remove(key) }
        override fun readFile(name: String) = files[name]
        override fun writeFile(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun listFiles(prefix: String) = files.keys.filter { it.startsWith(prefix) }
        override fun deleteFiles(prefix: String) { files.keys.filter { it.startsWith(prefix) }.forEach(files::remove) }
    }

    private val json = Json
    private fun entry(id: String, title: String) = BookEntry(id, title, "Автор", BookFormat.EPUB, "src-$id", 10, progressBlock = 4)

    private fun libraryValue(vararg entries: BookEntry) = json.encodeToString(ListSerializer(BookEntry.serializer()), entries.toList())

    private fun filled(): MemoryStorage = MemoryStorage().apply {
        values["library_v1"] = libraryValue(entry("a1", "Первая"), entry("b2", "Вторая"))
        values["bookmarks_v1"] = """[{"id":"x","entryId":"a1","bookTitle":"Первая","format":"EPUB","block":3,"excerpt":"текст"}]"""
        values["annotations_v1"] = "[]"
        values["reader_settings"] = """{"fontSize":22.0,"paged":true}"""
        values["voice_settings"] = """{"rate":1.25}"""
        values["catalog_secret"] = "не должно попасть в копию"
        files["a1.json"] = """{"title":"Первая","format":"EPUB","blocks":[]}""".encodeToByteArray()
        files["a1.img.im0"] = byteArrayOf(1, 2, 3)
        files["a1.img.im1"] = byteArrayOf(4, 5)
        files["b2.json"] = """{"title":"Вторая","format":"EPUB","blocks":[]}""".encodeToByteArray()
        files["catalog.json"] = "{}".encodeToByteArray()
        files["idx.lib.a1.json"] = "{}".encodeToByteArray()
    }

    private fun service(storage: BackupStorage, clock: () -> Long = { 1_700_000_000_000 }) = BackupService(storage, "1.8.0", clock)

    @Test
    fun `backup restores library and settings into an empty app exactly`() {
        val source = filled()
        val bytes = service(source).create()

        val target = MemoryStorage()
        val result = service(target).restore(bytes)

        val restored = assertIs<RestoreResult.Restored>(result)
        assertEquals(2, restored.manifest.books)
        assertEquals(source.values.filterKeys { it in BackupFormat.KV_KEYS }, target.values)
        assertEquals(setOf("a1.json", "a1.img.im0", "a1.img.im1", "b2.json"), target.files.keys)
        for ((name, data) in target.files) assertContentEquals(source.files.getValue(name), data, name)
    }

    @Test
    fun `backup holds no site passwords models caches or search index`() {
        val bytes = service(filled()).create()
        val manifest = (service(MemoryStorage()).inspect(bytes) as BackupCheck.Ok).manifest

        assertEquals(1, manifest.version)
        assertEquals("1.8.0", manifest.appVersion)
        assertEquals(listOf("library_v1", "bookmarks_v1", "annotations_v1", "reader_settings", "voice_settings"), manifest.keys)
        assertTrue(manifest.files.none { it.startsWith("idx.") || it == "catalog.json" })
        assertFalse(bytes.decodeToString().contains("catalog_secret"))
    }

    @Test
    fun `restore replaces existing data and removes books that are not in the backup`() {
        val bytes = service(filled()).create()

        val target = MemoryStorage().apply {
            values["library_v1"] = libraryValue(entry("zz", "Лишняя"))
            values["dictionary_v1"] = "[{\"stale\":true}]"
            values["reader_settings"] = """{"fontSize":10.0}"""
            files["zz.json"] = "{}".encodeToByteArray()
            files["zz.img.im0"] = byteArrayOf(9)
        }
        assertIs<RestoreResult.Restored>(service(target).restore(bytes))

        assertEquals(setOf("a1.json", "a1.img.im0", "a1.img.im1", "b2.json"), target.files.keys)
        assertNull(target.values["dictionary_v1"], "настройки, которых нет в копии, сбрасываются")
        assertEquals("""{"fontSize":22.0,"paged":true}""", target.values["reader_settings"])
    }

    @Test
    fun `copy from a newer format is refused without touching data`() {
        val newer = zipWith(BackupFormat.MANIFEST to """{"format":"localdoc-backup","version":99,"appVersion":"3.0.0"}""")
        val target = filled()
        val before = target.values.toMap()

        val check = service(target).inspect(newer)
        assertEquals(BackupCheck.TooNew(99, "3.0.0"), check)
        val failed = assertIs<RestoreResult.Failed>(service(target).restore(newer))
        assertTrue("более новой версией" in failed.message && "3.0.0" in failed.message, failed.message)
        assertEquals(before, target.values)
        assertEquals(6, target.files.size)
    }

    @Test
    fun `foreign broken or truncated files are rejected and data stays as it was`() {
        val target = filled()
        val before = target.values.toMap()
        val svc = service(target)

        assertIs<BackupCheck.Invalid>(svc.inspect("просто текст".encodeToByteArray()))
        assertIs<BackupCheck.Invalid>(svc.inspect(zipWith("readme.txt" to "привет")))
        assertIs<BackupCheck.Invalid>(svc.inspect(zipWith(BackupFormat.MANIFEST to """{"format":"other","version":1}""")))
        assertIs<BackupCheck.Invalid>(svc.inspect(zipWith(BackupFormat.MANIFEST to """{"format":"localdoc-backup","version":0}""")))

        // В копии есть библиотека, но нет файла одной из книг
        val full = service(filled()).create()
        val missingBook = zipWith(
            BackupFormat.MANIFEST to """{"format":"localdoc-backup","version":1,"keys":["library_v1"]}""",
            "kv/library_v1" to libraryValue(entry("a1", "Первая"))
        )
        val invalid = assertIs<BackupCheck.Invalid>(svc.inspect(missingBook))
        assertTrue("Первая" in invalid.reason)
        assertIs<RestoreResult.Failed>(svc.restore(missingBook))
        assertIs<RestoreResult.Failed>(svc.restore(full.copyOf(full.size / 2)))
        assertEquals(before, target.values)
        assertEquals(6, target.files.size)
    }

    @Test
    fun `file names from the archive cannot escape the app folder`() {
        assertFalse(BackupFormat.isSafeName("../evil"))
        assertFalse(BackupFormat.isSafeName("dir/file"))
        assertFalse(BackupFormat.isSafeName(""))
        assertFalse(BackupFormat.isSafeName("a..b"))
        assertTrue(BackupFormat.isSafeName("a1.img.im0"))

        val bytes = zipWith(
            BackupFormat.MANIFEST to """{"format":"localdoc-backup","version":1}""",
            "files/../evil.json" to "x",
            "files/ok.json" to "{}"
        )
        val target = MemoryStorage()
        assertIs<RestoreResult.Restored>(service(target).restore(bytes))
        assertEquals(setOf("ok.json"), target.files.keys)
    }

    @Test
    fun `an empty app can be backed up and the file name carries the extension`() {
        val bytes = service(MemoryStorage()).create()
        assertEquals(0, (service(MemoryStorage()).inspect(bytes) as BackupCheck.Ok).manifest.books)
        assertEquals("LocalDoc-копия-2023-11-14.ldbackup", BackupFormat.suggestedName(1_700_000_000_000))
        assertEquals("1970-01-01", BackupFormat.isoDate(0))
        assertEquals("2024-02-29", BackupFormat.isoDate(1_709_164_800_000))
    }

    private fun zipWith(vararg entries: Pair<String, String>): ByteArray =
        ZipWriter().apply { entries.forEach { (name, text) -> add(name, text) } }.finish()

    @Test
    fun `library with a wrong schema or a damaged book is rejected before anything is deleted`() {
        val target = filled()
        val before = target.values.toMap()
        val svc = service(target)
        val manifest = BackupFormat.MANIFEST to """{"format":"localdoc-backup","version":1,"keys":["library_v1"]}"""

        val wrongSchema = zipWith(manifest, "kv/library_v1" to "{}")
        assertIs<BackupCheck.Invalid>(svc.inspect(wrongSchema))
        assertIs<RestoreResult.Failed>(svc.restore(wrongSchema))

        val damagedBook = zipWith(
            manifest,
            "kv/library_v1" to libraryValue(entry("a1", "Первая")),
            "files/a1.json" to "не json книги"
        )
        assertIs<BackupCheck.Invalid>(svc.inspect(damagedBook))
        assertIs<RestoreResult.Failed>(svc.restore(damagedBook))

        assertEquals(before, target.values)
        assertEquals(6, target.files.size)
    }
}
