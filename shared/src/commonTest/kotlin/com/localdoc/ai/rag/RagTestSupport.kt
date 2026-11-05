package com.localdoc.ai.rag

class MemoryRagStorage : RagStorage {
    val data = LinkedHashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun put(key: String, value: String) {
        data[key] = value
    }
    override fun remove(key: String) {
        data.remove(key)
    }
    override fun clearAll() = data.clear()
}

/** Эмбеддер с известной размерностью; вектор детерминирован и нормализован. */
class FakeEmbedder(
    override val id: String,
    override val dimensions: Int,
    private val failing: () -> Boolean = { false }
) : EmbeddingProvider {
    val queries = mutableListOf<String>()
    val passages = mutableListOf<String>()

    private fun vector(text: String): FloatArray {
        if (failing()) error("embedder $id failed")
        val words = text.split(" ").size
        val raw = FloatArray(dimensions) { i -> if (i == 0) text.length.toFloat() else (words + i).toFloat() }
        return EmbeddingMath.l2Normalize(raw)
    }

    override fun encode(text: String) = encodePassage(text)
    override fun encodeQuery(text: String): FloatArray = vector(text).also { queries += text }
    override fun encodePassage(text: String): FloatArray = vector(text).also { passages += text }
}
