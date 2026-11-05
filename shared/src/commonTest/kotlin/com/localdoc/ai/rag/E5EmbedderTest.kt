package com.localdoc.ai.rag

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class E5EmbedderTest {

    private val tokenizer = UnigramTokenizer.fromTokenizerJson(
        """{"model":{"vocab":[["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",0.0],["▁",-1.0],["▁query",-2.0],["▁passage",-2.0],["▁a",-2.0],[":",-2.0]]}}"""
    )

    private class RecordingEncoder(private val dim: Int, private val value: (d: Int) -> Float) : OnnxTextEncoder {
        var lastIds: LongArray = LongArray(0)
        override fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray {
            lastIds = inputIds
            return FloatArray(inputIds.size * dim) { value(it % dim) }
        }
        override fun close() = Unit
    }

    @Test
    fun queryAndPassageGetTheirPrefixes() {
        val encoder = RecordingEncoder(2) { d -> d + 1f }
        val e5 = E5Embedder(tokenizer, encoder, dimensions = 2)

        e5.encodeQuery("a")
        val queryIds = encoder.lastIds.toList()
        e5.encodePassage("a")
        val passageIds = encoder.lastIds.toList()

        // «▁query» (5) против «▁passage» (6) в начале, дальше одинаково
        assertEquals(5L, queryIds[1])
        assertEquals(6L, passageIds[1])
        assertEquals(queryIds.drop(2), passageIds.drop(2))
    }

    @Test
    fun resultIsMeanPooledAndNormalized() {
        // Все токены одинаковые → среднее = (1, 2), нормализованное = (1, 2) / √5
        val e5 = E5Embedder(tokenizer, RecordingEncoder(2) { d -> d + 1f }, dimensions = 2)
        val v = e5.encode("a")
        assertTrue(abs(v[0] - 1f / sqrt(5f)) < 1e-5f)
        assertTrue(abs(v[1] - 2f / sqrt(5f)) < 1e-5f)
    }

    @Test
    fun wrongOutputShapeIsReportedWhenModelDimensionDiffers() {
        val e5 = E5Embedder(tokenizer, object : OnnxTextEncoder {
            override fun run(inputIds: LongArray, attentionMask: LongArray) = FloatArray(inputIds.size * 3 + 1)
            override fun close() = Unit
        }, dimensions = 2)
        assertFailsWith<IllegalStateException> { e5.encode("a") }
    }

    @Test
    fun signatureNamesModelAndSize() {
        assertEquals("e5-small-384", E5Embedder(tokenizer, RecordingEncoder(384) { 0f }).signature)
    }
}
