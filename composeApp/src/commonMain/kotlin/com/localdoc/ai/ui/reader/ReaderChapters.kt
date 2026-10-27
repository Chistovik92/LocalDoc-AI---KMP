package com.localdoc.ai.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.Chapter
import com.localdoc.ai.book.ChapterDetector
import com.localdoc.ai.book.ChapterKeywords
import com.localdoc.ai.book.ChapterPlanner
import com.localdoc.ai.domain.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * «Главы и оглавление»: найти начала глав по словам («Глава», «Часть», «Пролог»…) или разбить книгу нейросетью,
 * поправить список и применить. Применённое оглавление сохраняется в библиотеке, а «Сохранить как…» записывает
 * его в файл (EPUB, FB2): так книгу без оглавления можно пересохранить уже с оглавлением.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChaptersSheet(
    book: Book,
    canSaveAs: Boolean,
    onApply: (List<Chapter>) -> Unit,
    onSaveAs: () -> Unit,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit
) {
    val settings by AppGraph.readerSettings.value.collectAsState()
    val loadedModel by AppGraph.loadedModelId.collectAsState()
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf(settings.chapterWords.ifBlank { ChapterKeywords.DEFAULT.toText() }) }
    var draft by remember { mutableStateOf(book.chapters) }
    var withTitles by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val changed = draft != book.chapters
    val busy = progress != null

    fun findByWords() {
        AppGraph.readerSettings.update { it.copy(chapterWords = words.trim()) }
        val found = ChapterDetector.detect(book.blocks, ChapterKeywords.parse(words))
        draft = found
        message = if (found.isEmpty()) "По этим словам ничего не найдено" else "Найдено глав: ${found.size}"
    }

    fun splitWithModel() {
        job?.cancel()
        job = scope.launch {
            progress = 0f
            message = null
            try {
                val planner = ChapterPlanner(AppGraph.chapterModel, ChapterPlanner.Settings(titles = withTitles))
                draft = planner.plan(book.blocks, draft) { progress = it }
                message = "Глав в оглавлении: ${draft.size}"
            } catch (e: CancellationException) {
                message = "Остановлено"
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Не удалось разбить книгу"
            } finally {
                progress = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Главы и оглавление", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text("Слова, с которых начинаются главы") },
                supportingText = { Text("Через запятую. «Глава», «Часть» ищутся с номером, «Пролог», «Эпилог» — сами по себе.") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = ::findByWords, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Найти главы по этим словам")
            }

            if (loadedModel == null) {
                Text(
                    "Чтобы разбить книгу без заголовков на главы, загрузите модель.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenModels) { Text("Выбрать модель") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = withTitles, onCheckedChange = { withTitles = it }, enabled = !busy)
                    Text("Придумать названия глав (дольше)", style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                } else {
                    Button(onClick = ::splitWithModel, modifier = Modifier.fillMaxWidth()) { Text("Разбить на главы нейросетью") }
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (draft.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(draft) { _, chapter ->
                        ListItem(
                            headlineContent = { Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("с абзаца ${chapter.startBlock + 1} из ${book.blocks.size}") },
                            trailingContent = {
                                IconButton(onClick = { draft = draft - chapter }) {
                                    Icon(Icons.Default.Close, contentDescription = "Убрать из оглавления")
                                }
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onApply(draft); message = "Оглавление сохранено" }, enabled = changed && !busy) { Text("Применить") }
                if (canSaveAs && book.chapters.isNotEmpty() && !changed) {
                    OutlinedButton(onClick = onSaveAs) { Text("Сохранить как…") }
                }
            }
            Text(
                "Чтобы получить файл с оглавлением: примените главы и выберите «Сохранить как…» — в EPUB и FB2 оглавление запишется в файл.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
