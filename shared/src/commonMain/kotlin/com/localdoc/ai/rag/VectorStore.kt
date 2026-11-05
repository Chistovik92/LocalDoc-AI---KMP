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

/** Строковое хранилище, на котором держится индекс (в приложении — KeyValueStore, в тестах — память). */
interface RagStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clearAll()
}

class KeyValueRagStorage(private val kv: com.localdoc.ai.platform.KeyValueStore) : RagStorage {
    override fun get(key: String) = kv.get(key)
    override fun put(key: String, value: String) = kv.put(key, value)
    override fun remove(key: String) = kv.remove(key)
    override fun clearAll() = kv.clearAll()
}

/** Метаданные индекса документа: формат хранения и провайдер, чьими векторами он посчитан. */
@Serializable
data class IndexMeta(val format: Int, val provider: String)

/**
 * Локальное векторное хранилище RAG.
 * Векторы хранятся в JSON через платформенный KeyValueStore и ищутся
 * косинусной близостью (L2-нормализованные векторы => скалярное произведение).
 * Для сотен чанков поиск занимает миллисекунды без нативных зависимостей.
 */
class VectorStore(private val storage: RagStorage) {

    constructor(kv: com.localdoc.ai.platform.KeyValueStore) : this(KeyValueRagStorage(kv))


    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, List<StoredChunk>>()
    private val lock = SyncLock()

    /** Сохраняет индекс документа; [provider] — [EmbeddingProvider.signature] его векторов. */
    fun put(docId: String, chunks: List<StoredChunk>, provider: String = LEGACY_PROVIDER) {
        lock.withLock { cache[docId] = chunks }
        storage.put(key(docId), json.encodeToString(ListSerializer(StoredChunk.serializer()), chunks))
        storage.put(metaKey(docId), json.encodeToString(IndexMeta.serializer(), IndexMeta(FORMAT_VERSION, provider)))
        rememberDoc(docId)
    }

    /**
     * Провайдер, посчитавший индекс документа; null — индекса нет. Индекс без метаданных
     * (создан до версии 2.0) считается посчитанным хешированием.
     */
    fun providerOf(docId: String): String? {
        if (storage.get(key(docId)) == null) return null
        return meta(docId).provider
    }

    /** true — индекс есть, формат поддерживается и векторы посчитаны провайдером [provider]. */
    fun isCurrent(docId: String, provider: String): Boolean {
        if (storage.get(key(docId)) == null) return false
        val meta = meta(docId)
        return meta.format >= MIN_SUPPORTED_FORMAT && meta.provider == provider
    }

    private fun meta(docId: String): IndexMeta =
        storage.get(metaKey(docId))?.let { runCatching { json.decodeFromString(IndexMeta.serializer(), it) }.getOrNull() }
            ?: IndexMeta(LEGACY_FORMAT, LEGACY_PROVIDER)

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
            storage.remove(metaKey(evicted))
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
        storage.remove(metaKey(docId))
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
    private fun metaKey(docId: String) = "rag-meta:$docId"

    companion object {
        /** Формат хранения: 1 — только чанки (до 2.0), 2 — чанки + метаданные с провайдером. */
        const val FORMAT_VERSION = 2
        const val MIN_SUPPORTED_FORMAT = 1
        private const val LEGACY_FORMAT = 1

        /** Подпись [HashingEmbedder]: именно им считались все индексы до появления метаданных. */
        const val LEGACY_PROVIDER = "hash-384"

        private const val MAX_INDEXED_DOCS = 5
        private const val DOC_IDS_KEY = "rag-index:doc-ids"
    }
}
