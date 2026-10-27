package com.localdoc.ai.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Расписание автообновления: интервалы, дни недели, «тихие часы», сеть. Время — эпоха в мс и смещение пояса в минутах. */
class UpdateScheduleTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Понедельник 00:00 UTC в миллисекундах эпохи (день № 20 458 от 1970-01-01 — понедельник). */
    private val monday = 20_458L * day

    private fun at(dayOffset: Int, h: Int, m: Int = 0) = monday + dayOffset * day + h * hour + m * minute

    @Test
    fun `day of week and minute of day follow the time zone`() {
        assertEquals(1, UpdateSchedule.dayOfWeek(monday, 0))
        assertEquals(7, UpdateSchedule.dayOfWeek(monday - 1, 0))
        // UTC+3: понедельник 22:00 UTC — уже вторник 01:00
        assertEquals(2, UpdateSchedule.dayOfWeek(at(0, 22), 180))
        assertEquals(60, UpdateSchedule.minuteOfDay(at(0, 22), 180))
        assertEquals(4, UpdateSchedule.dayOfWeek(0, 0)) // 1 января 1970 — четверг
    }

    @Test
    fun `quiet hours wrap over midnight`() {
        val s = AutoUpdateSettings(quietEnabled = true, quietFromMinute = 23 * 60, quietToMinute = 7 * 60)
        assertTrue(UpdateSchedule.inQuietHours(s, 23 * 60))
        assertTrue(UpdateSchedule.inQuietHours(s, 0))
        assertTrue(UpdateSchedule.inQuietHours(s, 6 * 60 + 59))
        assertFalse(UpdateSchedule.inQuietHours(s, 7 * 60))
        assertFalse(UpdateSchedule.inQuietHours(s, 12 * 60))
        assertFalse(UpdateSchedule.inQuietHours(s.copy(quietEnabled = false), 2 * 60))
    }

    @Test
    fun `quiet hours inside one day and an empty range`() {
        val s = AutoUpdateSettings(quietEnabled = true, quietFromMinute = 13 * 60, quietToMinute = 15 * 60)
        assertTrue(UpdateSchedule.inQuietHours(s, 14 * 60))
        assertFalse(UpdateSchedule.inQuietHours(s, 15 * 60))
        assertFalse(UpdateSchedule.inQuietHours(s.copy(quietFromMinute = 600, quietToMinute = 600), 600))
    }

    @Test
    fun `disabled schedule never runs`() {
        assertNull(UpdateSchedule.nextRun(AutoUpdateSettings(enabled = false), null, monday, 0))
    }

    @Test
    fun `first run happens now when allowed`() {
        val s = AutoUpdateSettings(enabled = true, quietEnabled = false)
        assertEquals(at(0, 12), UpdateSchedule.nextRun(s, null, at(0, 12), 0))
    }

    @Test
    fun `next run is the last run plus the interval`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false)
        assertEquals(at(0, 16), UpdateSchedule.nextRun(s, at(0, 10), at(0, 11), 0))
    }

    @Test
    fun `an overdue run starts now not in the past`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false)
        assertEquals(at(2, 9), UpdateSchedule.nextRun(s, at(0, 10), at(2, 9), 0))
    }

    @Test
    fun `a run that lands in quiet hours moves to their end`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 12, quietEnabled = true, quietFromMinute = 23 * 60, quietToMinute = 7 * 60)
        // последний запуск в 14:00 → по интервалу 02:00 ночью → переносится на 07:00
        assertEquals(at(1, 7), UpdateSchedule.nextRun(s, at(0, 14), at(0, 15), 0))
    }

    @Test
    fun `quiet hours use the local time zone`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 1, quietEnabled = true, quietFromMinute = 23 * 60, quietToMinute = 7 * 60)
        // UTC+3: 19:30 UTC = 22:30 местного, +1 ч = 23:30 местного (тихо) → 07:00 местного = 04:00 UTC следующего дня
        assertEquals(at(1, 4), UpdateSchedule.nextRun(s, at(0, 19, 30), at(0, 19, 31), 180))
    }

    @Test
    fun `only the chosen days of the week are used`() {
        // только суббота (6) и воскресенье (7)
        val s = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false, days = setOf(6, 7))
        // понедельник 10:00 → ближайшая суббота 00:00 (понедельник + 5 дней)
        assertEquals(at(5, 0), UpdateSchedule.nextRun(s, at(0, 4), at(0, 10), 0))
    }

    @Test
    fun `an empty day set is treated as every day`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false, days = emptySet())
        assertEquals(at(0, 16), UpdateSchedule.nextRun(s, at(0, 10), at(0, 11), 0))
    }

    @Test
    fun `days and quiet hours combine`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 1, days = setOf(2), quietEnabled = true, quietFromMinute = 0, quietToMinute = 8 * 60)
        // вторник 00:00 тихо до 08:00
        assertEquals(at(1, 8), UpdateSchedule.nextRun(s, at(0, 20), at(0, 21), 0))
    }

    @Test
    fun `mobile data is refused when wifi only is on`() {
        val s = AutoUpdateSettings(wifiOnly = true)
        assertTrue(UpdateSchedule.networkAllows(s, NetworkKind.UNMETERED))
        assertFalse(UpdateSchedule.networkAllows(s, NetworkKind.MOBILE))
        assertFalse(UpdateSchedule.networkAllows(s, NetworkKind.NONE))
        assertTrue(UpdateSchedule.networkAllows(s.copy(wifiOnly = false), NetworkKind.MOBILE))
        assertFalse(UpdateSchedule.networkAllows(s.copy(wifiOnly = false), NetworkKind.NONE))
    }

    @Test
    fun `can run now needs enabled day quiet hours and network`() {
        val s = AutoUpdateSettings(enabled = true, quietEnabled = true, quietFromMinute = 23 * 60, quietToMinute = 7 * 60)
        assertTrue(UpdateSchedule.canRunNow(s, at(0, 12), 0, NetworkKind.UNMETERED))
        assertFalse(UpdateSchedule.canRunNow(s, at(0, 3), 0, NetworkKind.UNMETERED))
        assertFalse(UpdateSchedule.canRunNow(s, at(0, 12), 0, NetworkKind.MOBILE))
        assertFalse(UpdateSchedule.canRunNow(s.copy(enabled = false), at(0, 12), 0, NetworkKind.UNMETERED))
    }

    @Test
    fun `interval is never shorter than the minimum`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 0, quietEnabled = false)
        assertEquals(at(0, 11), UpdateSchedule.nextRun(s, at(0, 10), at(0, 10, 1), 0))
        assertEquals(1, AutoUpdateSettings.MIN_INTERVAL_HOURS)
    }

    @Test
    fun `after a refusal by the site the next attempt is postponed`() {
        val s = AutoUpdateSettings(enabled = true, intervalHours = 6, quietEnabled = false)
        val next = UpdateSchedule.nextRunAfterThrottle(s, at(0, 10), 0)
        assertEquals(at(0, 10) + UpdateSchedule.THROTTLE_RETRY_HOURS * hour, next)
    }
}
