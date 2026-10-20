package com.localdoc.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelFamily
import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.platform.PlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Фильтр каталога по объёму ОЗУ телефона. */
private enum class RamFilter(val label: String) {
    ALL("Все модели"),
    RAM4("До 4 ГБ ОЗУ"),
    RAM6("До 6 ГБ ОЗУ"),
    RAM8("До 8 ГБ ОЗУ"),
    RAM12("12+ ГБ ОЗУ");

    fun accepts(spec: ModelSpec): Boolean = when (this) {
        ALL -> true
        RAM4 -> spec.minRamGb <= 4
        RAM6 -> spec.minRamGb <= 6
        RAM8 -> spec.minRamGb <= 8
        RAM12 -> spec.minRamGb >= 12
    }
}

private fun ModelFamily.color(): Color = when (this) {
    ModelFamily.QWEN -> Color(0xFF615CED)
    ModelFamily.GEMMA -> Color(0xFF4285F4)
    ModelFamily.LLAMA -> Color(0xFF0668E1)
    ModelFamily.PHI -> Color(0xFF00A4EF)
    ModelFamily.SMOL -> Color(0xFFF9AB00)
    ModelFamily.DEEPSEEK -> Color(0xFF4D6BFE)
    ModelFamily.VIKHR -> Color(0xFFEA4335)
}

/**
 * Каталог локальных моделей: фильтр по ОЗУ, оценки качества/скорости/русского языка,
 * установка и загрузка в память. Ниже — раздел «О приложении» с проверкой обновлений.
 */
@Composable
fun ModelsScreen() {
    val scope = rememberCoroutineScope()
    val loadedId by AppGraph.loadedModelId.collectAsState()
    val downloadStates by AppGraph.downloadStates.collectAsState()
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyModelId by remember { mutableStateOf<String?>(null) }
    // Счётчик для перерисовки после удаления файла (isDownloaded читает файловую систему)
    var filesVersion by remember { mutableStateOf(0) }
    var ramFilter by remember { mutableStateOf(RamFilter.ALL) }
    var onlyRecommended by remember { mutableStateOf(false) }
    var russianFirst by remember { mutableStateOf(false) }

    val models = ModelCatalog.models
        .filter { ramFilter.accepts(it) && (!onlyRecommended || it.recommended) }
        .let { list -> if (russianFirst) list.sortedByDescending { it.russian } else list }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { Hero(loadedId) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                RamFilter.entries.forEach { f -> FilterChip(selected = ramFilter == f, onClick = { ramFilter = f }, label = { Text(f.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = onlyRecommended, onClick = { onlyRecommended = !onlyRecommended }, label = { Text("★ Рекомендуемые") })
                FilterChip(selected = russianFirst, onClick = { russianFirst = !russianFirst }, label = { Text("Лучший русский") })
            }
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }

        items(models, key = { it.id }) { spec ->
            val state = downloadStates[spec.id]
            val downloaded = remember(spec.id, filesVersion, state) { AppGraph.downloader.isDownloaded(spec) }
            ModelCard(
                spec = spec,
                isLoaded = loadedId == spec.id,
                downloaded = downloaded,
                state = state,
                busy = busyModelId,
                onDownload = { AppGraph.startDownload(spec) },
                onCancel = { AppGraph.cancelDownload(spec) },
                onDelete = {
                    scope.launch {
                        AppGraph.downloader.delete(spec)
                        AppGraph.clearDownloadState(spec.id)
                        filesVersion++
                    }
                },
                onLoad = {
                    scope.launch {
                        busyModelId = spec.id
                        loadError = null
                        stopGeneration()
                        val ok = AppGraph.engine.load(AppGraph.downloader.localPathFor(spec), spec.defaultContext, spec.defaultGpuLayers)
                        if (ok) {
                            AppGraph.setLoaded(spec)
                        } else {
                            // load() выгружает прежнюю модель до загрузки новой
                            AppGraph.setLoaded(null)
                            loadError = "Не удалось загрузить «${spec.name}» — вероятно, не хватает памяти. Попробуйте модель поменьше."
                        }
                        busyModelId = null
                    }
                },
                onUnload = {
                    scope.launch {
                        busyModelId = spec.id
                        stopGeneration()
                        // unload ждёт остановки генерации — не на главном потоке
                        withContext(Dispatchers.Default) { AppGraph.engine.unload() }
                        AppGraph.setLoaded(null)
                        busyModelId = null
                    }
                }
            )
        }

        item { AboutSection() }
    }
}

@Composable
private fun Hero(loadedId: String?) {
    val active = ModelCatalog.byId(loadedId.orEmpty())
    Box(modifier = Modifier.fillMaxWidth().background(GoogleColors.AiGradient, RoundedCornerShape(28.dp)).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Text("Нейросети на устройстве", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "Пересказы, объяснения и ответы по книгам — без интернета и без отправки текста в облако.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                HeroBadge(Icons.Default.WifiOff, "Офлайн")
                HeroBadge(Icons.Default.Security, "Приватно")
                HeroBadge(Icons.Default.CheckCircle, active?.name ?: "Модель не выбрана")
            }
        }
    }
}

