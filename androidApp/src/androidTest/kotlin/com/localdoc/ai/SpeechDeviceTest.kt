package com.localdoc.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.BookPager
import com.localdoc.ai.platform.AppContextHolder
import com.localdoc.ai.tts.ReadAloudController
import com.localdoc.ai.tts.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Реальный TextToSpeech: завершение первой реплики должно запустить следующую. */
@RunWith(AndroidJUnit4::class)
class SpeechDeviceTest {
    @Test
    fun systemSpeechAdvancesToNextSentence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { AppContextHolder.init(instrumentation.targetContext) }
        val speaker = Speaker()
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = ReadAloudController(speaker, scope)
        try {
            val text = "Первая короткая реплика. Вторая короткая реплика."
            val blocks = listOf(Block(BlockType.PARAGRAPH, text))
            instrumentation.runOnMainSync {
                controller.start("device-smoke", blocks, emptyList(), 0, pager = BookPager.build(blocks))
            }
            val state = withTimeout(30_000) {
                controller.state.first {
                    (it.playing && it.segment?.start?.let { start -> start > 0 } == true) || it.error != null
                }
            }
            check(state.error == null) { state.error ?: "Ошибка системного TTS" }
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                speaker.shutdown()
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
