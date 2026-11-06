package com.localdoc.ai.domain

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.lang.Language
import com.localdoc.ai.lang.LanguageDetector
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.lang.PageKey
import com.localdoc.ai.lang.StreamingPageTranslation
import com.localdoc.ai.platform.DebugLog
import com.localdoc.ai.tts.SpeechPageSource
import com.localdoc.ai.tts.VoiceSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Страницы книги для озвучки: оригинал или перевод страницы моделью (если у документа включён перевод). */
class BookSpeechSource(
    private val book: Book,
    override val pager: BookPager,
    private val docKey: String
) : SpeechPageSource {

    override suspend fun blocks(page: Int): List<Block> {
        DebugLog.d("speech blocks page=$page doc=$docKey translated=true")
        val p = pager.page(page)
        val original = book.blocks.subList(p.startBlock.coerceAtMost(book.blocks.size), p.endBlock.coerceAtMost(book.blocks.size))
        val target = AppGraph.translationTarget(docKey) ?: return original
        val settings = AppGraph.translationSettings.value.value
        val source = Languages.byCode(settings.sourceLanguage)
        // Документ переводит выбранный режим: локальная модель, Google Translate или удалённая модель по API
        val translator = AppGraph.translatorFor(docKey)
        return try {
            translator.translate(PageKey(docKey, page, target.code, settings.sourceLanguage, AppGraph.readerSettings.value.value.pageSize.chars), original, target, source)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            DebugLog.e("speech page translation failed page=$page doc=$docKey", t)
            // Озвучка не должна падать из-за недоступной/занятой локальной модели:
            // безопасно продолжаем оригиналом, как в версиях до потокового перевода.
            original
        }
    }
}

/** Язык перевода документа, если перевод включён. */
fun AppGraph.translationTarget(docKey: String): Language? =
    if (docKey in translating.value) Languages.byCode(translationSettings.value.value.targetLanguage) else null

/** Разбиение книги на страницы по размеру страницы из настроек читалки. */
fun AppGraph.pagerFor(book: Book): BookPager =
    BookPager.build(book.blocks, book.chapters, readerSettings.value.value.pageSize.chars)

/**
 * Запускает озвучку документа постранично. Язык речи — язык перевода (если он включён) или язык текста
 * с места старта; голос подбирается под него. Если язык не определён — остаётся выбранный в настройках голос.
 *
 * С включённым переводом озвучка ждёт перевод всей страницы: частичная страница не должна
 * переключать речь обратно на язык оригинала после первых готовых абзацев.
 */
fun AppGraph.startReading(docKey: String, book: Book, fromBlock: Int) {
    DebugLog.d("startReading doc=$docKey fromBlock=$fromBlock translated=${translationTarget(docKey)?.code}")
    val pager = pagerFor(book)
    val target = translationTarget(docKey)
    val language = target ?: detectLanguage(book, pager, fromBlock)
    val source = speechSourceFor(docKey, book, pager, target)
    applyVoiceFor(language) {
        try {
            readAloud.start(docKey, source, book.chapters, fromBlock, book.title)
        } catch (t: Throwable) {
            DebugLog.e("startReading failed doc=$docKey", t)
            throw t
        }
    }
}

/** Источник страниц для озвучки: с переводом — постраничный, без него — обычный. */
private fun AppGraph.speechSourceFor(
    docKey: String,
    book: Book,
    pager: BookPager,
    target: Language?
): SpeechPageSource {
    if (target == null) return BookSpeechSource(book, pager, docKey)
    // Документ переводит выбранный режим: локальная модель, Google Translate или удалённая модель по API
    val translator = translatorFor(docKey)
    return StreamingSpeechSource(
        book = book,
        pager = pager,
        docKey = docKey,
        target = target,
        translate = { key, blocks, source -> translator.translate(key, blocks, target, source) },
        progress = { key -> translator.progress.value[key].orEmpty() }
    )
}

/** Язык текста страницы с блока [fromBlock]; null — не определён или автоподбор голоса выключен. */
fun AppGraph.detectLanguage(book: Book, pager: BookPager, fromBlock: Int): Language? {
    if (!voiceSettings.value.value.autoLanguageVoice) return null
    val page = pager.page(pager.pageOfBlock(fromBlock))
    val sample = book.blocks.subList(page.startBlock.coerceAtMost(book.blocks.size), page.endBlock.coerceAtMost(book.blocks.size))
        .joinToString("\n") { it.text }
    return Languages.byCode(LanguageDetector.detect(sample))
}

/**
 * Подбирает голос под язык и применяет его к синтезатору, не меняя сохранённые настройки
 * (после чтения на другом языке возвращается обычный голос). [then] вызывается, когда голос готов.
 */
fun AppGraph.applyVoiceFor(language: Language?, then: () -> Unit) {
    val saved = voiceSettings.value.value
    if (language == null || !saved.autoLanguageVoice) {
        speaker.setLanguage(null)
        speaker.setEngine(saved.engineId)
        speaker.setVoice(saved.voiceId)
        then()
        return
    }
    var applied = false
    fun apply() {
        if (applied) return
        applied = true
        val installed = speaker.builtin.voices().map { it.id }.toSet()
        val pick = VoiceSelector.pick(language, saved, installed, speaker.systemVoices())
        speaker.setEngine(pick.engineId)
        speaker.setLanguage(pick.languageTag)
        speaker.setVoice(pick.voiceId)
        then()
    }
    speaker.prepareSystem(::apply)
    // Системного синтезатора может не быть вовсе — тогда колбэк не придёт, а встроенный голос всё равно доступен
    appScope.launch {
        delay(SYSTEM_TTS_WAIT_MS)
        apply()
    }
}


/**
 * Страницы для озвучки с переводом: абзацы отдаются плееру по мере перевода, а не всей страницей сразу.
 * Пока переведённого хватает, озвучка звучит; следующие абзацы догоняют её в том же порядке перевода.
 */
private class StreamingSpeechSource(
    private val book: Book,
    override val pager: BookPager,
    private val docKey: String,
    private val target: Language,
    private val translate: suspend (PageKey, List<Block>, Language?) -> List<Block>,
    private val progress: (PageKey) -> List<Block?>
) : SpeechPageSource {

    /**
     * Абзацы страницы в том виде, в каком они звучат. Ожидание ограничивается, чтобы непереведённое
     * начало страницы не замолчало надолго: первые абзацы уходят в голос по готовности.
     */
    override suspend fun blocks(page: Int): List<Block> {
        DebugLog.d("stream speech blocks page=$page doc=$docKey")
        val p = pager.page(page)
        val original = book.blocks.subList(p.startBlock.coerceAtMost(book.blocks.size), p.endBlock.coerceAtMost(book.blocks.size))
        val settings = AppGraph.translationSettings.value.value
        val key = PageKey(docKey, page, target.code, settings.sourceLanguage, AppGraph.readerSettings.value.value.pageSize.chars)
        return try {
            StreamingPageTranslation.translate(original, key, settings, translate = { k, blocks ->
            translate(k, blocks, Languages.byCode(settings.sourceLanguage))
            }, progress = progress)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            DebugLog.e("stream speech translation failed page=$page doc=$docKey", t)
            original
        }
    }
}

private const val SYSTEM_TTS_WAIT_MS = 1_500L
