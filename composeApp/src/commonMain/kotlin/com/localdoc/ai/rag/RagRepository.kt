package com.localdoc.ai.rag

/**
 * Локальный RAG: индексация документов чанками и семантический поиск по ним.
 * Полностью офлайн, состояние переживает перезапуск приложения.
 */
class RagRepository(
    private val store: VectorStore,
    private val embedder: EmbeddingProvider = HashingEmbedder()
) {

    /** Индексирует документ. Возвращает число созданных чанков. */
    fun indexDocument(docId: String, rawText: String): Int {
        store.remove(docId)
        val chunks = TextChunker.chunk(rawText).mapIndexed { idx, chunkText ->
            StoredChunk(
                text = chunkText,
                section = idx + 1,
                vector = embedder.encode(chunkText).toList()
            )
        }
        if (chunks.isEmpty()) return 0
        store.put(docId, chunks)
        return chunks.size
    }

    /** Индексирует документ, только если он ещё не проиндексирован. Возвращает число чанков. */
    fun ensureIndexed(docId: String, rawText: String): Int {
        val existing = store.get(docId)
        return if (existing.isNotEmpty()) existing.size else indexDocument(docId, rawText)
    }

    fun retrieveTopK(docId: String, question: String, topK: Int = 4): List<StoredChunk> {
        val query = embedder.encode(question)
        return store.topK(docId, query, topK)
    }

    fun removeDocument(docId: String) = store.remove(docId)
}
