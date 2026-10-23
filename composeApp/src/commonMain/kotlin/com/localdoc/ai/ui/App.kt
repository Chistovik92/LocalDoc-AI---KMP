package com.localdoc.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.vector.ImageVector
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookEntry
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.Bookmark
import com.localdoc.ai.domain.startReading
import com.localdoc.ai.domain.AppAction
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.notes.AnchorResolver
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.notes.TextAnchor
import com.localdoc.ai.domain.IncomingDoc
import com.localdoc.ai.platform.FilePicker
import com.localdoc.ai.platform.PlatformBackHandler
import com.localdoc.ai.platform.SystemPlayer
import com.localdoc.ai.ui.reader.PlayerBar
import com.localdoc.ai.ui.reader.ReaderView
import com.localdoc.ai.update.UpdateDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Books("Книги", Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    Web("Веб", Icons.Outlined.Language, Icons.Filled.Language),
    Assistant("Ассистент", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Settings("Настройки", Icons.Outlined.Settings, Icons.Filled.Settings)
}

/** Открытая книга в читалке. */
private const val SEARCH_INDEX_DELAY_MS = 3_000L

private class OpenBook(val entryId: String, val book: Book, val startBlock: Int)

private fun bookKey(entryId: String) = "book:$entryId"

@Composable
fun App() {
    LocalDocTheme {
        var tab by remember { mutableStateOf(Tab.Books) }
        var openBook by remember { mutableStateOf<OpenBook?>(null) }
        var processing by remember { mutableStateOf<String?>(null) }
        var pasteText by remember { mutableStateOf(false) }
        var askUrl by remember { mutableStateOf(false) }
        var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }
        var showCatalog by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val filePicker = remember { FilePicker() }
        val incoming by AppGraph.incoming.collectAsState()
        val action by AppGraph.pendingAction.collectAsState()
        val update by AppGraph.updates.update.collectAsState()
        val downloadState by AppGraph.updates.download.collectAsState()
        val updateStatus by AppGraph.updates.status.collectAsState()
        val player by AppGraph.readAloud.state.collectAsState()
        val webDocument by AppGraph.webSession.document.collectAsState()

        fun openModels() {
            openBook = null
            settingsSection = SettingsSection.MODELS
            tab = Tab.Settings
        }

        fun open(entry: BookEntry, listen: Boolean = false) {
            scope.launch {
                try {
                    val book = AppGraph.library.load(entry.id)
                    if (book == null) {
                        snackbar.showSnackbar("Книга не найдена — добавьте её снова")
                        return@launch
                    }
                    AppGraph.library.touch(entry.id)
                    openBook = OpenBook(entry.id, book, entry.progressBlock)
                    if (listen) AppGraph.startReading(bookKey(entry.id), book, entry.progressBlock)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    snackbar.showSnackbar("Не удалось открыть: ${t.message ?: "файл повреждён"}")
                }
            }
        }

        // Открывает книгу (или сохранённую веб-страницу) на нужном месте: закладка, цитата, результат поиска
        fun openPlace(entryId: String, block: Int) {
            AppGraph.library.saveProgress(entryId, block)
            val entry = AppGraph.library.entry(entryId)
            when {
                entry == null -> scope.launch { snackbar.showSnackbar("Книга удалена — ссылка больше не работает") }
                entry.format == BookFormat.WEB -> {
                    openBook = null
                    tab = Tab.Web
                    AppGraph.webSession.openSaved(entry.id)
                }
                else -> open(entry)
            }
        }

        // Якорь находит место и в заново разобранной книге, поэтому блок определяется по текущему тексту
        fun openAnchored(entryId: String, block: Int, anchor: TextAnchor?) {
            scope.launch {
                val book = if (anchor != null) AppGraph.library.load(entryId) else null
                val at = if (book != null && anchor != null) AnchorResolver.resolve(anchor, book.blocks, book.chapters).block else block
                openPlace(entryId, at)
            }
        }

        fun openBookmark(mark: Bookmark) = openAnchored(mark.entryId, mark.block, mark.anchor)

        fun openAnnotation(annotation: Annotation) = openAnchored(annotation.entryId, annotation.anchor.block, annotation.anchor)

        fun importAndOpen(label: String, block: suspend () -> Book) {
            scope.launchImport(
                onStart = { processing = label },
                onDone = { processing = null },
                onError = { message -> scope.launch { snackbar.showSnackbar(message) } }
            ) {
                val entry = AppGraph.library.add(block())
                open(entry)
            }
        }

        fun pickFile() {
            filePicker.pick("*/*") { uri ->
                if (uri != null) importAndOpen("Открываем книгу") { AppGraph.importer.import(uri) }
            }
        }

        // Восстанавливаем модель, загруженную до перезапуска; тихо проверяем обновления
        LaunchedEffect(Unit) {
            SystemPlayer.start()
            AppGraph.updates.check(silent = true)
            AppGraph.restoreLastModel()
        }

        // Поисковый индекс следует за библиотекой и каталогом; пауза не даёт перестраивать его на каждый кадр прокрутки
        val libraryEntries by AppGraph.library.entries.collectAsState()
        val catalogItems by AppGraph.catalog.items.collectAsState()
        val indexKey = libraryEntries.map { it.id to it.totalBlocks } to catalogItems.size
        LaunchedEffect(indexKey) {
            delay(SEARCH_INDEX_DELAY_MS)
            AppGraph.syncSearchIndex()
        }

        // Документы извне: «Поделиться», открытие файла, ссылки
        LaunchedEffect(incoming) {
            val item = incoming ?: return@LaunchedEffect
            AppGraph.acceptIncoming(null)
            when (item.type) {
                IncomingDoc.IncomingType.URL -> {
                    openBook = null
                    tab = Tab.Web
                    AppGraph.webSession.open(item.payload)
                }
                IncomingDoc.IncomingType.TEXT -> importAndOpen("Присланный текст") { AppGraph.importer.importText(item.payload, "Присланный текст") }
                IncomingDoc.IncomingType.FILE -> importAndOpen("Открываем книгу") { AppGraph.importer.import(item.payload) }
            }
        }

        // Действия виджета рабочего стола и deep link
        LaunchedEffect(action) {
            val current = action ?: return@LaunchedEffect
            AppGraph.requestAction(null)
            val last = AppGraph.library.entries.value.firstOrNull()
            when (current) {
                AppAction.CONTINUE_READING -> if (last != null) open(last) else tab = Tab.Books
                AppAction.OPEN_FILE -> pickFile()
                AppAction.OPEN_URL -> {
                    openBook = null
                    tab = Tab.Web
                    askUrl = true
                }
                AppAction.TOGGLE_PLAYBACK -> when {
                    AppGraph.readAloud.state.value.active -> AppGraph.readAloud.toggle()
                    last != null -> open(last, listen = true)
                }
            }
        }

        val reading = openBook
        if (showCatalog && reading == null) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    CatalogScreen(onBack = { showCatalog = false }, onOpenEntry = { showCatalog = false; open(it) })
                }
            }
        } else if (reading != null) {
            PlatformBackHandler(enabled = true) { openBook = null }
            key(bookKey(reading.entryId)) {
            Box(Modifier.fillMaxSize()) {
            ReaderView(
                book = reading.book,
                docKey = bookKey(reading.entryId),
                startBlock = reading.startBlock,
                onProgress = { AppGraph.library.saveProgress(reading.entryId, it) },
                onBack = { openBook = null },
                onOpenAssistant = { openBook = null; tab = Tab.Assistant },
                onOpenModels = ::openModels
            )
            DownloadBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp))
            }
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        // Плашка загрузки книги: прогресс, отмена, итог
                        DownloadBanner()
                        // Мини-плеер: озвучка продолжается, пока вы в другой вкладке
                        val webShowsPlayer = tab == Tab.Web && webDocument != null
                        if (player.active && !webShowsPlayer) {
                            PlayerBar(state = player, title = player.title.ifBlank { "Сейчас читается" }, onVoiceSettings = {
                                val id = AppGraph.readAloud.documentKey?.removePrefix("book:")
                                id?.let(AppGraph.library::entry)?.let { open(it) }
                            })
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = { tab = entry },
                                    icon = { Icon(if (tab == entry) entry.selectedIcon else entry.icon, contentDescription = entry.label) },
                                    label = { Text(entry.label) }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                // Читалка веб-страницы сама обрабатывает статус-бар (панели поверх текста)
                val readerOnTop = tab == Tab.Web && webDocument != null
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = padding.calculateBottomPadding())
                        .then(if (readerOnTop) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                ) {
                    when (tab) {
                        Tab.Books -> LibraryScreen(
                            onOpen = { open(it) },
                            onListen = { open(it, listen = true) },
                            onPickFile = ::pickFile,
                            onPasteText = { pasteText = true },
                            onOpenWeb = { tab = Tab.Web },
                            onOpenCatalog = { showCatalog = true },
                            onOpenModels = ::openModels,
                            onOpenAt = { entry, block -> openPlace(entry.id, block) }
                        )
                        Tab.Web -> WebScreen(onOpenAssistant = { tab = Tab.Assistant }, onOpenModels = ::openModels)
                        Tab.Assistant -> ChatScreen(onOpenModels = ::openModels)
                        Tab.Settings -> SettingsScreen(settingsSection, { settingsSection = it }, ::openBookmark, ::openAnnotation)
                    }
                }
            }
        }

        TextInputDialog(
            visible = pasteText,
            title = "Вставить текст",
            placeholder = "Вставьте или введите текст",
            confirmLabel = "Читать",
            singleLine = false,
            onDismiss = { pasteText = false },
            onConfirm = { text ->
                pasteText = false
                importAndOpen("Готовим текст") { AppGraph.importer.importText(text, text.lineSequence().first().take(60)) }
            }
        )
        TextInputDialog(
            visible = askUrl,
            title = "Открыть страницу",
            placeholder = "https://…",
            confirmLabel = "Открыть",
            onDismiss = { askUrl = false },
            onConfirm = { url ->
                askUrl = false
                AppGraph.webSession.open(url)
            }
        )
        ProcessingDialog(processing)
        WebLoginHost()

        update?.let { found ->
            val downloading = downloadState is UpdateDownload.Downloading
            AlertDialog(
                onDismissRequest = { if (!downloading) AppGraph.updates.dismiss() },
                title = { Text("Доступна версия ${found.version}") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(found.notes.ifBlank { found.title }.take(1200))
                        UpdateProgressInfo(downloadState, updateStatus)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !downloading, onClick = { AppGraph.updates.install(found) }) {
                        Text(
                            when {
                                downloading -> "Загрузка…"
                                found.downloadUrl != null -> "Скачать и установить"
                                else -> "Подробнее"
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onClick = { AppGraph.updates.dismiss() }) { Text(if (downloading) "Отмена" else "Позже") } }
            )
        }
    }
}

/** Импорт с индикатором и понятной ошибкой вместо падения. */
private fun CoroutineScope.launchImport(
    onStart: () -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    block: suspend () -> Unit
) {
    launch {
        onStart()
        try {
            block()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            onError("Не удалось открыть: ${t.message ?: t::class.simpleName}")
        } finally {
            onDone()
        }
    }
}
