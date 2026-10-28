package com.localdoc.ai.voice

import com.localdoc.ai.domain.PersistedSetting
import com.localdoc.ai.tts.ReadAloudController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Голосовое управление чтением. Выключено по умолчанию: включается пользователем после запроса микрофона. */
@Serializable
data class VoiceControlSettings(val enabled: Boolean = false)

/**
 * Слушает микрофон только пока открыт плеер озвучки и функция включена: в остальное время распознаватель остановлен
 * и батарею не тратит. Фразы разбирает [VoiceCommandParser], повторы отсекает [VoiceCommandGate].
 */
class VoiceControl(
    private val player: ReadAloudController,
    private val settings: PersistedSetting<VoiceControlSettings>,
    private val recognizer: PhraseRecognizer,
    private val scope: CoroutineScope,
    private val gate: VoiceCommandGate = VoiceCommandGate()
) {
    private val _status = MutableStateFlow<String?>(null)

    /** Причина, по которой распознавание остановилось (нет разрешения или языкового пакета); null — всё в порядке. */
    val status: StateFlow<String?> = _status.asStateFlow()

    val isOfflineAvailable: Boolean get() = recognizer.isOfflineAvailable

    private var listening = false

    fun start() {
        scope.launch {
            combine(settings.value, player.state) { s, p -> s.enabled && p.active && !p.sleepStopped }
                .distinctUntilChanged()
                .collect { shouldListen -> if (shouldListen) listen() else halt() }
        }
    }

    /** Включает функцию: сначала запрашивается микрофон, без него настройка остаётся выключенной. */
    fun enable(onResult: (Boolean) -> Unit) {
        if (!recognizer.isOfflineAvailable) {
            _status.value = "Офлайн-распознавание речи недоступно на этом устройстве"
            onResult(false)
            return
        }
        requestMicrophone { granted ->
            _status.value = if (granted) null else "Нет разрешения на микрофон"
            if (granted) settings.update { it.copy(enabled = true) }
            onResult(granted)
        }
    }

    fun disable() {
        settings.update { it.copy(enabled = false) }
        _status.value = null
    }

    private fun listen() {
        if (listening) return
        listening = true
        recognizer.start(
            onPhrase = { phrase ->
                val speaking = player.state.value.segment?.text
                gate.accept(VoiceCommandParser.parse(phrase, speaking))?.let(player::perform)
            },
            onError = { message ->
                listening = false
                _status.value = message
                settings.update { it.copy(enabled = false) }
            }
        )
    }

    private fun halt() {
        if (!listening) return
        listening = false
        recognizer.stop()
    }
}
