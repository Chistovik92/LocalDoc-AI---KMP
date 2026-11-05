package com.localdoc.ai.tts

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SleepTestEngine : SpeechEngine {
    val spoken = ArrayList<String>()
    val volumes = ArrayList<Float>()
    var autoFinish = true
    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        spoken.add(text)
        if (autoFinish) onFinished(true)
    }
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun prepare(onReady: () -> Unit) = onReady()
    override fun voices(): List<Voice> = emptyList()
    override fun setVoice(id: String?) = Unit
    override fun engines(): List<TtsEngineInfo> = emptyList()
    override fun setEngine(id: String?) = Unit
    override fun setVolume(volume: Float) { volumes.add(volume) }
}

class SleepTimerPlayerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)

    private val blocks = listOf(
        p("Первая глава начинается здесь и идёт."), p("Вторая фраза первой главы тоже есть."),
        p("Вторая глава открывается новым абзацем.")
    )
    private val chapters = listOf(Chapter("Первая", 0), Chapter("Вторая", 2))

    @Test
    fun `quiet chapter end stops without the spoken announcement and flags the sleep stop`() {
        val engine = SleepTestEngine()
        val player = ReadAloudController(engine, scope)
        player.quietChapterEnd = true
        player.start("doc", blocks, chapters, 0)

        assertTrue(engine.spoken.none { it.startsWith("Конец главы") }, "перед сном голос не объявляет конец главы")
        val state = player.state.value
        assertFalse(state.playing)
        assertTrue(state.sleepStopped)
        assertNull(state.stoppedAt, "автопереход к следующей главе сон не должен запускать")
    }

    @Test
    fun `without the flag chapter end is announced as before`() {
        val engine = SleepTestEngine()
        val player = ReadAloudController(engine, scope)
        player.start("doc", blocks, chapters, 0)
        assertTrue(engine.spoken.any { it.startsWith("Конец главы") })
        assertEquals(ReadAloudStop.CHAPTER_END, player.state.value.stoppedAt)
    }

    @Test
    fun `sleep stop pauses in place, flags state and restores volume`() {
        val engine = SleepTestEngine().apply { autoFinish = false }
        val player = ReadAloudController(engine, scope)
        player.start("doc", blocks, chapters, 0)
        player.setVolume(0.2f)
        player.sleepStop()

        val state = player.state.value
        assertTrue(state.active, "позиция сохраняется — можно продолжить")
        assertFalse(state.playing)
        assertTrue(state.sleepStopped)
        assertEquals(1f, engine.volumes.last())
    }

    @Test
    fun `sentences left counts to the chapter boundary`() {
        val engine = SleepTestEngine().apply { autoFinish = false }
        val player = ReadAloudController(engine, scope)
        player.start("doc", blocks, chapters, 0)
        val left = player.sentencesLeftInChapter()
        assertEquals(2, left)
    }

    @Test
    fun `play after sleep stop clears the flag`() {
        val engine = SleepTestEngine().apply { autoFinish = false }
        val player = ReadAloudController(engine, scope)
        player.start("doc", blocks, chapters, 0)
        player.sleepStop()
        player.play()
        assertFalse(player.state.value.sleepStopped)
    }
}
