package com.localdoc.ai.ui

import com.localdoc.ai.resources.add
import com.localdoc.ai.resources.add_a_catalog
import com.localdoc.ai.resources.added_to_the_library_2
import com.localdoc.ai.resources.address_for_example_http_192_168
import com.localdoc.ai.resources.an_http_or_https_address_is
import com.localdoc.ai.resources.back
import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.could_not_load_the_catalog
import com.localdoc.ai.resources.download_2
import com.localdoc.ai.resources.downloading
import com.localdoc.ai.resources.loading_2
import com.localdoc.ai.resources.name
import com.localdoc.ai.resources.new_opds_catalog
import com.localdoc.ai.resources.nothing_here_yet
import com.localdoc.ai.resources.opds_catalogs_open_libraries_project_gutenbe
import com.localdoc.ai.resources.remove
import com.localdoc.ai.resources.search
import com.localdoc.ai.resources.search_the_catalog
import com.localdoc.ai.resources.show_more
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.opds.OpdsCatalog
import com.localdoc.ai.opds.OpdsEntry
import com.localdoc.ai.opds.OpdsFeed
import com.localdoc.ai.platform.PlatformBackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Раздел «Онлайн-каталоги»: каталоги OPDS (открытые библиотеки и свои серверы вроде Calibre) — переход по разделам,
 * поиск и скачивание книги в библиотеку. Запросы идут через общую очередь запросов к сайту, поэтому листаются не мгновенно.
 */
@Composable
fun OpdsSection() {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<OpdsCatalog?>(null) }
    var stack by remember { mutableStateOf<List<OpdsFeed>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val loadFailedText = stringResource(Res.string.could_not_load_the_catalog)

    fun fail(e: Exception) {
        error = e.message ?: loadFailedText
    }

    fun load(url: String, replace: Boolean = false) {
        loading = true
        error = null
        scope.launch {
            try {
                val feed = AppGraph.opds.load(url)
                stack = if (replace) listOf(feed) else stack + feed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun search() {
        val feed = stack.firstOrNull() ?: return
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            try {
                stack = stack.take(1) + AppGraph.opds.search(feed, query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun more(feed: OpdsFeed) {
        val next = feed.nextUrl ?: return
        loading = true
        scope.launch {
            try {
                val page = AppGraph.opds.load(next)
                val merged = OpdsFeed(feed.title, feed.entries + page.entries, page.nextUrl, feed.startUrl, feed.search)
                stack = stack.dropLast(1) + merged
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun download(entry: OpdsEntry) {
        val best = entry.bestDownload() ?: return
        downloading = entry.id
        message = null
        error = null
        scope.launch {
            try {
                val file = AppGraph.opds.download(best, entry.title)
                AppGraph.importDownloaded(file, best.url)
                message = getString(Res.string.added_to_the_library_2, entry.title, best.label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                downloading = null
            }
        }
    }

    fun back() {
        error = null
        message = null
        if (stack.size > 1) stack = stack.dropLast(1) else { stack = emptyList(); catalog = null }
    }

    val open = catalog
    PlatformBackHandler(enabled = open != null) { back() }
    if (open == null) {
        CatalogList(onOpen = { c -> catalog = c; load(c.url, replace = true) })
        return
    }
    val feed = stack.lastOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text(stringResource(Res.string.back), maxLines = 1, softWrap = false) }
            Text(feed?.title?.ifBlank { open.title } ?: open.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (stack.firstOrNull()?.search != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.search_the_catalog)) },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { search() }, enabled = !loading && query.isNotBlank()) { Text(stringResource(Res.string.search), maxLines = 1, softWrap = false) }
            }
        }
        if (loading) Text(stringResource(Res.string.loading_2), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (feed != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(feed.entries) { entry ->
                    EntryRow(entry, downloading == entry.id, onOpen = { entry.navigationUrl?.let { load(it) } }, onDownload = { download(entry) })
                }
                if (feed.entries.isEmpty() && !loading) item { Text(stringResource(Res.string.nothing_here_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (feed.nextUrl != null) item {
                    OutlinedButton(onClick = { more(feed) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.show_more), maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun CatalogList(onOpen: (OpdsCatalog) -> Unit) {
    val catalogs by AppGraph.opdsCatalogs.all.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(Res.string.opds_catalogs_open_libraries_project_gutenbe),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(catalogs) { c ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(c) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!c.builtin) TextButton(onClick = { AppGraph.opdsCatalogs.remove(c.id) }) { Text(stringResource(Res.string.remove), maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.add_a_catalog), maxLines = 1, softWrap = false) }
    }
    if (adding) AddCatalogDialog(onDismiss = { adding = false })
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_opds_catalog)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text(stringResource(Res.string.name)) })
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; wrong = false },
                    singleLine = true,
                    isError = wrong,
                    label = { Text(stringResource(Res.string.address_for_example_http_192_168)) }
                )
                if (wrong) Text(stringResource(Res.string.an_http_or_https_address_is), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (AppGraph.opdsCatalogs.add(title, url)) onDismiss() else wrong = true }) { Text(stringResource(Res.string.add), maxLines = 1, softWrap = false) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel), maxLines = 1, softWrap = false) } }
    )
}

@Composable
private fun EntryRow(entry: OpdsEntry, busy: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().then(if (entry.navigationUrl != null) Modifier.clickable(onClick = onOpen) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.authors.isNotEmpty()) {
                    Text(entry.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.summary.isNotBlank()) {
                    Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            val best = entry.bestDownload()
            if (best != null) {
                Button(onClick = onDownload, enabled = !busy) { Text(if (busy) stringResource(Res.string.downloading) else stringResource(Res.string.download_2, best.label), maxLines = 1, softWrap = false) }
            }
        }
    }
}
