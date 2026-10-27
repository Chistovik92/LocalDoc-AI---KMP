package com.localdoc.ai.rag

/**
 * Локальный RAG: индексация документов чанками и семантический поиск по ним.
 * Полностью офлайн, состояние переживает перезапуск приложения.
 *
 * Провайдер эмбеддингов берётся при каждом вызове из [embedderSource]: пользователь может скачать
 * или удалить модель в любой момент. Индекс помнит, чьими векторами посчитан, и пересчитывается
 * при смене провайдера; пока новый индекс не готов, старый остаётся на месте.
 */
class RagRepository(
    private val store: VectorStore,
    private val embedderSource: () -> EmbeddingProvider,
    /** Вызывается, если провайдер упал; после вызова [embedderSource] должен отдавать запасной провайдер. */
    private val onEmbedderFailure: (Throwable) -> Unit = {}
) {
    constructor(store: VectorStore, embedder: EmbeddingProvider = HashingEmbedder()) : this(store, { embedder })

    /** Индексирует документ. Возвращает число созданных чанков. */
    fun indexDocument(docId: String, rawText: String): Int = index(docId, rawText, embedderSource())

    private fun index(docId: String, rawText: String, embedder: EmbeddingProvider): Int {
        // Сначала считаем всё, потом заменяем: ошибка посреди пересчёта не должна стирать рабочий индекс
        val chunks = TextChunker.chunk(rawText).mapIndexed { idx, chunkText ->
            StoredChunk(
                text = chunkText,
                section = idx + 1,
                vector = embedder.encodePassage(chunkText).toList()
            )
        }
        if (chunks.isEmpty()) {
            store.remove(docId)
            return 0
        }
        store.put(docId, chunks, embedder.signature)
        return chunks.size
    }

    /** Индексирует документ, если его нет или он посчитан другим провайдером. Возвращает число чанков. */
    fun ensureIndexed(docId: String, rawText: String): Int = withFallback { embedder ->
        val existing = store.get(docId)
        if (existing.isNotEmpty() && store.isCurrent(docId, embedder.signature)) existing.size
        else index(docId, rawText, embedder)
    }

    fun retrieveTopK(docId: String, question: String, topK: Int = 4): List<StoredChunk> = withFallback { embedder ->
        // Индекс другого провайдера лежит в другом пространстве векторов — искать по нему нельзя
        if (!store.isCurrent(docId, embedder.signature)) emptyList()
        else store.topK(docId, embedder.encodeQuery(question), topK)
    }

    fun removeDocument(docId: String) = store.remove(docId)

    private fun <T> withFallback(block: (EmbeddingProvider) -> T): T {
        val first = embedderSource()
        return try {
            block(first)
        } catch (e: Exception) {
            onEmbedderFailure(e)
            val next = embedderSource()
            if (next === first) throw e
            block(next)
        }
    }
}
