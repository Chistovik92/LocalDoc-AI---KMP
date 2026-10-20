package com.localdoc.ai.domain

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.lang.Language
import com.localdoc.ai.lang.LanguageDetector
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.lang.PageKey
import com.localdoc.ai.tts.SpeechPageSource
import com.localdoc.ai.tts.VoiceSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Страницы книги для озвучки: оригинал или перевод страницы моделью (если у документа включён перевод). */
class BookSpeechSource(
    private val book: Book,
    override val pager: BookPager,
    private val docKey: String
) : SpeechPageSource {

    override suspend fun blocks(page: Int): List<Block> {
        val p = pager.page(page)
        val original = book.blocks.subList(p.startBlock.coerceAtMost(book.blocks.size), p.endBlock.coerceAtMost(book.blocks.size))
        val target = AppGraph.translationTarget(docKey) ?: return original
        val settings = AppGraph.translationSettings.value.value
        val source = Languages.byCode(settings.sourceLanguage)
        return AppGraph.translator.translate(PageKey(docKey, page, target.code, settings.sourceLanguage), original, target, source)
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
 */
fun AppGraph.startReading(docKey: String, book: Book, fromBlock: Int) {
    val pager = pagerFor(book)
    val language = translationTarget(docKey) ?: detectLanguage(book, pager, fromBlock)
    applyVoiceFor(language) {
        readAloud.start(docKey, BookSpeechSource(book, pager, docKey), book.chapters, fromBlock, book.title)
    }
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

private const val SYSTEM_TTS_WAIT_MS = 1_500L
