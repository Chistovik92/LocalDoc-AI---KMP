package com.localdoc.ai.tts

import com.localdoc.ai.book.AdFilter
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Голос синтезатора. */
data class Voice(val id: String, val name: String, val language: String, val offline: Boolean = true)

/** Голосовой движок (на Android — системные TTS-движки: Google, RHVoice, sherpa-onnx/Piper…). */
data class TtsEngineInfo(val id: String, val label: String)

/**
 * Контракт синтезатора речи. onFinished(success) вызывается ровно один раз на реплику,
 * кроме остановки через stop() — тогда колбэк может не прийти.
 */
interface SpeechEngine {
    fun speak(text: String, onFinished: (Boolean) -> Unit)
    fun stop()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    /** Готовит движок; onReady вызывается, когда доступны голоса и движки. */
    fun prepare(onReady: () -> Unit)
    fun voices(): List<Voice>
    fun setVoice(id: String?)
    fun engines(): List<TtsEngineInfo>
    fun setEngine(id: String?)
}

/** Фрагмент озвучки: предложение (или часть длинного предложения) внутри блока. */
data class SpeechSegment(val block: Int, val start: Int, val end: Int, val text: String)

/** Разбиение книги на предложения для озвучки и подсветки. */
object SpeechSegmenter {
    private const val MAX_SEGMENT = 350
    private const val MIN_SEGMENT = 25
    // Конец предложения — только если дальше заглавная буква, цифра, кавычка или тире
    // (не режем «т. е. и», «рус. дореф. название»)
    private val SENTENCE_END = Regex("(?<=[.!?…])[\"»”)]*\\s+(?=[\"«„“(\\[A-ZА-ЯЁ0-9—–-])")
    private val URL = Regex("https?://\\S+|www\\.\\S+")

    fun segments(blocks: List<Block>): List<SpeechSegment> {
        val result = ArrayList<SpeechSegment>()
        blocks.forEachIndexed { index, block ->
            // Код не читается вслух, рекламные вставки пропускаются
            if (block.type == BlockType.CODE || AdFilter.isAdText(block.text)) return@forEachIndexed
            sentenceRanges(block.text).forEach { (s, e) ->
                val speakable = speakableText(block.text.substring(s, e))
                if (speakable.isNotBlank()) result.add(SpeechSegment(index, s, e, speakable))
            }
        }
        return result
    }

