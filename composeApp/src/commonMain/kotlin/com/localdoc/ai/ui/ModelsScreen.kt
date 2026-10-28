@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

import com.localdoc.ai.resources.about_the_app
import com.localdoc.ai.resources.active
import com.localdoc.ai.resources.all_models
import com.localdoc.ai.resources.best_for_russian
import com.localdoc.ai.resources.cancel_2
import com.localdoc.ai.resources.cancel_download
import com.localdoc.ai.resources.check_for_updates
import com.localdoc.ai.resources.could_not_load_probably_not_enough
import com.localdoc.ai.resources.delete
import com.localdoc.ai.resources.download_and_install
import com.localdoc.ai.resources.download_error
import com.localdoc.ai.resources.downloading_mb
import com.localdoc.ai.resources.from_gb_ram
import com.localdoc.ai.resources.install
import com.localdoc.ai.resources.license
import com.localdoc.ai.resources.loading
import com.localdoc.ai.resources.localdoc_ai_a_reader_for_books
import com.localdoc.ai.resources.n_12_gb_ram
import com.localdoc.ai.resources.no_model_selected
import com.localdoc.ai.resources.of_mb
import com.localdoc.ai.resources.offline
import com.localdoc.ai.resources.on_device_neural_networks
import com.localdoc.ai.resources.open_the_release_page
import com.localdoc.ai.resources.private
import com.localdoc.ai.resources.quality
import com.localdoc.ai.resources.recommended
import com.localdoc.ai.resources.russian
import com.localdoc.ai.resources.speed
import com.localdoc.ai.resources.summaries_explanations_and_answers_about_boo
import com.localdoc.ai.resources.top_pick
import com.localdoc.ai.resources.unload
import com.localdoc.ai.resources.up_to_4_gb_ram
import com.localdoc.ai.resources.up_to_6_gb_ram
import com.localdoc.ai.resources.up_to_8_gb_ram
import com.localdoc.ai.resources.use
import com.localdoc.ai.resources.version_is_available
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelFamily
import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.platform.PlatformActions
import com.localdoc.ai.update.UpdateDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Фильтр каталога по объёму ОЗУ телефона. */
private enum class RamFilter(val label: StringResource) {
    ALL(Res.string.all_models),
    RAM4(Res.string.up_to_4_gb_ram),
    RAM6(Res.string.up_to_6_gb_ram),
    RAM8(Res.string.up_to_8_gb_ram),
    RAM12(Res.string.n_12_gb_ram);

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
 * установка и загрузка в память. Экран — раздел «Нейросети» во вкладке «Настройки».
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
        item { SemanticSearchCard() }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                RamFilter.entries.forEach { f -> FilterChip(selected = ramFilter == f, onClick = { ramFilter = f }, label = { Text(stringResource(f.label)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = onlyRecommended, onClick = { onlyRecommended = !onlyRecommended }, label = { Text(stringResource(Res.string.recommended)) })
                FilterChip(selected = russianFirst, onClick = { russianFirst = !russianFirst }, label = { Text(stringResource(Res.string.best_for_russian)) })
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
                            loadError = getString(Res.string.could_not_load_probably_not_enough, spec.name)
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

    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Hero(loadedId: String?) {
    val active = ModelCatalog.byId(loadedId.orEmpty())
    Box(modifier = Modifier.fillMaxWidth().background(GoogleColors.AiGradient, RoundedCornerShape(28.dp)).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Text(stringResource(Res.string.on_device_neural_networks), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                stringResource(Res.string.summaries_explanations_and_answers_about_boo),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                HeroBadge(Icons.Default.WifiOff, stringResource(Res.string.offline))
                HeroBadge(Icons.Default.Security, stringResource(Res.string.private))
                HeroBadge(Icons.Default.CheckCircle, active?.name ?: stringResource(Res.string.no_model_selected))
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
            Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        stringResource(Res.string.from_gb_ram, spec.family.vendor, spec.parameters, formatSize(spec.sizeMb), spec.minRamGb),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    isLoaded -> Pill(stringResource(Res.string.active), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    spec.recommended -> Pill(stringResource(Res.string.top_pick), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }

            Text(spec.description, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Rating(stringResource(Res.string.quality), spec.quality, GoogleColors.Blue)
                Rating(stringResource(Res.string.speed), spec.speed, GoogleColors.Green)
                Rating(stringResource(Res.string.russian), spec.russian, GoogleColors.Red)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (spec.tags + stringResource(Res.string.license, spec.license)).forEach { tag ->
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
                            stringResource(Res.string.downloading_mb, state.downloadedBytes / (1024 * 1024)) +
                                (state.totalBytes.takeIf { it > 0 }?.let { " " + stringResource(Res.string.of_mb, it / (1024 * 1024)) } ?: "") +
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
                is DownloadState.Failed -> Text(stringResource(Res.string.download_error, state.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                when {
                    state is DownloadState.Progress -> TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel_2)) }
                    downloaded && isLoaded -> OutlinedButton(onClick = onUnload, enabled = busy == null) { Text(stringResource(Res.string.unload)) }
                    downloaded -> {
                        Button(onClick = onLoad, enabled = busy == null) {
                            if (busy == spec.id) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (busy == spec.id) stringResource(Res.string.loading) else stringResource(Res.string.use), maxLines = 1, softWrap = false)
                        }
                        TextButton(onClick = onDelete, enabled = busy == null) { Text(stringResource(Res.string.delete)) }
                    }
                    else -> FilledTonalButton(onClick = onDownload, enabled = busy == null) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.install, formatSize(spec.sizeMb)), maxLines = 1, softWrap = false)
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
internal fun AboutSection() {
    val updates = AppGraph.updates
    val update by updates.update.collectAsState()
    val status by updates.status.collectAsState()
    val checking by updates.checking.collectAsState()
    val download by updates.download.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.about_the_app), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.localdoc_ai_a_reader_for_books, PlatformActions.appVersion), style = MaterialTheme.typography.bodyMedium)
            update?.let { found ->
                Text(stringResource(Res.string.version_is_available, found.version), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (found.notes.isNotBlank()) {
                    Text(found.notes.take(600), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val busy = download is UpdateDownload.Downloading
                Button(onClick = { updates.install(found) }, enabled = !busy) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            busy -> stringResource(Res.string.loading)
                            found.downloadUrl != null -> stringResource(Res.string.download_and_install)
                            else -> stringResource(Res.string.open_the_release_page)
                        }
                    )
                }
                if (busy) OutlinedButton(onClick = updates::cancelDownload) { Text(stringResource(Res.string.cancel_download)) }
            }
            UpdateProgressInfo(download, status)
            OutlinedButton(onClick = { updates.check(silent = false) }, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.check_for_updates))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
