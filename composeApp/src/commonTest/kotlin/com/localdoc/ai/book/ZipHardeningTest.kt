package com.localdoc.ai.book

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: испорченный ZIP отвергается понятной ошибкой, а не выходом за границы массива. */
class ZipHardeningTest {

    private fun sample() = TestFiles.zip(mapOf("a.txt" to "первый", "dir/b.txt" to "второй", "c.xml" to "<x/>"))

    private fun centralStart(zip: ByteArray): Int {
        for (i in 0 until zip.size - 3) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4B.toByte() && zip[i + 2] == 1.toByte() && zip[i + 3] == 2.toByte()) return i
        }
        error("нет центрального каталога")
    }

    @Test
    fun `random damage to headers only ever raises IllegalArgumentException`() {
        val original = sample()
        val from = centralStart(original)
        val random = Random(7)
        repeat(1500) {
            val bytes = original.copyOf()
            repeat(1 + random.nextInt(4)) { bytes[from + random.nextInt(bytes.size - from)] = random.nextInt(256).toByte() }
            try {
                val zip = ZipArchive(bytes)
                for (name in zip.names.toList()) zip.read(name)
            } catch (e: IllegalArgumentException) {
                // понятная ошибка
            }
        }
    }

    @Test
    fun `entry name running past the end of the file is a clean error`() {
        val zip = sample().copyOf()
        val central = centralStart(zip)
        zip[central + 28] = 0xFF.toByte()
        zip[central + 29] = 0x7F
        assertFailsWith<IllegalArgumentException> { ZipArchive(zip) }
    }

    @Test
    fun `negative local offset is a clean error`() {
        val zip = sample().copyOf()
        val central = centralStart(zip)
        for (i in 0 until 4) zip[central + 42 + i] = 0xFF.toByte()
        assertFailsWith<IllegalArgumentException> { ZipArchive(zip).read("a.txt") }
    }

    @Test
    fun `stored entry whose sizes disagree is refused`() {
        val zip = sample().copyOf()
        val central = centralStart(zip)
        // Заявленный несжатый размер (4 байта по смещению 24) меньше фактических данных
        zip[central + 24] = 1
        assertFailsWith<IllegalArgumentException> { ZipArchive(zip).read("a.txt") }
    }

    @Test
    fun `an archive claiming more entries than its directory can hold is refused`() {
        val zip = sample().copyOf()
        val eocd = zip.size - 22
        zip[eocd + 10] = 0xFF.toByte(); zip[eocd + 11] = 0xFF.toByte()
        assertFailsWith<IllegalArgumentException> { ZipArchive(zip) }
    }

    @Test
    fun `intact archive still reads`() {
        val zip = ZipArchive(sample())
        assertEquals("первый", zip.readText("a.txt"))
        assertTrue("dir/b.txt" in zip.names)
    }
}
