package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Вспомогательные элементы UI и запуск генерации со сбором статистики (tokens/sec).
 */

@Composable
fun UrlInputDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    if (!visible) return
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть веб-страницу") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("https://example.com/article") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ProcessingOverlay(docTitle: String?) {
    if (docTitle == null) return
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            CircularProgressIndicator()
            Text(
                "Обработка: $docTitle",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Извлечение текста (при необходимости — локальный OCR)…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ProgressRow(text: String, fraction: Float?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Запуск генерации: добавляет ответ ассистента, стримит токены, считает tokens/sec. */
fun launchGeneration(flow: kotlinx.coroutines.flow.Flow<String>) {
    if (AppGraph.isGenerating.value) return
    AppGraph.isGenerating.value = true
    AppGraph.statsTokensPerSec.value = 0f
    AppGraph.addMessage(ChatMessage(ChatMessage.Role.ASSISTANT, ""))

    val previous = AppGraph.generationJob
    AppGraph.generationJob = AppGraph.appScope.launch {
        // Остановленная генерация может ещё дорабатывать токен в C-ядре — дожидаемся её
        previous?.join()
        val clock = TimeSource.Monotonic.markNow()
        var tokenCount = 0
        val acc = StringBuilder()

        try {
            flow.collect { token ->
                acc.append(token)
                tokenCount++
                AppGraph.updateLastAssistant(acc.toString())
                val seconds = clock.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000f
                AppGraph.statsTokensPerSec.value = tokenCount / seconds
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppGraph.updateLastAssistant(acc.toString().ifBlank { "Генерация остановлена." })
            throw e
        } catch (t: Throwable) {
            AppGraph.updateLastAssistant(acc.toString().ifBlank { "Ошибка генерации: ${t.message}" })
        } finally {
            AppGraph.isGenerating.value = false
            if (AppGraph.generationJob === coroutineContext[Job]) AppGraph.generationJob = null
        }
    }
}

/** Остановка генерации: прерывает C-ядро и отменяет сбор потока. */
fun stopGeneration() {
    AppGraph.engine.stop()
    AppGraph.generationJob?.cancel()
}
