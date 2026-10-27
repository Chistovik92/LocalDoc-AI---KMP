package com.localdoc.ai.rag

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingMathTest {

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-5f, "expected $expected but was $actual")

    @Test
    fun meanPoolIgnoresMaskedTokens() {
        // 3 токена × 2 измерения; третий — padding
        val hidden = floatArrayOf(1f, 2f, 3f, 4f, 100f, 100f)
        val pooled = EmbeddingMath.meanPool(hidden, longArrayOf(1, 1, 0), dim = 2)
        assertClose(2f, pooled[0])
        assertClose(3f, pooled[1])
    }

    @Test
    fun meanPoolWithAllTokensIsPlainMean() {
        val hidden = floatArrayOf(1f, 0f, 0f, 1f)
        val pooled = EmbeddingMath.meanPool(hidden, longArrayOf(1, 1), dim = 2)
        assertClose(0.5f, pooled[0])
        assertClose(0.5f, pooled[1])
    }

    @Test
    fun meanPoolWithEmptyMaskIsZeros() {
        val pooled = EmbeddingMath.meanPool(floatArrayOf(5f, 5f), longArrayOf(0), dim = 2)
        assertEquals(listOf(0f, 0f), pooled.toList())
    }

    @Test
    fun l2NormalizeGivesUnitVector() {
        val v = EmbeddingMath.l2Normalize(floatArrayOf(2f, 3f))
        val n = sqrt(13f)
        assertClose(2f / n, v[0])
        assertClose(3f / n, v[1])
        assertClose(1f, sqrt(v[0] * v[0] + v[1] * v[1]))
    }

    @Test
    fun l2NormalizeKeepsZeroVectorFinite() {
        val v = EmbeddingMath.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun l2NormalizeDoesNotMutateInput() {
        val src = floatArrayOf(3f, 4f)
        EmbeddingMath.l2Normalize(src)
        assertEquals(listOf(3f, 4f), src.toList())
    }
}
