package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen() {
    val scope = rememberCoroutineScope()
    val loadedId by AppGraph.loadedModelId.collectAsState()
    // specId -> текущее состояние загрузки
    val downloadStates by AppGraph.downloadStates.collectAsState()
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyModelId by remember { mutableStateOf<String?>(null) }
    // Счётчик для перерисовки после удаления файла (isDownloaded читает файловую систему)
    var filesVersion by remember { mutableStateOf(0) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        item {
            Text(
                "Каталог моделей (офлайн)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Скачайте GGUF-модель — она целиком работает на устройстве, без интернета.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            loadError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        items(ModelCatalog.models) { spec ->
            val state = downloadStates[spec.id]
            val isLoaded = loadedId == spec.id
            val downloaded = remember(spec.id, filesVersion, state) { AppGraph.downloader.isDownloaded(spec) }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isLoaded) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (isLoaded) {
                            Surface(color = MaterialTheme.colorScheme.secondary, shape = MaterialTheme.shapes.small) {
                                Text(
                                    "Активна",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        "${spec.description} Размер: ~${formatSize(spec.sizeMb)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    when (state) {
                        is DownloadState.Progress -> {
                            val fraction = if (state.totalBytes > 0) {
                                state.downloadedBytes.toFloat() / state.totalBytes
                            } else null
                            ProgressRow(
                                text = "Загрузка: ${state.downloadedBytes / (1024 * 1024)} МБ" +
                                    (state.totalBytes.takeIf { it > 0 }?.let { " / ${it / (1024 * 1024)} МБ" } ?: "") +
                                    (fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                                fraction = fraction
                            )
                        }
                        is DownloadState.Failed -> Text(
                            "Ошибка загрузки: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> {}
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        when {
                            state is DownloadState.Progress -> {
                                TextButton(onClick = {
                                    AppGraph.cancelDownload(spec)
                                }) { Text("Отменить") }
                            }
                            downloaded -> {
                                Button(
                                    enabled = busyModelId == null,
                                    onClick = {
                                        scope.launch {
                                            busyModelId = spec.id
                                            loadError = null
                                            stopGeneration()
                                            val ok = AppGraph.engine.load(
                                                AppGraph.downloader.localPathFor(spec),
                                                spec.defaultContext,
                                                spec.defaultGpuLayers
                                            )
                                            if (ok) {
                                                AppGraph.setLoaded(spec)
                                            } else {
                                                // load() выгружает прежнюю модель до загрузки новой
                                                AppGraph.setLoaded(null)
                                                loadError = "Не удалось загрузить модель (не хватает памяти?)"
                                            }
                                            busyModelId = null
                                        }
                                    }
                                ) {
                                    Text(
                                        when {
                                            busyModelId == spec.id -> "Загрузка…"
                                            isLoaded -> "Перезагрузить"
                                            else -> "Загрузить в память"
                                        }
                                    )
                                }
                                if (isLoaded) {
                                    OutlinedButton(
                                        enabled = busyModelId == null,
                                        onClick = {
                                            scope.launch {
                                                busyModelId = spec.id
                                                stopGeneration()
                                                // unload ждёт остановки генерации — не на главном потоке
                                                withContext(Dispatchers.Default) { AppGraph.engine.unload() }
                                                AppGraph.setLoaded(null)
                                                busyModelId = null
                                            }
                                        }
                                    ) { Text("Выгрузить") }
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            AppGraph.downloader.delete(spec)
                                            AppGraph.clearDownloadState(spec.id)
                                            filesVersion++
                                        }
                                    }) { Text("Удалить") }
                                }
                            }
                            else -> {
                                Button(
                                    enabled = busyModelId == null,
                                    onClick = { AppGraph.startDownload(spec) }
                                ) { Text("Скачать") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(sizeMb: Long): String =
    if (sizeMb >= 1024) "${(sizeMb * 10 / 1024) / 10.0} ГБ" else "$sizeMb МБ"
