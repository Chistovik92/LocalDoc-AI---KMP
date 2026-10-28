@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.speech_chunk_hint
import com.localdoc.ai.resources.speech_chunk_title
import com.localdoc.ai.resources.screen_page_explanation
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.localdoc.ai.resources.a_lightweight_open_synthesizer_for_100
import com.localdoc.ai.resources.add_ttf_otf
import com.localdoc.ai.resources.additional_voices_including_the_improved_enh
import com.localdoc.ai.resources.after_installing_the_engine_will_appear
import com.localdoc.ai.resources.ai_assistant
import com.localdoc.ai.resources.an_open_engine_with_natural_russian
import com.localdoc.ai.resources.appearance
import com.localdoc.ai.resources.auto_advance_to_the_next_chapter
import com.localdoc.ai.resources.auto_scroll_is_turned_on_in
import com.localdoc.ai.resources.auto_scroll_speed
import com.localdoc.ai.resources.automatic_russian
import com.localdoc.ai.resources.brightness_drag_along_the_left_edge
import com.localdoc.ai.resources.choose_a_model
import com.localdoc.ai.resources.choose_an_action_the_model_will
import com.localdoc.ai.resources.contents
import com.localdoc.ai.resources.continue_in_the_assistant
import com.localdoc.ai.resources.custom_font
import com.localdoc.ai.resources.default
import com.localdoc.ai.resources.delete_this_font
import com.localdoc.ai.resources.e_ink_mode_no_animations
import com.localdoc.ai.resources.e_ink_screen
import com.localdoc.ai.resources.engine_default
import com.localdoc.ai.resources.every_few_pages_the_screen_briefly
import com.localdoc.ai.resources.every_pages
import com.localdoc.ai.resources.follow_the_speech
import com.localdoc.ai.resources.font
import com.localdoc.ai.resources.font_weight
import com.localdoc.ai.resources.for_this_book_only
import com.localdoc.ai.resources.full_screen_refresh
import com.localdoc.ai.resources.gestures_and_buttons
import com.localdoc.ai.resources.justify_text
import com.localdoc.ai.resources.k_chars
import com.localdoc.ai.resources.keep_the_screen_on_while_reading
import com.localdoc.ai.resources.letter_spacing
import com.localdoc.ai.resources.line_spacing
import com.localdoc.ai.resources.lines_min
import com.localdoc.ai.resources.needs_internet
import com.localdoc.ai.resources.offline_2
import com.localdoc.ai.resources.open
import com.localdoc.ai.resources.open_engines_and_voices
import com.localdoc.ai.resources.orientation
import com.localdoc.ai.resources.page_by_page_instead_of_scrolling
import com.localdoc.ai.resources.page_color
import com.localdoc.ai.resources.page_margins
import com.localdoc.ai.resources.page_turn_animation
import com.localdoc.ai.resources.page_turning
import com.localdoc.ai.resources.paragraph_back
import com.localdoc.ai.resources.paragraph_forward
import com.localdoc.ai.resources.pause
import com.localdoc.ai.resources.pick_automatically_a_voice_that_speaks
import com.localdoc.ai.resources.pinch_changes_the_font_size
import com.localdoc.ai.resources.read
import com.localdoc.ai.resources.reading_mode
import com.localdoc.ai.resources.reset_to_the_common_settings
import com.localdoc.ai.resources.restore_system_brightness
import com.localdoc.ai.resources.russian_only
import com.localdoc.ai.resources.screen
import com.localdoc.ai.resources.sentence_back
import com.localdoc.ai.resources.sentence_forward
import com.localdoc.ai.resources.side_zone_width
import com.localdoc.ai.resources.smart_justification_hyphenation_spaces_stret
import com.localdoc.ai.resources.speech
import com.localdoc.ai.resources.speech_rate
import com.localdoc.ai.resources.stop_2
import com.localdoc.ai.resources.swipe_left_and_right_to_turn
import com.localdoc.ai.resources.system_default
import com.localdoc.ai.resources.system_synthesizer
import com.localdoc.ai.resources.tap_zones
import com.localdoc.ai.resources.text_size
import com.localdoc.ai.resources.the_animation_is_also_turned_off
import com.localdoc.ai.resources.the_center_of_the_screen_shows
import com.localdoc.ai.resources.thinking
import com.localdoc.ai.resources.this_is_not_a_ttf_or
import com.localdoc.ai.resources.to_let_the_ai_summarize_and
import com.localdoc.ai.resources.tts_settings
import com.localdoc.ai.resources.turn_pages_with_the_volume_buttons
import com.localdoc.ai.resources.voice
import com.localdoc.ai.resources.voice_data
import com.localdoc.ai.resources.voice_for_the_text_language
import com.localdoc.ai.resources.voice_pitch
import com.localdoc.ai.resources.voices_are_loading_if_the_list
import com.localdoc.ai.resources.you_can_choose_any_installed_engine
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import com.localdoc.ai.domain.splitEdit
import com.localdoc.ai.domain.applyOverride
import com.localdoc.ai.domain.BookOverride
import com.localdoc.ai.platform.FilePicker
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.MAX_AUTO_SCROLL_LPM
import com.localdoc.ai.domain.MAX_EINK_REFRESH_PAGES
import com.localdoc.ai.domain.MAX_READER_FONT_SIZE
import com.localdoc.ai.domain.MAX_ZONE_WIDTH
import com.localdoc.ai.domain.MIN_AUTO_SCROLL_LPM
import com.localdoc.ai.domain.MIN_EINK_REFRESH_PAGES
import com.localdoc.ai.domain.MIN_READER_FONT_SIZE
import com.localdoc.ai.domain.MIN_ZONE_WIDTH
import com.localdoc.ai.domain.OrientationLock
import com.localdoc.ai.domain.ReaderSettings
import com.localdoc.ai.domain.TapZones
import com.localdoc.ai.platform.ScreenControls
import com.localdoc.ai.book.Chapter
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import com.localdoc.ai.domain.ReaderFont
import com.localdoc.ai.domain.LanguageVoice
import com.localdoc.ai.domain.ReaderTheme
import com.localdoc.ai.book.PageSize
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.platform.PlatformActions
import com.localdoc.ai.tts.PiperSpeechEngine
import com.localdoc.ai.tts.ReadAloudState
import com.localdoc.ai.tts.TtsEngineInfo
import com.localdoc.ai.tts.Voice
import com.localdoc.ai.ui.GoogleColors
import com.localdoc.ai.ui.gradientTextStyle
import com.localdoc.ai.ui.stopGeneration
import kotlinx.coroutines.launch

