package com.localdoc.ai.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.localdoc.ai.domain.pagerFor
import com.localdoc.ai.domain.startReading
import com.localdoc.ai.lang.LanguageDetector
import com.localdoc.ai.lang.Languages
import com.localdoc.ai.lang.PageKey
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.SpanStyleType
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import com.localdoc.ai.domain.ReaderFont
import com.localdoc.ai.domain.ReaderSettings
import com.localdoc.ai.domain.ReaderTheme
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.tts.ReadAloudStop
import com.localdoc.ai.tts.SpeechSegment
import com.localdoc.ai.ui.TextInputDialog
import com.localdoc.ai.ui.launchGeneration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Цвета «бумаги» читалки. */
data class PaperColors(val background: Color, val text: Color, val secondary: Color, val highlight: Color)

@Composable
fun paperColors(theme: ReaderTheme): PaperColors {
    val scheme = MaterialTheme.colorScheme
    return when (theme) {
        ReaderTheme.SYSTEM -> PaperColors(scheme.surfaceContainerLowest, scheme.onSurface, scheme.onSurfaceVariant, scheme.primaryContainer)
        ReaderTheme.LIGHT -> PaperColors(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF5F6368), Color(0xFFD3E3FD))
        ReaderTheme.SEPIA -> PaperColors(Color(0xFFF6EEDC), Color(0xFF5B4636), Color(0xFF8A7560), Color(0xFFEBD9A8))
        ReaderTheme.DARK -> PaperColors(Color(0xFF1F1F1F), Color(0xFFE3E3E3), Color(0xFF9AA0A6), Color(0xFF0842A0))
        ReaderTheme.BLACK -> PaperColors(Color(0xFF000000), Color(0xFFC4C7C5), Color(0xFF80868B), Color(0xFF283593))
    }
}

/** Действия ИИ-суфлёра над открытым текстом. */
internal enum class ReaderAiAction(val label: String, val shortLabel: String, val icon: ImageVector) {
    SUMMARIZE_PAGE("Суммаризировать страницу", "Пересказ", Icons.Default.Summarize),
    EXPLAIN_TERM("Объяснить термин", "Термин", Icons.Default.Lightbulb),
    KEY_POINTS("Найти главное", "Главное", Icons.Default.TravelExplore)
}

/** Навигация по соседним главам (веб-новеллы). */
class ChapterNavigation(
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onTableOfContents: (() -> Unit)?
)

