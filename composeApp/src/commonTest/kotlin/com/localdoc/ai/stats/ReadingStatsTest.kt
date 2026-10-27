package com.localdoc.ai.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingTrackerTest {

    private val minute = 60_000L
    private var tracker = ReadingTracker { day(it) }

    /** Сутки считаются по 86 400 000 мс от нуля — для тестов хватает. */
    private fun day(ms: Long) = "day${ms / 86_400_000L}"

    @Test
    fun `first activity only starts the interval`() {
        assertNull(tracker.onActivity("a", 0, 1_000))
    }

    @Test
    fun `time and words between two activities are credited`() {
        tracker.onActivity("a", 0, 0)
        val delta = assertNotNull(tracker.onActivity("a", 200, minute))
        assertEquals(minute, delta.ms)
        assertEquals(200, delta.words)
        assertEquals("a", delta.bookId)
    }

    @Test
    fun `idle gap is not counted`() {
        tracker.onActivity("a", 0, 0)
        assertNull(tracker.onActivity("a", 300, ReadingTracker.IDLE_MS + 1))
        // и следующий шаг считается уже от новой точки
        val next = assertNotNull(tracker.onActivity("a", 500, ReadingTracker.IDLE_MS + 1 + minute))
        assertEquals(minute, next.ms)
        assertEquals(200, next.words)
    }

    @Test
    fun `pause closes the interval so the break is not counted`() {
        tracker.onActivity("a", 0, 0)
        tracker.onPause()
        assertNull(tracker.onActivity("a", 100, 2 * minute))
        val delta = assertNotNull(tracker.onActivity("a", 250, 3 * minute))
        assertEquals(minute, delta.ms)
        assertEquals(150, delta.words)
    }

    @Test
    fun `switching books starts a fresh interval`() {
        tracker.onActivity("a", 0, 0)
        assertNull(tracker.onActivity("b", 5_000, minute))
        val delta = assertNotNull(tracker.onActivity("b", 5_100, 2 * minute))
        assertEquals("b", delta.bookId)
        assertEquals(100, delta.words)
    }

    @Test
    fun `a long jump is navigation and gives time without words`() {
        tracker.onActivity("a", 0, 0)
        val delta = assertNotNull(tracker.onActivity("a", 50_000, minute))
        assertEquals(minute, delta.ms)
        assertEquals(0, delta.words)
    }

    @Test
    fun `scrolling faster than anyone reads gives time without words`() {
        tracker.onActivity("a", 0, 0)
        val delta = assertNotNull(tracker.onActivity("a", 1_500, 5_000))
        assertEquals(0, delta.words)
        assertEquals(5_000, delta.ms)
    }

    @Test
    fun `moving backwards gives time without words`() {
        tracker.onActivity("a", 1_000, 0)
        val delta = assertNotNull(tracker.onActivity("a", 900, minute))
        assertEquals(0, delta.words)
        assertEquals(minute, delta.ms)
    }

    @Test
    fun `credit goes to the day of the later activity`() {
        val midnight = 86_400_000L
        tracker.onActivity("a", 0, midnight - minute)
        val delta = assertNotNull(tracker.onActivity("a", 100, midnight + minute))
        // разрыв больше простоя не нужен: проверяем только день записи
        assertEquals("day1", delta.day)
    }
}

class ReadingStatsTest {

    @Test
    fun `adding a delta accumulates the day and the book`() {
        val stats = ReadingStats()
            .add(ReadingDelta("2026-10-26", "a", 60_000, 200))
            .add(ReadingDelta("2026-10-26", "a", 30_000, 100))
            .add(ReadingDelta("2026-10-26", "b", 10_000, 0))
        assertEquals(100_000, stats.days.getValue("2026-10-26").ms)
        assertEquals(300, stats.days.getValue("2026-10-26").words)
        assertEquals(90_000, stats.books.getValue("a").ms)
        assertEquals(300, stats.books.getValue("a").words)
    }

    @Test
    fun `empty delta changes nothing`() {
        val stats = ReadingStats()
        assertEquals(stats, stats.add(ReadingDelta("2026-10-26", "a", 0, 0)))
    }

    @Test
    fun `old days are pruned`() {
        var stats = ReadingStats()
        for (i in 1..ReadingStats.KEEP_DAYS + 20) {
            stats = stats.add(ReadingDelta("d${i.toString().padStart(4, '0')}", "a", 1_000, 1))
        }
        assertEquals(ReadingStats.KEEP_DAYS, stats.days.size)
        assertTrue("d0001" !in stats.days)
    }

    @Test
    fun `summary counts today week total and speed`() {
        val stats = ReadingStats(
            days = mapOf(
                "2026-10-27" to DayStats(ms = 10 * 60_000, words = 2_000),
                "2026-10-26" to DayStats(ms = 20 * 60_000, words = 4_000),
                "2026-10-10" to DayStats(ms = 30 * 60_000, words = 3_000)
            )
        )
        val s = ReadingSummary.of(stats, today = "2026-10-27")
        assertEquals(10 * 60_000, s.todayMs)
        assertEquals(30 * 60_000, s.weekMs)
        assertEquals(60 * 60_000, s.totalMs)
        assertEquals(9_000, s.totalWords)
        assertEquals(150, s.wordsPerMinute)
    }

