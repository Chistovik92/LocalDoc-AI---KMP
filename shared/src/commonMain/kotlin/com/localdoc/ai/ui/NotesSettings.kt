package com.localdoc.ai.ui

import com.localdoc.ai.resources.app_version
import com.localdoc.ai.resources.backup_from_books
import com.localdoc.ai.resources.backup_saved
import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.could_not_create_the_backup
import com.localdoc.ai.resources.could_not_read_the_file
import com.localdoc.ai.resources.create_backup
import com.localdoc.ai.resources.delete_word
import com.localdoc.ai.resources.file_saved
import com.localdoc.ai.resources.my_dictionary_md
import com.localdoc.ai.resources.no_quotes_yet
import com.localdoc.ai.resources.replace
import com.localdoc.ai.resources.restore_from_file
import com.localdoc.ai.resources.restore_from_the_backup
import com.localdoc.ai.resources.restored_books
import com.localdoc.ai.resources.select_a_word_in_the_reader
import com.localdoc.ai.resources.select_text_in_the_reader_and
import com.localdoc.ai.resources.the_backup_contains_the_library_with
import com.localdoc.ai.resources.the_current_library_bookmarks_notes_and
import com.localdoc.ai.resources.the_dictionary_is_empty
import com.localdoc.ai.resources.to_epub
import com.localdoc.ai.resources.to_markdown
import com.localdoc.ai.resources.words
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.unknown_error_lower
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
import com.localdoc.ai.platform.IoDispatcher
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
    val scope = rememberCoroutineScope()
    val all by AppGraph.annotations.all.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Annotation?>(null) }
    if (all.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Edit,
            title = stringResource(Res.string.no_quotes_yet),
            text = stringResource(Res.string.select_text_in_the_reader_and)
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
                        OutlinedButton(onClick = { exportBookNotes(scope, entryId, title, AppGraph.library.entry(entryId)?.author.orEmpty(), false) { message = it } }) {
                            Text(stringResource(Res.string.to_markdown), maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = { exportBookNotes(scope, entryId, title, AppGraph.library.entry(entryId)?.author.orEmpty(), true) { message = it } }) {
                            Text(stringResource(Res.string.to_epub), maxLines = 1, softWrap = false)
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
            initialColor = annotation.color,
            onDismiss = { edit = null },
            onSave = { text, color ->
                AppGraph.annotations.updateNote(annotation.id, text)
                if (color != annotation.color) AppGraph.annotations.updateColor(annotation.id, color)
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
            title = stringResource(Res.string.the_dictionary_is_empty),
            text = stringResource(Res.string.select_a_word_in_the_reader)
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "export") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val dictionaryFileName = stringResource(Res.string.my_dictionary_md)
                val fileSavedText = stringResource(Res.string.file_saved)
                OutlinedButton(onClick = {
                    val bytes = AnnotationExport.dictionaryMarkdown(words).encodeToByteArray()
                    AppGraph.fileSaver.save(dictionaryFileName, "text/markdown", bytes) { result ->
                        message = when (result) {
                            is SaveResult.Saved -> fileSavedText
                            SaveResult.Cancelled -> null
                            is SaveResult.Failed -> result.message
                        }
                    }
                }) { Text(stringResource(Res.string.to_markdown), maxLines = 1, softWrap = false) }
                Text(message ?: stringResource(Res.string.words, words.size), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
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
                trailingContent = { IconButton(onClick = { AppGraph.dictionary.remove(word.id) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.delete_word)) } },
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
    val backupSavedText = stringResource(Res.string.backup_saved)
    var pending by remember { mutableStateOf<Pair<ByteArray, BackupCheck.Ok>?>(null) }

    Text(
        stringResource(Res.string.the_backup_contains_the_library_with),
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
                    val bytes = withContext(IoDispatcher) { AppGraph.backup.create() }
                    AppGraph.fileSaver.save(BackupFormat.suggestedName(nowMillis()), BackupFormat.MIME, bytes) { result ->
                        busy = false
                        message = when (result) {
                            is SaveResult.Saved -> backupSavedText
                            SaveResult.Cancelled -> null
                            is SaveResult.Failed -> result.message
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    busy = false
                    message = getString(Res.string.could_not_create_the_backup, e.message ?: getString(Res.string.unknown_error_lower))
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(Res.string.create_backup)) }
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
                        when (val check = withContext(IoDispatcher) { AppGraph.backup.inspect(bytes) }) {
                            is BackupCheck.Ok -> pending = bytes to check
                            is BackupCheck.Invalid -> message = check.reason
                            is BackupCheck.TooNew -> message = AppGraph.backup.tooNewMessage(check)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = getString(Res.string.could_not_read_the_file, e.message ?: getString(Res.string.unknown_error_lower))
                    } finally {
                        busy = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(Res.string.restore_from_file)) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }

    pending?.let { (bytes, check) ->
        val manifest = check.manifest
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(Res.string.restore_from_the_backup)) },
            text = {
                Text(
                    stringResource(Res.string.backup_from_books, BackupFormat.isoDate(manifest.createdAt), manifest.books) +
                        manifest.appVersion.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.app_version, it) }.orEmpty() +
                        stringResource(Res.string.the_current_library_bookmarks_notes_and)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val result = withContext(IoDispatcher) { AppGraph.restoreBackup(bytes) }
                        busy = false
                        message = when (result) {
                            is RestoreResult.Restored -> getString(Res.string.restored_books, result.manifest.books)
                            is RestoreResult.Failed -> result.message
                        }
                    }
                }) { Text(stringResource(Res.string.replace)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(Res.string.cancel)) } }
        )
    }
}