/**
 * Экран чтения (книги и веб-страницы): скрываемые панели, плеер озвучки с подсветкой
 * предложения, ИИ-суфлёр, перевод, закладки, настройки шрифта/бумаги/голоса и оглавление.
 *
 * Текст делится на страницы ([BookPager]). Режим отображения выбирается в настройках:
 * сплошная прокрутка или по одной странице. Озвучка, перевод и ИИ в любом режиме работают
 * с текущей страницей, а не со всей книгой.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderView(
    book: Book,
    docKey: String,
    startBlock: Int,
    onProgress: (Int) -> Unit,
    onBack: (() -> Unit)?,
    onOpenAssistant: () -> Unit,
    onOpenModels: () -> Unit,
    chapterNavigation: ChapterNavigation? = null,
    /** false — экран внутри Scaffold с нижней навигацией, отступ системной панели уже учтён. */
    applyNavigationBarInsets: Boolean = true,
    topBarActions: @Composable RowScope.() -> Unit = {}
) {
    val settings by AppGraph.readerSettings.value.collectAsState()
    val translationSettings by AppGraph.translationSettings.value.collectAsState()
    val translatingDocs by AppGraph.translating.collectAsState()
    val bookmarks by AppGraph.bookmarks.all.collectAsState()
    val translatedPages by AppGraph.translator.progress.collectAsState()
    val translationError by AppGraph.translator.error.collectAsState()
    val paper = paperColors(settings.theme)
    val scope = rememberCoroutineScope()
    val lastBlock = (book.blocks.size - 1).coerceAtLeast(0)

    val pager = remember(book, settings.pageSize) { AppGraph.pagerFor(book) }
    val translating = docKey in translatingDocs
    val target = Languages.byCode(translationSettings.targetLanguage)
    // Перевод показывается постранично в любом режиме: так он не мешает прокрутке и озвучке
    val pagedDisplay = settings.paged || translating
    // Позиция переживает смену размера страницы: новая разбивка открывается на том же блоке
    var anchorBlock by remember { mutableStateOf(startBlock) }
    var page by remember(pager) { mutableStateOf(pager.pageOfBlock(anchorBlock)) }
    LaunchedEffect(page, pager) { anchorBlock = pager.page(page).startBlock }
    val pageInfo = pager.page(page)
    val firstIndex = if (pagedDisplay) pageInfo.startBlock else 0
    val itemCount = if (pagedDisplay) pageInfo.blockCount else book.blocks.size
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (startBlock - firstIndex).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    )
    var pendingLocal by remember { mutableStateOf<Int?>(null) }

    val player by AppGraph.readAloud.state.collectAsState()
    val isMine = AppGraph.readAloud.documentKey == docKey && player.active
    val speaking: SpeechSegment? = if (isMine) player.segment else null

    var uiVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var askTerm by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }

    val currentBlock by remember(firstIndex) { derivedStateOf { (firstIndex + listState.firstVisibleItemIndex).coerceAtMost(lastBlock) } }
    val chapterIndex = book.chapterIndexAt(currentBlock)
    val chapterTitle = book.chapters.getOrNull(chapterIndex)?.title
    val percent = (pager.progressOfPage(page) * 100).toInt()

    val entryId = docKey.substringAfter(':')
    val entry = AppGraph.library.entry(entryId)
    val pageBookmark = bookmarks.firstOrNull { it.entryId == entryId && it.block in pageInfo.startBlock until pageInfo.endBlock }

    val sourceCode = translationSettings.sourceLanguage
    val layout = settings.pageSize.chars
    val pageKey = PageKey(docKey, page, target?.code.orEmpty(), sourceCode, layout)
    val translatedBlocks: List<Block?>? =
        if (translating && target != null) translatedPages[pageKey] ?: AppGraph.translator.cached(pageKey) else null

    fun pageBlocks(index: Int): List<Block> {
        val p = pager.page(index)
        return book.blocks.subList(p.startBlock.coerceAtMost(book.blocks.size), p.endBlock.coerceAtMost(book.blocks.size))
    }

    fun displayBlock(global: Int): Block =
        translatedBlocks?.getOrNull(global - pageInfo.startBlock) ?: book.blocks[global]

    // Сохранение прогресса без записи на каждый кадр прокрутки
    LaunchedEffect(listState, docKey, firstIndex) {
        snapshotFlow { firstIndex + listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(600)
            .collectLatest { onProgress(it.coerceAtMost(lastBlock)) }
    }

    // В режиме прокрутки страница — это страница под первым видимым блоком
    LaunchedEffect(pagedDisplay, pager) {
        if (pagedDisplay) return@LaunchedEffect
        snapshotFlow { pager.pageOfBlock(listState.firstVisibleItemIndex) }
            .distinctUntilChanged()
            .collect { page = it }
    }

    // Смена страницы / режима: новая страница начинается сверху (или с нужного блока после перехода)
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(pagedDisplay, page) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        if (pagedDisplay) {
            listState.scrollToItem(pendingLocal ?: 0)
            pendingLocal = null
        }
    }
    var wasPaged by remember { mutableStateOf(pagedDisplay) }
    LaunchedEffect(pagedDisplay) {
        if (!pagedDisplay && wasPaged) listState.scrollToItem(pager.page(page).startBlock)
        wasPaged = pagedDisplay
    }

    // Прокрутка вниз прячет панели (полное погружение), заметная прокрутка вверх — возвращает
    LaunchedEffect(listState) {
        var last = 0
        snapshotFlow { listState.firstVisibleItemIndex * 10_000 + listState.firstVisibleItemScrollOffset }
            .collectLatest { pos ->
                if (pos > last + 400) uiVisible = false
                if (pos < last - 1200) uiVisible = true
                last = pos
            }
    }

    // Озвучка перешла на другую страницу — в постраничном режиме листаем за ней
    LaunchedEffect(player.page, isMine, pagedDisplay, settings.followSpeech) {
        if (isMine && pagedDisplay && settings.followSpeech && player.page != page) page = player.page
    }

    // Следим за читаемым предложением: если абзац ушёл за экран — докручиваем
    LaunchedEffect(speaking?.block, settings.followSpeech, page) {
        val block = speaking?.block ?: return@LaunchedEffect
        if (!settings.followSpeech) return@LaunchedEffect
        val local = block - firstIndex
        if (local !in 0 until itemCount) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.dropLast(1).any { it.index == local }
        if (!fullyVisible) listState.animateScrollToItem(local)
    }

    // Ассистент и ИИ-суфлёр получают текущую страницу, а не весь текст книги
    LaunchedEffect(page, book) {
        AppGraph.currentDoc.value = ParsedDoc(title = book.title, content = pageBlocks(page).joinToString("\n\n") { it.text }, source = book.source)
    }

    // Перевод текущей страницы и подготовка соседней
    LaunchedEffect(translating, target?.code, sourceCode, page, pager, retry, layout) {
        if (!translating || target == null) return@LaunchedEffect
        val nextKey = PageKey(docKey, page + 1, target.code, sourceCode, layout)
        AppGraph.translator.clearError()
        AppGraph.translator.retainOnly(setOf(pageKey, nextKey))
        val source = Languages.byCode(sourceCode)
        AppGraph.translator.request(pageKey, pageBlocks(page), target, source)
        if (page + 1 < pager.size) AppGraph.translator.request(nextKey, pageBlocks(page + 1), target, source)
    }

    fun startTranslation(enabled: Boolean) {
        AppGraph.translating.value = if (enabled) AppGraph.translating.value + docKey else AppGraph.translating.value - docKey
        // Идущая озвучка продолжится с этого же места уже на новом языке
        if (isMine) AppGraph.startReading(docKey, book, speaking?.block ?: currentBlock)
    }

    // Автоперевод: язык книги отличается от языка перевода
    LaunchedEffect(docKey, translationSettings.autoTranslate) {
        if (!translationSettings.autoTranslate || docKey in AppGraph.translating.value || target == null) return@LaunchedEffect
        val detected = LanguageDetector.detect(pageBlocks(page).joinToString("\n") { it.text })
        if (detected != null && !Languages.sameLanguage(detected, target.code) && AppGraph.loadedModelId.value != null) {
            AppGraph.translating.value = AppGraph.translating.value + docKey
        }
    }

    fun goToBlock(block: Int) {
        val b = block.coerceIn(0, lastBlock)
        val targetPage = pager.pageOfBlock(b)
        if (pagedDisplay) pendingLocal = b - pager.page(targetPage).startBlock
        page = targetPage
        if (!pagedDisplay) scope.launch { listState.scrollToItem(b) }
        if (isMine) AppGraph.readAloud.jumpToBlock(b)
    }

    fun seekPage(index: Int) = goToBlock(pager.page(index.coerceIn(0, (pager.size - 1).coerceAtLeast(0))).startBlock)

    fun listen() = AppGraph.startReading(docKey, book, if (pagedDisplay) pageInfo.startBlock.coerceAtLeast(currentBlock) else currentBlock)

    fun runAi(action: ReaderAiAction, term: String? = null) {
        showAi = true
        if (AppGraph.isGenerating.value || AppGraph.loadedModelId.value == null) return
        val pageText = pageBlocks(page).joinToString("\n\n") { it.text }
        val (fragment, prompt, label) = when (action) {
            ReaderAiAction.SUMMARIZE_PAGE -> Triple(pageText, "Кратко перескажи этот фрагмент в 3–5 пунктах.", action.label)
            ReaderAiAction.KEY_POINTS -> {
                val start = book.chapters.getOrNull(chapterIndex)?.startBlock ?: 0
                val end = book.chapters.getOrNull(chapterIndex + 1)?.startBlock ?: book.blocks.size
                Triple(
                    book.blocks.subList(start.coerceAtMost(end), end).joinToString("\n\n") { it.text },
                    "Найди главное в этом тексте: ключевые события, идеи, персонажей и выводы — списком.",
                    action.label + (chapterTitle?.let { " — $it" } ?: "")
                )
            }
            ReaderAiAction.EXPLAIN_TERM -> Triple(
                pageText,
                "Объясни простыми словами, что означает «$term» в контексте этого фрагмента. Если в тексте нет пояснения — дай общее определение.",
                "Объяснить: $term"
            )
        }
        AppGraph.addMessage(ChatMessage(ChatMessage.Role.USER, label))
        val doc = ParsedDoc(title = book.title, content = fragment.ifBlank { pageText }, source = book.source)
        // Движок генерирует по одному запросу: перевод ждёт, пока ИИ-суфлёр ответит
        scope.launch {
            AppGraph.translator.hold()
            // Отмена (уход с экрана) или сбой не должны оставить перевод на паузе навсегда
            var handedOver = false
            try {
                withTimeoutOrNull(AI_WAIT_FOR_TRANSLATION_MS) { AppGraph.translator.awaitIdle() }
                launchGeneration(AppGraph.pipeline.ask(doc, prompt))
                AppGraph.generationJob?.let { job ->
                    job.invokeOnCompletion { AppGraph.translator.release() }
                    handedOver = true
                }
            } finally {
                if (!handedOver) AppGraph.translator.release()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(paper.background)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 88.dp, bottom = 240.dp),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { uiVisible = !uiVisible }
        ) {
            items(itemCount, key = { firstIndex + it }) { local ->
                val index = firstIndex + local
                Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth()) {
                    BlockText(
                        block = displayBlock(index),
                        settings = settings,
                        paper = paper,
                        highlight = speaking?.takeIf { it.block == index },
                        onTap = if (isMine) ({ AppGraph.readAloud.jumpToBlock(index) }) else null,
                        modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 24.dp)
                    )
                }
            }
            if (chapterNavigation != null && (!pagedDisplay || page >= pager.size - 1)) {
                item { ChapterFooter(chapterNavigation, paper) }
            }
        }

        AnimatedVisibility(
            visible = uiVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(chapterTitle, "стр. ${page + 1}/${pager.size}", "$percent%").joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        topBarActions()
                        if (entry != null) {
                            IconButton(onClick = {
                                val block = if (pagedDisplay) pageInfo.startBlock else currentBlock
                                AppGraph.bookmarks.toggle(
                                    entry, pageInfo.startBlock until pageInfo.endBlock, block,
                                    book.blocks.getOrNull(block)?.text.orEmpty(), chapterTitle.orEmpty()
                                )
                            }) {
                                Icon(
                                    if (pageBookmark != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = if (pageBookmark != null) "Убрать закладку" else "Добавить закладку",
                                    tint = if (pageBookmark != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showVoice = true }) { Icon(Icons.Default.RecordVoiceOver, contentDescription = "Голос и озвучка") }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Ещё") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (book.chapters.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Оглавление") }, onClick = { showMenu = false; showToc = true })
                                }
                                DropdownMenuItem(text = { Text("Закладки") }, onClick = { showMenu = false; showBookmarks = true })
                                DropdownMenuItem(
                                    text = { Text(if (translating) "Перевод: включён" else "Перевод…") },
                                    onClick = { showMenu = false; showTranslate = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (settings.paged) "Читать прокруткой" else "Читать по страницам") },
                                    onClick = { showMenu = false; AppGraph.readerSettings.update { it.copy(paged = !it.paged) } }
                                )
                                DropdownMenuItem(text = { Text("Шрифт и фон") }, onClick = { showMenu = false; showSettings = true })
                            }
                        }
                    }
                    if (translating) {
                        val done = translatedBlocks?.count { it != null } ?: 0
                        val total = pageInfo.blockCount
                        TranslationBanner(
                            text = translationError ?: if (done >= total) "Перевод: ${target?.name.orEmpty()}" else "Переводим страницу… $done из $total",
                            isError = translationError != null,
                            onRetry = { retry++ },
                            onOff = { startTranslation(false) }
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    // Подложка, чтобы кнопки ИИ не лежали поверх текста
                    if (uiVisible || isMine) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    } else Modifier
                )
                .then(if (applyNavigationBarInsets) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
        ) {
            val stoppedAt = player.stoppedAt
            if (isMine && stoppedAt != null) {
                ChapterEndCard(
                    stop = stoppedAt,
                    hasNextPage = chapterNavigation?.hasNext == true,
                    onContinue = { AppGraph.readAloud.play() },
                    onNextPage = { chapterNavigation?.onNext?.invoke() }
                )
            }
            AnimatedVisibility(visible = uiVisible) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    ReaderAiAction.entries.forEach { action ->
                        AiChip(action.shortLabel, action.icon) {
                            if (action == ReaderAiAction.EXPLAIN_TERM) askTerm = true else runAi(action)
                        }
                    }
                }
            }
            if (isMine) {
                PlayerBar(state = player, title = chapterTitle ?: book.title, onVoiceSettings = { showVoice = true })
            } else {
                AnimatedVisibility(visible = uiVisible) {
                    PageBar(
                        page = page,
                        pageCount = pager.size,
                        showPageButtons = pagedDisplay,
                        onSeek = ::seekPage,
                        onListen = ::listen,
                        onAskAi = { showAi = true }
                    )
                }
            }
        }
    }

    if (showSettings) ReaderSettingsSheet(onDismiss = { showSettings = false })
    if (showVoice) VoiceSettingsSheet(onDismiss = { showVoice = false })
    if (showToc) {
        TocSheet(
            chapters = book.chapters,
            currentIndex = chapterIndex,
            onDismiss = { showToc = false },
            onSelect = { chapter ->
                showToc = false
                goToBlock(chapter.startBlock)
            }
        )
    }
    if (showTranslate) {
        TranslateSheet(
            enabled = translating,
            status = if (translating) translationError ?: "Страница ${page + 1} из ${pager.size}" else null,
            onToggle = ::startTranslation,
            onDismiss = { showTranslate = false },
            onOpenModels = { showTranslate = false; onOpenModels() }
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            title = "Закладки",
            bookmarks = bookmarks.filter { it.entryId == entryId }.sortedBy { it.block },
            showBookTitle = false,
            onJump = { mark -> showBookmarks = false; goToBlock(mark.block) },
            onDelete = { AppGraph.bookmarks.remove(it.id) },
            onDismiss = { showBookmarks = false }
        )
    }
    if (showAi) {
        AiAnswerSheet(
            onDismiss = { showAi = false },
            onOpenAssistant = { showAi = false; onOpenAssistant() },
            onOpenModels = { showAi = false; onOpenModels() },
            onAction = { action -> if (action == ReaderAiAction.EXPLAIN_TERM) askTerm = true else runAi(action) }
        )
    }
    TextInputDialog(
        visible = askTerm,
        title = "Объяснить термин",
        placeholder = "Слово или выражение из текста",
        confirmLabel = "Спросить",
        onDismiss = { askTerm = false },
        onConfirm = { term ->
            askTerm = false
            runAi(ReaderAiAction.EXPLAIN_TERM, term)
        }
    )
}

