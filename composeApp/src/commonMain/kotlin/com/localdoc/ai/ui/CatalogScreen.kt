@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

import com.localdoc.ai.resources.add_to_collection
import com.localdoc.ai.resources.add_to_library
import com.localdoc.ai.resources.added_to_the_library
import com.localdoc.ai.resources.adding_books
import com.localdoc.ai.resources.adding_books_of
import com.localdoc.ai.resources.allow_all_files_access_in_the
import com.localdoc.ai.resources.and_txt_html_md_xml
import com.localdoc.ai.resources.back
import com.localdoc.ai.resources.books_on_this_device
import com.localdoc.ai.resources.cancel
import com.localdoc.ai.resources.clear
import com.localdoc.ai.resources.clear_selection
import com.localdoc.ai.resources.could_not_open_the_file
import com.localdoc.ai.resources.create
import com.localdoc.ai.resources.delete_collection
import com.localdoc.ai.resources.failed
import com.localdoc.ai.resources.files
import com.localdoc.ai.resources.find_books
import com.localdoc.ai.resources.folder_or_location
import com.localdoc.ai.resources.for_example_summer_reading
import com.localdoc.ai.resources.found
import com.localdoc.ai.resources.found_2
import com.localdoc.ai.resources.got_it
import com.localdoc.ai.resources.hide_added
import com.localdoc.ai.resources.in_library
import com.localdoc.ai.resources.new_collection
import com.localdoc.ai.resources.no_collections_yet_select_books_and
import com.localdoc.ai.resources.remove_from_the_catalog
import com.localdoc.ai.resources.scan_again
import com.localdoc.ai.resources.scan_the_device_or_pick_a
import com.localdoc.ai.resources.search_the_catalog
import com.localdoc.ai.resources.select_all
import com.localdoc.ai.resources.selected
import com.localdoc.ai.resources.sort
import com.localdoc.ai.resources.stop
import com.localdoc.ai.resources.the_app_will_scan_files_and
import com.localdoc.ai.resources.the_catalog_is_empty
import com.localdoc.ai.resources.whole_device
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import com.localdoc.ai.catalog.CatalogFilter
import com.localdoc.ai.catalog.CatalogItem
import com.localdoc.ai.catalog.CatalogQuery
import com.localdoc.ai.catalog.CatalogSort
import com.localdoc.ai.catalog.ShelfKind
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.PlatformBackHandler
import kotlinx.coroutines.launch

private const val DEVICE_ROOT = "device"

/**
 * «Книги на устройстве»: сканирование всего устройства, папки или подключённого ресурса и полки в стиле
 * интернет-магазинов — авторы, жанры, годы, серии, форматы, папки, языки и свои подборки; поиск, сортировка,
 * добавление в библиотеку по одной книге или всей выборкой.
 */
