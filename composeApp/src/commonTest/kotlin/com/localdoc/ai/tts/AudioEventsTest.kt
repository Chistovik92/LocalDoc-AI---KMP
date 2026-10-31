package com.localdoc.ai.tts

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.tts.AudioEventsTestHarness.FakeAudioEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
/**
 * Поведение плеера при событиях звука: звонок, чужой плеер, наушники.
 * Правила: звонок — пауза и продолжение после; другой плеер — пауза без продолжения;
 * отключение наушников — пауза; подключение — продолжение, только если прервала система.
 */
class AudioEventsTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun p(text: String) = Block(BlockType.PARAGRAPH, text)

    private inner class Harness {
        val engine = FakeEngine()
        val audio = FakeAudioEvents()
        val controller = ReadAloudController(engine, scope, audio)
    }    private fun Harness.open(autoFinish: Boolean = false) {
        engine.autoFinish = autoFinish
        controller.start("doc", listOf(p("Первое предложение."), p("Второе предложение."), p("Третье предложение.")), emptyList(), 0)
        // autoFinish = false: реплика «играет», пока тест не разрешит ей закончить
    }

    @Test
    fun `phone call pauses playback and resumes after the call ends`() {
        val h = Harness(); h.open()

        h.audio.fireInterrupted()
        assertFalse(h.controller.state.value.playing, "звонок должен поставить озвучку на паузу")
        assertTrue(h.controller.state.value.active, "плеер остаётся открытым на паузе")

        h.audio.fireRegained()
        assertTrue(h.controller.state.value.playing, "после звонка чтение продолжается само")
    }

    @Test
    fun `another player takes focus permanently - no auto resume`() {
        val h = Harness(); h.open()

        h.audio.fireInterrupted()
        assertFalse(h.controller.state.value.playing)

        // AUDIOFOCUS_LOSS: regain не придёт — пользователь сам решает, когда продолжить
        assertFalse(h.controller.state.value.playing, "без GAIN чтение не продолжается само")
        h.controller.play()
        assertTrue(h.controller.state.value.playing, "по нажатию пользователя чтение продолжается")
    }

    @Test
    fun `user pause wins over system resume`() {
        val h = Harness(); h.open()

        h.audio.fireInterrupted()
        assertFalse(h.controller.state.value.playing)

        // Пользователь снял паузу во время звонка и слушает дальше
        h.controller.play()
        assertTrue(h.controller.state.value.playing)

        // Регейн не должен перезапускать уже играющую озвучку (иначе реплика зазвучала бы с начала)
        val spokenBefore = h.engine.spoken.size
        h.audio.fireRegained()
        assertTrue(h.controller.state.value.playing, "регейн не должен останавливать играющую озвучку")
        assertEquals(spokenBefore, h.engine.spoken.size, "регейн не должен прерывать текущую реплику")
    }

    @Test
    fun `headphones disconnected, pause and reconnected resume after interruption only`() {
        val h = Harness(); h.open()

        // Звонок прервал чтение
        h.audio.fireInterrupted()
        assertFalse(h.controller.state.value.playing)

        // Пока читали (стояли на паузе) — подключили наушники: продолжаем (звонок сам прервал)
        h.audio.fireHeadphonesConnected()
        assertTrue(h.controller.state.value.playing, "подключение наушников продолжает прерванную звонком озвучку")

        // Выдернули наушники во время чтения — пауза
        h.audio.fireHeadphonesDisconnected()
        assertFalse(h.controller.state.value.playing, "отключение наушников ставит озвучку на паузу")
        assertTrue(h.controller.state.value.active)

        // Подключили снова: прерывание было системное (наушники), продолжаем
        h.audio.fireHeadphonesConnected()
        assertTrue(h.controller.state.value.playing)
    }

    @Test
    fun `headphones connected does not start paused by user playback`() {
        val h = Harness(); h.open()

        h.controller.pause()
        assertFalse(h.controller.state.value.playing)

        h.audio.fireHeadphonesConnected()
        assertFalse(h.controller.state.value.playing, "подключение наушников не возобновляет пользовательскую паузу")

        // И после звонка: пользовательская пауза главнее регейна
        h.audio.fireInterrupted()
        h.audio.fireRegained()
        assertFalse(h.controller.state.value.playing, "регейн не снимает паузу, поставленную пользователем после прерывания")
    }

    @Test
    fun `stop closes the audio session`() {
        val h = Harness(); h.open(autoFinish = true)

        assertTrue(h.audio.started, "сессия событий звука открыта при старте озвучки")
        h.controller.stop()
        assertFalse(h.audio.started, "стоп закрывает сессию событий звука")
        assertFalse(h.controller.state.value.active)
    }

    @Test
    fun `sleep timer closes the audio session`() {
        val h = Harness(); h.open()

        h.controller.sleepStop()
        assertFalse(h.audio.started, "таймер сна закрывает сессию событий звука")
    }
}
