package com.localdoc.ai.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class RingLogTest {

    private class FakeFiles : RingLogFiles {
        val data = HashMap<String, ByteArray>()
        override fun write(name: String, bytes: ByteArray) { data[name] = bytes }
        override fun read(name: String) = data[name]
        override fun list(prefix: String) = data.keys.filter { it.startsWith(prefix) }
        override fun delete(name: String) { data.remove(name) }
    }

    private fun setUp(): FakeFiles {
        RingLog.resetForTests()
        return FakeFiles()
    }

    @Test
    fun `records to memory and writes to disk on flush`() {
        val files = setUp()
        RingLog.bind(files, { 1_000L })
        RingLog.d("первое сообщение")
        RingLog.e("сбой", RuntimeException("детали"))

        // До порога сброса на диск ничего не записано
        assertTrue(files.data.isEmpty())

        RingLog.flush()
        val text = RingLog.readAll()
        assertTrue("первое сообщение" in text)
        assertTrue("сбой" in text && "детали" in text)
        assertTrue(files.data.keys.any { it.startsWith("debuglog.") })
    }

    @Test
    fun `buffer flushes to disk automatically at the threshold`() {
        val files = setUp()
        RingLog.bind(files, { 1_000L })
        repeat(40) { RingLog.d("строка $it") }
        // 40 строк превышают порог 32 — буфер уже сброшен на диск
        assertTrue(files.data.keys.any { it.startsWith("debuglog.") }, "буфер должен сброситься без явного flush")
        val text = RingLog.readAll()
        assertTrue("строка 0" in text && "строка 39" in text)
    }

    @Test
    fun `bind flushes lines accumulated before binding`() {
        val files = setUp()
        // До bind журнал держит строки только в памяти: readAll видит их, на диск ещё ничего не сброшено
        RingLog.d("ранняя строка")
        assertTrue("ранняя строка" in RingLog.readAll(), "строка должна держаться в памяти до bind")
        assertTrue(files.data.isEmpty(), "до bind на диск ничего не пишется")
        RingLog.bind(files, { 1_000L })
        assertTrue("ранняя строка" in RingLog.readAll(), "строка сохраняется после bind")
        assertTrue(files.data.keys.any { it.startsWith("debuglog.") }, "после bind буфер сброшен на диск")
    }

    @Test
    fun `a line longer than one file rotates into the next file and readAll reassembles it`() {
        val files = setUp()
        RingLog.bind(files, { 1_000L })
        // 300k символов больше одного файла (128k) — строка распадётся на несколько файлов
        val big = "x".repeat(300_000)
        RingLog.d(big)
        RingLog.flush()
        val names = files.data.keys.filter { it.startsWith("debuglog.") }
        assertTrue(names.size >= 2, "длинная строка должна занять больше одного файла: $names")
        assertTrue(big in RingLog.readAll())
    }

    @Test
    fun `oldest files are dropped keeping the newest bounded amount`() {
        val files = setUp()
        RingLog.bind(files, { 1_000L })
        // 600k символов занимают пять файлов по 128k — старейший отбрасывается, остаются четыре
        val big = "y".repeat(600_000)
        RingLog.d(big)
        RingLog.flush()
        val names = files.data.keys.filter { it.startsWith("debuglog.") }
        assertTrue(names.size <= 4, "хранится не больше четырёх файлов: ${names.size}")
        val all = RingLog.readAll()
        assertTrue(all.length < big.length, "старейший кусок отброшен")
        assertTrue("y".repeat(50_000) in all, "хвост журнала сохранён")
    }
}
