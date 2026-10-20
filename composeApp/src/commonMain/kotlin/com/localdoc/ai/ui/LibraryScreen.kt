package com.localdoc.ai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.LibraryItem
import com.localdoc.ai.book.chapterLabel
import com.localdoc.ai.book.groupLibrary
import com.localdoc.ai.book.novelTitle
import com.localdoc.ai.book.shelfKey
import com.localdoc.ai.domain.AppGraph

/** Фильтры библиотеки по типу источника. */
private enum class LibraryFilter(val label: String, val formats: Set<BookFormat>?) {
    ALL("Все", null),
    BOOKS("Книги", setOf(BookFormat.EPUB, BookFormat.FB2)),
    PDF("PDF", setOf(BookFormat.PDF)),
    TEXT("Текст и Markdown", setOf(BookFormat.TEXT, BookFormat.MARKDOWN)),
    WEB("Веб", setOf(BookFormat.WEB, BookFormat.HTML))
}

/** Вкладка «Книги» в стиле Google Play Книг: поиск, «Продолжить чтение», полка обложек. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (BookEntry) -> Unit,
    onListen: (BookEntry) -> Unit,
    onPickFile: () -> Unit,
    onPasteText: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenModels: () -> Unit
) {
    val entries by AppGraph.library.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var showAdd by remember { mutableStateOf(false) }

    val filtered = entries.filter { e ->
        (filter.formats == null || e.format in filter.formats.orEmpty()) &&
            (query.isBlank() || e.title.contains(query, ignoreCase = true) || e.author.contains(query, ignoreCase = true))
    }
    val continueEntry = entries.firstOrNull()
    val shelf = remember(filtered) { groupLibrary(filtered) }
    var openedNovel by remember { mutableStateOf<String?>(null) }
    // Группа читается из актуального списка: после удаления главы шторка обновляется или закрывается
    val novelOnSheet = shelf.filterIsInstance<LibraryItem.Novel>().firstOrNull { it.shelfKey == openedNovel }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(116.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GooglePillField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск в библиотеке",
                    leadingIcon = Icons.Default.Search,
                    trailing = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Очистить") }
                        } else {
                            IconButton(onClick = onOpenModels) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Text("AI", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
                                }
                            }
                        }
                    }
                )
            }

            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Ваша библиотека пуста",
                        text = "Добавьте книгу EPUB, FB2, PDF, TXT, Markdown или HTML — или откройте веб-страницу. Всё читается и озвучивается прямо на устройстве."
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onPickFile) { Text("Открыть файл") }
                            OutlinedButton(onClick = onOpenWeb) { Text("Веб-страница") }
                        }
                    }
                }
            } else {
                if (continueEntry != null && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinueReadingCard(continueEntry, onOpen = { onOpen(continueEntry) }, onListen = { onListen(continueEntry) })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        LibraryFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
                items(shelf, key = { it.shelfKey }) { item ->
                    when (item) {
                        is LibraryItem.Single -> BookTile(item.entry, onClick = { onOpen(item.entry) }, onDelete = { AppGraph.library.remove(item.entry.id) })
                        is LibraryItem.Novel -> NovelTile(
                            novel = item,
                            onClick = { openedNovel = item.shelfKey },
                            onDelete = { item.chapters.forEach { AppGraph.library.remove(it.id) } }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAdd = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Добавить") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    novelOnSheet?.let { novel ->
        NovelChaptersSheet(
            novel = novel,
            onDismiss = { openedNovel = null },
            onOpen = { openedNovel = null; onOpen(it) },
            onDelete = { AppGraph.library.remove(it.id) }
        )
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Text("Добавить в библиотеку", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            AddOption(Icons.Default.FileOpen, "Файл с устройства", "EPUB, FB2, FB2.ZIP, PDF, TXT, Markdown, HTML") { showAdd = false; onPickFile() }
            AddOption(Icons.Default.Language, "Веб-страница по ссылке", "Статьи и веб-новеллы в режиме чтения") { showAdd = false; onOpenWeb() }
            AddOption(Icons.Default.ContentPaste, "Вставить текст", "Скопированный фрагмент станет документом") { showAdd = false; onPasteText() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinueReadingCard(entry: BookEntry, onOpen: () -> Unit, onListen: () -> Unit) {
    // Глава новеллы показывается как «Новелла» + заголовок главы, а не длинное обрезанное имя
    val novel = entry.novelTitle()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(novel ?: entry.title, entry.author, entry.format, Modifier.width(72.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Продолжить чтение", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(novel ?: entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                val subtitle = if (novel != null) entry.chapterLabel() else entry.author
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                // Узкий экран: кнопки переносятся целиком, текст внутри не ломается по буквам
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpen) { Text("Читать", maxLines = 1, softWrap = false) }
                    FilledTonalButton(onClick = onListen) {
                        Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Слушать", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Плитка группы глав одной веб-новеллы: обложка новеллы, число глав и прогресс последней открытой. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelTile(novel: LibraryItem.Novel, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box {
            BookCover(novel.title, novel.host, BookFormat.WEB, Modifier.fillMaxWidth())
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Удалить все главы") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
        Text(novel.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(chaptersCount(novel.chapters.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelChaptersSheet(novel: LibraryItem.Novel, onDismiss: () -> Unit, onOpen: (BookEntry) -> Unit, onDelete: (BookEntry) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(novel.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "${novel.host} · ${chaptersCount(novel.chapters.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
            items(novel.chapters, key = { it.id }) { chapter ->
                val last = chapter.id == novel.mostRecent.id
                ListItem(
                    headlineContent = {
                        Text(chapter.chapterLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            when {
                                last -> "Последняя открытая · ${(chapter.progress * 100).toInt()}%"
                                chapter.progress > 0f -> "${(chapter.progress * 100).toInt()}%"
                                else -> "Не начата"
                            }
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(chapter) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить главу") }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { onOpen(chapter) }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** «1 глава», «2 главы», «5 глав», «21 глава». */
private fun chaptersCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "глав"
        n % 10 == 1 -> "глава"
        n % 10 in 2..4 -> "главы"
        else -> "глав"
    }
    return "$n $word"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(entry: BookEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box {
            BookCover(entry.title, entry.author, entry.format, Modifier.fillMaxWidth())
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Удалить из библиотеки") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
        Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (entry.progress > 0f) "${(entry.progress * 100).toInt()}% · ${entry.format.label}" else entry.format.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
