package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.platform.KeyValueStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Долговременное хранение переведённых страниц, чтобы перевод не терялся при выходе из читалки
 * или перезапуске приложения. В памяти [PageTranslator] держит только несколько последних страниц;
 * этот стор отдаёт уже готовые переводы с диска без повторного запроса к модели.
 */
interface TranslationStore {
    /** Перевод страницы [key], сохранённый ранее; null — перевода нет. */
    fun load(key: PageKey): List<Block>?

    /** Сохраняет перевод страницы [key]. */
    fun save(key: PageKey, blocks: List<Block>)

    /** Удаляет все переводы документа (книга удалена или документ сменил разбивку). */
    fun clearDoc(docKey: String)
}

/**
 * Реализация поверх строкового KV-хранилища: каждая страница — отдельный ключ
 * `translation_v1:<docKey>:<page>:<layout>`, значение — JSON списка блоков.
 */
class KvTranslationStore(
    private val kv: KeyValueStore,
    private val serializer: KSerializer<List<Block>> = ListSerializer(Block.serializer()),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TranslationStore {

    override fun load(key: PageKey): List<Block>? {
        val raw = kv.get(kvKey(key)) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    override fun save(key: PageKey, blocks: List<Block>) {
        val kvKey = kvKey(key)
        kv.put(kvKey, json.encodeToString(serializer, blocks))
        // Реестр ключей документа — чтобы при удалении книги убрать все её переводы
        register(key, kvKey)
    }

    override fun clearDoc(docKey: String) {
        val registry = kv.get(registryKey(docKey)) ?: return
        val keys = runCatching { json.decodeFromString(ListSerializer(String.serializer()), registry) }.getOrNull()
            ?: return
        keys.forEach(kv::remove)
        kv.remove(registryKey(docKey))
    }

    private fun register(key: PageKey, kvKey: String) {
        val registry = kv.get(registryKey(key.docKey))
        val existing = registry?.let {
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        } ?: emptyList()
        if (kvKey !in existing) {
            kv.put(registryKey(key.docKey), json.encodeToString(ListSerializer(String.serializer()), existing + kvKey))
        }
    }

    private fun kvKey(key: PageKey): String =
        "translation_v1:${key.docKey}:${key.page}:${key.target}:${key.layout}"

    private fun registryKey(docKey: String): String = "translation_registry:$docKey"
}
