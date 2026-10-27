package com.localdoc.ai.sync

import kotlinx.serialization.Serializable

/**
 * Одна синхронизируемая запись: прогресс книги, закладка, цитата, подборка. [id] — «вид/ключ», стабильный на
 * всех устройствах; [data] — JSON полезной нагрузки, для слияния непрозрачный. Удаление — запись-«надгробие»
 * ([deleted]), иначе удалённое на одном устройстве вернулось бы с другого.
 */
@Serializable
data class SyncRecord(
    val id: String,
    val updatedAt: Long,
    val device: String,
    val deleted: Boolean = false,
    val data: String = ""
) {
    val kind: String get() = id.substringBefore('/')

    /** Одинаковое содержимое (время и устройство правки не важны). */
    fun sameContent(other: SyncRecord?) = other != null && deleted == other.deleted && (deleted || data == other.data)
}

/** Документ синхронизации — то, что (после шифрования) лежит в облаке. Формат описан в docs/BACKUP_FORMAT.md. */
@Serializable
data class SyncDocument(
    val format: String = SyncFormat.ID,
    val version: Int = SyncFormat.VERSION,
    /** Растёт на единицу при каждой записи в облако: откат облака к старому состоянию заметен по уменьшению. */
    val revision: Long = 0,
    val writtenBy: String = "",
    val writtenAt: Long = 0,
    val records: List<SyncRecord> = emptyList()
)

object SyncFormat {
    const val ID = "localdoc-sync"
    const val VERSION = 1
    const val FILE_NAME = "localdoc-sync.ldsync"

    /** Надгробия старше этого срока забываются: устройство, не выходившее на связь дольше, получит запись заново. */
    const val TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000
}

/** Строка журнала изменений: что слияние сделало с записью. */
@Serializable
data class SyncLogEntry(val at: Long, val id: String, val action: String, val detail: String = "")

/** Состояние последней успешной синхронизации этого устройства (хранится в KV, в резервную копию не входит). */
@Serializable
data class SyncState(
    val deviceId: String = "",
    /** Ревизия облака, которую мы видели в последний раз. */
    val revision: Long = 0,
    val lastSyncAt: Long = 0,
    /** Общая «база»: слитый набор записей на момент последней синхронизации, включая надгробия и чужие виды. */
    val records: List<SyncRecord> = emptyList(),
    /** Записи, которые пока нечем применить (нет книги на этом устройстве): ждут, но не считаются удалёнными. */
    val deferred: List<String> = emptyList(),
    val log: List<SyncLogEntry> = emptyList()
)