/** Плеер озвучки: предложение/абзац назад и вперёд, плей/пауза, стоп. */
@Composable
fun PlayerBar(state: ReadAloudState, title: String, onVoiceSettings: () -> Unit) {
    val player = AppGraph.readAloud
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.segment?.text ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SleepTimerButton()
                com.localdoc.ai.platform.AudioRouteButton()
                IconButton(onClick = onVoiceSettings) { Icon(Icons.Default.RecordVoiceOver, contentDescription = stringResource(Res.string.voice)) }
            }
            if (state.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (state.position + 1).toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                IconButton(onClick = player::previousParagraph) { Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(Res.string.paragraph_back)) }
                IconButton(onClick = player::previousSentence) { Icon(Icons.Default.FastRewind, contentDescription = stringResource(Res.string.sentence_back)) }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.playing) stringResource(Res.string.pause) else stringResource(Res.string.read),
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(onClick = player::nextSentence) { Icon(Icons.Default.FastForward, contentDescription = stringResource(Res.string.sentence_forward)) }
                IconButton(onClick = player::nextParagraph) { Icon(Icons.Default.SkipNext, contentDescription = stringResource(Res.string.paragraph_forward)) }
                IconButton(
                    onClick = player::stop,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Default.Stop, contentDescription = stringResource(Res.string.stop_2)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(onDismiss: () -> Unit, docKey: String? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(Res.string.appearance), style = MaterialTheme.typography.titleLarge)
            ReaderSettingsContent(docKey)
        }
    }
}

