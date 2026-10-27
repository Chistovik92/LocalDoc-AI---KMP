package com.localdoc.ai.domain

import com.localdoc.ai.book.PageSize
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
    val followSpeech: Boolean = true,
    /** false — сплошная прокрутка текста, true — по одной странице. Озвучка, перевод и ИИ работают по страницам в обоих режимах. */
    val paged: Boolean = false,
    val pageSize: PageSize = PageSize.MEDIUM,
    /** Веб-новеллы: после озвучки или прокрутки до конца главы сама открывается следующая и чтение продолжается. */
    val autoNext: Boolean = false,
    /** Слова, по которым в тексте ищутся начала глав («Глава, Часть, Пролог…»); пусто — стандартный список. */
    val chapterWords: String = ""
)

/** Голос, закреплённый за языком: движок (null — системный по умолчанию) и id голоса. */
@Serializable
data class LanguageVoice(val engineId: String? = null, val voiceId: String? = null)

@Serializable
data class VoiceSettings(
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voiceId: String? = null,
    val engineId: String? = null,
    /** Голос под язык текста (или перевода): установленный Piper, затем системный голос этого языка. */
    val autoLanguageVoice: Boolean = true,
    /** Явный выбор пользователя по языкам: код языка → голос. */
    val perLanguage: Map<String, LanguageVoice> = emptyMap()
)

/** Перевод «на лету»: язык перевода, язык оригинала (null — определять автоматически) и автозапуск. */
@Serializable
data class TranslationSettings(
    val targetLanguage: String = "ru",
    val sourceLanguage: String? = null,
    /** Переводить открытые книги автоматически, если язык текста отличается от языка перевода. */
    val autoTranslate: Boolean = false
)

/** Настройка, сохраняемая в KV-хранилище в виде JSON. */
class PersistedSetting<T>(
    private val kv: KeyValueStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val default: T
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _value = MutableStateFlow(
        kv.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default
    )
    val value: StateFlow<T> = _value.asStateFlow()

    /** Перечитывает значение из хранилища (после восстановления из резервной копии); без записи — берётся значение по умолчанию. */
    fun reload() {
        _value.value = kv.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default
    }

    fun update(transform: (T) -> T) {
        val next = transform(_value.value)
        _value.value = next
        kv.put(key, json.encodeToString(serializer, next))
    }
}
