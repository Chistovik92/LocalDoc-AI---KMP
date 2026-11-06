package com.localdoc.ai

import android.speech.tts.TextToSpeech
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Реальный TextToSpeech: завершение первой реплики должно запустить следующую. */
@RunWith(AndroidJUnit4::class)
class SpeechDeviceTest {
    @Test
    fun systemSpeechAdvancesToNextSentence() = runBlocking {
        // На CI-эмуляторе системного TTS-движка или русского голоса может не быть (AOSP без Google TTS,
        // голосовые данные не скачаны): speak() тогда не вызывает колбэки, и тест завис бы на таймауте.
        // Реальную озвучку проверяют на физических устройствах (ROADMAP 2.9.0), поэтому здесь пропускаем.
        assumeTrue("Системный TTS недоступен на эмуляторе", systemTtsAvailable())

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

    /** true, если системный TTS инициализируется и умеет русский язык. */
    private fun systemTtsAvailable(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        val status = AtomicInteger(TextToSpeech.ERROR)
        var languageOk = false
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { s ->
            status.set(s)
            if (s == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
                languageOk = r == TextToSpeech.LANG_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            }
            latch.countDown()
        }
        return try {
            latch.await(10, TimeUnit.SECONDS) && status.get() == TextToSpeech.SUCCESS && languageOk
        } finally {
            tts.shutdown()
        }
    }
}
