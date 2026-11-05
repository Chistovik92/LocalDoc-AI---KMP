package com.localdoc.ai.rag

/** Запуск ONNX-модели энкодера: на входе id токенов и маска, на выходе плоский last_hidden_state `[токены × dim]`. */
interface OnnxTextEncoder {
    fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray
    fun close()
}

/**
 * multilingual-e5-small: токенизация → ONNX → средний пулинг по маске → L2-нормализация.
 * Модель обучена с префиксами «query: » у запросов и «passage: » у фрагментов — без них качество падает.
 */
class E5Embedder(
    private val tokenizer: UnigramTokenizer,
    private val encoder: OnnxTextEncoder,
    override val dimensions: Int = DIMENSIONS
) : EmbeddingProvider {

    override val id: String get() = "e5-small"

    override fun encode(text: String): FloatArray = encodePassage(text)

    override fun encodeQuery(text: String): FloatArray = embed(QUERY_PREFIX + text)

    override fun encodePassage(text: String): FloatArray = embed(PASSAGE_PREFIX + text)

    fun close() = encoder.close()

    private fun embed(prefixed: String): FloatArray {
        val ids = tokenizer.encode(prefixed)
        val mask = LongArray(ids.size) { 1L }
        val hidden = encoder.run(ids, mask)
        check(hidden.size == ids.size * dimensions) {
            "модель вернула ${hidden.size} значений, ожидалось ${ids.size} × $dimensions"
        }
        return EmbeddingMath.l2Normalize(EmbeddingMath.meanPool(hidden, mask, dimensions))
    }

    companion object {
        const val DIMENSIONS = 384
        const val QUERY_PREFIX = "query: "
        const val PASSAGE_PREFIX = "passage: "
    }
}
