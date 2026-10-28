package com.localdoc.ai.ui

import com.localdoc.ai.resources.cancel_2
import com.localdoc.ai.resources.checking_file_integrity
import com.localdoc.ai.resources.delete_the_model
import com.localdoc.ai.resources.download_mb
import com.localdoc.ai.resources.error
import com.localdoc.ai.resources.of_mb_2
import com.localdoc.ai.resources.repeat
import com.localdoc.ai.resources.smart_book_search
import com.localdoc.ai.resources.smart_search_is_on
import com.localdoc.ai.resources.starting_the_model_and_measuring_speed
import com.localdoc.ai.resources.the_model_is_downloaded_but_turned
import com.localdoc.ai.resources.the_model_was_turned_off_automatically
import com.localdoc.ai.resources.the_multilingual_e5_small_model_finds
import com.localdoc.ai.resources.use_the_model
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
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
            Text(stringResource(Res.string.smart_book_search), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.the_multilingual_e5_small_model_finds, sizeMb),
                style = MaterialTheme.typography.bodySmall
            )
            when (val s = state) {
                SemanticModelState.NotInstalled -> Button(onClick = { manager.download() }) { Text(stringResource(Res.string.download_mb, sizeMb)) }
                is SemanticModelState.Downloading -> {
                    val fraction = if (s.totalBytes > 0) s.downloadedBytes.toFloat() / s.totalBytes else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.of_mb_2, s.downloadedBytes / (1024 * 1024), s.totalBytes / (1024 * 1024)), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { manager.cancel() }) { Text(stringResource(Res.string.cancel_2)) }
                }
                SemanticModelState.Verifying -> Text(stringResource(Res.string.checking_file_integrity))
                SemanticModelState.Loading -> Text(stringResource(Res.string.starting_the_model_and_measuring_speed))
                is SemanticModelState.Unsupported -> Text(s.reason, style = MaterialTheme.typography.bodySmall)
                is SemanticModelState.Rejected ->
                    Installed(stringResource(Res.string.the_model_was_turned_off_automatically, s.reason), enabled, onEnabled, onDelete)
                is SemanticModelState.Failed -> {
                    Text(stringResource(Res.string.error, s.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { manager.download() }) { Text(stringResource(Res.string.repeat)) }
                }
                SemanticModelState.Ready -> Installed(stringResource(Res.string.smart_search_is_on), enabled, onEnabled, onDelete)
                SemanticModelState.Disabled -> Installed(stringResource(Res.string.the_model_is_downloaded_but_turned), enabled, onEnabled, onDelete)
            }
        }
    }
}

@Composable
private fun Installed(message: String, enabled: Boolean, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    Text(message, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = enabled, onCheckedChange = onEnabled)
        Text(stringResource(Res.string.use_the_model))
    }
    OutlinedButton(onClick = onDelete) { Text(stringResource(Res.string.delete_the_model)) }
}
