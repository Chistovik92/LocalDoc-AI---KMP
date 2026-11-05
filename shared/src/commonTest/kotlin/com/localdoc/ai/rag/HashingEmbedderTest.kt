package com.localdoc.ai.rag

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashingEmbedderTest {

    private val embedder = HashingEmbedder(dimensions = 384)

    private fun dot(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    @Test
    fun vectorsAreL2Normalized() {
        val v = embedder.encode("Договор аренды заключён 1 марта 2024 года")
        val norm = sqrt(v.sumOf { (it * it).toDouble() })
        assertEquals(384, v.size)
        assertTrue(abs(norm - 1.0) < 1e-4, "norm = $norm")
    }

    @Test
    fun encodingIsDeterministicAndCaseInsensitive() {
        assertTrue(embedder.encode("Привет Мир").contentEquals(embedder.encode("привет мир")))
    }

    @Test
    fun relatedTextIsCloserThanUnrelated() {
        val query = embedder.encode("срок аренды квартиры")
        val related = embedder.encode("Срок аренды квартиры составляет один год")
        val unrelated = embedder.encode("Рецепт борща со сметаной и чесноком")
        assertTrue(dot(query, related) > dot(query, unrelated))
    }

    @Test
    fun emptyTextGivesZeroVector() {
        assertTrue(embedder.encode("").all { it == 0f })
    }
}
