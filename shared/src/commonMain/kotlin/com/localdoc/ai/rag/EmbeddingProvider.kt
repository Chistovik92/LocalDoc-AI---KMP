package com.localdoc.ai.rag

/**
 * Провайдер эмбеддингов для локального векторного поиска.
 *
 * Реализация по умолчанию — детерминированный feature hashing (без внешних файлов):
 * не требует скачивания ONNX-модели и работает мгновенно на любом устройстве.
 * Качество — на уровне лексического поиска; для полного семантического ретривала
 * достаточно заменить на ONNX-модель (multilingual-e5-small, 384-dim) — интерфейс совместим.
 */
interface EmbeddingProvider {
    val dimensions: Int

    /** Короткое имя провайдера; вместе с размерностью даёт [signature] индекса. */
    val id: String get() = "custom"

    /** Подпись пространства векторов: индексы с разной подписью несовместимы и пересчитываются. */
    val signature: String get() = "$id-$dimensions"

    fun encode(text: String): FloatArray

    /** Вектор поискового запроса (у моделей e5 — с префиксом «query: »). */
    fun encodeQuery(text: String): FloatArray = encode(text)

    /** Вектор фрагмента документа (у моделей e5 — с префиксом «passage: »). */
    fun encodePassage(text: String): FloatArray = encode(text)
}

class HashingEmbedder(override val dimensions: Int = 384) : EmbeddingProvider {

    override val id: String get() = "hash"

    private fun hash32(s: String, seed: Int): Int {
        var h = seed xor 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    override fun encode(text: String): FloatArray {
        val v = FloatArray(dimensions)
        val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

        fun add(token: String, weight: Float) {
            val h = hash32(token, 0)
            val idx = ((h % dimensions) + dimensions) % dimensions
            val sign = if (hash32(token, 1) and 1 == 0) 1f else -1f
            v[idx] += sign * weight
        }

        for (i in tokens.indices) {
            add(tokens[i], 1.0f)
            if (i + 1 < tokens.size) {
                add(tokens[i] + "_" + tokens[i + 1], 0.6f) // биграммы усиливают связность
            }
        }

        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-12f)
        for (i in v.indices) v[i] /= norm
        return v
    }
}
