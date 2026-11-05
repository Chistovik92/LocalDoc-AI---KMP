package com.localdoc.ai.storage

import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.Bookmark
import com.localdoc.ai.domain.ReaderSettings
import com.localdoc.ai.domain.TranslationSettings
import com.localdoc.ai.domain.VoiceSettings
import com.localdoc.ai.notes.Annotation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object StorageSchema {
    /** Ключ с версией схемы хранилища. Нет ключа — данные любой версии 1.x, появившейся до версионирования. */
    const val KEY = "storage_schema"

    /** Текущая версия схемы. Увеличивается вместе с новым шагом в [StorageMigrations]. */
    const val CURRENT = 1

    /** Исходное значение ключа, заменённого миграцией, сохраняется под этим префиксом (один раз). */
    const val BACKUP_PREFIX = "migration_backup_"
}

sealed interface MigrationReport {
    data object UpToDate : MigrationReport

    /** Данные сохранены более новой версией: ничего не трогаем, чтобы не испортить. */
    data class TooNew(val version: Int) : MigrationReport

    data class Migrated(val from: Int, val to: Int, val rewrittenKeys: List<String>, val droppedItems: Int) : MigrationReport
}

/**
 * Миграции хранилища: чистый апгрейд с любой 1.x до [StorageSchema.CURRENT]. Шаг 1 «спасает» данные: список
 * (библиотека, закладки, цитаты) читается по элементам — испорченный элемент не обнуляет весь список, а отдельные
 * неподходящие поля настроек заменяются значениями по умолчанию. Прежнее значение любого изменённого ключа лежит
 * в `migration_backup_<ключ>`. Нечитаемое целиком значение остаётся как есть.
 */
class StorageMigrations(private val kv: KvAccess) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun run(): MigrationReport {
        val from = kv.getValue(StorageSchema.KEY)?.trim()?.toIntOrNull() ?: 0
        if (from > StorageSchema.CURRENT) return MigrationReport.TooNew(from)
        if (from == StorageSchema.CURRENT) return MigrationReport.UpToDate
        val rewritten = ArrayList<String>()
        var dropped = 0
        if (from < 1) dropped += stepSalvage(rewritten)
        kv.putValue(StorageSchema.KEY, StorageSchema.CURRENT.toString())
        return MigrationReport.Migrated(from, StorageSchema.CURRENT, rewritten, dropped)
    }

    private fun stepSalvage(rewritten: MutableList<String>): Int {
        var dropped = 0
        dropped += salvageList("library_v1", BookEntry.serializer(), rewritten)
        dropped += salvageList("bookmarks_v1", Bookmark.serializer(), rewritten)
        dropped += salvageList("annotations_v1", Annotation.serializer(), rewritten)
        salvageObject("reader_settings", ReaderSettings.serializer(), rewritten)
        salvageObject("voice_settings", VoiceSettings.serializer(), rewritten)
        salvageObject("translation_settings", TranslationSettings.serializer(), rewritten)
        return dropped
    }

    private fun <T> salvageList(key: String, serializer: KSerializer<T>, rewritten: MutableList<String>): Int {
        val raw = kv.getValue(key) ?: return 0
        val array = (parse(raw) as? JsonArray) ?: return 0
        val good = ArrayList<JsonElement>()
        for (item in array) {
            if (runCatching { json.decodeFromJsonElement(serializer, item) }.isSuccess) good.add(item)
        }
        if (good.size == array.size) return 0
        replace(key, raw, JsonArray(good).toString(), rewritten)
        return array.size - good.size
    }

    private fun <T> salvageObject(key: String, serializer: KSerializer<T>, rewritten: MutableList<String>) {
        val raw = kv.getValue(key) ?: return
        val obj = (parse(raw) as? JsonObject) ?: return
        if (runCatching { json.decodeFromJsonElement(serializer, obj) }.isSuccess) return
        val kept = obj.filter { (name, value) ->
            runCatching { json.decodeFromJsonElement(serializer, JsonObject(mapOf(name to value))) }.isSuccess
        }
        replace(key, raw, JsonObject(kept).toString(), rewritten)
    }

    private fun parse(raw: String): JsonElement? = runCatching { json.parseToJsonElement(raw) }.getOrNull()

    private fun replace(key: String, old: String, new: String, rewritten: MutableList<String>) {
        val backup = StorageSchema.BACKUP_PREFIX + key
        if (kv.getValue(backup) == null) kv.putValue(backup, old)
        kv.putValue(key, new)
        rewritten.add(key)
    }
}
