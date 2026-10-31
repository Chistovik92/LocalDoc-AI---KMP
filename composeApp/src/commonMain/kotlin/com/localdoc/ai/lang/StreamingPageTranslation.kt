package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.domain.TranslationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Перевод страницы «на лету» для озвучки: абзацы уходят в голос по мере готовности, а не всей страницей сразу.
 *
 * Озвучка не должна молчать, пока модель переводит всю страницу: переведённые абзацы звучат сразу,
 * а остальные догоняют их в том же порядке. Ждётся ровно столько, сколько нужно для следующего абзаца;
 * если перевод не успел — звучит оригинал, чтение не прерывается.
 */
object StreamingPageTranslation {

    /** Сколько ждать первый абзац страницы; не дождались — страница звучит как есть (оригинал). */
    private const val FIRST_BLOCK_TIMEOUT_MS = 120_000L
    /** Сколько ждать следующий абзац после уже готовых. */
    private const val NEXT_BLOCK_TIMEOUT_MS = 120_000L

    /**
     * Абзацы страницы для озвучки: переведённые — по мере готовности.
     * [translate] — перевод страницы в общую очередь; [progress] — готовые абзацы страницы из переводчика.
     * Возвращает столько абзацев, сколько успело перевестись, но не меньше одного (иначе молчание).
     */
    suspend fun translate(
        original: List<Block>,
        key: PageKey,
        settings: TranslationSettings,
        translate: suspend (PageKey, List<Block>) -> List<Block>,
        progress: suspend (PageKey) -> List<Block?>
    ): List<Block> {
        if (original.isEmpty() || !settings.speakAsTranslated) return translate(key, original)
        // Не дождались первого абзаца — страница звучит в обычном порядке (перевод целиком)
        var ready = awaitBlocks(key, progress, 1, FIRST_BLOCK_TIMEOUT_MS) ?: return translate(key, original)
        while (ready.size < original.size) {
            val next = awaitBlocks(key, progress, ready.size + 1, NEXT_BLOCK_TIMEOUT_MS) ?: break
            ready = next
        }
        return ready
    }

    /**
     * Ждёт, пока в переводе страницы не появятся первые [count] абзацев; null — не дождались.
     * Отдаёт готовые абзацы (оригинал не подмешивается: непереведённые абзацы — в конце).
     */
    private suspend fun awaitBlocks(
        key: PageKey,
        progress: suspend (PageKey) -> List<Block?>,
        count: Int,
        timeoutMs: Long
    ): List<Block>? {
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            progressOf(key, progress, count)
        }
        return result?.takeIf { it.isNotEmpty() }
    }

    /** Первые [count] переведённых абзацев страницы (ждёт, пока они появятся). */
    private suspend fun progressOf(key: PageKey, progress: suspend (PageKey) -> List<Block?>, count: Int): List<Block> {
        var blocks = progress(key)
        while (!hasReady(blocks, count)) {
            kotlinx.coroutines.delay(POLL_MS)
            blocks = progress(key)
        }
        return blocks.take(count).filterNotNull()
    }

    private fun hasReady(blocks: List<Block?>, count: Int): Boolean {
        if (blocks.size < count) return false
        return (0 until count).all { blocks[it] != null }
    }

    private const val POLL_MS = 200L
}