@Composable
fun CatalogScreen(onBack: () -> Unit, onOpenEntry: (com.localdoc.ai.book.BookEntry) -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    val scope = rememberCoroutineScope()
    val items by AppGraph.catalog.items.collectAsState()
    val roots by AppGraph.catalog.roots.collectAsState()
    val userShelves by AppGraph.catalog.shelves.collectAsState()
    val scan by AppGraph.catalog.state.collectAsState()
    val library by AppGraph.library.entries.collectAsState()

    var filter by remember { mutableStateOf(CatalogFilter()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var includeText by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newShelf by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }

    // «Добавлено» берётся из библиотеки: книгу могли удалить после добавления
    val libraryIds = library.map { it.id }.toSet()
    val shown = remember(items, filter, userShelves, libraryIds) {
        CatalogQuery.filter(items.map { if (it.libraryId != null && it.libraryId !in libraryIds) it.copy(libraryId = null) else it }, filter, userShelves)
    }
    val shelfValues = remember(items, filter.kind, userShelves) { CatalogQuery.shelf(items, filter.kind, userShelves) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back)) }
            Text(stringResource(Res.string.books_on_this_device), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item { ScanCard(roots = roots, scanRunning = scan.running, includeText = includeText, onIncludeText = { includeText = it }) }
            item {
                if (scan.running || scan.message != null) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (scan.running) {
                                Text(scan.phase + if (scan.found > 0) " " + stringResource(Res.string.found, scan.found) else "", style = MaterialTheme.typography.titleSmall)
                                if (scan.total > 0) LinearProgressIndicator(progress = { scan.processed.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { AppGraph.catalog.cancel() }) { Text(stringResource(Res.string.stop), maxLines = 1, softWrap = false) }
                            } else {
                                Text(scan.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (items.isEmpty() && !scan.running) {
                item {
                    EmptyState(
                        icon = Icons.Default.Folder,
                        title = stringResource(Res.string.the_catalog_is_empty),
                        text = stringResource(Res.string.scan_the_device_or_pick_a)
                    )
                }
            } else {
                item {
                    GooglePillField(
                        value = filter.query,
                        onValueChange = { filter = filter.copy(query = it) },
                        placeholder = stringResource(Res.string.search_the_catalog),
                        leadingIcon = Icons.Default.Search,
                        trailing = { if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.clear)) } }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        ShelfKind.entries.forEach { kind ->
                            FilterChip(selected = filter.kind == kind, onClick = { filter = filter.copy(kind = kind, value = null) }, label = { Text(kind.label, maxLines = 1, softWrap = false) })
                        }
                    }
                }
                if (filter.kind != ShelfKind.ALL && filter.kind != ShelfKind.RECENT) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            shelfValues.take(60).forEach { entry ->
                                FilterChip(
                                    selected = filter.value == entry.value,
                                    onClick = { filter = filter.copy(value = if (filter.value == entry.value) null else entry.value) },
                                    label = { Text("${entry.value} · ${entry.count}", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                            if (filter.kind == ShelfKind.MINE && shelfValues.isEmpty()) {
                                Text(stringResource(Res.string.no_collections_yet_select_books_and), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.found_2, shown.size), style = MaterialTheme.typography.labelLarge)
                        Box {
                            FilterChip(selected = false, onClick = { sortMenu = true }, label = { Text(stringResource(Res.string.sort, filter.sort.label), maxLines = 1, softWrap = false) })
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                CatalogSort.entries.forEach { sort ->
                                    DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortMenu = false; filter = filter.copy(sort = sort) })
                                }
                            }
                        }
                        FilterChip(selected = filter.hideAdded, onClick = { filter = filter.copy(hideAdded = !filter.hideAdded) }, label = { Text(stringResource(Res.string.hide_added), maxLines = 1, softWrap = false) })
                        if (filter.kind == ShelfKind.MINE && filter.value != null) {
                            TextButton(onClick = { AppGraph.catalog.deleteShelf(filter.value.orEmpty()); filter = filter.copy(value = null) }) {
                                Text(stringResource(Res.string.delete_collection), maxLines = 1, softWrap = false)
                            }
                        }
                        if (shown.isNotEmpty()) {
                            TextButton(onClick = { selected = if (selected.containsAll(shown.map { it.uri })) emptySet() else shown.map { it.uri }.toSet() }) {
                                Text(if (selected.containsAll(shown.map { it.uri })) stringResource(Res.string.clear_selection) else stringResource(Res.string.select_all), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                items(shown, key = { it.uri }) { item ->
                    CatalogRow(
                        item = item,
                        selected = item.uri in selected,
                        onToggle = { selected = if (item.uri in selected) selected - item.uri else selected + item.uri },
                        onOpen = {
                            scope.launch {
                                importing = item.name
                                val result = AppGraph.addFromCatalog(listOf(item))
                                importing = null
                                if (result.added == 1) AppGraph.catalog.items.value.firstOrNull { it.uri == item.uri }?.libraryId?.let(AppGraph.library::entry)?.let(onOpenEntry)
                                else report = result.failed.firstOrNull() ?: getString(Res.string.could_not_open_the_file)
                            }
                        }
                    )
                }
            }
        }
        if (selected.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(Res.string.selected, selected.size), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = {
                        val chosen = items.filter { it.uri in selected }
                        scope.launch {
                            importing = getString(Res.string.adding_books)
                            val result = AppGraph.addFromCatalog(chosen) { done, total -> scope.launch { importing = getString(Res.string.adding_books_of, done, total) } }
                            importing = null
                            selected = emptySet()
                            report = getString(Res.string.added_to_the_library, result.added) + if (result.failed.isNotEmpty()) getString(Res.string.failed, result.failed.size, result.failed.first()) else ""
                        }
                    }) { Text(stringResource(Res.string.add_to_library), maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { newShelf = true }) { Text(stringResource(Res.string.add_to_collection), maxLines = 1, softWrap = false) }
                    TextButton(onClick = { selected = emptySet() }) { Text(stringResource(Res.string.cancel), maxLines = 1, softWrap = false) }
                }
            }
        }
    }

    if (newShelf) {
        TextInputDialog(
            visible = true,
            title = stringResource(Res.string.new_collection),
            placeholder = stringResource(Res.string.for_example_summer_reading),
            confirmLabel = stringResource(Res.string.create),
            onDismiss = { newShelf = false },
            onConfirm = { name ->
                newShelf = false
                AppGraph.catalog.createShelf(name, selected.toList())
                selected = emptySet()
                filter = filter.copy(kind = ShelfKind.MINE, value = name.trim().take(60))
            }
        )
    }
    ProcessingDialog(importing)
    report?.let { message ->
        AlertDialog(
            onDismissRequest = { report = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(Res.string.got_it), maxLines = 1, softWrap = false) } }
        )
    }
}

