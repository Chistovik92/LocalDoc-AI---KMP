package com.localdoc.ai.domain

import com.localdoc.ai.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Цвет «бумаги» в читалке. */
@Serializable
enum class ReaderTheme(val label: String) { SYSTEM("Авто"), LIGHT("Светлая"), SEPIA("Сепия"), DARK("Тёмная"), BLACK("Ночь") }

@Serializable
enum class ReaderFont(val label: String) { SERIF("С засечками"), SANS("Без засечек") }

@Serializable
data class ReaderSettings(
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.6f,
    val font: ReaderFont = ReaderFont.SERIF,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val justify: Boolean = false,
    /** Автопрокрутка к читаемому предложению. */
    val followSpeech: Boolean = true
)

@Serializable
data class VoiceSettings(
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voiceId: String? = null,
    val engineId: String? = null
)

/** Настройка, сохраняемая в KV-хранилище в виде JSON. */
class PersistedSetting<T>(
    private val kv: KeyValueStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    default: T
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _value = MutableStateFlow(
        kv.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default
    )
    val value: StateFlow<T> = _value.asStateFlow()

    fun update(transform: (T) -> T) {
        val next = transform(_value.value)
        _value.value = next
        kv.put(key, json.encodeToString(serializer, next))
    }
}
