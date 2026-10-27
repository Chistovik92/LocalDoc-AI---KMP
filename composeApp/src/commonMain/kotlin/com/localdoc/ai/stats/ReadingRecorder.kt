package com.localdoc.ai.stats

import com.localdoc.ai.book.Book
import com.localdoc.ai.net.nowMillis

/**
 * Принимает события чтения (прокрутка в читалке, предложения озвучки), ведёт [ReadingTracker] и копит результат,
 * записывая его в хранилище не чаще раза в [flushEveryMs]: запись JSON на каждую прокрутку была бы лишней.
 */
class ReadingRecorder(
    /** Применяет изменение к сохранённой статистике. */
    private val update: ((ReadingStats) -> ReadingStats) -> Unit,
    private val clock: () -> Long = ::nowMillis,
    offsetMs: (Long) -> Long = { 0L },
    private val flushEveryMs: Long = FLUSH_MS
) {
    private val tracker = ReadingTracker { DateMath.dayOf(it, offsetMs(it)) }
    private val pending = ArrayList<ReadingDelta>()
    private var lastFlush = clock()

    private var cachedBook: Book? = null
    private var cachedWords: IntArray = IntArray(0)

    /** Читатель (или озвучка) дошёл до блока [block] книги [book]. */
    fun onPosition(bookId: String, book: Book, block: Int) {
        val words = wordsBefore(book, block)
        val now = clock()
        tracker.onActivity(bookId, words, now)?.let { pending.add(it) }
        if (now - lastFlush >= flushEveryMs) flush()
    }

    /** Пауза озвучки, выход из читалки: начатый интервал закрывается, накопленное сохраняется. */
    fun onPause() {
        tracker.onPause()
        flush()
    }

    fun flush() {
        lastFlush = clock()
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        update { stats -> batch.fold(stats) { acc, delta -> acc.add(delta) } }
    }

    /** Слов в книге до блока [block]; индекс считается один раз на книгу. */
    private fun wordsBefore(book: Book, block: Int): Int {
        if (cachedBook !== book) {
            cachedWords = prefixWords(book)
            cachedBook = book
        }
        return cachedWords[block.coerceIn(0, cachedWords.size - 1)]
    }

    companion object {
        const val FLUSH_MS = 15_000L

        /** prefix[i] — число слов в блоках до i-го; последний элемент — всего слов. */
        internal fun prefixWords(book: Book): IntArray {
            val prefix = IntArray(book.blocks.size + 1)
            book.blocks.forEachIndexed { i, block -> prefix[i + 1] = prefix[i] + countWords(block.text) }
            return prefix
        }

        internal fun countWords(text: String): Int {
            var count = 0
            var inWord = false
            for (c in text) {
                if (c.isWhitespace()) inWord = false
                else if (!inWord) { inWord = true; count++ }
            }
            return count
        }
    }
}
