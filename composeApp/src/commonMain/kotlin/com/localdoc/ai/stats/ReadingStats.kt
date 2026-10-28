package com.localdoc.ai.stats

import com.localdoc.ai.backup.BackupFormat
import kotlinx.serialization.Serializable

/** Сколько прочитано за один шаг: время и слова, записанные на сутки [day] (локальные, `2026-10-27`). */
data class ReadingDelta(val day: String, val bookId: String, val ms: Long, val words: Int)

@Serializable
data class DayStats(val ms: Long = 0, val words: Int = 0)

@Serializable
data class BookReadingStats(val ms: Long = 0, val words: Int = 0)

/**
 * Статистика чтения, хранится локально одним JSON. Новые поля добавляются только с значениями по умолчанию,
 * чтобы копии любой версии читались без миграций.
 */
@Serializable
data class ReadingStats(
    val days: Map<String, DayStats> = emptyMap(),
    val books: Map<String, BookReadingStats> = emptyMap()
) {
    fun add(delta: ReadingDelta): ReadingStats {
        if (delta.ms <= 0 && delta.words <= 0) return this
        val day = days[delta.day] ?: DayStats()
        val book = books[delta.bookId] ?: BookReadingStats()
        return copy(
            days = (days + (delta.day to DayStats(day.ms + delta.ms, day.words + delta.words))).prune(),
            books = books + (delta.bookId to BookReadingStats(book.ms + delta.ms, book.words + delta.words))
        )
    }

    /** Убирает книгу из счётчиков по книгам (удалена из библиотеки); сутки остаются: время всё равно было прочитано. */
    fun forgetBook(bookId: String): ReadingStats = copy(books = books - bookId)

    private fun Map<String, DayStats>.prune(): Map<String, DayStats> =
        if (size <= KEEP_DAYS) this else entries.sortedBy { it.key }.drop(size - KEEP_DAYS).associate { it.key to it.value }

    companion object {
        /** Сколько суток истории хранится. */
        const val KEEP_DAYS = 730
    }
}

/**
 * Учёт времени и слов по событиям активности (прокрутка, смена страницы, следующее предложение озвучки).
 * Время между событиями засчитывается, только если оно не больше [IDLE_MS]: простой, пауза и фон не считаются.
 */
class ReadingTracker(
    private val dayOf: (Long) -> String
) {
    private var bookId: String? = null
    private var lastAt = 0L
    private var lastWords = 0

    /** [wordsBefore] — сколько слов в книге до текущего места; [now] — время события, мс. */
    fun onActivity(bookId: String, wordsBefore: Int, now: Long): ReadingDelta? {
        val previous = this.bookId
        val gap = now - lastAt
        val wordsStep = wordsBefore - lastWords
        this.bookId = bookId
        lastAt = now
        lastWords = wordsBefore
        if (previous != bookId || gap <= 0 || gap > IDLE_MS) return null
        // Большой скачок, возврат назад и «пролистывание» быстрее любого чтения — это навигация: слова не считаем
        val reading = wordsStep in 1..MAX_STEP_WORDS && wordsStep * 60_000L / gap <= MAX_WORDS_PER_MINUTE
        return ReadingDelta(dayOf(now), bookId, gap, if (reading) wordsStep else 0)
    }

    /** Чтение остановлено (пауза озвучки, приложение ушло в фон): следующий интервал начнётся заново. */
    fun onPause() {
        bookId = null
    }

    companion object {
        /** Дольше без событий — человек отвлёкся, время не засчитывается. */
        const val IDLE_MS = 3 * 60_000L
        const val MAX_STEP_WORDS = 3_000
        const val MAX_WORDS_PER_MINUTE = 1_200
    }
}

/** Сводка для экрана статистики. */
class ReadingSummary private constructor(
    val todayMs: Long,
    val weekMs: Long,
    val totalMs: Long,
    val totalWords: Int,
    /** Средняя скорость чтения, слов в минуту; 0 — данных ещё мало. */
    val wordsPerMinute: Int,
    val streakDays: Int,
    private val stats: ReadingStats,
    private val today: String
) {
    /** Последние [count] суток от старых к новым; дни без чтения — с нулями. */
    fun lastDays(count: Int): List<Pair<String, DayStats>> =
        (count - 1 downTo 0).map { back ->
            val day = DateMath.minusDays(today, back)
            day to (stats.days[day] ?: DayStats())
        }

    companion object {
        /** Чтения короче минуты мало для оценки скорости. */
        private const val MIN_MS_FOR_SPEED = 60_000L
        private const val MIN_DAY_MS = 60_000L

        fun of(stats: ReadingStats, today: String): ReadingSummary {
            val week = (0..6).map { DateMath.minusDays(today, it) }.toSet()
            val totalMs = stats.days.values.sumOf { it.ms }
            val totalWords = stats.days.values.sumOf { it.words }
            val speed = if (totalMs >= MIN_MS_FOR_SPEED) (totalWords * 60_000L / totalMs).toInt() else 0
            return ReadingSummary(
                todayMs = stats.days[today]?.ms ?: 0,
                weekMs = stats.days.filterKeys { it in week }.values.sumOf { it.ms },
                totalMs = totalMs,
                totalWords = totalWords,
                wordsPerMinute = speed,
                streakDays = streak(stats, today),
                stats = stats,
                today = today
            )
        }

        /** Серия подряд идущих суток с чтением; если сегодня ещё не читали, серия считается от вчера. */
        private fun streak(stats: ReadingStats, today: String): Int {
            fun read(day: String) = (stats.days[day]?.ms ?: 0) >= MIN_DAY_MS
            var day = if (read(today)) today else DateMath.minusDays(today, 1)
            var count = 0
            while (read(day)) {
                count++
                day = DateMath.minusDays(day, 1)
            }
            return count
        }
    }
}

/** Даты вида `2026-10-27` без платформенных календарей. */
object DateMath {
    private const val DAY_MS = 86_400_000L

    /** Сутки момента [millis] при смещении местного времени [offsetMs] от UTC. */
    fun dayOf(millis: Long, offsetMs: Long): String = BackupFormat.isoDate(millis + offsetMs)

    fun minusDays(day: String, days: Int): String = BackupFormat.isoDate(epochDay(day) * DAY_MS - days * DAY_MS)

    /** Номер суток от 1970-01-01 (алгоритм «days from civil»). */
    private fun epochDay(day: String): Long {
        val (y0, m, d) = day.split('-').map { it.toInt() }
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }
}
