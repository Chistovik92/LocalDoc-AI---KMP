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

/** Голосовой движок (системные TTS-движки Android: Google, RHVoice…; встроенный Piper). */
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
    /** Подсказка: эта реплика будет следующей — движок может синтезировать её заранее. */
    fun prefetch(text: String) = Unit
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

    /** [firstBlockIndex] — номер первого блока списка в книге: страница озвучивается отдельно от остальных. */
    fun segments(blocks: List<Block>, firstBlockIndex: Int = 0): List<SpeechSegment> {
        val result = ArrayList<SpeechSegment>()
        blocks.forEachIndexed { index, block ->
            // Код не читается вслух, рекламные вставки пропускаются
            if (block.type == BlockType.CODE || AdFilter.isAdText(block.text)) return@forEachIndexed
            sentenceRanges(block.text).forEach { (s, e) ->
                val speakable = speakableText(block.text.substring(s, e))
                if (speakable.isNotBlank()) result.add(SpeechSegment(firstBlockIndex + index, s, e, speakable))
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