@Composable
private fun HeroBadge(icon: ImageVector, text: String) {
    Surface(color = Color.White.copy(alpha = 0.2f), shape = CircleShape) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    spec: ModelSpec,
    isLoaded: Boolean,
    downloaded: Boolean,
    state: DownloadState?,
    busy: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit
) {
    val familyColor = spec.family.color()
    Surface(
        color = if (isLoaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = if (isLoaded) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).background(familyColor.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                ) {
                    Text(spec.family.label.take(1), color = familyColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${spec.family.vendor} · ${spec.parameters} · ${formatSize(spec.sizeMb)} · от ${spec.minRamGb} ГБ ОЗУ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    isLoaded -> Pill("Активна", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    spec.recommended -> Pill("★ Выбор", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }

            Text(spec.description, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Rating("Качество", spec.quality, GoogleColors.Blue)
                Rating("Скорость", spec.speed, GoogleColors.Green)
                Rating("Русский", spec.russian, GoogleColors.Red)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (spec.tags + "Лицензия: ${spec.license}").forEach { tag ->
                    Surface(color = familyColor.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }

            when (state) {
                is DownloadState.Progress -> {
                    val fraction = if (state.totalBytes > 0) state.downloadedBytes.toFloat() / state.totalBytes else null
                    Column {
                        Text(
                            "Загрузка: ${state.downloadedBytes / (1024 * 1024)} МБ" +
                                (state.totalBytes.takeIf { it > 0 }?.let { " из ${it / (1024 * 1024)} МБ" } ?: "") +
                                (fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
                is DownloadState.Failed -> Text("Ошибка загрузки: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    state is DownloadState.Progress -> TextButton(onClick = onCancel) { Text("Отменить") }
                    downloaded && isLoaded -> OutlinedButton(onClick = onUnload, enabled = busy == null) { Text("Выгрузить") }
                    downloaded -> {
                        Button(onClick = onLoad, enabled = busy == null) {
                            if (busy == spec.id) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (busy == spec.id) "Загрузка…" else "Использовать")
                        }
                        TextButton(onClick = onDelete, enabled = busy == null) { Text("Удалить") }
                    }
                    else -> FilledTonalButton(onClick = onDownload, enabled = busy == null) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Установить · ${formatSize(spec.sizeMb)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, shape = CircleShape) {
        Text(text, color = content, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Rating(label: String, value: Int, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RatingDots(value, color, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AboutSection() {
    val updates = AppGraph.updates
    val update by updates.update.collectAsState()
    val status by updates.status.collectAsState()
    val checking by updates.checking.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("О приложении", style = MaterialTheme.typography.titleMedium)
            Text("LocalDoc AI ${PlatformActions.appVersion} — читалка книг и веб-страниц с локальным ИИ и озвучкой.", style = MaterialTheme.typography.bodyMedium)
            update?.let { found ->
                Text("Доступна версия ${found.version}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (found.notes.isNotBlank()) {
                    Text(found.notes.take(600), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { updates.install(found) }) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (found.downloadUrl != null) "Скачать и установить" else "Открыть страницу релиза")
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = { updates.check(silent = false) }, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Проверить обновления")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
