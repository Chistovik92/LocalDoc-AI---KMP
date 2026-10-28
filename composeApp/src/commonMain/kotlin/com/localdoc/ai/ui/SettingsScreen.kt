package com.localdoc.ai.ui

import com.localdoc.ai.resources.backup
import com.localdoc.ai.resources.bookmarks
import com.localdoc.ai.resources.delete_bookmark
import com.localdoc.ai.resources.dictionary
import com.localdoc.ai.resources.download_speed_scheduled_checks_for_new
import com.localdoc.ai.resources.downloads_and_updates
import com.localdoc.ai.resources.font_background_page_by_page_mode
import com.localdoc.ai.resources.highlighted_passages_with_notes_export_to
import com.localdoc.ai.resources.image_cache_downloaded_novel_pages
import com.localdoc.ai.resources.library_and_settings_in_one_file
import com.localdoc.ai.resources.models_for_summaries_answers_and_translation
import com.localdoc.ai.resources.neural_networks
import com.localdoc.ai.resources.no_bookmarks_yet
import com.localdoc.ai.resources.no_text
import com.localdoc.ai.resources.online_catalogs
import com.localdoc.ai.resources.opds_catalogs_open_libraries_and_your
import com.localdoc.ai.resources.open_a_book_or_web_page
import com.localdoc.ai.resources.progress_bookmarks_and_notes_between_devices
import com.localdoc.ai.resources.quotes_and_notes
import com.localdoc.ai.resources.reading_and_appearance
import com.localdoc.ai.resources.reading_statistics
import com.localdoc.ai.resources.saved_places_in_books_and_web
import com.localdoc.ai.resources.settings
import com.localdoc.ai.resources.site_sign_ins_forget_site
import com.localdoc.ai.resources.sites_and_sign_in
import com.localdoc.ai.resources.speech_and_voices
import com.localdoc.ai.resources.speed_piper_voices_system_synthesizers
import com.localdoc.ai.resources.storage
import com.localdoc.ai.resources.sync
import com.localdoc.ai.resources.time_speed_and_streak_by_day
import com.localdoc.ai.resources.to_settings
import com.localdoc.ai.resources.translation
import com.localdoc.ai.resources.translation_and_original_language_auto_trans
import com.localdoc.ai.resources.updates_and_about
import com.localdoc.ai.resources.version_update_check
import com.localdoc.ai.resources.words_from_books_with_translation_a
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Backup
import com.localdoc.ai.resources.interface_and_accessibility
import com.localdoc.ai.resources.language_contrast_motion
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sync
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
enum class SettingsSection(val title: StringResource, val description: StringResource, val icon: ImageVector) {
    MODELS(Res.string.neural_networks, Res.string.models_for_summaries_answers_and_translation, Icons.Default.Memory),
    VOICE(Res.string.speech_and_voices, Res.string.speed_piper_voices_system_synthesizers, Icons.Default.RecordVoiceOver),
    TRANSLATION(Res.string.translation, Res.string.translation_and_original_language_auto_trans, Icons.Default.Translate),
    READER(Res.string.reading_and_appearance, Res.string.font_background_page_by_page_mode, Icons.Default.FormatSize),
    STATS(Res.string.reading_statistics, Res.string.time_speed_and_streak_by_day, Icons.Default.BarChart),
    BOOKMARKS(Res.string.bookmarks, Res.string.saved_places_in_books_and_web, Icons.Default.Bookmarks),
    NOTES(Res.string.quotes_and_notes, Res.string.highlighted_passages_with_notes_export_to, Icons.Default.EditNote),
    DICTIONARY(Res.string.dictionary, Res.string.words_from_books_with_translation_a, Icons.Default.Translate),
    CATALOGS(Res.string.online_catalogs, Res.string.opds_catalogs_open_libraries_and_your, Icons.Default.Language),
    UPDATES(Res.string.downloads_and_updates, Res.string.download_speed_scheduled_checks_for_new, Icons.Default.Sync),
    SITES(Res.string.sites_and_sign_in, Res.string.site_sign_ins_forget_site, Icons.Default.Lock),
    STORAGE(Res.string.storage, Res.string.image_cache_downloaded_novel_pages, Icons.Default.Storage),
    BACKUP(Res.string.backup, Res.string.library_and_settings_in_one_file, Icons.Default.Backup),
    SYNC(Res.string.sync, Res.string.progress_bookmarks_and_notes_between_devices, Icons.Default.Backup),
    INTERFACE(Res.string.interface_and_accessibility, Res.string.language_contrast_motion, Icons.Default.Accessibility),
    ABOUT(Res.string.updates_and_about, Res.string.version_update_check, Icons.Default.Info)
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
            IconButton(onClick = { onSection(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.to_settings)) }
            Text(stringResource(section.title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                SettingsSection.MODELS -> ModelsScreen()
                SettingsSection.CATALOGS -> OpdsSection()
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
                        SettingsSection.UPDATES -> UpdatesSection()
                        SettingsSection.SITES -> SitesSection()
                        SettingsSection.STORAGE -> StorageSection()
                        SettingsSection.BACKUP -> BackupSection()
                        SettingsSection.SYNC -> SyncSection()
                        SettingsSection.INTERFACE -> InterfaceSection()
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
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
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
                        Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(section.description),
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
            title = stringResource(Res.string.no_bookmarks_yet),
            text = stringResource(Res.string.open_a_book_or_web_page)
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { mark ->
            ListItem(
                overlineContent = { Text(mark.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                headlineContent = { Text(mark.excerpt.ifBlank { stringResource(Res.string.no_text) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = mark.chapter.takeIf { it.isNotBlank() }?.let { c -> ({ Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
                trailingContent = { IconButton(onClick = { AppGraph.bookmarks.remove(mark.id) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.delete_bookmark)) } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onOpen(mark) }
            )
        }
    }
}
