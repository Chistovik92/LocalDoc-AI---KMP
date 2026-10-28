package com.localdoc.ai.domain

import com.localdoc.ai.book.Book
import com.localdoc.ai.stats.ReadingRecorder
import kotlinx.coroutines.launch

/**
 * Озвучка тоже считается чтением: пока звучат предложения книги, события уходят в [recorder].
 * Пауза и остановка закрывают интервал, поэтому простой плеера не засчитывается.
 */
internal fun AppGraph.feedStatsFromReadAloud(recorder: ReadingRecorder) {
    appScope.launch {
        var loaded: Pair<String, Book>? = null
        readAloud.state.collect { state ->
            val id = readAloud.documentKey?.takeIf { it.startsWith("book:") }?.removePrefix("book:")
            val block = state.segment?.block
            if (!state.playing || id == null || block == null) {
                recorder.onPause()
                return@collect
            }
            if (loaded?.first != id) loaded = library.load(id)?.let { id to it }
            loaded?.let { recorder.onPosition(id, it.second, block, state.segment?.start ?: 0) }
        }
    }
}
