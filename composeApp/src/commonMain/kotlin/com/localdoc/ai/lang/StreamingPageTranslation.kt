package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.domain.TranslationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Перевод страницы «на лету» для озвучки: первые готовые абзацы уходят в голос, не дожидаясь
 * перевода всей страницы.
 *
 * Озвучка не должна молчать, пока модель переводит всю страницу: как только первый абзац
 * готов, страница отдаётся плееру — готовые абзацы звучат переводом, остальные пока
 * оригиналом (по мере продвижения перевода следующая страница уже звучит целиком переводом).
 * Не дождались и первого абзаца за [FIRST_BLOCK_TIMEOUT_MS] — страница звучит как есть:
 * чтение не прерывается и не замолкает на минуту ради ожидания перевода.
 *
 * Число блоков всегда равно числу блоков оригинала: индексы абзацев (подсветка, прыжки,
 * закладки) остаются верными, текст не теряется.
 */
object StreamingPageTranslation {

    /** Сколько ждать первый абзац перевода; не дождались — страница звучит в оригинале. */
    private const val FIRST_BLOCK_TIMEOUT_MS = 20_000L

    /**
     * Абзацы страницы для озвучки: переведённые — по мере готовности, остальные — оригинал.
     * [translate] — перевод страницы в общую очередь; [progress] — готовые абзацы страницы из переводчика.
     */
    suspend fun translate(
        original: List<Block>,
        key: PageKey,
        settings: TranslationSettings,
        translate: suspend (PageKey, List<Block>) -> List<Block>,
        progress: suspend (PageKey) -> List<Block?>
    ): List<Block> {
        if (original.isEmpty()) return original
        if (!settings.speakAsTranslated) return translate(key, original)
        // Готовые блоки (перевод) на своих местах; null — ещё не готов, звучит оригинал
        val ready = awaitFirst(key, progress) ?: return translate(key, original)
        return List(original.size) { i -> ready.getOrNull(i) ?: original[i] }
    }

    /** Ждёт появления первого переведённого блока страницы; null — не дождались. */
    private suspend fun awaitFirst(
        key: PageKey,
        progress: suspend (PageKey) -> List<Block?>
    ): List<Block?>? = withTimeoutOrNull(FIRST_BLOCK_TIMEOUT_MS) {
        var blocks = progress(key)
        while (blocks.all { it == null }) {
            delay(POLL_MS)
            blocks = progress(key)
        }
        blocks
    }

    private const val POLL_MS = 200L
}
