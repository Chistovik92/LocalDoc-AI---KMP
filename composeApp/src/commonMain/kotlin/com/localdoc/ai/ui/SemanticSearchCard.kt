package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.rag.SemanticModelCatalog
import com.localdoc.ai.rag.SemanticModelState
import kotlinx.coroutines.launch

/** Карточка «Умный поиск по книге»: скачивание, включение и удаление модели эмбеддингов. */
@Composable
fun SemanticSearchCard(modifier: Modifier = Modifier) {
    val manager = AppGraph.semanticModel
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(manager.isEnabled()) }
    val sizeMb = SemanticModelCatalog.totalBytes / (1024 * 1024)
    val onEnabled: (Boolean) -> Unit = { enabled = it; manager.setEnabled(it) }
    val onDelete: () -> Unit = { scope.launch { manager.delete() } }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Умный поиск по книге", style = MaterialTheme.typography.titleMedium)
            Text(
                "Модель multilingual-e5-small находит фрагменты по смыслу, а не по словам. " +
                    "Скачивается один раз (≈$sizeMb МБ), работает офлайн. Без неё используется обычный поиск.",
                style = MaterialTheme.typography.bodySmall
            )
            when (val s = state) {
                SemanticModelState.NotInstalled -> Button(onClick = { manager.download() }) { Text("Скачать ≈$sizeMb МБ") }
                is SemanticModelState.Downloading -> {
                    val fraction = if (s.totalBytes > 0) s.downloadedBytes.toFloat() / s.totalBytes else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${s.downloadedBytes / (1024 * 1024)} из ${s.totalBytes / (1024 * 1024)} МБ", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { manager.cancel() }) { Text("Отменить") }
                }
                SemanticModelState.Verifying -> Text("Проверяем целостность файлов…")
                SemanticModelState.Loading -> Text("Запускаем модель и измеряем скорость…")
                is SemanticModelState.Unsupported -> Text(s.reason, style = MaterialTheme.typography.bodySmall)
                is SemanticModelState.Rejected ->
                    Installed("Модель отключена автоматически: ${s.reason}. Работает обычный поиск.", enabled, onEnabled, onDelete)
                is SemanticModelState.Failed -> {
                    Text("Ошибка: ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { manager.download() }) { Text("Повторить") }
                }
                SemanticModelState.Ready -> Installed("Умный поиск включён.", enabled, onEnabled, onDelete)
                SemanticModelState.Disabled -> Installed("Модель скачана, но выключена.", enabled, onEnabled, onDelete)
            }
        }
    }
}

@Composable
private fun Installed(message: String, enabled: Boolean, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    Text(message, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = enabled, onCheckedChange = onEnabled)
        Text("Использовать модель")
    }
    OutlinedButton(onClick = onDelete) { Text("Удалить модель") }
}
