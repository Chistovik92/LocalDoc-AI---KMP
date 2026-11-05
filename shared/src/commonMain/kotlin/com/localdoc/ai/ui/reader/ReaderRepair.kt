package com.localdoc.ai.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.RepairChange
import com.localdoc.ai.book.RepairKind
import com.localdoc.ai.book.RepairResult
import com.localdoc.ai.book.TextRepair
import com.localdoc.ai.book.TextRepairPlanner
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.choose_a_model
import com.localdoc.ai.resources.fix_breaks_after
import com.localdoc.ai.resources.fix_breaks_ai_needs_model
import com.localdoc.ai.resources.fix_breaks_apply
import com.localdoc.ai.resources.fix_breaks_before
import com.localdoc.ai.resources.fix_breaks_check
import com.localdoc.ai.resources.fix_breaks_check_ai
import com.localdoc.ai.resources.fix_breaks_failed
import com.localdoc.ai.resources.fix_breaks_hint
import com.localdoc.ai.resources.fix_breaks_hyphens
import com.localdoc.ai.resources.fix_breaks_joined
import com.localdoc.ai.resources.fix_breaks_none
import com.localdoc.ai.resources.fix_breaks_removed
import com.localdoc.ai.resources.fix_breaks_spaces
import com.localdoc.ai.resources.fix_breaks_split
import com.localdoc.ai.resources.fix_breaks_title
import com.localdoc.ai.resources.stop
import com.localdoc.ai.resources.stopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val EXAMPLES = 12

/**
 * «Исправить переносы и абзацы»: программные правила находят оборванные строки, переносы слов, слипшиеся абзацы
 * и колонтитулы; спорные места можно проверить локальной моделью. Перед применением показаны число правок и примеры
 * «было / стало»; применённый текст сохраняется в библиотеке без потери прогресса, закладок и цитат.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepairSheet(
    book: Book,
    onApply: (RepairResult) -> Unit,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit
) {
    val loadedModel by AppGraph.loadedModelId.collectAsState()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<RepairResult?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = progress != null

    fun search(useModel: Boolean) {
        job?.cancel()
        job = scope.launch {
            progress = 0f
            message = null
            try {
                result = withContext(Dispatchers.Default) {
                    val starts = book.chapters.map { it.startBlock }.toSet()
                    val pdf = book.format == BookFormat.PDF
                    if (useModel) TextRepairPlanner(AppGraph.chapterModel).plan(book.blocks, starts, pdf) { progress = it }
                    else TextRepair.repair(book.blocks, keep = starts, breakBefore = if (pdf) starts else emptySet())
                }
            } catch (e: CancellationException) {
                message = getString(Res.string.stopped)
                throw e
            } catch (e: Exception) {
                message = e.message ?: getString(Res.string.fix_breaks_failed)
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
            Text(stringResource(Res.string.fix_breaks_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(Res.string.fix_breaks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (busy) {
                LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.stop)) }
            } else {
                OutlinedButton(onClick = { search(useModel = false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.fix_breaks_check))
                }
                if (loadedModel == null) {
                    Text(stringResource(Res.string.fix_breaks_ai_needs_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenModels) { Text(stringResource(Res.string.choose_a_model)) }
                } else {
                    OutlinedButton(onClick = { search(useModel = true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.fix_breaks_check_ai))
                    }
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            result?.let { found ->
                if (found.changes.isEmpty()) Text(stringResource(Res.string.fix_breaks_none), style = MaterialTheme.typography.bodyMedium)
                else RepairPreview(found)
            }

            Button(
                onClick = { result?.let(onApply) },
                enabled = result?.changes?.isNotEmpty() == true && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(Res.string.fix_breaks_apply)) }
        }
    }
}

@Composable
private fun RepairPreview(found: RepairResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val joined = found.count(RepairKind.JOIN)
        val hyphens = found.count(RepairKind.HYPHEN)
        val split = found.count(RepairKind.SPLIT)
        val removed = found.count(RepairKind.REMOVE)
        val spaces = found.count(RepairKind.SPACES)
        if (joined > 0) Text(stringResource(Res.string.fix_breaks_joined, joined), style = MaterialTheme.typography.bodyMedium)
        if (hyphens > 0) Text(stringResource(Res.string.fix_breaks_hyphens, hyphens), style = MaterialTheme.typography.bodyMedium)
        if (split > 0) Text(stringResource(Res.string.fix_breaks_split, split), style = MaterialTheme.typography.bodyMedium)
        if (removed > 0) Text(stringResource(Res.string.fix_breaks_removed, removed), style = MaterialTheme.typography.bodyMedium)
        if (spaces > 0) Text(stringResource(Res.string.fix_breaks_spaces, spaces), style = MaterialTheme.typography.bodyMedium)
    }
    LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(found.changes.take(EXAMPLES)) { change -> RepairExample(change) }
    }
}

@Composable
private fun RepairExample(change: RepairChange) {
    Column {
        Text(
            stringResource(Res.string.fix_breaks_before) + ": " + change.before,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(Res.string.fix_breaks_after) + ": " + change.after.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
