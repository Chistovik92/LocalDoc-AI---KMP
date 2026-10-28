package com.localdoc.ai.domain

import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.orientation_landscape
import com.localdoc.ai.resources.orientation_portrait
import com.localdoc.ai.resources.theme_auto
import org.jetbrains.compose.resources.StringResource
import com.localdoc.ai.resources.tap_edges
import com.localdoc.ai.resources.tap_edges_reversed
import com.localdoc.ai.resources.tap_menu_only
import com.localdoc.ai.resources.font_sans
import com.localdoc.ai.resources.font_serif
import com.localdoc.ai.resources.theme_dark
import com.localdoc.ai.resources.theme_light
import com.localdoc.ai.resources.theme_night
import com.localdoc.ai.resources.theme_sepia
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
enum class ReaderTheme(val label: StringResource) { SYSTEM(Res.string.theme_auto), LIGHT(Res.string.theme_light), SEPIA(Res.string.theme_sepia), DARK(Res.string.theme_dark), BLACK(Res.string.theme_night) }

@Serializable
enum class ReaderFont(val label: StringResource) { SERIF(Res.string.font_serif), SANS(Res.string.font_sans) }

/** Зоны касания: края листают, центр открывает меню. */
@Serializable
enum class TapZones(val label: StringResource) {
    EDGES(Res.string.tap_edges),
    EDGES_REVERSED(Res.string.tap_edges_reversed),
    MENU_ONLY(Res.string.tap_menu_only)
}

/** Блокировка ориентации экрана в читалке. */
@Serializable
enum class OrientationLock(val label: StringResource) { AUTO(Res.string.theme_auto), PORTRAIT(Res.string.orientation_portrait), LANDSCAPE(Res.string.orientation_landscape) }

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
    val chapterWords: String = "",
    /** Свой шрифт: имя файла TTF/OTF в хранилище приложения (`fonts/…`); null — шрифт из [font]. */
    val fontFile: String? = null,
    /** Поля страницы по горизонтали, dp. */
    val margin: Float = 24f,
    /** Межбуквенный интервал, sp. */
    val letterSpacing: Float = 0f,
    /** Жирность шрифта: 300 — тонкий, 400 — обычный, 700 — жирный. */
    val fontWeight: Int = 400,
    /** Выключка с переносами слов и балансом строк — пробелы растягиваются меньше. */
    val smartJustify: Boolean = false,
    val tapZones: TapZones = TapZones.EDGES,
    /** Ширина боковой зоны листания в долях ширины экрана. */
    val tapZoneWidth: Float = 0.3f,
    /** Листание кнопками громкости (Android). */
    val volumeKeys: Boolean = false,
    /** Размер шрифта щипком двумя пальцами. */
    val pinchZoom: Boolean = true,
    /** Листание горизонтальным свайпом. */
    val swipePaging: Boolean = false,
    /** Яркость вертикальным жестом у левого края; [brightness] null — яркость системы. */
    val brightnessGesture: Boolean = false,
    val brightness: Float? = null,
    val keepScreenOn: Boolean = false,
    val orientation: OrientationLock = OrientationLock.AUTO,
    val pageAnimation: Boolean = true,
    /** Скорость автопрокрутки в строках в минуту. */
    val autoScrollSpeed: Int = 20,
    /** Режим для e-ink: без анимаций, полное обновление экрана раз в [eInkRefreshPages] страниц. */
    val eInk: Boolean = false,
    val eInkRefreshPages: Int = 6
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
