package com.localdoc.ai.tts

/** Синхронный фейковый движок и фейковые события звука для тестов плеера. */
internal object AudioEventsTestHarness {

    /** Фейковые события звука: тесты вызывают fire*(), имитируя звонок, чужой плеер и наушники. */
    class FakeAudioEvents : AudioInterruptions {
        var started = false
        private var onInterrupted: () -> Unit = {}
        private var onRegained: () -> Unit = {}
        private var onHeadphonesDisconnected: () -> Unit = {}
        private var onHeadphonesConnected: () -> Unit = {}

        override fun setListener(
            onInterrupted: () -> Unit,
            onRegained: () -> Unit,
            onHeadphonesDisconnected: () -> Unit,
            onHeadphonesConnected: () -> Unit
        ) {
            this.onInterrupted = onInterrupted
            this.onRegained = onRegained
            this.onHeadphonesDisconnected = onHeadphonesDisconnected
            this.onHeadphonesConnected = onHeadphonesConnected
        }

        override fun start() { started = true }
        override fun stop() { started = false }

        fun fireInterrupted() = onInterrupted()
        fun fireRegained() = onRegained()
        fun fireHeadphonesDisconnected() = onHeadphonesDisconnected()
        fun fireHeadphonesConnected() = onHeadphonesConnected()
    }
}

/** Синхронный фейковый движок: результат реплики задаётся тестом. */
internal class FakeEngine(private val failOn: (String) -> Boolean = { false }) : SpeechEngine {
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
