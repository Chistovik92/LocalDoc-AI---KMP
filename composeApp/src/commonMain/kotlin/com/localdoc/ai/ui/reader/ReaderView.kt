package com.localdoc.ai.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import com.localdoc.ai.domain.OrientationLock
import com.localdoc.ai.domain.PageTurn
import com.localdoc.ai.domain.TapAction
import com.localdoc.ai.domain.autoScrollDelta
import com.localdoc.ai.domain.brightnessAfterDrag
import com.localdoc.ai.domain.needsFullRefresh
import com.localdoc.ai.domain.pageAnimationEnabled
import com.localdoc.ai.domain.pageTurn
import com.localdoc.ai.domain.pinchFontSize
import com.localdoc.ai.domain.swipeAction
import com.localdoc.ai.domain.tapAction
import com.localdoc.ai.notes.AnnotationColor
import com.localdoc.ai.notes.TextMark
import com.localdoc.ai.notes.markAt
import com.localdoc.ai.platform.ScreenControls
import com.localdoc.ai.platform.VolumeKeys
import kotlinx.coroutines.delay
import kotlin.math.abs
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.localdoc.ai.notes.AnchorResolver
import com.localdoc.ai.notes.Annotation
import com.localdoc.ai.notes.TextAnchor
import com.localdoc.ai.notes.WordSelection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ChatMessage
import com.localdoc.ai.domain.ReaderFont
import com.localdoc.ai.domain.ReaderSettings
import com.localdoc.ai.domain.ReaderTheme
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.tts.ReadAloudStop
import com.localdoc.ai.tts.SpeechSegment
import com.localdoc.ai.ui.BookImage
import com.localdoc.ai.ui.SaveAsDialog
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
    /** Книга из библиотеки: новое оглавление (найденное по словам, нейросетью или отмеченное вручную) нужно сохранить. */
    onChaptersChanged: ((List<com.localdoc.ai.book.Chapter>) -> Unit)? = null,
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
    var showSaveAs by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var askTerm by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var showAnnotations by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Новая заметка (место выделения) и правка существующей; слово для словаря: слово + предложение
    var noteDraft by remember { mutableStateOf<TextAnchor?>(null) }
    var noteEdit by remember { mutableStateOf<Annotation?>(null) }
    var wordLookup by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Смена ключа пересоздаёт область выделения и тем самым снимает выделение после действия
    var selectionEpoch by remember { mutableStateOf(0) }
    val toolbarState = remember { SelectionToolbarState() }
    val textToolbar = remember(toolbarState) { ReaderTextToolbar(toolbarState) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    val currentBlock by remember(firstIndex) { derivedStateOf { (firstIndex + listState.firstVisibleItemIndex).coerceAtMost(lastBlock) } }
    val chapterIndex = book.chapterIndexAt(currentBlock)
    val chapterTitle = book.chapters.getOrNull(chapterIndex)?.title
    val percent = (pager.progressOfPage(page) * 100).toInt()

    val entryId = docKey.substringAfter(':')
    val entry = AppGraph.library.entry(entryId)
    val allAnnotations by AppGraph.annotations.all.collectAsState()
    val bookAnnotations = remember(allAnnotations, entryId) { allAnnotations.filter { it.entryId == entryId } }
    // Цитаты и заметки, подсвеченные в тексте: место ищется в текущем разборе книги, как и у закладок
    val marksByBlock = remember(book, bookAnnotations) { annotationMarks(book, bookAnnotations) }
    // Закладка стоит там, куда её якорь указывает в текущем разборе книги, а не на сохранённом номере блока
    val resolvedBookmarks = remember(bookmarks, book) { bookmarks.filter { it.entryId == entryId }.associateWith { it.resolveBlock(book) } }
    val pageBookmark = resolvedBookmarks.entries.firstOrNull { it.value in pageInfo.startBlock until pageInfo.endBlock }?.key

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

    // Автопереход: дочитали главу прокруткой до кнопок «Далее» — через пару секунд открывается следующая
    val hasFooter = chapterNavigation != null && (!pagedDisplay || page >= pager.size - 1)
    val footerVisible by remember(itemCount, hasFooter) {
        derivedStateOf { hasFooter && listState.layoutInfo.visibleItemsInfo.any { it.index == itemCount } }
    }
    var scrolledByUser by remember(docKey) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex * 10_000 + listState.firstVisibleItemScrollOffset }
            .collect { if (it > 0) scrolledByUser = true }
    }
    LaunchedEffect(footerVisible, settings.autoNext, scrolledByUser, isMine) {
        if (footerVisible && settings.autoNext && scrolledByUser && !isMine && chapterNavigation?.hasNext == true) {
            kotlinx.coroutines.delay(AUTO_NEXT_DELAY_MS)
            chapterNavigation.onNext()
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

    /** Место выделенного текста в книге: сначала рядом с читаемым местом, затем по всей книге. */
    fun locateSelection(selected: String): TextAnchor? {
        val near = if (pagedDisplay) pageInfo.startBlock until pageInfo.endBlock
        else (currentBlock - 5).coerceAtLeast(0)..(currentBlock + 60).coerceAtMost(lastBlock)
        return TextAnchor.locate(book.blocks, book.chapters, selected, near)
            ?: TextAnchor.locate(book.blocks, book.chapters, selected, 0..lastBlock)
    }

    fun onSelectionAction(action: SelectionAction) {
        val copy = toolbarState.copy ?: return
        scope.launch {
            val selected = readSelection(clipboard, copy, keepInClipboard = action == SelectionAction.COPY)
            if (selected == null) {
                notice = "Не удалось получить выделенный текст"
                return@launch
            }
            when (action) {
                SelectionAction.COPY -> notice = "Скопировано"
                SelectionAction.QUOTE, SelectionAction.NOTE -> {
                    val anchor = locateSelection(selected)
                    when {
                        entry == null || anchor == null -> notice = "Это место не удалось сохранить: выделите текст книги"
                        action == SelectionAction.QUOTE -> {
                            AppGraph.annotations.add(entry, anchor)
                            notice = "Цитата сохранена"
                        }
                        else -> noteDraft = anchor
                    }
                }
                SelectionAction.CHAPTER -> {
                    val at = locateSelection(selected)?.block
                    val title = selected.trim().replace(Regex("\\s+"), " ").take(90)
                    if (onChaptersChanged == null || at == null || title.isEmpty()) {
                        notice = "Это место не удалось отметить: выделите текст книги"
                    } else {
                        onChaptersChanged(
                            (book.chapters.filter { it.startBlock != at } + com.localdoc.ai.book.Chapter(title, at)).sortedBy { it.startBlock }
                        )
                        notice = "Начало главы отмечено"
                    }
                }
                SelectionAction.WORD -> {
                    val word = WordSelection.wordOf(selected)
                    if (word == null) {
                        notice = "Выделите одно слово или короткое выражение"
                    } else {
                        val block = locateSelection(word)?.block ?: currentBlock
                        wordLookup = word to WordSelection.sentenceAround(book.blocks.getOrNull(block)?.text.orEmpty(), word)
                    }
                }
            }
            selectionEpoch++
        }
    }

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

    // Управление: листание зонами, свайпом и кнопками громкости, автопрокрутка, e-ink
    var autoScroll by remember { mutableStateOf(false) }
    val reducedMotion = remember { ScreenControls.prefersReducedMotion }
    val animate = pageAnimationEnabled(settings.pageAnimation, settings.eInk, reducedMotion)

    fun turn(forward: Boolean) {
        scope.launch {
            val canScroll = if (forward) listState.canScrollForward else listState.canScrollBackward
            when (pageTurn(forward, canScroll, pagedDisplay, page, pager.size)) {
                PageTurn.SCROLL -> {
                    val distance = listState.layoutInfo.viewportSize.height * SCREEN_SCROLL_FRACTION * (if (forward) 1f else -1f)
                    if (animate) listState.animateScrollBy(distance) else listState.scrollBy(distance)
                }
                PageTurn.PAGE -> seekPage(if (forward) page + 1 else page - 1)
                PageTurn.NONE -> when {
                    forward && chapterNavigation?.hasNext == true -> chapterNavigation.onNext()
                    !forward && chapterNavigation?.hasPrevious == true -> chapterNavigation.onPrevious()
                }
            }
        }
    }

    fun handleTap(x: Float, width: Float) {
        if (autoScroll) {
            autoScroll = false
            return
        }
        when (tapAction(x, width, settings.tapZones, settings.tapZoneWidth)) {
            TapAction.MENU -> uiVisible = !uiVisible
            TapAction.NEXT -> turn(true)
            TapAction.PREVIOUS -> turn(false)
        }
    }

    // Жесты живут дольше одной перекомпозиции — им нужны всегда свежие функции
    val turnNow by rememberUpdatedState<(Boolean) -> Unit> { forward -> turn(forward) }
    val tapNow by rememberUpdatedState<(Float, Float) -> Unit> { x, width -> handleTap(x, width) }
    val brightnessNow by rememberUpdatedState(settings.brightness ?: DEFAULT_BRIGHTNESS)
    val lineHeightPx = with(LocalDensity.current) { (settings.fontSize * settings.lineHeight).sp.toPx() }
    val lineHeightNow by rememberUpdatedState(lineHeightPx)
    val speedNow by rememberUpdatedState(settings.autoScrollSpeed)

    LaunchedEffect(settings.keepScreenOn) { ScreenControls.setKeepScreenOn(settings.keepScreenOn) }
    LaunchedEffect(settings.orientation) { ScreenControls.setOrientation(settings.orientation) }
    LaunchedEffect(settings.brightness) { ScreenControls.setBrightness(settings.brightness) }
    DisposableEffect(Unit) {
        onDispose {
            ScreenControls.setKeepScreenOn(false)
            ScreenControls.setOrientation(OrientationLock.AUTO)
            ScreenControls.setBrightness(null)
        }
    }
    val volumePaging = settings.volumeKeys && ScreenControls.supportsVolumeKeys
    DisposableEffect(volumePaging) {
        if (volumePaging) VolumeKeys.handler = { forward -> turnNow(forward) }
        onDispose { if (volumePaging) VolumeKeys.handler = null }
    }

    // Автопрокрутка: плавный сдвиг по кадрам; в конце страницы — следующая страница, в конце книги — остановка
    LaunchedEffect(autoScroll, pagedDisplay, pager) {
        if (!autoScroll) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        var carry = 0f
        while (true) {
            val now = withFrameNanos { it }
            carry += autoScrollDelta(speedNow, lineHeightNow, (now - lastNanos) / 1_000_000L)
            lastNanos = now
            val whole = carry.toInt()
            if (whole < 1) continue
            carry -= whole
            if (listState.scrollBy(whole.toFloat()) == 0f) {
                if (pagedDisplay && page < pager.size - 1) page += 1 else autoScroll = false
            }
        }
    }

    // Анимация листания и полное обновление экрана e-ink каждые N страниц
    val turnAnim = remember { Animatable(1f) }
    var turnDirection by remember { mutableStateOf(1) }
    var turns by remember { mutableStateOf(0) }
    var refreshPhase by remember { mutableStateOf(0) }
    var shownPage by remember { mutableStateOf(page) }
    LaunchedEffect(page) {
        if (page == shownPage) return@LaunchedEffect
        turnDirection = if (page > shownPage) 1 else -1
        shownPage = page
        turns++
        if (animate && pagedDisplay) {
            turnAnim.snapTo(0f)
            turnAnim.animateTo(1f, tween(PAGE_TURN_MS))
        } else {
            turnAnim.snapTo(1f)
        }
        if (settings.eInk && needsFullRefresh(turns, settings.eInkRefreshPages)) {
            try {
                refreshPhase = 1
                delay(EINK_FLASH_MS)
                refreshPhase = 2
                delay(EINK_FLASH_MS)
            } finally {
                refreshPhase = 0
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(paper.background)) {
        CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        key(selectionEpoch) { SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 88.dp, bottom = 240.dp),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .graphicsLayer {
                    alpha = turnAnim.value
                    translationX = (1f - turnAnim.value) * turnDirection * PAGE_TURN_SHIFT_DP.dp.toPx()
                }
                // Щипок двумя пальцами меняет размер шрифта; перехватывается раньше списка, чтобы тот не прокручивался
                .pointerInput(settings.pinchZoom) {
                    if (!settings.pinchZoom) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoom = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoom *= event.calculateZoom()
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (abs(zoom - 1f) > PINCH_MIN_CHANGE) {
                            AppGraph.readerSettings.update { it.copy(fontSize = pinchFontSize(it.fontSize, zoom)) }
                        }
                    }
                }
                // Горизонтальный свайп листает; жест, который взял список или выделение текста, не считается
                .pointerInput(settings.swipePaging) {
                    if (!settings.swipePaging) return@pointerInput
                    val minDistance = SWIPE_MIN_DP.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        var consumed = false
                        var multiTouch = false
                        var end = down.position
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.size > 1) multiTouch = true
                            if (event.changes.any { it.isConsumed }) consumed = true
                            event.changes.firstOrNull { it.id == down.id }?.let { end = it.position }
                        } while (event.changes.any { it.pressed })
                        if (!consumed && !multiTouch) {
                            swipeAction(end.x - down.position.x, end.y - down.position.y, minDistance)?.let { turnNow(it == TapAction.NEXT) }
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { offset -> tapNow(offset.x, size.width.toFloat()) } }
        ) {
            items(itemCount, key = { firstIndex + it }) { local ->
                val index = firstIndex + local
                Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth()) {
                    BlockText(
                        block = displayBlock(index),
                        entryId = entryId,
                        referer = book.source.takeIf { it.startsWith("http") },
                        settings = settings,
                        paper = paper,
                        highlight = speaking?.takeIf { it.block == index },
                        marks = if (translatedBlocks == null) marksByBlock[index].orEmpty() else emptyList(),
                        onTap = if (isMine) ({ AppGraph.readAloud.jumpToBlock(index) }) else null,
                        onMarkTap = { id -> bookAnnotations.firstOrNull { it.id == id }?.let { noteEdit = it } },
                        modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 24.dp)
                    )
                }
            }
            if (chapterNavigation != null && (!pagedDisplay || page >= pager.size - 1)) {
                item { ChapterFooter(chapterNavigation, paper) }
            }
        }
        } }
        }

        // Яркость: тянем вверх или вниз у левого края
        if (settings.brightnessGesture) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(BRIGHTNESS_STRIP_DP.dp)
                    .pointerInput(Unit) {
                        var live = DEFAULT_BRIGHTNESS
                        val save = { AppGraph.readerSettings.update { it.copy(brightness = live) } }
                        detectVerticalDragGestures(
                            onDragStart = { live = brightnessNow },
                            onDragEnd = { save() },
                            onDragCancel = { save() },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                live = brightnessAfterDrag(live, dy, size.height.toFloat())
                                ScreenControls.setBrightness(live)
                                notice = "Яркость ${(live * 100).toInt()}%"
                            }
                        )
                    }
            )
        }

        if (toolbarState.visible) {
            SelectionActionBar(
                canAnnotate = entry != null && !translating,
                canMarkChapter = entry != null && !translating && onChaptersChanged != null,
                onAction = ::onSelectionAction,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = if (uiVisible) 76.dp else 12.dp, start = 16.dp, end = 16.dp)
            )
        }
        ReaderNotice(
            text = notice,
            onGone = { notice = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 170.dp, start = 16.dp, end = 16.dp)
        )

        AnimatedVisibility(
            visible = uiVisible,
            enter = if (animate) slideInVertically { -it } + fadeIn() else EnterTransition.None,
            exit = if (animate) slideOutVertically { -it } + fadeOut() else ExitTransition.None,
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
                                    book.blocks.getOrNull(block)?.text.orEmpty(), chapterTitle.orEmpty(),
                                    TextAnchor.ofBlock(book.blocks, book.chapters, block),
                                    blockOf = { it.resolveBlock(book) }
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
                                if (entry != null && onChaptersChanged != null && book.format != com.localdoc.ai.book.BookFormat.WEB) {
                                    DropdownMenuItem(text = { Text("Главы и оглавление…") }, onClick = { showMenu = false; showChapters = true })
                                }
                                DropdownMenuItem(text = { Text("Закладки") }, onClick = { showMenu = false; showBookmarks = true })
                                if (entry != null) {
                                    DropdownMenuItem(
                                        text = { Text("Цитаты и заметки" + if (bookAnnotations.isNotEmpty()) " (${bookAnnotations.size})" else "") },
                                        onClick = { showMenu = false; showAnnotations = true }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (translating) "Перевод: включён" else "Перевод…") },
                                    onClick = { showMenu = false; showTranslate = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (settings.paged) "Читать прокруткой" else "Читать по страницам") },
                                    onClick = { showMenu = false; AppGraph.readerSettings.update { it.copy(paged = !it.paged) } }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (autoScroll) "Остановить автопрокрутку" else "Автопрокрутка") },
                                    onClick = { showMenu = false; autoScroll = !autoScroll; if (autoScroll) uiVisible = false }
                                )
                                DropdownMenuItem(text = { Text("Шрифт и фон") }, onClick = { showMenu = false; showSettings = true })
                                if (entry != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (book.format == com.localdoc.ai.book.BookFormat.WEB) "Скачать книгу целиком…" else "Сохранить как…") },
                                        onClick = { showMenu = false; showSaveAs = true }
                                    )
                                }
                                if (chapterNavigation != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (settings.autoNext) "Автопереход: включён" else "Автопереход к следующей главе") },
                                        onClick = { showMenu = false; AppGraph.readerSettings.update { it.copy(autoNext = !it.autoNext) } }
                                    )
                                }
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
            AnimatedVisibility(
                visible = uiVisible,
                enter = if (animate) fadeIn() + expandVertically() else EnterTransition.None,
                exit = if (animate) fadeOut() + shrinkVertically() else ExitTransition.None
            ) {
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
                AnimatedVisibility(
                    visible = uiVisible,
                    enter = if (animate) fadeIn() + expandVertically() else EnterTransition.None,
                    exit = if (animate) fadeOut() + shrinkVertically() else ExitTransition.None
                ) {
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

        // Полное обновление e-ink: короткая чёрная, затем белая вспышка
        if (refreshPhase != 0) {
            Box(Modifier.fillMaxSize().background(if (refreshPhase == 1) Color.Black else Color.White))
        }
    }

    if (showSettings) ReaderSettingsSheet(onDismiss = { showSettings = false })
    if (showSaveAs && entry != null) SaveAsDialog(entry = entry, onDismiss = { showSaveAs = false })
    if (showChapters && entry != null && onChaptersChanged != null) {
        ChaptersSheet(
            book = book,
            canSaveAs = true,
            onApply = onChaptersChanged,
            onSaveAs = { showChapters = false; showSaveAs = true },
            onOpenModels = { showChapters = false; onOpenModels() },
            onDismiss = { showChapters = false }
        )
    }
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
            onJump = { mark -> showBookmarks = false; goToBlock(mark.resolveBlock(book)) },
            onDelete = { AppGraph.bookmarks.remove(it.id) },
            onDismiss = { showBookmarks = false }
        )
    }
    if (showAnnotations) {
        AnnotationsSheet(
            annotations = bookAnnotations,
            onJump = { a ->
                showAnnotations = false
                goToBlock(AnchorResolver.resolve(a.anchor, book.blocks, book.chapters).block)
            },
            onEdit = { noteEdit = it },
            onDelete = { AppGraph.annotations.remove(it.id) },
            onExport = { epub -> exportBookNotes(entryId, book.title, book.author, epub) { message -> if (message != null) notice = message } },
            onDismiss = { showAnnotations = false }
        )
    }
    noteDraft?.let { anchor ->
        NoteDialog(
            quote = anchor.quote,
            initial = "",
            initialColor = AnnotationColor.YELLOW,
            onDismiss = { noteDraft = null },
            onSave = { text, color ->
                entry?.let { AppGraph.annotations.add(it, anchor, text, color) }
                noteDraft = null
                notice = "Заметка сохранена"
            }
        )
    }
    noteEdit?.let { annotation ->
        NoteDialog(
            quote = annotation.quote,
            initial = annotation.note,
            initialColor = annotation.color,
            onDismiss = { noteEdit = null },
            onSave = { text, color ->
                AppGraph.annotations.updateNote(annotation.id, text)
                if (color != annotation.color) AppGraph.annotations.updateColor(annotation.id, color)
                noteEdit = null
            },
            onDelete = {
                AppGraph.annotations.remove(annotation.id)
                noteEdit = null
            }
        )
    }
    wordLookup?.let { (word, sentence) ->
        WordLookupSheet(
            word = word,
            sentence = sentence,
            entryId = entryId,
            bookTitle = book.title,
            sourceLanguage = sourceCode,
            onOpenModels = { wordLookup = null; onOpenModels() },
            onDismiss = { wordLookup = null }
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

/** Подсвеченные места по блокам: куда приходятся цитаты и заметки в текущем разборе книги. */
private fun annotationMarks(book: Book, annotations: List<Annotation>): Map<Int, List<TextMark>> {
    val result = HashMap<Int, MutableList<TextMark>>()
    for (annotation in annotations) {
        val at = AnchorResolver.resolve(annotation.anchor, book.blocks, book.chapters)
        if (!at.exact) continue
        for (index in at.block..at.endBlock) {
            val length = book.blocks.getOrNull(index)?.text?.length ?: continue
            val from = if (index == at.block) at.start else 0
            val to = if (index == at.endBlock) at.end else length
            if (to > from) result.getOrPut(index) { ArrayList() }.add(TextMark(from until to, annotation.color, annotation.id))
        }
    }
    return result
}

private const val AI_WAIT_FOR_TRANSLATION_MS = 60_000L
private const val AUTO_NEXT_DELAY_MS = 2_500L

/** Листание зоной или кнопкой прокручивает почти экран: последняя строка остаётся на виду. */
private const val SCREEN_SCROLL_FRACTION = 0.9f
private const val PAGE_TURN_MS = 240
private const val PAGE_TURN_SHIFT_DP = 40
private const val EINK_FLASH_MS = 120L
private const val PINCH_MIN_CHANGE = 0.08f
private const val SWIPE_MIN_DP = 72
private const val BRIGHTNESS_STRIP_DP = 28
private const val DEFAULT_BRIGHTNESS = 0.5f
/** Дольше этого нажатие на подсветку — выделение текста, а не открытие заметки. */
private const val MARK_TAP_MAX_MS = 400L
private const val LIST_BULLET = "•  "

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
    entryId: String?,
    referer: String?,
    settings: ReaderSettings,
    paper: PaperColors,
    highlight: SpeechSegment?,
    marks: List<TextMark>,
    onTap: (() -> Unit)?,
    onMarkTap: (String) -> Unit,
    modifier: Modifier
) {
    if (block.type == BlockType.IMAGE) {
        block.image?.let { BookImage(entryId, it, block.text, referer, modifier.padding(vertical = 8.dp)) }
        return
    }
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
    val text = buildBlockText(block, highlight, highlightColor, marks)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val bulletLength = if (block.type == BlockType.LIST_ITEM) LIST_BULLET.length else 0
    Text(
        text = if (block.type == BlockType.LIST_ITEM) AnnotatedString(LIST_BULLET) + text else text,
        onTextLayout = { layout = it },
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
            // Нажатие на подсвеченное место открывает заметку; стоит последним, чтобы сработать раньше остальных нажатий
            .then(
                if (marks.isEmpty()) Modifier else Modifier.pointerInput(marks, bulletLength) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (up.uptimeMillis - down.uptimeMillis > MARK_TAP_MAX_MS) return@awaitEachGesture
                        val offset = layout?.getOffsetForPosition(up.position)?.minus(bulletLength) ?: return@awaitEachGesture
                        marks.markAt(offset)?.let {
                            up.consume()
                            onMarkTap(it.annotationId)
                        }
                    }
                }
            )
    )
}

private fun buildBlockText(
    block: Block,
    highlight: SpeechSegment?,
    highlightColor: Color,
    marks: List<TextMark>
): AnnotatedString = buildAnnotatedString {
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
    for (mark in marks) {
        val s = mark.range.first.coerceIn(0, length)
        val e = (mark.range.last + 1).coerceIn(s, length)
        if (e > s) addStyle(SpanStyle(background = Color(mark.color.argb)), s, e)
    }
    if (highlight != null) {
        val s = highlight.start.coerceIn(0, length)
        val e = highlight.end.coerceIn(s, length)
        addStyle(SpanStyle(background = highlightColor), s, e)
    }
}
