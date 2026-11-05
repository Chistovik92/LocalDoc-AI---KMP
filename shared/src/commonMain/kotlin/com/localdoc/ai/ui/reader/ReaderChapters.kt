package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.apply
import com.localdoc.ai.resources.chapters_and_table_of_contents
import com.localdoc.ai.resources.chapters_found
import com.localdoc.ai.resources.chapters_in_the_table_of_contents
import com.localdoc.ai.resources.choose_a_model
import com.localdoc.ai.resources.come_up_with_chapter_titles_slower
import com.localdoc.ai.resources.comma_separated_chapter_and_part_are
import com.localdoc.ai.resources.could_not_split_the_book
import com.localdoc.ai.resources.find_chapters_by_these_words
import com.localdoc.ai.resources.from_paragraph_of
import com.localdoc.ai.resources.nothing_found_for_these_words
import com.localdoc.ai.resources.remove_from_the_table_of_contents
import com.localdoc.ai.resources.save_as
import com.localdoc.ai.resources.split_into_chapters_with_ai
import com.localdoc.ai.resources.stop
import com.localdoc.ai.resources.stopped
import com.localdoc.ai.resources.table_of_contents_saved
import com.localdoc.ai.resources.to_get_a_file_with_the
import com.localdoc.ai.resources.to_split_a_book_without_headings
import com.localdoc.ai.resources.words_that_chapters_start_with
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
    val nothingFoundText = stringResource(Res.string.nothing_found_for_these_words)
    val chaptersFoundFormat = stringResource(Res.string.chapters_found, "%1\$s")
    val contentsSavedText = stringResource(Res.string.table_of_contents_saved)

    fun findByWords() {
        AppGraph.readerSettings.update { it.copy(chapterWords = words.trim()) }
        val found = ChapterDetector.detect(book.blocks, ChapterKeywords.parse(words))
        draft = found
        message = if (found.isEmpty()) nothingFoundText else chaptersFoundFormat.replace("%1\$s", found.size.toString())
    }

    fun splitWithModel() {
        job?.cancel()
        job = scope.launch {
            progress = 0f
            message = null
            try {
                val planner = ChapterPlanner(AppGraph.chapterModel, ChapterPlanner.Settings(titles = withTitles))
                draft = planner.plan(book.blocks, draft) { progress = it }
                message = getString(Res.string.chapters_in_the_table_of_contents, draft.size)
            } catch (e: CancellationException) {
                message = getString(Res.string.stopped)
                throw e
            } catch (e: Exception) {
                message = e.message ?: getString(Res.string.could_not_split_the_book)
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
            Text(stringResource(Res.string.chapters_and_table_of_contents), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text(stringResource(Res.string.words_that_chapters_start_with)) },
                supportingText = { Text(stringResource(Res.string.comma_separated_chapter_and_part_are)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = ::findByWords, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.find_chapters_by_these_words))
            }

            if (loadedModel == null) {
                Text(
                    stringResource(Res.string.to_split_a_book_without_headings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenModels) { Text(stringResource(Res.string.choose_a_model)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = withTitles, onCheckedChange = { withTitles = it }, enabled = !busy)
                    Text(stringResource(Res.string.come_up_with_chapter_titles_slower), style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.stop)) }
                } else {
                    Button(onClick = ::splitWithModel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.split_into_chapters_with_ai)) }
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (draft.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(draft) { _, chapter ->
                        ListItem(
                            headlineContent = { Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(Res.string.from_paragraph_of, chapter.startBlock + 1, book.blocks.size)) },
                            trailingContent = {
                                IconButton(onClick = { draft = draft - chapter }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.remove_from_the_table_of_contents))
                                }
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onApply(draft); message = contentsSavedText }, enabled = changed && !busy) { Text(stringResource(Res.string.apply)) }
                if (canSaveAs && book.chapters.isNotEmpty() && !changed) {
                    OutlinedButton(onClick = onSaveAs) { Text(stringResource(Res.string.save_as)) }
                }
            }
            Text(
                stringResource(Res.string.to_get_a_file_with_the),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