@Composable
private fun ScanCard(
    roots: List<com.localdoc.ai.catalog.CatalogRoot>,
    scanRunning: Boolean,
    includeText: Boolean,
    onIncludeText: (Boolean) -> Unit
) {
    val scanner = AppGraph.deviceScanner
    var needsAccess by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.find_books), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.the_app_will_scan_files_and),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val wholeDeviceLabel = stringResource(Res.string.whole_device)
                if (scanner.supportsWholeDevice) {
                    Button(enabled = !scanRunning, onClick = {
                        if (scanner.hasAllFilesAccess()) AppGraph.catalog.scan(DEVICE_ROOT, wholeDeviceLabel, includeText)
                        else { needsAccess = true; scanner.requestAllFilesAccess() }
                    }) { Text(stringResource(Res.string.whole_device), maxLines = 1, softWrap = false) }
                }
                OutlinedButton(enabled = !scanRunning, onClick = {
                    scanner.pickFolder { root -> if (root != null) AppGraph.catalog.scan(root.id, root.title, includeText) }
                }) { Text(stringResource(Res.string.folder_or_location), maxLines = 1, softWrap = false) }
            }
            if (needsAccess) {
                Text(
                    stringResource(Res.string.allow_all_files_access_in_the),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilterChip(selected = includeText, onClick = { onIncludeText(!includeText) }, label = { Text(stringResource(Res.string.and_txt_html_md_xml), maxLines = 1, softWrap = false) })
            roots.forEach { root ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(Res.string.files, root.files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !scanRunning, onClick = { AppGraph.catalog.scan(root.id, root.title, includeText) }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.scan_again)) }
                    IconButton(enabled = !scanRunning, onClick = { AppGraph.catalog.forgetRoot(root.id) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.remove_from_the_catalog)) }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(item: CatalogItem, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    ListItem(
        leadingContent = { Checkbox(checked = selected, onCheckedChange = { onToggle() }) },
        headlineContent = { Text(item.title.ifBlank { item.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = listOfNotNull(
                item.author.takeIf { it.isNotBlank() }, item.year?.toString(), item.series.takeIf { it.isNotBlank() },
                item.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            Column {
                if (details.isNotBlank()) Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(item.format.label, style = MaterialTheme.typography.labelMedium)
                Text(formatBytes(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (item.libraryId != null) Text(stringResource(Res.string.in_library), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onOpen)
    )
}
