@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.export.ExportFormat
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.DownloadPhase

/**
 * «Сохранить как…» / «Скачать книгу целиком…»: выбор формата. Для веб-страниц можно скачать всё
 * произведение (приложение само запросит оглавление и загрузит главы) или только открытую страницу.
 */
@Composable
fun SaveAsDialog(entry: BookEntry, onDismiss: () -> Unit) {
    val isWeb = entry.format == BookFormat.WEB
    var format by remember { mutableStateOf(ExportFormat.EPUB) }
    var whole by remember { mutableStateOf(isWeb) }
    var addToLibrary by remember { mutableStateOf(true) }
    val busy = AppGraph.downloads.busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isWeb) "Скачать книгу" else "Сохранить как…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isWeb) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = whole, onClick = { whole = true })
                        Text("Всё произведение: приложение само найдёт оглавление или готовый файл и соберёт книгу с главами", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !whole, onClick = { whole = false })
                        Text("Только эта страница", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Формат файла", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label, maxLines = 1, softWrap = false) })
                    }
                }
                if (isWeb && whole) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = addToLibrary, onCheckedChange = { addToLibrary = it })
                        Text("Добавить книгу в библиотеку", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (busy) {
                    Text("Сейчас уже идёт другая загрузка — дождитесь её завершения.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (isWeb && whole) AppGraph.downloads.downloadWhole(entry, format, addToLibrary) else AppGraph.downloads.convert(entry, format)
                    onDismiss()
                }
            ) { Text(if (isWeb && whole) "Скачать" else "Сохранить", maxLines = 1, softWrap = false) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", maxLines = 1, softWrap = false) } }
    )
}

/** Плашка текущей загрузки книги: этап, прогресс по главам, «Отмена»; по окончании — итог и «Закрыть». */
@Composable
fun DownloadBanner(modifier: Modifier = Modifier) {
    val job by AppGraph.downloads.job.collectAsState()
    val current = job ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = current.progress
            val phaseText = buildString {
                append(p.phase.label)
                if (p.total > 0) append(" · ${p.done} из ${p.total}")
                p.message?.takeIf { it.isNotBlank() && p.total == 0 }?.let { append(" · ").append(it) }
            }
            Text(phaseText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!current.finished) {
                if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { AppGraph.downloads.cancel() }) { Text("Отмена", maxLines = 1, softWrap = false) }
                }
            } else {
                current.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { AppGraph.downloads.dismiss() }) {
                        Text(if (p.phase == DownloadPhase.DONE) "Готово" else "Закрыть", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
