package com.localdoc.ai.novel

import com.localdoc.ai.i18n.L10n
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.auto_update_notification_title

/** Чем закончился запуск по расписанию. */
enum class RunResult { DISABLED, NOT_ALLOWED, DONE, THROTTLED }

/**
 * Запуск автообновления: проверяет, можно ли сейчас (день, «тихие часы», сеть), запускает проверку новых глав,
 * сообщает о находках и назначает следующий запуск. Платформа (WorkManager / BGTaskScheduler, сеть, уведомления)
 * приходит функциями, поэтому вся логика проверяется тестами без устройства.
 */
class AutoUpdateService(
    private val settings: () -> AutoUpdateSettings,
    private val checker: NovelUpdateChecker,
    private val lastRun: () -> Long?,
    private val saveLastRun: (Long) -> Unit,
    private val network: () -> NetworkKind,
    private val tzOffsetMinutes: () -> Int,
    private val now: () -> Long,
    /** Назначить запуск на время [Long] (мс эпохи); Boolean — запускать только в безлимитной сети. */
    private val schedule: (Long, Boolean) -> Unit,
    private val cancel: () -> Unit,
    private val notify: (title: String, text: String) -> Unit
) {
    suspend fun runScheduled(): RunResult {
        val s = settings()
        if (!s.enabled) {
            cancel()
            return RunResult.DISABLED
        }
        val nowMs = now()
        val tz = tzOffsetMinutes()
        if (!UpdateSchedule.canRunNow(s, nowMs, tz, network())) {
            // Нельзя сейчас (ночь, мобильный интернет, выходной день): пробуем позже, не крутясь вхолостую
            schedule(UpdateSchedule.nextAllowed(s, nowMs + RETRY_MS, tz), s.wifiOnly)
            return RunResult.NOT_ALLOWED
        }
        val report = checker.checkAll()
        saveLastRun(nowMs)
        if (report.updates.isNotEmpty()) notify(L10n.get(Res.string.auto_update_notification_title), noticeText(report.updates))
        if (report.throttled) {
            schedule(UpdateSchedule.nextRunAfterThrottle(s, nowMs, tz), s.wifiOnly)
            return RunResult.THROTTLED
        }
        reschedule()
        return RunResult.DONE
    }

    /** Пересчитывает ближайший запуск (после смены настроек, перезагрузки устройства и т. п.). */
    fun reschedule() {
        val s = settings()
        val at = UpdateSchedule.nextRun(s, lastRun(), now(), tzOffsetMinutes())
        if (at == null) cancel() else schedule(at, s.wifiOnly)
    }

    companion object {
        /** Через сколько повторять запуск, если сейчас нельзя. */
        const val RETRY_MS = 30 * 60_000L
        private const val LISTED = 3

        /** Текст уведомления: до трёх произведений поимённо, дальше — только количество. */
        fun noticeText(updates: List<NovelUpdate>): String =
            if (updates.size <= LISTED) {
                updates.joinToString(", ") { "«${it.title}» +${it.newChapters.size}" }
            } else {
                "Новых глав: ${updates.sumOf { it.newChapters.size }} в ${updates.size} новеллах"
            }
    }
}
