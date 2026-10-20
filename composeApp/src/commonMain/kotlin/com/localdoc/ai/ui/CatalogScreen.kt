@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui

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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Книги на устройстве", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item { ScanCard(roots = roots, scanRunning = scan.running, includeText = includeText, onIncludeText = { includeText = it }) }
            item {
                if (scan.running || scan.message != null) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (scan.running) {
                                Text(scan.phase + if (scan.found > 0) " · найдено: ${scan.found}" else "", style = MaterialTheme.typography.titleSmall)
                                if (scan.total > 0) LinearProgressIndicator(progress = { scan.processed.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { AppGraph.catalog.cancel() }) { Text("Остановить", maxLines = 1, softWrap = false) }
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
                        title = "Каталог пуст",
                        text = "Просканируйте устройство или выберите папку — найденные книги появятся на полках по авторам, жанрам и годам."
                    )
                }
            } else {
                item {
                    GooglePillField(
                        value = filter.query,
                        onValueChange = { filter = filter.copy(query = it) },
                        placeholder = "Поиск по названию, автору, серии",
                        leadingIcon = Icons.Default.Search,
                        trailing = { if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Default.Close, contentDescription = "Очистить") } }
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
                                Text("Подборок пока нет: отметьте книги и нажмите «В подборку».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("Найдено: ${shown.size}", style = MaterialTheme.typography.labelLarge)
                        Box {
                            FilterChip(selected = false, onClick = { sortMenu = true }, label = { Text("Сортировка: ${filter.sort.label}", maxLines = 1, softWrap = false) })
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                CatalogSort.entries.forEach { sort ->
                                    DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortMenu = false; filter = filter.copy(sort = sort) })
                                }
                            }
                        }
                        FilterChip(selected = filter.hideAdded, onClick = { filter = filter.copy(hideAdded = !filter.hideAdded) }, label = { Text("Скрыть добавленные", maxLines = 1, softWrap = false) })
                        if (filter.kind == ShelfKind.MINE && filter.value != null) {
                            TextButton(onClick = { AppGraph.catalog.deleteShelf(filter.value.orEmpty()); filter = filter.copy(value = null) }) {
                                Text("Удалить подборку", maxLines = 1, softWrap = false)
                            }
                        }
                        if (shown.isNotEmpty()) {
                            TextButton(onClick = { selected = if (selected.containsAll(shown.map { it.uri })) emptySet() else shown.map { it.uri }.toSet() }) {
                                Text(if (selected.containsAll(shown.map { it.uri })) "Снять выбор" else "Выбрать все", maxLines = 1, softWrap = false)
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
                                else report = result.failed.firstOrNull() ?: "Не удалось открыть файл"
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
                    Text("Выбрано: ${selected.size}", style = MaterialTheme.typography.labelLarge)
                    Button(onClick = {
                        val chosen = items.filter { it.uri in selected }
                        scope.launch {
                            importing = "Добавляем книги"
                            val result = AppGraph.addFromCatalog(chosen) { done, total -> importing = "Добавляем книги: $done из $total" }
                            importing = null
                            selected = emptySet()
                            report = "Добавлено в библиотеку: ${result.added}" + if (result.failed.isNotEmpty()) ". Не удалось: ${result.failed.size} (${result.failed.first()})" else ""
                        }
                    }) { Text("В библиотеку", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { newShelf = true }) { Text("В подборку…", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { selected = emptySet() }) { Text("Отмена", maxLines = 1, softWrap = false) }
                }
            }
        }
    }

    if (newShelf) {
        TextInputDialog(
            visible = true,
            title = "Новая подборка",
            placeholder = "Например: «Прочитать летом»",
            confirmLabel = "Создать",
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
            confirmButton = { TextButton(onClick = { report = null }) { Text("Понятно", maxLines = 1, softWrap = false) } }
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
            Text("Найти книги", style = MaterialTheme.typography.titleMedium)
            Text(
                "Приложение просмотрит файлы и составит каталог: книги останутся на своих местах, пока вы не добавите их в библиотеку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanner.supportsWholeDevice) {
                    Button(enabled = !scanRunning, onClick = {
                        if (scanner.hasAllFilesAccess()) AppGraph.catalog.scan(DEVICE_ROOT, "Всё устройство", includeText)
                        else { needsAccess = true; scanner.requestAllFilesAccess() }
                    }) { Text("Всё устройство", maxLines = 1, softWrap = false) }
                }
                OutlinedButton(enabled = !scanRunning, onClick = {
                    scanner.pickFolder { root -> if (root != null) AppGraph.catalog.scan(root.id, root.title, includeText) }
                }) { Text("Папка или ресурс…", maxLines = 1, softWrap = false) }
            }
            if (needsAccess) {
                Text(
                    "Разрешите «Доступ ко всем файлам» в открывшихся настройках, вернитесь и нажмите «Всё устройство» ещё раз. " +
                        "Либо выберите папку — для неё разрешение не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilterChip(selected = includeText, onClick = { onIncludeText(!includeText) }, label = { Text("Включая TXT, HTML, Markdown, XML", maxLines = 1, softWrap = false) })
            roots.forEach { root ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Файлов: ${root.files}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !scanRunning, onClick = { AppGraph.catalog.scan(root.id, root.title, includeText) }) { Icon(Icons.Default.Refresh, contentDescription = "Сканировать заново") }
                    IconButton(enabled = !scanRunning, onClick = { AppGraph.catalog.forgetRoot(root.id) }) { Icon(Icons.Default.Delete, contentDescription = "Убрать из каталога") }
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
                if (item.libraryId != null) Text("в библиотеке", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onOpen)
    )
}
