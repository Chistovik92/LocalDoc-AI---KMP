package com.localdoc.ai.novel

import kotlinx.serialization.Serializable

/** Какая сеть сейчас у устройства (для правила «только по Wi-Fi»). */
enum class NetworkKind { NONE, MOBILE, UNMETERED }

/**
 * Настройки автообновления скачанных новелл. Время суток — минуты от полуночи по местному времени,
 * дни недели — 1 (понедельник) … 7 (воскресенье); пустое множество дней значит «каждый день».
 */
@Serializable
data class AutoUpdateSettings(
    val enabled: Boolean = false,
    val intervalHours: Int = 12,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val quietEnabled: Boolean = true,
    val quietFromMinute: Int = 23 * 60,
    val quietToMinute: Int = 7 * 60,
    /** true — только по Wi-Fi / безлимитной сети; на мобильном интернете проверка не запускается. */
    val wifiOnly: Boolean = true
) {
    companion object {
        const val MIN_INTERVAL_HOURS = 1
        val INTERVAL_CHOICES = listOf(3, 6, 12, 24, 72, 168)
    }
}

/** Чистые функции расписания: никакой платформы, время и пояс приходят аргументами. */
object UpdateSchedule {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val MINUTES_IN_DAY = 24 * 60
    private const val SEARCH_STEPS = 27

    /** Через сколько часов повторить попытку после отказа сайта (429 / 503 / 403). */
    const val THROTTLE_RETRY_HOURS = 3L

    /** День недели 1 (пн) … 7 (вс) для момента [epochMs] в поясе со смещением [tzOffsetMinutes]. */
    fun dayOfWeek(epochMs: Long, tzOffsetMinutes: Int): Int {
        val epochDay = (epochMs + tzOffsetMinutes * MINUTE_MS).floorDiv(DAY_MS)
        // 1970-01-01 — четверг (4)
        return (epochDay + 3).mod(7L).toInt() + 1
    }

    fun minuteOfDay(epochMs: Long, tzOffsetMinutes: Int): Int =
        ((epochMs + tzOffsetMinutes * MINUTE_MS).mod(DAY_MS) / MINUTE_MS).toInt()

    /** Попадает ли минута суток в «тихие часы» (диапазон может переходить через полночь; одинаковые границы — пусто). */
    fun inQuietHours(settings: AutoUpdateSettings, minuteOfDay: Int): Boolean {
        if (!settings.quietEnabled) return false
        val from = settings.quietFromMinute.mod(MINUTES_IN_DAY)
        val to = settings.quietToMinute.mod(MINUTES_IN_DAY)
        return when {
            from == to -> false
            from < to -> minuteOfDay in from until to
            else -> minuteOfDay >= from || minuteOfDay < to
        }
    }

    fun networkAllows(settings: AutoUpdateSettings, network: NetworkKind): Boolean = when (network) {
        NetworkKind.NONE -> false
        NetworkKind.MOBILE -> !settings.wifiOnly
        NetworkKind.UNMETERED -> true
    }

    /** Можно ли начать проверку прямо сейчас. */
    fun canRunNow(settings: AutoUpdateSettings, nowMs: Long, tzOffsetMinutes: Int, network: NetworkKind): Boolean =
        settings.enabled &&
            dayAllowed(settings, dayOfWeek(nowMs, tzOffsetMinutes)) &&
            !inQuietHours(settings, minuteOfDay(nowMs, tzOffsetMinutes)) &&
            networkAllows(settings, network)

    /**
     * Когда запускать следующую проверку: [lastRunMs] + интервал (но не раньше [nowMs]), сдвинутое на ближайший
     * разрешённый день вне «тихих часов». null — автообновление выключено. Первая проверка (последнего запуска нет) — сразу.
     */
    fun nextRun(settings: AutoUpdateSettings, lastRunMs: Long?, nowMs: Long, tzOffsetMinutes: Int): Long? {
        if (!settings.enabled) return null
        val interval = settings.intervalHours.coerceAtLeast(AutoUpdateSettings.MIN_INTERVAL_HOURS) * HOUR_MS
        val due = if (lastRunMs == null) nowMs else lastRunMs + interval
        return nextAllowed(settings, maxOf(due, nowMs), tzOffsetMinutes)
    }

    /** После отказа сайта следующая попытка — через [THROTTLE_RETRY_HOURS] часов (с поправкой на день и «тихие часы»). */
    fun nextRunAfterThrottle(settings: AutoUpdateSettings, nowMs: Long, tzOffsetMinutes: Int): Long =
        nextAllowed(settings, nowMs + THROTTLE_RETRY_HOURS * HOUR_MS, tzOffsetMinutes)

    /** Ближайший к [atMs] момент (не раньше) в разрешённый день и вне «тихих часов». */
    fun nextAllowed(settings: AutoUpdateSettings, atMs: Long, tzOffsetMinutes: Int): Long {
        var t = atMs
        repeat(SEARCH_STEPS) {
            if (!dayAllowed(settings, dayOfWeek(t, tzOffsetMinutes))) {
                val localMidnight = (t + tzOffsetMinutes * MINUTE_MS).floorDiv(DAY_MS) * DAY_MS
                t = localMidnight + DAY_MS - tzOffsetMinutes * MINUTE_MS
                return@repeat
            }
            val minute = minuteOfDay(t, tzOffsetMinutes)
            if (inQuietHours(settings, minute)) {
                val to = settings.quietToMinute.mod(MINUTES_IN_DAY)
                val delta = (to - minute).mod(MINUTES_IN_DAY)
                // доли минуты отбрасываем и добавляем минуты до конца тишины
                t = t - t.mod(MINUTE_MS) + delta * MINUTE_MS
                return@repeat
            }
            return t
        }
        return t
    }

    private fun dayAllowed(settings: AutoUpdateSettings, dayOfWeek: Int) = settings.days.isEmpty() || dayOfWeek in settings.days
}
