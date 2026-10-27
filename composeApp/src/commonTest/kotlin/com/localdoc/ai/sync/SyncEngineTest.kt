package com.localdoc.ai.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun twoDevices(): Triple<MemoryCloud, Device, Device> {
        val cloud = MemoryCloud()
        return Triple(cloud, Device(cloud, T0), Device(cloud, T0 + 1000))
    }

    @Test
    fun `data from one device arrives on another and a repeat sync changes nothing`() = runTest {
        val (cloud, a, b) = twoDevices()
        a.progress.data["bk1"] = "10"
        a.marks.data["bk1/m1"] = "закладка"
        val first = assertIs<SyncOutcome.Done>(a.sync())
        assertEquals(2, first.pushed)

        val pulled = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(2, pulled.pulled)
        assertEquals(a.progress.data, b.progress.data)
        assertEquals(a.marks.data, b.marks.data)

        val uploads = cloud.uploads
        val again = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(0, again.pulled)
        assertEquals(uploads, cloud.uploads)
    }

    @Test
    fun `edits of different records on two devices both survive`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bk/1"] = "a1"
        b.marks.data["bk/2"] = "b2"
        a.sync()
        b.sync()
        a.now += 5000
        a.sync()
        assertEquals(mapOf("bk/1" to "a1", "bk/2" to "b2"), a.marks.data)
        assertEquals(a.marks.data, b.marks.data)
    }

    @Test
    fun `conflicting edits keep the later one and write the conflict to the log`() = runTest {
        val (_, a, b) = twoDevices()
        a.progress.data["bk"] = "1"
        a.sync()
        b.sync()
        a.progress.data["bk"] = "50"
        a.now = T0 + 10_000
        a.sync()
        b.progress.data["bk"] = "70"
        b.now = T0 + 20_000
        val outcome = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(1, outcome.conflicts)
        assertEquals("70", b.progress.data["bk"])
        assertTrue(b.engine.state().log.any { it.action == "conflict" && it.id == "progress/bk" })

        a.now = T0 + 30_000
        a.sync()
        assertEquals("70", a.progress.data["bk"])
    }

    @Test
    fun `deletion travels as a tombstone and the record does not come back`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.marks.data["bk/2"] = "y"
        a.sync()
        b.sync()
        a.marks.data.remove("bk/1")
        a.now += 5000
        a.sync()
        b.now += 6000
        b.sync()
        assertEquals(setOf("bk/2"), b.marks.data.keys)
        b.now += 1000
        b.sync()
        a.now += 9000
        a.sync()
        assertEquals(setOf("bk/2"), a.marks.data.keys)
    }

    @Test
    fun `wrong encryption password changes nothing`() = runTest {
        val (cloud, a, _) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.sync()
        val stranger = Device(cloud, T0 + 5000, FastCipher("другой пароль"))
        stranger.marks.data["bk/9"] = "mine"
        assertIs<SyncOutcome.WrongPassword>(stranger.sync())
        assertEquals(mapOf("bk/9" to "mine"), stranger.marks.data)
        assertNull(stranger.state)
    }

    @Test
    fun `connection lost while downloading leaves device and state untouched`() = runTest {
        val (cloud, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.sync()
        cloud.failDownload = true
        assertIs<SyncOutcome.Failed>(b.sync())
        assertTrue(b.marks.data.isEmpty())
        assertNull(b.state)
        cloud.failDownload = false
        assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(a.marks.data, b.marks.data)
    }

    @Test
    fun `connection lost while uploading applies nothing and the next sync completes the work`() = runTest {
        val (cloud, a, b) = twoDevices()
        a.marks.data["bk/1"] = "from-a"
        a.sync()
        b.marks.data["bk/2"] = "from-b"
        cloud.failUpload = true
        assertIs<SyncOutcome.Failed>(b.sync())
        assertEquals(setOf("bk/2"), b.marks.data.keys) // чужая запись ещё не применена
        assertNull(b.state)
        cloud.failUpload = false
        b.now += 1000
        assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(setOf("bk/1", "bk/2"), b.marks.data.keys)
        a.now += 5000
        a.sync()
        assertEquals(setOf("bk/1", "bk/2"), a.marks.data.keys)
    }

    @Test
    fun `another device writing during upload makes the engine merge again`() = runTest {
        val (cloud, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.sync()
        b.sync()
        val c = Device(cloud, T0 + 3000)
        c.sync()
        c.marks.data["bk/c"] = "from-c"
        b.marks.data["bk/b"] = "from-b"
        cloud.beforeUpload = { c.sync() }
        b.now += 5000
        assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(setOf("bk/1", "bk/b", "bk/c"), b.marks.data.keys)
    }

    @Test
    fun `records without a book on this device wait and are not treated as deleted`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bkX/1"] = "для книги, которой нет у b"
        a.sync()
        b.marks.unappliable += "bkX/1"
        val first = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(1, first.deferred)
        assertTrue(b.marks.data.isEmpty())

        // книга появилась: запись применяется, а не удаляется как «исчезнувшая»
        b.marks.unappliable.clear()
        b.now += 1000
        val second = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(0, second.deferred)
        assertEquals("для книги, которой нет у b", b.marks.data["bkX/1"])
        a.now += 5000
        a.sync()
        assertEquals(setOf("bkX/1"), a.marks.data.keys)
    }

    @Test
    fun `a failing kind is reported and the others are still applied`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.progress.data["bk"] = "7"
        a.sync()
        b.marks.failApply = true
        val outcome = assertIs<SyncOutcome.Done>(b.sync())
        assertEquals(listOf("bookmark"), outcome.failedKinds)
        assertEquals("7", b.progress.data["bk"])
        b.marks.failApply = false
        b.now += 1000
        b.sync()
        assertEquals("x", b.marks.data["bk/1"])
    }

    @Test
    fun `unknown kinds from a newer version survive a round trip`() = runTest {
        val (cloud, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.sync()
        // «новая» версия приложения записала запись неизвестного вида
        val plain = a.cipher.open(cloud.file!!.bytes).decodeToString()
        val unknown = """{"id":"future/z","updatedAt":${T0 + 1},"device":"n","data":"?"},"""
        val withUnknown = plain.replace("\"records\":[", "\"records\":[$unknown")
        cloud.file = RemoteFile(a.cipher.seal(withUnknown.encodeToByteArray()), "forced")
        b.sync()
        b.marks.data["bk/2"] = "y"
        b.now += 1000
        b.sync()
        val final = a.cipher.open(cloud.file!!.bytes).decodeToString()
        assertTrue("future/z" in final && "bk/2" in final)
    }

    @Test
    fun `a rolled back cloud stops in safe mode until the user decides`() = runTest {
        val (cloud, a, _) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.sync()
        val oldFile = cloud.file
        a.marks.data["bk/2"] = "y"
        a.now += 1000
        a.sync()
        cloud.file = oldFile // облако восстановили из старой копии
        a.now += 1000
        val outcome = assertIs<SyncOutcome.Diverged>(a.sync())
        assertEquals(DivergenceReason.CLOUD_ROLLED_BACK, outcome.reason)
        assertEquals(setOf("bk/1", "bk/2"), a.marks.data.keys)
        assertEquals(oldFile, cloud.file)

        assertIs<SyncOutcome.Done>(a.sync(DivergenceChoice.KEEP_LOCAL))
        val b = Device(cloud, T0 + 9000)
        b.sync()
        assertEquals(setOf("bk/1", "bk/2"), b.marks.data.keys)
    }

    @Test
    fun `mass deletion from the cloud stops in safe mode and keep local overrides it`() = runTest {
        val (cloud, a, b) = twoDevices()
        repeat(8) { a.marks.data["bk/$it"] = "m$it" }
        a.sync()
        b.sync()
        a.marks.data.clear()
        a.now += 5000
        a.sync()
        b.now += 6000
        val copiesBefore = b.copies
        val outcome = assertIs<SyncOutcome.Diverged>(b.sync())
        assertEquals(DivergenceReason.MASS_DELETE, outcome.reason)
        assertEquals(8, b.marks.data.size)
        assertEquals(copiesBefore, b.copies)

        assertIs<SyncOutcome.Done>(b.sync(DivergenceChoice.KEEP_LOCAL))
        assertEquals(8, b.marks.data.size)
        a.now += 9000
        a.sync()
        assertEquals(8, a.marks.data.size)
        assertNotNull(cloud.file)
    }

    @Test
    fun `using the cloud replaces local data and makes a safety copy`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bk/1"] = "cloud"
        a.sync()
        b.marks.data["bk/9"] = "local only"
        assertIs<SyncOutcome.Done>(b.sync(DivergenceChoice.USE_REMOTE))
        assertEquals(mapOf("bk/1" to "cloud"), b.marks.data)
        assertEquals(1, b.copies)
        assertEquals(listOf(setOf("bookmark")), b.applied)
    }

    @Test
    fun `a newer sync format is not touched`() = runTest {
        val (cloud, a, _) = twoDevices()
        val newer = """{"format":"localdoc-sync","version":99,"records":[]}"""
        cloud.file = RemoteFile(a.cipher.seal(newer.encodeToByteArray()), "1")
        assertEquals(SyncOutcome.TooNew(99), a.sync())
    }

    @Test
    fun `a book imported later does not overwrite the waiting cloud progress with its default`() = runTest {
        val (_, a, b) = twoDevices()
        a.progress.data["bk"] = "7"
        a.sync()
        b.progress.unappliable += "bk"
        b.sync()
        // книга импортирована: у неё прогресс по умолчанию
        b.progress.unappliable.clear()
        b.progress.data["bk"] = "0"
        b.now += 1000
        b.sync()
        assertEquals("7", b.progress.data["bk"])
        a.now += 5000
        a.sync()
        assertEquals("7", a.progress.data["bk"])
    }

    @Test
    fun `a failed removal is retried and the record is not resurrected in the cloud`() = runTest {
        val (_, a, b) = twoDevices()
        a.marks.data["bk/1"] = "x"
        a.marks.data["bk/2"] = "y"
        a.sync()
        b.sync()
        a.marks.data.remove("bk/1")
        a.now += 5000
        a.sync()
        b.marks.failApply = true
        b.now += 6000
        b.sync()
        b.marks.failApply = false
        b.now += 1000
        b.sync()
        assertEquals(setOf("bk/2"), b.marks.data.keys)
        a.now += 9000
        a.sync()
        assertEquals(setOf("bk/2"), a.marks.data.keys)
    }
}
