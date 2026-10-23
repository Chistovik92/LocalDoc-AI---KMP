package com.localdoc.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.backup.BackupCheck
import com.localdoc.ai.backup.BackupFormat
import com.localdoc.ai.backup.RestoreResult
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.net.nowMillis
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.notes.AnnotationExport
import com.localdoc.ai.platform.FilePicker
import com.localdoc.ai.platform.SaveResult
import com.localdoc.ai.ui.reader.AnnotationRow
import com.localdoc.ai.ui.reader.NoteDialog
import com.localdoc.ai.ui.reader.exportBookNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел «Цитаты и заметки»: все книги, выгрузка в Markdown / EPUB, переход к месту. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesSection(onOpen: (Annotation) -> Unit) {
    val all by AppGraph.annotations.all.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Annotation?>(null) }
    if (all.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Edit,
            title = "Цитат пока нет",
            text = "Выделите текст в читалке и выберите «Цитата» или «Заметка» — они появятся здесь и в выгрузке для заметочников."
        )
        return
    }
    val byBook = all.groupBy { it.entryId }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        message?.let { text -> item(key = "message") { Text(text, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
        byBook.forEach { (entryId, list) ->
            val title = list.first().bookTitle
            item(key = "book:$entryId") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportBookNotes(entryId, title, AppGraph.library.entry(entryId)?.author.orEmpty(), false) { message = it } }) {
                            Text("В Markdown", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = { exportBookNotes(entryId, title, AppGraph.library.entry(entryId)?.author.orEmpty(), true) { message = it } }) {
                            Text("В EPUB", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            items(list.sortedWith(compareBy({ it.anchor.block }, { it.anchor.start })), key = { it.id }) { item ->
                AnnotationRow(item, showBookTitle = false, onJump = onOpen, onEdit = { edit = it }, onDelete = { AppGraph.annotations.remove(it.id) })
            }
        }
    }
    edit?.let { annotation ->
        NoteDialog(
            quote = annotation.quote,
            initial = annotation.note,
            onDismiss = { edit = null },
            onSave = { text ->
                AppGraph.annotations.updateNote(annotation.id, text)
                edit = null
            }
        )
    }
}

/** Раздел «Словарь»: личный список слов с переводом и предложением из книги. */
@Composable
fun DictionarySection() {
    val words by AppGraph.dictionary.all.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    if (words.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Translate,
            title = "Словарь пуст",
            text = "Выделите слово в читалке и нажмите «Слово»: модель переведёт его в контексте, а вы сохраните в личный список."
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "export") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = {
                    val bytes = AnnotationExport.dictionaryMarkdown(words).encodeToByteArray()
                    AppGraph.fileSaver.save("Мой словарь.md", "text/markdown", bytes) { result ->
                        message = when (result) {
                            is SaveResult.Saved -> "Файл сохранён"
                            SaveResult.Cancelled -> null
                            is SaveResult.Failed -> result.message
                        }
                    }
                }) { Text("В Markdown", maxLines = 1, softWrap = false) }
                Text(message ?: "Слов: ${words.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
        items(words, key = { it.id }) { word ->
            ListItem(
                overlineContent = word.bookTitle.takeIf { it.isNotBlank() }?.let { t -> ({ Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                headlineContent = { Text(word.word, style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Column {
                        Text(word.meaning, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (word.context.isNotBlank()) {
                            Text(word.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                trailingContent = { IconButton(onClick = { AppGraph.dictionary.remove(word.id) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить слово") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    }
}

/** Раздел «Резервная копия»: библиотека и настройки в одном файле; пароли сайтов не входят. */
@Composable
fun BackupSection() {
    val scope = rememberCoroutineScope()
    val picker = remember { FilePicker() }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<ByteArray, BackupCheck.Ok>?>(null) }

    Text(
        "Копия содержит библиотеку с прогрессом чтения, закладки, цитаты и заметки, словарь и настройки читалки, озвучки и перевода — " +
            "одним файлом. Пароли и сессии сайтов, скачанные модели и голоса в копию не попадают.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            message = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.Default) { AppGraph.backup.create() }
                    AppGraph.fileSaver.save(BackupFormat.suggestedName(nowMillis()), BackupFormat.MIME, bytes) { result ->
                        busy = false
                        message = when (result) {
                            is SaveResult.Saved -> "Копия сохранена"
                            SaveResult.Cancelled -> null
                            is SaveResult.Failed -> result.message
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    busy = false
                    message = "Не удалось создать копию: ${e.message ?: "ошибка"}"
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Создать копию…") }
    OutlinedButton(
        enabled = !busy,
        onClick = {
            message = null
            picker.pick("*/*") { uri ->
                if (uri == null) return@pick
                busy = true
                scope.launch {
                    try {
                        val bytes = AppGraph.files.readBytes(uri)
                        when (val check = withContext(Dispatchers.Default) { AppGraph.backup.inspect(bytes) }) {
                            is BackupCheck.Ok -> pending = bytes to check
                            is BackupCheck.Invalid -> message = check.reason
                            is BackupCheck.TooNew -> message = AppGraph.backup.tooNewMessage(check)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Не удалось прочитать файл: ${e.message ?: "ошибка"}"
                    } finally {
                        busy = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Восстановить из файла…") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }

    pending?.let { (bytes, check) ->
        val manifest = check.manifest
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Восстановить из копии?") },
            text = {
                Text(
                    "Копия от ${BackupFormat.isoDate(manifest.createdAt)}: книг — ${manifest.books}" +
                        manifest.appVersion.takeIf { it.isNotBlank() }?.let { ", версия приложения $it" }.orEmpty() +
                        ".\n\nТекущая библиотека, закладки, заметки и настройки будут заменены данными из копии."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { AppGraph.restoreBackup(bytes) }
                        busy = false
                        message = when (result) {
                            is RestoreResult.Restored -> "Восстановлено: книг — ${result.manifest.books}"
                            is RestoreResult.Failed -> result.message
                        }
                    }
                }) { Text("Заменить") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } }
        )
    }
}
