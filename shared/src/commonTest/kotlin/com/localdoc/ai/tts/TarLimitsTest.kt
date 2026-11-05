package com.localdoc.ai.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Аудит безопасности 2.0.0: tar-бомба не должна заполнить диск. */
class TarLimitsTest {

    private class CountingSink : TarSink {
        var written = 0L
        override fun directory(path: String) {}
        override fun beginFile(path: String) {}
        override fun write(bytes: ByteArray, offset: Int, length: Int) { written += length }
        override fun endFile() {}
    }

    @Test
    fun `archive larger than the limit is stopped before it is written`() {
        val tar = buildTar(listOf(TarEntry("pack/model.onnx", ByteArray(50_000)), TarEntry("pack/more.bin", ByteArray(50_000))))
        val sink = CountingSink()
        assertFailsWith<ArchiveException> { TarReader(chunkedSource(tar), maxTotalBytes = 60_000).extractTo(sink, 1) }
        assertTrue(sink.written <= 60_000, "записано ${sink.written}")
    }

    @Test
    fun `a single huge declared size is refused up front`() {
        val tar = buildTar(listOf(TarEntry("pack/a.bin", ByteArray(10_000))))
        val sink = CountingSink()
        assertFailsWith<ArchiveException> { TarReader(chunkedSource(tar), maxTotalBytes = 1_000).extractTo(sink, 1) }
        assertEquals(0L, sink.written)
    }

    @Test
    fun `archive within the limit still extracts`() {
        val tar = buildTar(listOf(TarEntry("pack/a.bin", ByteArray(10_000))))
        val sink = CountingSink()
        TarReader(chunkedSource(tar), maxTotalBytes = 20_000).extractTo(sink, 1)
        assertEquals(10_000L, sink.written)
    }
}
