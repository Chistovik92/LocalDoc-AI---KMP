package com.localdoc.ai.backup

import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.storage.KvAccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Настройки копии по расписанию. Хранятся на устройстве и в саму копию не входят. */
@Serializable
data class BackupScheduleSettings(
    val enabled: Boolean = false,
    val everyDays: Int = 7,
    /** Сколько последних автоматических копий хранить. */
    val keep: Int = 5,
    val lastRunAt: Long = 0
)

/**
 * Копия по расписанию: при запуске приложения, если с прошлой копии прошло [BackupScheduleSettings.everyDays],
 * в хранилище приложения кладётся `autobackup-<дата>.ldbackup`, лишние старые копии удаляются. Фонового
 * планировщика (WorkManager / BGTaskScheduler) пока нет — проверка идёт при запуске.
 */
class ScheduledBackup(
    private val kv: KvAccess,
    private val storage: BackupStorage,
    private val service: BackupService,
    private val clock: () -> Long = ::nowMillis
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun settings(): BackupScheduleSettings =
        kv.getValue(KEY)?.let { runCatching { json.decodeFromString(BackupScheduleSettings.serializer(), it) }.getOrNull() }
            ?: BackupScheduleSettings()

    fun update(settings: BackupScheduleSettings) {
        val safe = settings.copy(everyDays = settings.everyDays.coerceIn(1, 365), keep = settings.keep.coerceIn(1, 30))
        kv.putValue(KEY, json.encodeToString(BackupScheduleSettings.serializer(), safe))
    }

    fun isDue(): Boolean = settings().let { it.enabled && clock() - it.lastRunAt >= it.everyDays * DAY_MS }

    /** Имена сохранённых автоматических копий, новые первыми. */
    fun existing(): List<String> = storage.listFiles(FILE_PREFIX).filter { it.endsWith(".${BackupFormat.EXTENSION}") }.sortedDescending()

    /** Делает копию, если пора. Возвращает имя файла или null (не пора или сбой — тогда повторим при следующем запуске). */
    fun runIfDue(): String? {
        if (!isDue()) return null
        val now = clock()
        val name = "$FILE_PREFIX${BackupFormat.isoDate(now)}.${BackupFormat.EXTENSION}"
        try {
            storage.writeFile(name, service.create())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val current = settings()
        update(current.copy(lastRunAt = now))
        existing().drop(current.keep.coerceIn(1, 30)).forEach { storage.deleteFiles(it) }
        return name
    }

    companion object {
        const val KEY = "backup_schedule"
        const val FILE_PREFIX = "autobackup-"
        private const val DAY_MS = 86_400_000L
    }
}