/** Оформление и режим чтения — для шторки читалки и вкладки «Настройки». */
@Composable
internal fun ReaderSettingsContent(docKey: String? = null) {
    val global by AppGraph.readerSettings.value.collectAsState()
    val overrides by AppGraph.bookOverrides.value.collectAsState()
    val bookOverride = docKey?.let { overrides[it] }
    // Правка идёт в настройки книги, пока они включены: оформление остаётся у книги, остальное — в общих
    val settings = global.applyOverride(bookOverride)
    val update: ((ReaderSettings) -> ReaderSettings) -> Unit = { change ->
        if (docKey != null && bookOverride != null) {
            val (newGlobal, newOverride) = global.splitEdit(change(settings))
            AppGraph.readerSettings.update { newGlobal }
            AppGraph.bookOverrides.update { it + (docKey to newOverride) }
        } else AppGraph.readerSettings.update(change)
    }
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf(AppGraph.fonts.list()) }
    var fontError by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (docKey != null) {
            SwitchRow(stringResource(Res.string.for_this_book_only), bookOverride != null) { on ->
                AppGraph.bookOverrides.update { if (on) it + (docKey to BookOverride()) else it - docKey }
            }
            if (bookOverride != null && !bookOverride.isEmpty) {
                TextButton(onClick = { AppGraph.bookOverrides.update { it + (docKey to BookOverride()) } }) { Text(stringResource(Res.string.reset_to_the_common_settings)) }
            }
        }
        SectionLabel(stringResource(Res.string.page_color))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ReaderTheme.entries.forEach { theme ->
                val paper = paperColors(theme)
                val selected = settings.theme == theme
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { update { it.copy(theme = theme) } }) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(52.dp)
                            .background(paper.background, CircleShape)
                            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), CircleShape)
                    ) {
                        Text("Аа", color = paper.text, fontFamily = FontFamily.Serif)
                    }
                    Text(stringResource(theme.label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        SectionLabel(stringResource(Res.string.font))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { font ->
                FilterChip(
                    selected = settings.font == font,
                    onClick = { update { it.copy(font = font) } },
                    label = { Text(stringResource(font.label), fontFamily = if (font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif) },
                    leadingIcon = if (settings.font == font) ({ Icon(Icons.Default.Check, null) }) else null
                )
            }
        }

        SectionLabel(stringResource(Res.string.custom_font))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.fontFile == null, onClick = { update { it.copy(fontFile = null) } }, label = { Text(stringResource(Res.string.default)) })
            fonts.forEach { name ->
                FilterChip(
                    selected = settings.fontFile == name,
                    onClick = { update { it.copy(fontFile = name) } },
                    label = { Text(name.substringBeforeLast('.'), maxLines = 1) }
                )
            }
            AssistChip(onClick = {
                FilePicker().pick("*/*") { uri ->
                    if (uri != null) scope.launch {
                        val name = AppGraph.fonts.import(uri)
                        fontError = name == null
                        fonts = AppGraph.fonts.list()
                        if (name != null) update { it.copy(fontFile = name) }
                    }
                }
            }, label = { Text(stringResource(Res.string.add_ttf_otf)) })
        }
        if (fontError) Text(stringResource(Res.string.this_is_not_a_ttf_or), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (settings.fontFile != null) {
            TextButton(onClick = {
                val name = settings.fontFile
                AppGraph.readerSettings.update { if (it.fontFile == name) it.copy(fontFile = null) else it }
                AppGraph.bookOverrides.update { map -> map.mapValues { (_, o) -> if (o.fontFile == name) o.copy(fontFile = null) else o } }
                name?.let { AppGraph.fonts.remove(it) }
                fonts = AppGraph.fonts.list()
            }) { Text(stringResource(Res.string.delete_this_font)) }
        }

        LabeledSlider(stringResource(Res.string.text_size), "${settings.fontSize.toInt()} sp", settings.fontSize, MIN_READER_FONT_SIZE..MAX_READER_FONT_SIZE) { v -> update { it.copy(fontSize = v) } }
        LabeledSlider(stringResource(Res.string.line_spacing), "×${(settings.lineHeight * 10).toInt() / 10.0}", settings.lineHeight, 1.2f..2.2f) { v -> update { it.copy(lineHeight = v) } }

        LabeledSlider(stringResource(Res.string.font_weight), settings.fontWeight.toString(), settings.fontWeight.toFloat(), 300f..700f) { v ->
            update { it.copy(fontWeight = (v / 100f).roundToInt() * 100) }
        }
        LabeledSlider(stringResource(Res.string.letter_spacing), "${(settings.letterSpacing * 10).roundToInt() / 10.0} sp", settings.letterSpacing, -0.5f..3f) { v ->
            update { it.copy(letterSpacing = (v * 10).roundToInt() / 10f) }
        }
        LabeledSlider(stringResource(Res.string.page_margins), "${settings.margin.toInt()} dp", settings.margin, 8f..64f) { v -> update { it.copy(margin = v.roundToInt().toFloat()) } }
        SwitchRow(stringResource(Res.string.justify_text), settings.justify) { v -> update { it.copy(justify = v) } }
        if (settings.justify) {
            SwitchRow(stringResource(Res.string.smart_justification_hyphenation_spaces_stret), settings.smartJustify) { v -> update { it.copy(smartJustify = v) } }
        }
        SwitchRow(stringResource(Res.string.follow_the_speech), settings.followSpeech) { v -> update { it.copy(followSpeech = v) } }
        SwitchRow(stringResource(Res.string.auto_advance_to_the_next_chapter), settings.autoNext) { v -> update { it.copy(autoNext = v) } }
        SectionLabel(stringResource(Res.string.reading_mode))
        SwitchRow(stringResource(Res.string.page_by_page_instead_of_scrolling), settings.paged) { v -> update { it.copy(paged = v) } }
        Text(
            stringResource(Res.string.screen_page_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SectionLabel(stringResource(Res.string.speech_chunk_title))
        Text(
            stringResource(Res.string.speech_chunk_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageSize.entries.forEach { size ->
                FilterChip(
                    selected = settings.pageSize == size,
                    onClick = { update { it.copy(pageSize = size) } },
                    label = { Text(stringResource(Res.string.k_chars, stringResource(size.label), size.chars / 1000.0), maxLines = 1, softWrap = false) }
                )
            }
        }
        ReaderControlsSettings(settings, update)
    }
}

/** Управление читалкой: зоны касания, жесты, экран, анимация, автопрокрутка и режим e-ink. */
@Composable
private fun ReaderControlsSettings(settings: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    SectionLabel(stringResource(Res.string.tap_zones))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TapZones.entries.forEach { zones ->
            EngineRow(stringResource(zones.label), settings.tapZones == zones) { update { it.copy(tapZones = zones) } }
        }
    }
    if (settings.tapZones != TapZones.MENU_ONLY) {
        LabeledSlider(stringResource(Res.string.side_zone_width), "${(settings.tapZoneWidth * 100).toInt()}%", settings.tapZoneWidth, MIN_ZONE_WIDTH..MAX_ZONE_WIDTH) { v ->
            update { it.copy(tapZoneWidth = v) }
        }
    }
    Text(
        stringResource(Res.string.the_center_of_the_screen_shows),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionLabel(stringResource(Res.string.gestures_and_buttons))
    SwitchRow(stringResource(Res.string.swipe_left_and_right_to_turn), settings.swipePaging) { v -> update { it.copy(swipePaging = v) } }
    SwitchRow(stringResource(Res.string.pinch_changes_the_font_size), settings.pinchZoom) { v -> update { it.copy(pinchZoom = v) } }
    SwitchRow(stringResource(Res.string.brightness_drag_along_the_left_edge), settings.brightnessGesture) { v -> update { it.copy(brightnessGesture = v) } }
    if (settings.brightness != null) {
        TextButton(onClick = { update { it.copy(brightness = null) } }) { Text(stringResource(Res.string.restore_system_brightness, ((settings.brightness ?: 0f) * 100).toInt())) }
    }
    if (ScreenControls.supportsVolumeKeys) {
        SwitchRow(stringResource(Res.string.turn_pages_with_the_volume_buttons), settings.volumeKeys) { v -> update { it.copy(volumeKeys = v) } }
    }

    SectionLabel(stringResource(Res.string.screen))
    SwitchRow(stringResource(Res.string.keep_the_screen_on_while_reading), settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
    if (ScreenControls.supportsOrientationLock) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrientationLock.entries.forEach { lock ->
                FilterChip(
                    selected = settings.orientation == lock,
                    onClick = { update { it.copy(orientation = lock) } },
                    label = { Text(stringResource(Res.string.orientation, stringResource(lock.label)), maxLines = 1, softWrap = false) }
                )
            }
        }
    }

    SectionLabel(stringResource(Res.string.page_turning))
    SwitchRow(stringResource(Res.string.page_turn_animation), settings.pageAnimation && !settings.eInk) { v -> update { it.copy(pageAnimation = v) } }
    Text(
        stringResource(Res.string.the_animation_is_also_turned_off),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LabeledSlider(
        stringResource(Res.string.auto_scroll_speed), stringResource(Res.string.lines_min, settings.autoScrollSpeed), settings.autoScrollSpeed.toFloat(),
        MIN_AUTO_SCROLL_LPM.toFloat()..MAX_AUTO_SCROLL_LPM.toFloat()
    ) { v -> update { it.copy(autoScrollSpeed = v.toInt()) } }
    Text(
        stringResource(Res.string.auto_scroll_is_turned_on_in),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionLabel(stringResource(Res.string.e_ink_screen))
    SwitchRow(stringResource(Res.string.e_ink_mode_no_animations), settings.eInk) { v -> update { it.copy(eInk = v) } }
    if (settings.eInk) {
        LabeledSlider(
            stringResource(Res.string.full_screen_refresh), stringResource(Res.string.every_pages, settings.eInkRefreshPages), settings.eInkRefreshPages.toFloat(),
            MIN_EINK_REFRESH_PAGES.toFloat()..MAX_EINK_REFRESH_PAGES.toFloat()
        ) { v -> update { it.copy(eInkRefreshPages = v.toInt()) } }
        Text(
            stringResource(Res.string.every_few_pages_the_screen_briefly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Открытые движки синтеза речи, которые можно поставить на Android и выбрать в приложении. */
private data class OpenEngine(val name: String, val description: StringResource, val url: String)

private val OPEN_ENGINES = listOf(
    OpenEngine("RHVoice", Res.string.an_open_engine_with_natural_russian, "https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android"),
    OpenEngine("eSpeak NG", Res.string.a_lightweight_open_synthesizer_for_100, "https://f-droid.org/packages/com.reecedunn.espeak/")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(Res.string.speech), style = MaterialTheme.typography.titleLarge)
            VoiceSettingsContent()
        }
    }
}

/** Скорость, высота, голоса Piper, системные движки — для шторки читалки и вкладки «Настройки». */
@Composable
internal fun VoiceSettingsContent() {
    val voice by AppGraph.voiceSettings.value.collectAsState()
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var engines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }

    val builtinActive = voice.engineId == PiperSpeechEngine.ENGINE_ID

    LaunchedEffect(voice.engineId, refresh) {
        AppGraph.speaker.prepare {
            AppGraph.appScope.launch { voices = AppGraph.speaker.voices() }
        }
        // Системные движки нужны и при встроенном голосе — чтобы к ним можно было вернуться
        AppGraph.speaker.prepareSystem {
            AppGraph.appScope.launch { engines = AppGraph.speaker.engines() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchLine(
            stringResource(Res.string.voice_for_the_text_language),
            stringResource(Res.string.pick_automatically_a_voice_that_speaks),
            voice.autoLanguageVoice
        ) { v -> AppGraph.updateVoice { it.copy(autoLanguageVoice = v) } }
        LabeledSlider(stringResource(Res.string.speech_rate), "×${(voice.rate * 10).toInt() / 10.0}", voice.rate, 0.5f..2.5f) { v -> AppGraph.updateVoice { it.copy(rate = v) } }
        LabeledSlider(stringResource(Res.string.voice_pitch), "×${(voice.pitch * 10).toInt() / 10.0}", voice.pitch, 0.5f..1.6f) { v -> AppGraph.updateVoice { it.copy(pitch = v) } }

        BuiltinVoicesSection()

        VoiceControlSection()

        SectionLabel(stringResource(Res.string.system_synthesizer))
        // Выбор системного движка — и способ вернуться к нему со встроенного голоса (на iOS тоже)
        EngineRow(stringResource(Res.string.system_default), voice.engineId == null) { AppGraph.updateVoice { it.copy(engineId = null, voiceId = null) } }
        if (PlatformActions.supportsTtsEngines) {
            Text(
                stringResource(Res.string.you_can_choose_any_installed_engine),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            engines.forEach { engine ->
                EngineRow(engine.label, voice.engineId == engine.id) { AppGraph.updateVoice { it.copy(engineId = engine.id, voiceId = null) } }
            }
        }

        if (!builtinActive) SystemVoiceList(voices, voice.voiceId)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { PlatformActions.installTtsData(); refresh++ }) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.voice_data), maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { PlatformActions.openTtsSettings(); refresh++ }) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.tts_settings), maxLines = 1, softWrap = false)
            }
        }

        if (PlatformActions.supportsTtsEngines) {
            HorizontalDivider()
            SectionLabel(stringResource(Res.string.open_engines_and_voices))
            OPEN_ENGINES.forEach { engine ->
                ListItem(
                    headlineContent = { Text(engine.name) },
                    supportingContent = { Text(stringResource(engine.description)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(Res.string.open)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { PlatformActions.openUrl(engine.url) }
                )
            }
            Text(
                stringResource(Res.string.after_installing_the_engine_will_appear),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(Res.string.additional_voices_including_the_improved_enh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Голоса выбранного системного движка (при встроенном голосе не показываются). */
@Composable
private fun SystemVoiceList(voices: List<Voice>, selectedId: String?) {
    var onlyRussian by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(stringResource(Res.string.voice), Modifier.weight(1f))
        FilterChip(selected = onlyRussian, onClick = { onlyRussian = !onlyRussian }, label = { Text(stringResource(Res.string.russian_only), maxLines = 1, softWrap = false) })
    }
    val shown = voices.filter { !onlyRussian || it.language.startsWith("ru") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            item {
                VoiceRow(stringResource(Res.string.automatic_russian), stringResource(Res.string.engine_default), selectedId == null) { AppGraph.updateVoice { it.copy(voiceId = null) } }
            }
            itemsIndexed(shown) { _, v ->
                VoiceRow(v.name, v.language + if (!v.offline) " " + stringResource(Res.string.needs_internet) else " " + stringResource(Res.string.offline_2), selectedId == v.id) {
                    AppGraph.updateVoice { settings ->
                        val code = Languages.baseCode(v.language)
                        settings.copy(
                            voiceId = v.id,
                            perLanguage = if (code != null) settings.perLanguage + (code to LanguageVoice(settings.engineId, v.id)) else settings.perLanguage
                        )
                    }
                    // Пробная фраза не должна сбивать идущую озвучку книги
                    if (!AppGraph.readAloud.state.value.playing) AppGraph.speaker.speak("Так звучит выбранный голос.") { }
                }
            }
        }
    }
    if (voices.isEmpty()) {
        Text(stringResource(Res.string.voices_are_loading_if_the_list), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(chapters: List<Chapter>, currentIndex: Int, onDismiss: () -> Unit, onSelect: (Chapter) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(Res.string.contents), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            itemsIndexed(chapters) { index, chapter ->
                val selected = index == currentIndex
                ListItem(
                    headlineContent = {
                        Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.clickable { onSelect(chapter) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiAnswerSheet(
    onDismiss: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenModels: () -> Unit,
    onAction: (ReaderAiAction) -> Unit
) {
    val messages by AppGraph.messages.collectAsState()
    val generating by AppGraph.isGenerating.collectAsState()
    val loaded by AppGraph.loadedModelId.collectAsState()
    val answer = messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }
    val question = messages.lastOrNull { it.role == ChatMessage.Role.USER }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoogleColors.Blue)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.ai_assistant), style = gradientTextStyle(MaterialTheme.typography.titleLarge))
            }
            if (loaded == null) {
                Text(stringResource(Res.string.to_let_the_ai_summarize_and), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenModels) { Text(stringResource(Res.string.choose_a_model)) }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ReaderAiAction.entries.forEach { action -> AiChip(stringResource(action.label), action.icon) { onAction(action) } }
            }
            if (answer != null) {
                question?.let { Text(it.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SelectionContainer {
                            Text(answer.text.ifBlank { if (generating) stringResource(Res.string.thinking) else "…" }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                Text(stringResource(Res.string.choose_an_action_the_model_will), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (generating) {
                    OutlinedButton(onClick = { stopGeneration() }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.stop_2), maxLines = 1, softWrap = false) }
                }
                TextButton(onClick = onOpenAssistant) { Text(stringResource(Res.string.continue_in_the_assistant), maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 4.dp))
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun EngineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    )
}
