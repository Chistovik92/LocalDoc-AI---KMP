package com.localdoc.ai.rag

import com.localdoc.ai.platform.SyncLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class StoredChunk(
    val text: String,
    val section: Int,
    val vector: List<Float>
)

/**
 * Локальное векторное хранилище RAG.
 * Векторы хранятся в JSON через платформенный KeyValueStore и ищутся
 * косинусной близостью (L2-нормализованные векторы => скалярное произведение).
 * Для сотен чанков поиск занимает миллисекунды без нативных зависимостей.
 */
class VectorStore(private val storage: com.localdoc.ai.platform.KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, List<StoredChunk>>()
    private val lock = SyncLock()

    fun put(docId: String, chunks: List<StoredChunk>) {
        lock.withLock { cache[docId] = chunks }
        storage.put(key(docId), json.encodeToString(ListSerializer(StoredChunk.serializer()), chunks))
        rememberDoc(docId)
    }

    /**
     * KV-хранилище (SharedPreferences / NSUserDefaults) целиком держится в памяти,
     * поэтому храним индексы только последних [MAX_INDEXED_DOCS] документов.
     */
    private fun rememberDoc(docId: String) = lock.withLock {
        // Весь read-modify-write списка под замком (SyncLock не реентерабелен — remove() не вызываем)
        val ids = (loadDocIds() - docId) + docId
        ids.dropLast(MAX_INDEXED_DOCS).forEach { evicted ->
            cache.remove(evicted)
            storage.remove(key(evicted))
        }
        storage.put(DOC_IDS_KEY, json.encodeToString(ListSerializer(String.serializer()), ids.takeLast(MAX_INDEXED_DOCS)))
    }

    private fun loadDocIds(): List<String> = storage.get(DOC_IDS_KEY)?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun get(docId: String): List<StoredChunk> = lock.withLock {
        cache[docId] ?: run {
            val raw = storage.get(key(docId))
            val parsed = raw?.let {
                runCatching {
                    json.decodeFromString(ListSerializer(StoredChunk.serializer()), it)
                }.getOrDefault(emptyList())
            } ?: emptyList()
            cache[docId] = parsed
            parsed
        }
    }

    fun remove(docId: String) {
        lock.withLock { cache.remove(docId) }
        storage.remove(key(docId))
    }

    fun clearAll() {
        lock.withLock { cache.clear() }
        storage.clearAll()
    }

    /** Топ-K наиболее релевантных чанков по запросу. */
    fun topK(docId: String, queryVector: FloatArray, k: Int): List<StoredChunk> {
        val chunks = get(docId)
        return chunks
            .map { it to dot(queryVector, it.vector) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun dot(a: FloatArray, b: List<Float>): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun key(docId: String) = "rag:$docId"

    private companion object {
        const val MAX_INDEXED_DOCS = 5
        const val DOC_IDS_KEY = "rag-index:doc-ids"
    }
}
