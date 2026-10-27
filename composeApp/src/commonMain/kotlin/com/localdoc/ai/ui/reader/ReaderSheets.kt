@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui.reader

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
                IconButton(onClick = onVoiceSettings) { Icon(Icons.Default.RecordVoiceOver, contentDescription = "Голос") }
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
                IconButton(onClick = player::previousParagraph) { Icon(Icons.Default.SkipPrevious, contentDescription = "Абзац назад") }
                IconButton(onClick = player::previousSentence) { Icon(Icons.Default.FastRewind, contentDescription = "Предложение назад") }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.playing) "Пауза" else "Читать",
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(onClick = player::nextSentence) { Icon(Icons.Default.FastForward, contentDescription = "Предложение вперёд") }
                IconButton(onClick = player::nextParagraph) { Icon(Icons.Default.SkipNext, contentDescription = "Абзац вперёд") }
                IconButton(
                    onClick = player::stop,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Default.Stop, contentDescription = "Стоп") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleLarge)
            ReaderSettingsContent()
        }
    }
}

/** Оформление и режим чтения — для шторки читалки и вкладки «Настройки». */
@Composable
internal fun ReaderSettingsContent() {
    val settings by AppGraph.readerSettings.value.collectAsState()
    val update = AppGraph.readerSettings::update
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Цвет страницы")
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
                    Text(theme.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        SectionLabel("Шрифт")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { font ->
                FilterChip(
                    selected = settings.font == font,
                    onClick = { update { it.copy(font = font) } },
                    label = { Text(font.label, fontFamily = if (font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif) },
                    leadingIcon = if (settings.font == font) ({ Icon(Icons.Default.Check, null) }) else null
                )
            }
        }

        LabeledSlider("Размер текста", "${settings.fontSize.toInt()} sp", settings.fontSize, 13f..32f) { v -> update { it.copy(fontSize = v) } }
        LabeledSlider("Межстрочный интервал", "×${(settings.lineHeight * 10).toInt() / 10.0}", settings.lineHeight, 1.2f..2.2f) { v -> update { it.copy(lineHeight = v) } }

        SwitchRow("Выравнивание по ширине", settings.justify) { v -> update { it.copy(justify = v) } }
        SwitchRow("Следовать за озвучкой", settings.followSpeech) { v -> update { it.copy(followSpeech = v) } }
        SwitchRow("Автопереход к следующей главе (веб-новеллы)", settings.autoNext) { v -> update { it.copy(autoNext = v) } }
        SectionLabel("Режим чтения")
        SwitchRow("Постранично (вместо прокрутки)", settings.paged) { v -> update { it.copy(paged = v) } }
        Text(
            "Озвучка, перевод и ИИ работают по страницам в любом режиме — большая книга не зависает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageSize.entries.forEach { size ->
                FilterChip(
                    selected = settings.pageSize == size,
                    onClick = { update { it.copy(pageSize = size) } },
                    label = { Text("${size.label} · ${size.chars / 1000.0} тыс. зн.", maxLines = 1, softWrap = false) }
                )
            }
        }
    }
}

/** Открытые движки синтеза речи, которые можно поставить на Android и выбрать в приложении. */
private data class OpenEngine(val name: String, val description: String, val url: String)

private val OPEN_ENGINES = listOf(
    OpenEngine("RHVoice", "Открытый движок с естественными русскими голосами (Анна, Елена, Ирина, Александр…). Работает офлайн.", "https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android"),
    OpenEngine("eSpeak NG", "Лёгкий открытый синтезатор на 100+ языков (F-Droid).", "https://f-droid.org/packages/com.reecedunn.espeak/")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Озвучка", style = MaterialTheme.typography.titleLarge)
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
            "Голос под язык текста",
            "Сам выбирать голос, который говорит на языке книги или перевода (установленный Piper, затем системный)",
            voice.autoLanguageVoice
        ) { v -> AppGraph.updateVoice { it.copy(autoLanguageVoice = v) } }
        LabeledSlider("Скорость речи", "×${(voice.rate * 10).toInt() / 10.0}", voice.rate, 0.5f..2.5f) { v -> AppGraph.updateVoice { it.copy(rate = v) } }
        LabeledSlider("Высота голоса", "×${(voice.pitch * 10).toInt() / 10.0}", voice.pitch, 0.5f..1.6f) { v -> AppGraph.updateVoice { it.copy(pitch = v) } }

        BuiltinVoicesSection()

        VoiceControlSection()

        SectionLabel("Системный синтезатор")
        // Выбор системного движка — и способ вернуться к нему со встроенного голоса (на iOS тоже)
        EngineRow("Системный по умолчанию", voice.engineId == null) { AppGraph.updateVoice { it.copy(engineId = null, voiceId = null) } }
        if (PlatformActions.supportsTtsEngines) {
            Text(
                "Можно выбрать любой установленный движок — в том числе сторонние и открытые.",
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
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Голосовые данные", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { PlatformActions.openTtsSettings(); refresh++ }) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Настройки TTS", maxLines = 1, softWrap = false)
            }
        }

        if (PlatformActions.supportsTtsEngines) {
            HorizontalDivider()
            SectionLabel("Открытые движки и голоса")
            OPEN_ENGINES.forEach { engine ->
                ListItem(
                    headlineContent = { Text(engine.name) },
                    supportingContent = { Text(engine.description) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { PlatformActions.openUrl(engine.url) }
                )
            }
            Text(
                "После установки движок появится в списке выше — выберите его и нужный голос.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Дополнительные голоса (в т.ч. улучшенные «Enhanced» и «Premium») загружаются в Настройках iOS → Универсальный доступ → Устный контент → Голоса.",
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
        SectionLabel("Голос", Modifier.weight(1f))
        FilterChip(selected = onlyRussian, onClick = { onlyRussian = !onlyRussian }, label = { Text("Только русские", maxLines = 1, softWrap = false) })
    }
    val shown = voices.filter { !onlyRussian || it.language.startsWith("ru") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            item {
                VoiceRow("Автоматически (русский)", "по умолчанию движка", selectedId == null) { AppGraph.updateVoice { it.copy(voiceId = null) } }
            }
            itemsIndexed(shown) { _, v ->
                VoiceRow(v.name, v.language + if (!v.offline) " · нужен интернет" else " · офлайн", selectedId == v.id) {
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
        Text("Голоса загружаются… Если список пуст — установите голосовые данные.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(chapters: List<Chapter>, currentIndex: Int, onDismiss: () -> Unit, onSelect: (Chapter) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Оглавление", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
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
                Text("ИИ-суфлёр", style = gradientTextStyle(MaterialTheme.typography.titleLarge))
            }
            if (loaded == null) {
                Text("Чтобы ИИ мог пересказывать и объяснять текст, загрузите локальную модель — она работает без интернета.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenModels) { Text("Выбрать модель") }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ReaderAiAction.entries.forEach { action -> AiChip(action.label, action.icon) { onAction(action) } }
            }
            if (answer != null) {
                question?.let { Text(it.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SelectionContainer {
                            Text(answer.text.ifBlank { if (generating) "Думаю…" else "…" }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                Text("Выберите действие — модель ответит по открытому тексту.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (generating) {
                    OutlinedButton(onClick = { stopGeneration() }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Стоп", maxLines = 1, softWrap = false) }
                }
                TextButton(onClick = onOpenAssistant) { Text("Продолжить в Ассистенте", maxLines = 1, softWrap = false) }
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EngineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
        modifier = Modifier.clickable(onClick = onClick)
    )
}