    /** Диапазоны предложений в тексте; короткие склеиваются, длинные режутся по знакам препинания. */
    internal fun sentenceRanges(text: String): List<Pair<Int, Int>> {
        val raw = ArrayList<Pair<Int, Int>>()
        var start = 0
        SENTENCE_END.findAll(text).forEach { m ->
            raw.add(start to m.range.first)
            start = m.range.last + 1
        }
        if (start < text.length) raw.add(start to text.length)

        val merged = ArrayList<Pair<Int, Int>>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && (last.second - last.first) < MIN_SEGMENT) merged[merged.lastIndex] = last.first to r.second
            else merged.add(r)
        }
        return merged.flatMap { splitLong(text, it.first, it.second) }
            .map { (s, e) -> trimRange(text, s, e) }
            .filter { it.second > it.first }
    }

    private fun splitLong(text: String, start: Int, end: Int): List<Pair<Int, Int>> {
        if (end - start <= MAX_SEGMENT) return listOf(start to end)
        val parts = ArrayList<Pair<Int, Int>>()
        var s = start
        while (end - s > MAX_SEGMENT) {
            val window = text.substring(s, s + MAX_SEGMENT)
            val cut = listOf(window.lastIndexOfAny(charArrayOf(';', ':', ',', '—')), window.lastIndexOf(' '))
                .firstOrNull { it > MAX_SEGMENT / 3 } ?: MAX_SEGMENT - 1
            parts.add(s to s + cut + 1)
            s += cut + 1
        }
        parts.add(s to end)
        return parts
    }

    private fun trimRange(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s to e
    }

    /** Текст для синтезатора: без ссылок и служебных символов, на которых движки спотыкаются. */
    internal fun speakableText(s: String): String =
        s.replace(URL, " ")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B-\\u200F\\uFEFF]"), "")
            .replace(Regex("[*_#`|<>\\[\\]{}~^]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Почему озвучка остановилась сама. */
enum class ReadAloudStop { CHAPTER_END, BOOK_END }

data class ReadAloudState(
    /** Плеер открыт (идёт или стоит на паузе). */
    val active: Boolean = false,
    val playing: Boolean = false,
    /** Название книги/страницы — для уведомления, виджета и экрана блокировки. */
    val title: String = "",
    val segment: SpeechSegment? = null,
    val position: Int = 0,
    val total: Int = 0,
    val stoppedAt: ReadAloudStop? = null,
    val error: String? = null
)

/**
 * Плеер чтения вслух: последовательно отдаёт движку предложения, подсвечивает текущее,
 * поддерживает паузу, стоп и переходы по предложениям/абзацам.
 *
 * Устойчивость: фрагмент, который движок не смог озвучить (реклама, эмодзи, редкие символы),
 * пропускается — чтение продолжается. Остановка только после нескольких сбоев подряд.
 * В конце главы озвучка не переходит дальше сама, а предупреждает и ждёт пользователя.
 */
class ReadAloudController(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var segments: List<SpeechSegment> = emptyList()
    private var chapterStarts: List<Int> = emptyList()
    private var index = 0
    private var session = 0
    private var consecutiveFailures = 0
    /** Ключ документа, который сейчас озвучивается (id книги или URL страницы). */
    var documentKey: String? = null
        private set

    private var title = ""

    fun start(key: String, blocks: List<Block>, chapters: List<Chapter>, fromBlock: Int, title: String = "") {
        documentKey = key
        this.title = title
        segments = SpeechSegmenter.segments(blocks)
        chapterStarts = chapters.map { it.startBlock }.filter { it > 0 }.sorted()
        if (segments.isEmpty()) {
            _state.value = ReadAloudState(error = "В тексте нет фрагментов для озвучки")
            return
        }
        index = segments.indexOfFirst { it.block >= fromBlock }.takeIf { it >= 0 } ?: 0
        play()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        if (segments.isEmpty()) return
        if (index !in segments.indices) index = 0
        session++
        // Прежняя реплика (другой документ, объявление «Конец главы») не должна звучать поверх
        engine.stop()
        consecutiveFailures = 0
        speakCurrent()
    }

    fun pause() {
        session++
        engine.stop()
        publish(playing = false)
    }

    fun stop() {
        session++
        engine.stop()
        segments = emptyList()
        documentKey = null
        _state.value = ReadAloudState()
    }

    fun nextSentence() = seek(index + 1)
    fun previousSentence() = seek(index - 1)

    fun nextParagraph() {
        val current = segments.getOrNull(index)?.block ?: return
        seek(segments.indexOfFirst { it.block > current }.takeIf { it >= 0 } ?: segments.lastIndex)
    }

    fun previousParagraph() {
        val current = segments.getOrNull(index) ?: return
        // В середине абзаца — в его начало, иначе — в начало предыдущего
        val paragraphStart = segments.indexOfFirst { it.block == current.block }
        val target = if (index > paragraphStart) {
            paragraphStart
        } else {
            val prevBlock = segments.getOrNull(paragraphStart - 1)?.block
            if (prevBlock == null) 0 else segments.indexOfFirst { it.block == prevBlock }
        }
        seek(target)
    }

    /** Начать чтение с указанного абзаца (тап по тексту при активном плеере). */
    fun jumpToBlock(block: Int) {
        val target = segments.indexOfFirst { it.block >= block }
        if (target >= 0) seek(target)
    }

    private fun seek(target: Int) {
        if (segments.isEmpty()) return
        index = target.coerceIn(0, segments.lastIndex)
        if (_state.value.playing) {
            session++
            engine.stop()
            speakCurrent()
        } else {
            publish(playing = false)
        }
    }

    private fun speakCurrent() {
        val segment = segments.getOrNull(index) ?: return
        val mySession = session
        publish(playing = true)
        engine.speak(segment.text) { success ->
            scope.launch { if (mySession == session) onSegmentFinished(success) }
        }
    }

    private fun onSegmentFinished(success: Boolean) {
        if (success) consecutiveFailures = 0 else consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            session++
            publish(playing = false, error = "Голосовой движок не отвечает. Выберите другой голос в настройках озвучки.")
            return
        }
        val finished = segments[index]
        val nextIndex = index + 1
        if (nextIndex > segments.lastIndex) {
            finishWith(ReadAloudStop.BOOK_END, "Текст закончился.")
            return
        }
        val next = segments[nextIndex]
        index = nextIndex
        val crossesChapter = chapterStarts.any { it in (finished.block + 1)..next.block }
        if (crossesChapter) {
            finishWith(ReadAloudStop.CHAPTER_END, "Конец главы.")
            return
        }
        speakCurrent()
    }

    /** Предупреждение голосом и остановка: дальше пользователь решает сам. */
    private fun finishWith(reason: ReadAloudStop, announcement: String) {
        session++
        publish(playing = false, stoppedAt = reason)
        engine.speak(announcement) { }
    }

    private fun publish(playing: Boolean, stoppedAt: ReadAloudStop? = null, error: String? = null) {
        _state.value = ReadAloudState(
            active = segments.isNotEmpty(),
            playing = playing,
            title = title,
            segment = segments.getOrNull(index),
            position = index,
            total = segments.size,
            stoppedAt = stoppedAt,
            error = error
        )
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 4
    }
}
