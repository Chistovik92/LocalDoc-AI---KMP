package com.localdoc.ai.lang

import com.localdoc.ai.book.Block
import com.localdoc.ai.domain.TranslationSettings
/**
 * Перевод страницы для озвучки.
 *
 * Плеер получает страницу целиком, поэтому смешивать готовые переведённые абзацы с оригиналом
 * нельзя: после первого переведённого абзаца он сразу начинал читать исходный текст. Ждём
 * завершения перевода страницы и отдаём её одним языком.
 */
object StreamingPageTranslation {

    /** [progress] нужен вызывающему источнику для связи с переводчиком; здесь снимок не используется. */
    suspend fun translate(
        original: List<Block>,
        key: PageKey,
        @Suppress("UNUSED_PARAMETER") settings: TranslationSettings,
        translate: suspend (PageKey, List<Block>) -> List<Block>,
        progress: suspend (PageKey) -> List<Block?>
    ): List<Block> {
        if (original.isEmpty()) return original
        return translate(key, original)
    }
}
