package com.localdoc.ai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.find_in_book_hint
import com.localdoc.ai.resources.find_in_book_nothing
import com.localdoc.ai.resources.find_in_book_short
import com.localdoc.ai.resources.find_in_book_title
import com.localdoc.ai.resources.found_2
import com.localdoc.ai.resources.searching
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Найденное в книге вхождение: блок, границы в тексте блока и подпись главы. */
internal data class BookHit(val block: Int, val start: Int, val end: Int, val chapterTitle: String?)

/**
 * Поиск по тексту открытой книги: все слова запроса должны встретиться в одном абзаце (блоке),
 * как в поиске по библиотеке; окончания не важны (совпадение по началу слова), регистр не учитывается.
 */
internal fun findInBook(book: Book, rawQuery: String, limit: Int = 200): List<BookHit> {
    val words = rawQuery.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
    if (words.isEmpty()) return emptyList()
    val hits = ArrayList<BookHit>()
    for ((index, block) in book.blocks.withIndex()) {
        if (block.type == BlockType.IMAGE) continue
        val text = block.text.lowercase()
        var first = Int.MAX_VALUE
        var last = -1
        var all = true
        for (word in words) {
            val at = text.indexOf(word)
            if (at < 0) {
                all = false
                break
            }
            if (at < first) first = at
            if (at + word.length > last) last = at + word.length
        }
        if (all) {
            val chapter = book.chapters.getOrNull(book.chapterIndexAt(index))?.title
            hits.add(BookHit(index, first, last, chapter))
            if (hits.size >= limit) break
        }
    }
    return hits
}

/** Фрагмент блока вокруг найденного места: совпадение выделяется цветом темы. */
private fun hitSnippet(book: Book, hit: BookHit, highlight: Color, context: Int = 60): AnnotatedString {
    val text = book.blocks[hit.block].text
    val from = (hit.start - context).coerceAtLeast(0)
    val to = (hit.end + context).coerceAtMost(text.length)
    return buildAnnotatedString {
        if (from > 0) append('…')
        append(text.substring(from, hit.start))
        val start = length
        append(text.substring(hit.start, hit.end))
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight), start, length)
        append(text.substring(hit.end, to))
        if (to < text.length) append('…')
    }
}

/**
 * Лист «Поиск в книге»: поле ввода, живой поиск с задержкой, список вхождений с переходом
 * на найденное место. Открывается и горячей клавишей Ctrl+F, и из меню читалки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindInBookSheet(
    book: Book,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember(book) { mutableStateOf<List<BookHit>>(emptyList()) }
    var searching by remember(book) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    // Живой поиск с задержкой: пересчёт не на каждое нажатие (книга может быть большой)
    LaunchedEffect(book) {
        snapshotFlow { query }
            .debounce(250)
            .distinctUntilChanged()
            .collectLatest {
                searching = true
                hits = findInBook(book, it)
                searching = false
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.find_in_book_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(Res.string.find_in_book_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
            if (query.isNotBlank()) {
                Text(
                    if (searching) stringResource(Res.string.searching)
                    else stringResource(Res.string.found_2, hits.size.toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            if (!searching && hits.isEmpty() && query.isNotBlank()) {
                Text(
                    stringResource(Res.string.find_in_book_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                itemsIndexed(hits, key = { _, h -> h.block * 100_000 + h.start }) { _, hit ->
                    ListItem(
                        headlineContent = {
                            Text(hitSnippet(book, hit, MaterialTheme.colorScheme.primary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = hit.chapterTitle?.let {
                            { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = stringResource(Res.string.find_in_book_short)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onJump(hit.block) }
                    )
                }
            }
        }
    }
}
