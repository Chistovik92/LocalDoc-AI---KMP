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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.model.ModelSpec
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen() {
    val scope = rememberCoroutineScope()
    val loadedId by AppGraph.loadedModelId.collectAsState()
    // specId -> текущее состояние загрузки
    var downloadStates by remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyModelId by remember { mutableStateOf<String?>(null) }

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
            val downloaded = AppGraph.downloader.isDownloaded(spec)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(spec.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${spec.description} Размер: ~${spec.sizeMb / 1024} ГБ",
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
                                    (state.totalBytes.takeIf { it > 0 }?.let { " / ${it / (1024 * 1024)} МБ" } ?: ""),
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
                                    AppGraph.downloader.cancel(spec)
                                }) { Text("Отменить") }
                            }
                            downloaded -> {
                                Button(
                                    enabled = busyModelId == null,
                                    onClick = {
                                        scope.launch {
                                            busyModelId = spec.id
                                            loadError = null
                                            val ok = AppGraph.engine.load(
                                                AppGraph.downloader.localPathFor(spec),
                                                spec.defaultContext,
                                                spec.defaultGpuLayers
                                            )
                                            if (ok) {
                                                AppGraph.setLoaded(spec)
                                            } else {
                                                loadError = "Не удалось загрузить модель (не хватает памяти?)"
                                            }
                                            busyModelId = null
                                        }
                                    }
                                ) {
                                    Text(if (isLoaded) "Загружена" else "Загрузить в память")
                                }
                                if (isLoaded) {
                                    OutlinedButton(onClick = {
                                        AppGraph.engine.unload()
                                        AppGraph.setLoaded(null)
                                    }) { Text("Выгрузить") }
                                } else {
                                    TextButton(onClick = {
                                        scope.launch { AppGraph.downloader.delete(spec) }
                                    }) { Text("Удалить") }
                                }
                            }
                            else -> {
                                Button(
                                    enabled = busyModelId == null,
                                    onClick = {
                                        scope.launch {
                                            AppGraph.downloader.download(spec).collect { s ->
                                                downloadStates = downloadStates + (spec.id to s)
                                                if (s is DownloadState.Done) busyModelId = null
                                                if (s is DownloadState.Failed) busyModelId = null
                                            }
                                        }
                                    }
                                ) { Text("Скачать") }
                            }
                        }
                    }
                }
            }
        }
    }
}
