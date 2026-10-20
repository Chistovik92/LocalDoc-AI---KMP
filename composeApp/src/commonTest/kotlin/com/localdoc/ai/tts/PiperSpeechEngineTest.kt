package com.localdoc.ai.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeBackend(private val failOn: (String) -> Boolean = { false }) : PiperBackend {
    val generated = ArrayList<String>()
    val loads = ArrayList<String>()
    var loadOk = true
    var released = 0

    override fun load(paths: PiperVoicePaths): Boolean {
        loads.add(paths.model)
        return loadOk
    }

    override fun generate(text: String, speed: Float): Pcm? {
        generated.add(text)
        return if (failOn(text)) null else Pcm(FloatArray(10) { 0.1f }, 22050)
    }

    override fun release() {
        released++
    }
}

private class FakeOutput : PcmOutput {
    var played = 0
    var lastPitch = 0f
    /** Если задан — воспроизведение «висит», пока тест его не завершит. */
    var gate: CompletableDeferred<Boolean>? = null

    override suspend fun play(pcm: Pcm, pitch: Float): Boolean {
        played++
        lastPitch = pitch
        return gate?.await() ?: true
    }

    override fun release() = Unit
}

class PiperSpeechEngineTest {

    private val unconfined = CoroutineScope(Dispatchers.Unconfined)

    private fun engine(backend: PiperBackend, output: PcmOutput, installed: Set<String> = setOf("irina")) =
        PiperSpeechEngine(backend, output, unconfined, Dispatchers.Unconfined) { id ->
            if (id in installed) PiperVoicePaths("/v/$id/model.onnx", "/v/$id/tokens.txt", "/v/$id/espeak-ng-data") else null
        }.apply { setVoice("irina") }

    @Test
    fun `callback fires exactly once with success`() {
        val output = FakeOutput()
        val tts = engine(FakeBackend(), output)
        val results = ArrayList<Boolean>()
        tts.speak("Привет, мир.") { results.add(it) }
        assertEquals(listOf(true), results)
        assertEquals(1, output.played)
    }

    @Test
    fun `synthesis failure reports false once`() {
        val tts = engine(FakeBackend(failOn = { "😀" in it }), FakeOutput())
        val results = ArrayList<Boolean>()
        tts.speak("Эмодзи 😀") { results.add(it) }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `missing voice and blank text report false`() {
        val tts = engine(FakeBackend(), FakeOutput(), installed = emptySet())
        val results = ArrayList<Boolean>()
        tts.speak("Текст") { results.add(it) }
        tts.speak("   ") { results.add(it) }
        assertEquals(listOf(false, false), results)
    }

    @Test
    fun `stop interrupts playback without a callback`() {
        val output = FakeOutput().apply { gate = CompletableDeferred() }
        val tts = engine(FakeBackend(), output)
        val results = ArrayList<Boolean>()
        tts.speak("Долгая фраза.") { results.add(it) }
        tts.stop()
        output.gate?.complete(true)
        assertTrue(results.isEmpty(), "после stop() колбэк не приходит")
    }

    @Test
    fun `new utterance cancels the previous one`() {
        val output = FakeOutput().apply { gate = CompletableDeferred() }
        val tts = engine(FakeBackend(), output)
        val first = ArrayList<Boolean>()
        val second = ArrayList<Boolean>()
        tts.speak("Первая.") { first.add(it) }
        output.gate = null
        tts.speak("Вторая.") { second.add(it) }
        assertTrue(first.isEmpty())
        assertEquals(listOf(true), second)
    }

    @Test
    fun `prefetched sentence is not synthesized twice`() {
        val backend = FakeBackend()
        val tts = engine(backend, FakeOutput())
        tts.prefetch("Следующее предложение.")
        tts.speak("Следующее предложение.") { }
        assertEquals(listOf("Следующее предложение."), backend.generated)
    }

    @Test
    fun `voice is loaded once and reloaded after switching`() {
        val backend = FakeBackend()
        val tts = engine(backend, FakeOutput(), installed = setOf("irina", "denis"))
        tts.speak("Раз.") { }
        tts.speak("Два.") { }
        assertEquals(1, backend.loads.size)
        tts.setVoice("denis")
        tts.speak("Три.") { }
        assertEquals(listOf("/v/irina/model.onnx", "/v/denis/model.onnx"), backend.loads)
    }

    @Test
    fun `pitch is passed to the output`() {
        val output = FakeOutput()
        val tts = engine(FakeBackend(), output)
        tts.setPitch(1.3f)
        tts.speak("Высота.") { }
        assertEquals(1.3f, output.lastPitch)
    }

    @Test
    fun `voices lists only installed packs`() {
        val tts = engine(FakeBackend(), FakeOutput(), installed = setOf("vits-piper-ru_RU-denis-medium"))
        assertEquals(listOf("vits-piper-ru_RU-denis-medium"), tts.voices().map { it.id })
    }

    @Test
    fun `router switches to builtin engine and back`() {
        val system = RecordingEngine()
        val builtinOutput = FakeOutput()
        val router = SpeechRouter(system, engine(FakeBackend(), builtinOutput))

        router.speak("Системный.") { }
        router.setEngine(PiperSpeechEngine.ENGINE_ID)
        router.setVoice("irina")
        router.speak("Встроенный.") { }
        assertTrue(router.isBuiltinActive)
        assertEquals(listOf("Системный."), system.spoken)
        assertEquals(1, builtinOutput.played)

        router.setEngine(null)
        router.speak("Снова системный.") { }
        assertFalse(router.isBuiltinActive)
        assertEquals(listOf("Системный.", "Снова системный."), system.spoken)
        assertEquals(listOf<String?>(null), system.engineIds, "служебный id встроенного движка в системный не передаётся")
    }
}

private class RecordingEngine : SpeechEngine {
    val spoken = ArrayList<String>()
    val engineIds = ArrayList<String?>()
    override fun speak(text: String, onFinished: (Boolean) -> Unit) {
        spoken.add(text)
        onFinished(true)
    }
    override fun stop() = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun prepare(onReady: () -> Unit) = onReady()
    override fun voices(): List<Voice> = emptyList()
    override fun setVoice(id: String?) = Unit
    override fun engines(): List<TtsEngineInfo> = emptyList()
    override fun setEngine(id: String?) {
        engineIds.add(id)
    }
}