    @Test
    fun `speed is zero until there is enough reading time`() {
        val stats = ReadingStats(days = mapOf("2026-10-27" to DayStats(ms = 20_000, words = 100)))
        assertEquals(0, ReadingSummary.of(stats, "2026-10-27").wordsPerMinute)
    }

    @Test
    fun `streak counts consecutive days ending today or yesterday`() {
        val day = DayStats(ms = 5 * 60_000, words = 500)
        val stats = ReadingStats(days = mapOf("2026-10-27" to day, "2026-10-26" to day, "2026-10-25" to day, "2026-10-23" to day))
        assertEquals(3, ReadingSummary.of(stats, "2026-10-27").streakDays)
        // сегодня ещё не читали — серия не обрывается
        val yesterdayOnly = ReadingStats(days = mapOf("2026-10-26" to day, "2026-10-25" to day))
        assertEquals(2, ReadingSummary.of(yesterdayOnly, "2026-10-27").streakDays)
        // два пропущенных дня — серия сорвана
        assertEquals(0, ReadingSummary.of(ReadingStats(days = mapOf("2026-10-24" to day)), "2026-10-27").streakDays)
    }

    @Test
    fun `last days list is oldest first and fills gaps`() {
        val stats = ReadingStats(days = mapOf("2026-10-27" to DayStats(60_000, 100), "2026-10-25" to DayStats(120_000, 200)))
        val last = ReadingSummary.of(stats, "2026-10-27").lastDays(3)
        assertEquals(listOf("2026-10-25", "2026-10-26", "2026-10-27"), last.map { it.first })
        assertEquals(listOf(120_000L, 0L, 60_000L), last.map { it.second.ms })
    }

    @Test
    fun `date arithmetic crosses month and year borders`() {
        assertEquals("2026-02-28", DateMath.minusDays("2026-03-01", 1))
        assertEquals("2023-12-31", DateMath.minusDays("2024-01-01", 1))
        assertEquals("2024-02-29", DateMath.minusDays("2024-03-01", 1))
        assertEquals("2026-10-20", DateMath.minusDays("2026-10-27", 7))
    }

    @Test
    fun `day label from millis uses the given offset`() {
        // 2026-10-27 23:30 UTC при смещении +3 ч — уже 28-е
        val utc = 1_793_143_800_000L
        assertEquals("2026-10-27", DateMath.dayOf(utc, 0))
        assertEquals("2026-10-28", DateMath.dayOf(utc, 3 * 3_600_000L))
    }
}

class ReadingRecorderTest {

    private fun book(vararg texts: String) = com.localdoc.ai.book.Book(
        title = "t", format = com.localdoc.ai.book.BookFormat.EPUB,
        blocks = texts.map { com.localdoc.ai.book.Block(com.localdoc.ai.book.BlockType.PARAGRAPH, it) }
    )

    @Test
    fun `counts words ignoring extra whitespace`() {
        assertEquals(0, ReadingRecorder.countWords("  \n "))
        assertEquals(4, ReadingRecorder.countWords("Раз  два\nтри\tчетыре"))
    }

    @Test
    fun `word prefix covers every block`() {
        val prefix = ReadingRecorder.prefixWords(book("один два", "три", "четыре пять шесть"))
        assertEquals(listOf(0, 2, 3, 6), prefix.toList())
    }

    @Test
    fun `writes are buffered until the flush interval and on pause`() {
        var stats = ReadingStats()
        var writes = 0
        var now = 0L
        val recorder = ReadingRecorder({ f -> stats = f(stats); writes++ }, { now }, { 0L }, flushEveryMs = 15_000)
        val b = book(*Array(10) { "слово ".repeat(100) })

        recorder.onPosition("a", b, 0)
        now += 5_000
        recorder.onPosition("a", b, 1)
        now += 5_000
        recorder.onPosition("a", b, 2)
        assertEquals(0, writes, "до интервала запись не нужна")

        recorder.onPause()
        assertEquals(1, writes)
        assertEquals(10_000, stats.days.values.sumOf { it.ms })
        assertEquals(200, stats.days.values.sumOf { it.words })
    }

    @Test
    fun `flush without pending data writes nothing`() {
        var writes = 0
        val recorder = ReadingRecorder({ writes++ }, { 0L })
        recorder.flush()
        assertEquals(0, writes)
    }

    @Test
    fun `sentence offsets inside a paragraph keep word progress in step with time`() {
        var stats = ReadingStats()
        var now = 0L
        val recorder = ReadingRecorder({ f -> stats = f(stats) }, { now }, { 0L }, flushEveryMs = 1)
        val text = "раз два три. четыре пять шесть. семь восемь девять."
        val b = book(text)
        recorder.onPosition("a", b, 0, 0)
        now += 6_000
        recorder.onPosition("a", b, 0, text.indexOf("четыре"))
        now += 6_000
        recorder.onPosition("a", b, 0, text.indexOf("семь"))
        recorder.flush()
        assertEquals(6, stats.days.values.sumOf { it.words })
    }
}
