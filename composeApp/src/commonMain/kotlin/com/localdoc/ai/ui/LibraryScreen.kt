package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.localdoc.ai.domain.QuickAction
import com.localdoc.ai.platform.FilePicker
import com.localdoc.ai.parser.ParsedDoc
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(onOpenChat: () -> Unit) {
    val scope = rememberCoroutineScope()
    val doc by AppGraph.currentDoc.collectAsState()
    val isGenerating by AppGraph.isGenerating.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val filePicker = remember { FilePicker() }

    fun setDoc(newDoc: ParsedDoc?) {
        AppGraph.currentDoc.value = newDoc
        processing = null
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Документы и источники",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Перехватывайте страницы из браузера кнопкой «Поделиться», открывайте PDF и текстовые файлы.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { showUrlDialog = true }, modifier = Modifier.weight(1f)) {
                Text("Открыть ссылку")
            }
            OutlinedButton(
                onClick = {
                    filePicker.pick("application/pdf") { uri ->
                        if (uri != null) {
                            scope.launch {
                                processing = "PDF"
                                try {
                                    setDoc(AppGraph.pdfExtractor.extract(uri))
                                } catch (t: Throwable) {
                                    error = "Не удалось прочитать PDF: ${t.message}"
                                    processing = null
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Выбрать PDF") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val currentDoc = doc
        if (currentDoc != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(currentDoc.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Символов: ${currentDoc.content.length} · ~токенов: ${currentDoc.approxTokens}" +
                            (if (currentDoc.pageCount > 1) " · страниц: ${currentDoc.pageCount}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "Источник: ${currentDoc.source.ifBlank { "локальный файл" }}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    TextButton(onClick = onOpenChat) { Text("Открыть чат по документу →") }
                }
            }

            Text(
                "Быстрые действия",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QuickAction.entries.forEach { action ->
                    OutlinedButton(
                        enabled = !isGenerating,
                        onClick = {
                            val d = AppGraph.currentDoc.value ?: return@OutlinedButton
                            onOpenChat()
                            val flow = if (
                                d.content.length > 12_000 &&
                                (action == QuickAction.SUMMARY || action == QuickAction.EXPLAIN)
                            ) {
                                AppGraph.pipeline.summarizeLongDoc(d)
                            } else {
                                AppGraph.pipeline.ask(d, action.prompt)
                            }
                            launchGeneration(scope, flow)
                        }
                    ) { Text(action.label) }
                }
            }

            Text(
                "Озвучивание (локальный TTS)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val text = currentDoc.content.take(4000)
                    AppGraph.speaker.speak(text)
                }) { Text("Читать документ") }
                OutlinedButton(onClick = { AppGraph.speaker.stop() }) { Text("Стоп") }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Документ не открыт", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Отправьте ссылку или PDF в LocalDoc AI из браузера / проводника, " +
                            "либо откройте ссылку или файл кнопками выше.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    UrlInputDialog(
        visible = showUrlDialog,
        onDismiss = { showUrlDialog = false },
        onConfirm = { url ->
            showUrlDialog = false
            scope.launch {
                processing = url
                try {
                    setDoc(AppGraph.webParser.parseUrl(url))
                } catch (t: Throwable) {
                    error = "Не удалось загрузить страницу: ${t.message}"
                    processing = null
                }
            }
        }
    )

    if (processing != null) {
        // Полноэкранное перекрытие процесса извлечения текста
        ProcessingOverlay(processing)
    }
    }
}
