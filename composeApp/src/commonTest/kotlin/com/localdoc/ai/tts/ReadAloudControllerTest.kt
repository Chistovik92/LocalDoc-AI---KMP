package com.localdoc.ai.tts

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Синхронный фейковый движок: результат реплики задаётся тестом. */
private class FakeEngine(private val failOn: (String) -> Boolean = { false }) : SpeechEngine {
    val spoken = ArrayList<String>()
    var autoFinish = true

    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        spoken.add(text)
        if (autoFinish) onFinished(!failOn(text))
    }

    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun prepare(onReady: () -> Unit) = onReady()
    override fun voices(): List<Voice> = emptyList()
    override fun setVoice(id: String?) = Unit
    override fun engines(): List<TtsEngineInfo> = emptyList()
    override fun setEngine(id: String?) = Unit
}

class ReadAloudControllerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)

    @Test
    fun `failed fragment is skipped and reading continues`() {
        val engine = FakeEngine(failOn = { "😀" in it })
        val controller = ReadAloudController(engine, scope)
        controller.start(
            "doc",
            listOf(p("Первое предложение абзаца."), p("Сломанный фрагмент 😀 с эмодзи."), p("Третий абзац читается дальше.")),
            emptyList(),
            0
        )

        assertTrue(engine.spoken.any { it.startsWith("Третий абзац") }, "чтение не должно останавливаться на сбойном фрагменте")
        assertEquals(ReadAloudStop.BOOK_END, controller.state.value.stoppedAt)
    }

    @Test
    fun `ad paragraphs are not read aloud`() {
        val engine = FakeEngine()
        val controller = ReadAloudController(engine, scope)
        controller.start("doc", listOf(p("Начало статьи здесь."), p("Реклама. ООО «Ромашка», erid: 2SDnj"), p("Продолжение статьи.")), emptyList(), 0)
        assertFalse(engine.spoken.any { "erid" in it })
        assertTrue(engine.spoken.any { it.startsWith("Продолжение") })
    }

    @Test
    fun `reading stops at chapter end with a warning and continues on demand`() {
        val engine = FakeEngine()
        val controller = ReadAloudController(engine, scope)
        val blocks = listOf(p("Текст первой главы закончился."), Block(BlockType.HEADING, "Глава 2", 1), p("Текст второй главы."))
        controller.start("doc", blocks, listOf(Chapter("Глава 1", 0), Chapter("Глава 2", 1)), 0)

        val state = controller.state.value
        assertEquals(ReadAloudStop.CHAPTER_END, state.stoppedAt)
        assertFalse(state.playing)
        assertEquals("Конец главы.", engine.spoken.last())
        assertFalse(engine.spoken.any { it.startsWith("Текст второй") }, "в следующую главу без пользователя не переходим")

        controller.play()
        assertTrue(engine.spoken.any { it.startsWith("Текст второй") })
    }

    @Test
    fun `navigation by sentences and paragraphs`() {
        val engine = FakeEngine().apply { autoFinish = false }
        val controller = ReadAloudController(engine, scope)
        controller.start("doc", listOf(p("Раз — это первое длинное предложение. Два — это второе длинное предложение."), p("Три — это третье длинное предложение.")), emptyList(), 0)
        assertEquals(0, controller.state.value.segment?.block)

        controller.nextParagraph()
        assertEquals(1, controller.state.value.segment?.block)
        controller.previousParagraph()
        assertEquals(0, controller.state.value.segment?.block)
        controller.nextSentence()
        assertEquals("Два — это второе длинное предложение.", controller.state.value.segment?.text)

        controller.pause()
        assertFalse(controller.state.value.playing)
        controller.stop()
        assertFalse(controller.state.value.active)
    }

    @Test
    fun `segmenter splits sentences and keeps offsets for highlight`() {
        val text = "Первое предложение готово. Второе тоже уже готово! А третье ещё не готово? Да."
        val segments = SpeechSegmenter.segments(listOf(p(text)))
        assertTrue(segments.size >= 2)
        assertEquals("Первое предложение готово.", text.substring(segments[0].start, segments[0].end))
    }

    @Test
    fun `long sentence is split into speakable parts`() {
        val long = (1..80).joinToString(", ") { "слово$it" } + "."
        val segments = SpeechSegmenter.segments(listOf(p(long)))
        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 360 })
    }
}