private const val AI_WAIT_FOR_TRANSLATION_MS = 60_000L

/** Полоса состояния перевода под верхней панелью: прогресс страницы или ошибка с повтором. */
@Composable
private fun TranslationBanner(text: String, isError: Boolean, onRetry: () -> Unit, onOff: () -> Unit) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isError) TextButton(onClick = onRetry) { Text("Повторить", maxLines = 1, softWrap = false) }
            TextButton(onClick = onOff) { Text("Выключить", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
internal fun AiChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        shape = RoundedCornerShape(12.dp),
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = null
    )
}

@Composable
private fun ChapterEndCard(stop: ReadAloudStop, hasNextPage: Boolean, onContinue: () -> Unit, onNextPage: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                if (stop == ReadAloudStop.CHAPTER_END) "Глава закончилась — озвучка на паузе." else "Конец главы — озвучка остановлена.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            when {
                stop == ReadAloudStop.CHAPTER_END -> TextButton(onClick = onContinue) { Text("Читать дальше") }
                hasNextPage -> TextButton(onClick = onNextPage) { Text("Следующая глава") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterFooter(nav: ChapterNavigation, paper: PaperColors) {
    // Текст кнопок — строго в одну строку; если не помещаются, переносится сама кнопка
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
    ) {
        OutlinedButton(onClick = nav.onPrevious, enabled = nav.hasPrevious) { Text("← Назад", maxLines = 1, softWrap = false) }
        nav.onTableOfContents?.let { TextButton(onClick = it) { Text("Оглавление", color = paper.text, maxLines = 1, softWrap = false) } }
        Button(onClick = nav.onNext, enabled = nav.hasNext) { Text("Далее →", maxLines = 1, softWrap = false) }
    }
}

/** Один блок текста с типографикой читалки, Markdown-выделениями и подсветкой предложения. */
@Composable
private fun BlockText(
    block: Block,
    settings: ReaderSettings,
    paper: PaperColors,
    highlight: SpeechSegment?,
    onTap: (() -> Unit)?,
    modifier: Modifier
) {
    val family = if (settings.font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif
    val base = settings.fontSize
    val highlightColor = if (paper.background.luminance() < 0.3f) paper.highlight.copy(alpha = 0.8f) else paper.highlight
    val style = when (block.type) {
        BlockType.HEADING -> TextStyle(
            fontFamily = family,
            fontSize = (base * if (block.level <= 1) 1.5f else 1.25f).sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (base * 1.9f).sp
        )
        BlockType.QUOTE -> TextStyle(fontFamily = family, fontSize = base.sp, fontStyle = FontStyle.Italic, lineHeight = (base * settings.lineHeight).sp)
        BlockType.CODE -> TextStyle(fontFamily = FontFamily.Monospace, fontSize = (base * 0.82f).sp, lineHeight = (base * 1.35f).sp)
        BlockType.LIST_ITEM, BlockType.PARAGRAPH, BlockType.IMAGE -> TextStyle(fontFamily = family, fontSize = base.sp, lineHeight = (base * settings.lineHeight).sp)
    }
    val topPadding = when (block.type) {
        BlockType.HEADING -> if (block.level <= 1) 28.dp else 18.dp
        BlockType.QUOTE, BlockType.CODE -> 6.dp
        else -> 0.dp
    }
    val bottomPadding = if (block.type == BlockType.HEADING) 10.dp else (base * 0.7f).dp
    val text = buildBlockText(block, highlight, highlightColor)
    Text(
        text = if (block.type == BlockType.LIST_ITEM) AnnotatedString("•  ") + text else text,
        style = style.copy(
            color = if (block.type == BlockType.QUOTE) paper.secondary else paper.text,
            textAlign = if (settings.justify && block.type == BlockType.PARAGRAPH) TextAlign.Justify else TextAlign.Start
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding)
            .then(
                when (block.type) {
                    BlockType.CODE -> Modifier.background(paper.secondary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(12.dp)
                    BlockType.QUOTE -> Modifier.padding(start = 12.dp)
                    BlockType.LIST_ITEM -> Modifier.padding(start = (block.level.coerceAtMost(4) * 16).dp)
                    else -> Modifier
                }
            )
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
    )
}

private fun buildBlockText(block: Block, highlight: SpeechSegment?, highlightColor: Color): AnnotatedString = buildAnnotatedString {
    append(block.text)
    val length = block.text.length
    for (span in block.spans) {
        val s = span.start.coerceIn(0, length)
        val e = span.end.coerceIn(s, length)
        val style = when (span.style) {
            SpanStyleType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            SpanStyleType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            SpanStyleType.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = highlightColor.copy(alpha = 0.35f))
        }
        addStyle(style, s, e)
    }
    if (highlight != null) {
        val s = highlight.start.coerceIn(0, length)
        val e = highlight.end.coerceIn(s, length)
        addStyle(SpanStyle(background = highlightColor), s, e)
    }
}
