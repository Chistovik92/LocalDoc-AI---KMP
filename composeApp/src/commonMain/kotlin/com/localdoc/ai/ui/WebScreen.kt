package com.localdoc.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.PlatformActions
import com.localdoc.ai.platform.PlatformBackHandler
import com.localdoc.ai.ui.reader.ChapterNavigation
import com.localdoc.ai.ui.reader.ReaderView

/**
 * Вкладка «Веб»: адресная строка в стиле Google, страница в режиме чтения
 * (без рекламы и меню), озвучка с подсветкой и навигация по главам веб-новелл.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(onOpenAssistant: () -> Unit, onOpenModels: () -> Unit) {
    val session = AppGraph.webSession
    val document by session.document.collectAsState()
    val loading by session.loading.collectAsState()
    val error by session.error.collectAsState()
    val toc by session.toc.collectAsState()
    val tocLoading by session.tocLoading.collectAsState()
    var showToc by remember { mutableStateOf(false) }
    var askUrl by remember { mutableStateOf(false) }

    val doc = document
    if (doc != null) {
        val nav = doc.book.web
        PlatformBackHandler(enabled = true) { session.close() }
        // Новая глава — новое состояние списка: иначе прокрутка и прогресс старой главы перейдут в новую
        key(session.docKey(doc)) {
        ReaderView(
            book = doc.book,
            docKey = session.docKey(doc),
            startBlock = doc.startBlock,
            onProgress = session::saveProgress,
            onBack = session::close,
            onOpenAssistant = onOpenAssistant,
            onOpenModels = onOpenModels,
            applyNavigationBarInsets = false,
            chapterNavigation = ChapterNavigation(
                hasPrevious = nav?.prev != null,
                hasNext = nav?.next != null,
                onPrevious = { session.previous() },
                onNext = { session.next() },
                onTableOfContents = {
                    showToc = true
                    if (toc.isEmpty()) session.loadTableOfContents()
                }
            ),
            topBarActions = {
                IconButton(onClick = { askUrl = true }) { Icon(Icons.Default.Language, contentDescription = "Новый адрес") }
                IconButton(onClick = { PlatformActions.openUrl(doc.book.source) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в браузере")
                }
            }
        )
        }
    } else {
        WebHome(onOpen = session::open, onOpenSaved = session::openSaved, error = error)
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("Оглавление новеллы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            when {
                tocLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                toc.isEmpty() -> Column(Modifier.padding(24.dp)) {
                    Text(
                        error ?: "Список глав не найден. С загруженной локальной моделью приложение попробует найти оглавление само.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AssistChip(
                        onClick = { session.clearError(); session.loadTableOfContents() },
                        label = { Text("Повторить") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                else -> LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                    items(toc) { link ->
                        val current = link.url == doc?.book?.source
                        ListItem(
                            headlineContent = {
                                Text(link.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.clickable { showToc = false; session.open(link.url) }
                        )
                    }
                }
            }
        }
    }

    TextInputDialog(
        visible = askUrl,
        title = "Открыть страницу",
        placeholder = "https://…",
        confirmLabel = "Открыть",
        onDismiss = { askUrl = false },
        onConfirm = { askUrl = false; session.open(it) }
    )
    ProcessingDialog(loading?.let { "Загрузка страницы" }, subtitle = loading.orEmpty())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebHome(onOpen: (String) -> Unit, onOpenSaved: (String) -> Unit, error: String?) {
    val entries by AppGraph.library.entries.collectAsState()
    val recent = entries.filter { it.format == BookFormat.WEB }
    var address by remember { mutableStateOf("") }

    LazyColumn(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(Modifier.height(48.dp))
            // Логотип в фирменных цветах Google
            val colors = listOf(GoogleColors.Blue, GoogleColors.Red, GoogleColors.Yellow, GoogleColors.Blue, GoogleColors.Green, GoogleColors.Red, GoogleColors.Yellow, GoogleColors.Blue)
            Text(
                buildAnnotatedString {
                    "LocalDoc".forEachIndexed { i, c -> withStyle(SpanStyle(color = colors[i % colors.size])) { append(c) } }
                },
                fontSize = 44.sp,
                fontWeight = FontWeight.Medium
            )
            Text("Режим чтения без рекламы", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            GooglePillField(
                value = address,
                onValueChange = { address = it },
                placeholder = "Введите адрес или вставьте ссылку",
                leadingIcon = Icons.Default.Public,
                imeAction = ImeAction.Go,
                onSubmit = { if (address.isNotBlank()) onOpen(address) },
                trailing = {
                    IconButton(onClick = { if (address.isNotBlank()) onOpen(address) }, enabled = address.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Открыть")
                    }
                },
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.widthIn(max = 640.dp)) {
                listOf("Без рекламы и меню", "Главы веб-новелл", "Озвучка с подсветкой", "ИИ-пересказ").forEach { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .then(Modifier)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (recent.isNotEmpty()) {
            item {
                Text(
                    "Недавние страницы",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            items(recent, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(entry.author.ifBlank { entry.source }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry.progress > 0f) Text("Прочитано ${(entry.progress * 100).toInt()}%")
                        }
                    },
                    leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { onOpen(entry.source) }) { Icon(Icons.Default.Refresh, contentDescription = "Обновить из сети") }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.widthIn(max = 640.dp).clickable { onOpenSaved(entry.id) }
                )
            }
        }
    }
}
