package com.localdoc.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.search.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Поиск по тексту всех книг библиотеки и по каталогу устройства. Результат книги открывает её на найденном месте;
 * файл каталога открывает каталог. Пока индекс строится, показывается ход работы и поиск повторяется по завершении.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSearchSheet(
    initialQuery: String,
    onOpenBook: (BookEntry, Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val indexing by AppGraph.searchIndexProgress.collectAsState()
    val building = indexing != null

    LaunchedEffect(Unit) { AppGraph.syncSearchIndex() }
    LaunchedEffect(query, building) {
        error = null
        if (query.trim().length < MIN_QUERY_CHARS) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        try {
            results = AppGraph.searchContent(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Поиск не удался"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("Поиск по тексту книг", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Слово или фраза") },
                modifier = Modifier.fillMaxWidth()
            )
            indexing?.let { progress ->
                Text("Индексируем библиотеку — ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        val found = results
        when {
            error != null -> Hint(error.orEmpty())
            query.trim().length < MIN_QUERY_CHARS -> Hint("Введите хотя бы два символа. Ищутся все слова запроса в одном абзаце, окончания не важны.")
            found == null -> Hint("Ищем…")
            found.isEmpty -> Hint(if (building) "Пока ничего — индекс ещё строится" else "Ничего не найдено")
            else -> LazyColumn(modifier = Modifier.heightIn(max = RESULTS_MAX_HEIGHT.dp).padding(top = 8.dp, bottom = 24.dp)) {
                items(found.books, key = { "${it.entry.id}:${it.block}" }) { hit ->
                    ListItem(
                        overlineContent = { Text(hit.entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        headlineContent = { Text(hit.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onOpenBook(hit.entry, hit.block) }
                    )
                }
                if (found.catalog.isNotEmpty()) {
                    item(key = "catalog-header") {
                        Text(
                            "Найдено на устройстве",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                    items(found.catalog, key = { "catalog:${it.uri}" }) { item ->
                        ListItem(
                            overlineContent = { Text(item.format.label) },
                            headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = item.author.takeIf { it.isNotBlank() }?.let { a -> ({ Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clickable { onOpenCatalog() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).padding(bottom = 24.dp)
    )
}

private const val MIN_QUERY_CHARS = 2
private const val SEARCH_DELAY_MS = 250L
private const val RESULTS_MAX_HEIGHT = 480
