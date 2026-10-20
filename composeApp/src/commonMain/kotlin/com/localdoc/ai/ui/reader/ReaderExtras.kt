@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.localdoc.ai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Bookmark
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.lang.Languages

/** Выбор языка из списка; [allowAuto] добавляет пункт «Определять автоматически» (результат null). */
@Composable
internal fun LanguagePickerDialog(title: String, selected: String?, allowAuto: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (allowAuto) {
                    item { LanguageRow("Определять автоматически", "", selected == null) { onPick(null) } }
                }
                items(Languages.all, key = { it.code }) { language ->
                    LanguageRow(language.name, language.native, selected == language.code) { onPick(language.code) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", maxLines = 1, softWrap = false) } }
    )
}

@Composable
private fun LanguageRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotBlank() && subtitle != title) ({ Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Язык перевода и оригинала, автоперевод — общий блок для шторки читалки и вкладки «Настройки». */
@Composable
internal fun TranslationSettingsContent() {
    val settings by AppGraph.translationSettings.value.collectAsState()
    var pickTarget by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    val target = Languages.byCode(settings.targetLanguage)
    val source = Languages.byCode(settings.sourceLanguage)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ListItem(
            headlineContent = { Text("Язык перевода") },
            supportingContent = { Text(target?.let { "${it.name} · ${it.native}" } ?: "Не выбран", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { pickTarget = true }
        )
        ListItem(
            headlineContent = { Text("Язык оригинала") },
            supportingContent = { Text(source?.name ?: "Определять автоматически", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { pickSource = true }
        )
        SwitchLine("Переводить автоматически", "Включать перевод, когда язык книги отличается от языка перевода", settings.autoTranslate) { v ->
            AppGraph.translationSettings.update { it.copy(autoTranslate = v) }
        }
    }
    if (pickTarget) {
        LanguagePickerDialog("Язык перевода", settings.targetLanguage, allowAuto = false, onPick = { code ->
            if (code != null) AppGraph.translationSettings.update { it.copy(targetLanguage = code) }
            pickTarget = false
        }, onDismiss = { pickTarget = false })
    }
    if (pickSource) {
        LanguagePickerDialog("Язык оригинала", settings.sourceLanguage, allowAuto = true, onPick = { code ->
            AppGraph.translationSettings.update { it.copy(sourceLanguage = code) }
            pickSource = false
        }, onDismiss = { pickSource = false })
    }
}

/** Строка с переключателем и пояснением; длинный текст переносится, переключатель остаётся справа. */
@Composable
internal fun SwitchLine(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Шторка «Перевод» в читалке: включение перевода страниц и язык. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslateSheet(
    enabled: Boolean,
    status: String?,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit
) {
    val modelId by AppGraph.loadedModelId.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Перевод", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            if (modelId == null) {
                Text(
                    "Перевод выполняет локальная нейросеть — сначала загрузите модель.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Button(onClick = onOpenModels, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Выбрать модель", maxLines = 1, softWrap = false) }
            }
            SwitchLine("Переводить текст", "Страницы переводятся по одной; соседняя готовится заранее", enabled, onToggle)
            TranslationSettingsContent()
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Text(
                "Озвучка читает перевод голосом выбранного языка. Голоса — в Настройки → Озвучка и голоса.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/** Закладки книги или всей библиотеки: переход и удаление. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(
    title: String,
    bookmarks: List<Bookmark>,
    showBookTitle: Boolean,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (bookmarks.isEmpty()) {
            Text(
                "Закладок пока нет. Нажмите значок закладки в верхней панели читалки, чтобы запомнить место.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                items(bookmarks, key = { it.id }) { mark ->
                    ListItem(
                        overlineContent = if (showBookTitle) ({ Text(mark.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                        headlineContent = { Text(mark.excerpt.ifBlank { "Без текста" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = mark.chapter.takeIf { it.isNotBlank() }?.let { c -> ({ Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                        trailingContent = { IconButton(onClick = { onDelete(mark) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить закладку") } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onJump(mark) }
                    )
                }
            }
        }
    }
}

/** Нижняя панель чтения по страницам: ползунок, страницы, «Слушать» и «Спросить ИИ». */
@Composable
internal fun PageBar(
    page: Int,
    pageCount: Int,
    showPageButtons: Boolean,
    onSeek: (Int) -> Unit,
    onListen: () -> Unit,
    onAskAi: () -> Unit
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val span = lastPage.coerceAtLeast(1)
    val position = dragging ?: (page.toFloat() / span)
    val shownPage = (position * span).toInt().coerceIn(0, lastPage)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Slider(
                value = position.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * span).toInt().coerceIn(0, lastPage)) }
                    dragging = null
                }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (showPageButtons) {
                    IconButton(onClick = { onSeek(page - 1) }, enabled = page > 0) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Предыдущая страница")
                    }
                }
                Text(
                    "Стр. ${shownPage + 1} из $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                if (showPageButtons) {
                    IconButton(onClick = { onSeek(page + 1) }, enabled = page < lastPage) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Следующая страница")
                    }
                }
                FilledTonalIconButton(onClick = onListen) { Icon(Icons.Default.Headphones, contentDescription = "Слушать") }
                ExtendedFloatingActionButton(
                    onClick = onAskAi,
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    text = { Text("Спросить ИИ", maxLines = 1, softWrap = false) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
