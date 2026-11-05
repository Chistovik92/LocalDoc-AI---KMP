package com.localdoc.ai.platform

import com.localdoc.ai.novel.NetworkKind

/**
 * Фоновые проверки новых глав: Android — WorkManager, iOS — BGTaskScheduler. Платформа лишь будит приложение
 * в назначенное время; что проверять и когда нельзя, решает общий код ([com.localdoc.ai.novel.AutoUpdateService]).
 * Сроки на обеих платформах приблизительные: система может сдвинуть запуск (экономия батареи, на iOS — по привычкам пользователя).
 */
expect object BackgroundUpdates {
    /** Назначить единственный запуск на время [atEpochMs]; прежнее назначение заменяется. [requireUnmetered] — только в безлимитной сети. */
    fun schedule(atEpochMs: Long, requireUnmetered: Boolean)

    fun cancel()

    fun currentNetwork(): NetworkKind

    /** Смещение местного времени от UTC в минутах. */
    fun timeZoneOffsetMinutes(): Int

    /** Показать уведомление о новых главах. */
    fun notifyNewChapters(title: String, text: String)

    /** Запросить у системы разрешение на уведомления (iOS; на Android оно запрашивается при запуске). */
    fun requestNotificationPermission()
}
