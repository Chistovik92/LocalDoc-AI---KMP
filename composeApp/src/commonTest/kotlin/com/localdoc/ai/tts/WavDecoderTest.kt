package com.localdoc.ai.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WavDecoderTest {

    /** Собирает минимальный RIFF/PCM WAV: 1 канал, sampleRate, 16 бит, заданные сэмплы. */
    private fun wav(sampleRate: Int, channels: Int, samples: List<Short>): ByteArray {
        val bits = 16
        val bytesPerSample = bits / 8
        val dataSize = samples.size * channels * bytesPerSample
        val buf = java.io.ByteArrayOutputStream()
        val w = java.io.DataOutputStream(buf)

        fun string(s: String) = w.writeBytes(s)
        fun le32(v: Int) { w.writeByte(v and 0xFF); w.writeByte((v ushr 8) and 0xFF); w.writeByte((v ushr 16) and 0xFF); w.writeByte((v ushr 24) and 0xFF) }
        fun le16(v: Int) { w.writeByte(v and 0xFF); w.writeByte((v ushr 8) and 0xFF) }

        string("RIFF"); le32(36 + dataSize); string("WAVE")
        string("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate); le32(sampleRate * channels * bytesPerSample); le16(channels * bytesPerSample); le16(bits)
        string("data"); le32(dataSize)
        for (s in samples) { for (c in 0 until channels) le16(s.toInt()) }
        w.flush()
        return buf.toByteArray()
    }

    @Test
    fun `decodes mono 16-bit pcm`() {
        val pcm = WavDecoder.decode(wav(22_050, 1, listOf(0, 16384, -16384, 32767)))

        assertNotNull(pcm)
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(4, pcm.samples.size)
        assertEquals(0f, pcm.samples[0], 0.001f)
        assertEquals(0.5f, pcm.samples[1], 0.001f)
        assertEquals(-0.5f, pcm.samples[2], 0.001f)
        assertEquals(1f, pcm.samples[3], 0.001f)
    }

    @Test
    fun `stereo is downmixed to mono`() {
        val pcm = WavDecoder.decode(wav(8_000, 2, listOf(32767)))

        assertNotNull(pcm)
        assertEquals(1, pcm.samples.size)
        assertEquals(1f, pcm.samples[0], 0.001f)
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(WavDecoder.decode(ByteArray(100)))
        assertNull(WavDecoder.decode("not a wav file".encodeToByteArray()))
    }
}