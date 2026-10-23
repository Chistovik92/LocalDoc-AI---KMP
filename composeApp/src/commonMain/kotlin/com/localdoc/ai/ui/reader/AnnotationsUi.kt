package com.localdoc.ai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.notes.AnnotationExport
import com.localdoc.ai.notes.BookNotes
import com.localdoc.ai.platform.SaveResult

/** Список цитат и заметок: нажатие — переход к месту, значки — правка заметки и удаление. */
@Composable
internal fun AnnotationList(
    annotations: List<Annotation>,
    showBookTitle: Boolean,
    onJump: (Annotation) -> Unit,
    onEdit: (Annotation) -> Unit,
    onDelete: (Annotation) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(annotations, key = { it.id }) { item ->
            ListItem(
                overlineContent = (if (showBookTitle) item.bookTitle else item.chapter).takeIf { it.isNotBlank() }
                    ?.let { text -> ({ Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                headlineContent = { Text(item.quote.replace('\n', ' '), maxLines = 4, overflow = TextOverflow.Ellipsis) },
                supportingContent = item.note.takeIf { it.isNotBlank() }
                    ?.let { note -> ({ Text(note, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) }) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onEdit(item) }) { Icon(Icons.Default.Edit, contentDescription = "Изменить заметку") }
                        IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить цитату") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable { onJump(item) }
            )
        }
    }
}

/** Цитаты и заметки одной книги с выгрузкой в Markdown и EPUB. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationsSheet(
    annotations: List<Annotation>,
    onJump: (Annotation) -> Unit,
    onEdit: (Annotation) -> Unit,
    onDelete: (Annotation) -> Unit,
    onExport: (epub: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Цитаты и заметки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (annotations.isEmpty()) {
            Text(
                "Выделите текст в читалке — появятся действия «Цитата» и «Заметка».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                OutlinedButton(onClick = { onExport(false) }) { Text("В Markdown", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { onExport(true) }) { Text("В EPUB", maxLines = 1, softWrap = false) }
            }
            AnnotationList(annotations, showBookTitle = false, onJump = onJump, onEdit = onEdit, onDelete = onDelete, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * Сохраняет цитаты, заметки, закладки и слова книги в Markdown или EPUB через «Сохранить как…».
 * [onMessage] получает итог для показа пользователю (null — пользователь отменил сохранение).
 */
internal fun exportBookNotes(entryId: String, title: String, author: String, epub: Boolean, onMessage: (String?) -> Unit) {
    val notes = BookNotes(
        title = title,
        author = author,
        annotations = AppGraph.annotations.forEntry(entryId),
        bookmarks = AppGraph.bookmarks.forEntry(entryId),
        words = AppGraph.dictionary.all.value.filter { it.entryId == entryId }
    )
    if (notes.isEmpty) {
        onMessage("В этой книге пока нет цитат, заметок и закладок")
        return
    }
    val extension = if (epub) "epub" else "md"
    val bytes = try {
        if (epub) AnnotationExport.epub(notes) else AnnotationExport.markdown(notes).encodeToByteArray()
    } catch (e: Exception) {
        onMessage("Не удалось подготовить файл: ${e.message ?: "ошибка"}")
        return
    }
    val mime = if (epub) "application/epub+zip" else "text/markdown"
    AppGraph.fileSaver.save(AnnotationExport.fileName(notes, extension), mime, bytes) { result ->
        onMessage(
            when (result) {
                is SaveResult.Saved -> "Файл сохранён"
                SaveResult.Cancelled -> null
                is SaveResult.Failed -> result.message
            }
        )
    }
}
