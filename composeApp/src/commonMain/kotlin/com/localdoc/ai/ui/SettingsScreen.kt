package com.localdoc.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localdoc.ai.book.Bookmark
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.platform.PlatformBackHandler
import com.localdoc.ai.ui.reader.ReaderSettingsContent
import com.localdoc.ai.ui.reader.TranslationSettingsContent
import com.localdoc.ai.ui.reader.VoiceSettingsContent

/** Разделы вкладки «Настройки». Экран выбора моделей остаётся прежним и открывается как раздел. */
enum class SettingsSection(val title: String, val description: String, val icon: ImageVector) {
    MODELS("Нейросети", "Модели для пересказа, ответов и перевода", Icons.Default.Memory),
    VOICE("Озвучка и голоса", "Скорость, голоса Piper, системные синтезаторы", Icons.Default.RecordVoiceOver),
    TRANSLATION("Перевод", "Язык перевода и оригинала, автоперевод", Icons.Default.Translate),
    READER("Чтение и оформление", "Шрифт, фон, режим «по страницам», размер страницы", Icons.Default.FormatSize),
    STATS("Статистика чтения", "Время, скорость и серия по дням", Icons.Default.BarChart),
    BOOKMARKS("Закладки", "Сохранённые места в книгах и веб-страницах", Icons.Default.Bookmarks),
    NOTES("Цитаты и заметки", "Выделенные места с заметками, выгрузка в Markdown и EPUB", Icons.Default.EditNote),
    DICTIONARY("Словарь", "Слова из книг с переводом — личный список", Icons.Default.Translate),
    SITES("Сайты и вход", "Входы на сайты, «Забыть сайт»", Icons.Default.Lock),
    STORAGE("Хранилище", "Кэш картинок, скачанные страницы новелл", Icons.Default.Storage),
    BACKUP("Резервная копия", "Библиотека и настройки одним файлом, восстановление", Icons.Default.Backup),
    ABOUT("Обновления и о приложении", "Версия, проверка обновлений", Icons.Default.Info)
}

/** Вкладка «Настройки»: список разделов или выбранный раздел с кнопкой «Назад». */
@Composable
fun SettingsScreen(
    section: SettingsSection?,
    onSection: (SettingsSection?) -> Unit,
    onOpenBookmark: (Bookmark) -> Unit,
    onOpenAnnotation: (Annotation) -> Unit
) {
    PlatformBackHandler(enabled = section != null) { onSection(null) }
    if (section == null) {
        SettingsHome(onSection)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = { onSection(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К настройкам") }
            Text(section.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                SettingsSection.MODELS -> ModelsScreen()
                SettingsSection.BOOKMARKS -> BookmarksSection(onOpenBookmark)
                SettingsSection.NOTES -> NotesSection(onOpenAnnotation)
                SettingsSection.DICTIONARY -> DictionarySection()
                else -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                ) {
                    when (section) {
                        SettingsSection.VOICE -> VoiceSettingsContent()
                        SettingsSection.TRANSLATION -> TranslationSettingsContent()
                        SettingsSection.READER -> ReaderSettingsContent()
                        SettingsSection.STATS -> StatsSection()
                        SettingsSection.SITES -> SitesSection()
                        SettingsSection.STORAGE -> StorageSection()
                        SettingsSection.BACKUP -> BackupSection()
                        SettingsSection.ABOUT -> AboutSection()
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHome(onSection: (SettingsSection) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        SettingsSection.entries.forEach { section ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().clickable { onSection(section) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            section.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Все закладки библиотеки: книга, место и выдержка; нажатие открывает книгу на этом месте. */
@Composable
private fun BookmarksSection(onOpen: (Bookmark) -> Unit) {
    val bookmarks by AppGraph.bookmarks.all.collectAsState()
    if (bookmarks.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Bookmarks,
            title = "Закладок пока нет",
            text = "Откройте книгу или веб-страницу и нажмите значок закладки в верхней панели читалки."
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { mark ->
            ListItem(
                overlineContent = { Text(mark.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                headlineContent = { Text(mark.excerpt.ifBlank { "Без текста" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = mark.chapter.takeIf { it.isNotBlank() }?.let { c -> ({ Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                trailingContent = { IconButton(onClick = { AppGraph.bookmarks.remove(mark.id) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить закладку") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onOpen(mark) }
            )
        }
    }
}
