package com.localdoc.ai.ui.reader

import com.localdoc.ai.resources.page_of_total
import com.localdoc.ai.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.MaterialTheme
import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.PagePos
import com.localdoc.ai.book.PageSpec
import com.localdoc.ai.book.ScreenLayout
import com.localdoc.ai.book.ScreenPage
import com.localdoc.ai.book.ScreenPager
import com.localdoc.ai.book.SegmentPages
import com.localdoc.ai.book.slice
import com.localdoc.ai.book.slices
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.ReaderSettings
import com.localdoc.ai.notes.TextMark
import com.localdoc.ai.tts.SpeechSegment
import com.localdoc.ai.ui.BookImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Всё, от чего зависит раскладка страниц: другой ключ — другие страницы. */
internal data class ScreenLayoutKey(
    val docKey: String,
    val blockCount: Int,
    val chapterStarts: List<Int>,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val settings: ScreenTypography
)

/** Поля настроек, меняющие вёрстку (тема, яркость и т. п. сюда не входят). */
internal data class ScreenTypography(
    val fontSize: Float,
    val lineHeight: Float,
    val font: com.localdoc.ai.domain.ReaderFont,
    val fontFile: String?,
    val fontWeight: Int,
    val letterSpacing: Float,
    val justify: Boolean,
    val smartJustify: Boolean,
    val margin: Float
) {
    constructor(s: ReaderSettings) : this(s.fontSize, s.lineHeight, s.font, s.fontFile, s.fontWeight, s.letterSpacing, s.justify, s.smartJustify, s.margin)
}

/** Готовые раскладки: возврат к прежним настройкам или повороту не пересчитывает книгу. */
internal object ScreenLayoutCache {
    private const val CAPACITY = 4
    private val entries = LinkedHashMap<ScreenLayoutKey, ScreenLayout>()

    fun get(key: ScreenLayoutKey): ScreenLayout? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: ScreenLayoutKey, layout: ScreenLayout) {
        entries.remove(key)
        entries[key] = layout
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }
}

/** Состояние раскладки для интерфейса: страницы, готовые к этому моменту, и идёт ли расчёт. */
@Stable
internal class ScreenLayoutState {
    var layout by mutableStateOf(ScreenLayout.EMPTY)
    var computing by mutableStateOf(false)
}

/** Сколько новых страниц накапливается между обновлениями раскладки в интерфейсе. */
private const val PUBLISH_EVERY_PAGES = 25

/** Отступы области страницы от краёв экрана, dp: сверху немного, снизу — под номер страницы. */
private const val PAGE_TOP_DP = 12
private const val PAGE_BOTTOM_DP = 36

/**
 * Считает экранные страницы в фоне при каждой смене размера области, шрифта, интервала, полей, выключки
 * или поворота. Сначала раскладывается глава с места чтения [focus], затем соседние и остальные.
 * Интерфейс не блокируется: до готовности страницы [ScreenLayoutState.layout] её не содержит.
 */
@Composable
internal fun rememberScreenLayout(
    book: Book,
    docKey: String,
    settings: ReaderSettings,
    area: IntSize,
    enabled: Boolean,
    focus: () -> PagePos
): ScreenLayoutState {
    val state = remember { ScreenLayoutState() }
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val focusNow by rememberUpdatedState(focus)
    val key = if (!enabled || area.width <= 0 || area.height <= 0) null else ScreenLayoutKey(
        docKey, book.blocks.size, book.chapters.map { it.startBlock }, area.width, area.height,
        density.density, density.fontScale, ScreenTypography(settings)
    )
    LaunchedEffect(key, book) {
        if (key == null) return@LaunchedEffect
        ScreenLayoutCache.get(key)?.let {
            state.layout = it
            state.computing = false
            return@LaunchedEffect
        }
        state.layout = ScreenLayout.EMPTY
        state.computing = true
        try {
            val family = readerFamily(settings, AppGraph.fonts.load(settings.fontFile))
            val spec = PageSpec(pageContentWidthPx(area.width.toFloat(), settings, density), area.height.toFloat())
            val result = withContext(Dispatchers.Default) {
                layoutBook(book, spec, ComposePageMeasurer(TextMeasurer(resolver, density, direction, 1), settings, family, density), focusNow().block) {
                    state.layout = it
                }
            }
            ScreenLayoutCache.put(key, result)
        } finally {
            state.computing = false
        }
    }
    return state
}

/** Раскладывает книгу по главам, начиная с главы, где стоит читатель; после каждой порции страниц зовёт [publish]. */
private suspend fun layoutBook(book: Book, spec: PageSpec, measurer: ComposePageMeasurer, focusBlock: Int, publish: (ScreenLayout) -> Unit): ScreenLayout {
    var layout = ScreenLayout.empty(book.blocks.size, book.chapters)
    val segments = layout.segments
    val first = segments.indexOfLast { it.first <= focusBlock }.coerceAtLeast(0)
    val order = (listOf(first, first + 1, first - 1) + segments.indices).filter { it in segments.indices }.distinct()
    for (s in order) {
        val pages = ArrayList<ScreenPage>()
        var published = 0
        ScreenPager.layoutSegment(book.blocks, segments[s], spec, measurer) { page ->
            pages.add(page)
            if (pages.size - published >= PUBLISH_EVERY_PAGES) {
                published = pages.size
                layout = layout.with(s, SegmentPages(pages.toList(), complete = false))
                publish(layout)
            }
        }
        layout = layout.with(s, SegmentPages(pages.toList(), complete = true))
        publish(layout)
    }
    return layout
}

/** Метка внизу страницы: «N из M»; «~» — число страниц ещё уточняется. */
@Composable
internal fun pageLabel(ordinal: Int, exact: Boolean, total: Int, complete: Boolean): String =
    stringResource(Res.string.page_of_total, (if (exact) "" else "~") + (ordinal + 1), (if (complete) "" else "~") + total)

/**
 * Одна экранная страница: куски блоков по смещениям, без прокрутки. Область страницы сообщает свой размер
 * через [onAreaSize] — по нему считается раскладка.
 */
@Composable
internal fun ScreenPageContent(
    page: ScreenPage?,
    computing: Boolean,
    book: Book,
    entryId: String?,
    settings: ReaderSettings,
    paper: PaperColors,
    speaking: SpeechSegment?,
    marksByBlock: Map<Int, List<TextMark>>,
    label: String?,
    bottomInset: Boolean,
    onAreaSize: (IntSize) -> Unit,
    onBlockTap: ((Int) -> Unit)?,
    onMarkTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    val pageSpec = PageSpec(pageContentWidthPx(area.width.toFloat(), settings, density), area.height.toFloat())
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = PAGE_TOP_DP.dp, bottom = PAGE_BOTTOM_DP.dp)
                .then(if (bottomInset) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .onSizeChanged { area = it; onAreaSize(it) }
        ) {
            if (page != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = READER_COLUMN_MAX_DP.dp)
                        .fillMaxWidth()
                        .padding(horizontal = settings.margin.dp)
                        .clipToBounds()
                ) {
                    Column {
                        for (slice in page.slices(book.blocks)) {
                            val block = book.blocks[slice.block]
                            if (block.type == BlockType.IMAGE) {
                                ImageSlot(block, entryId, book.source, density, pageSpec)
                                continue
                            }
                            BlockText(
                                block = block.slice(slice.from, slice.to),
                                entryId = entryId,
                                referer = null,
                                settings = settings,
                                paper = paper,
                                highlight = speaking?.let { clipSpeech(it, slice.block, slice.from, slice.to) },
                                marks = clipMarks(marksByBlock[slice.block].orEmpty(), slice.from, slice.to),
                                onTap = onBlockTap?.let { tap -> { tap(slice.block) } },
                                onMarkTap = onMarkTap,
                                modifier = Modifier.fillMaxWidth(),
                                continuesPrev = !slice.first,
                                continuesNext = !slice.last
                            )
                        }
                    }
                }
            } else if (computing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = paper.secondary,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (bottomInset) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

/** Картинка в отведённом ей на странице месте: масштабируется под страницу, подпись под ней. */
@Composable
private fun ImageSlot(block: Block, entryId: String?, source: String, density: androidx.compose.ui.unit.Density, spec: PageSpec) {
    val ref = block.image ?: return
    val slot = imageSlotHeightPx(block, spec, density)
    val body = imageBodyHeightPx(block, slot, density)
    Box(Modifier.fillMaxWidth().height(with(density) { slot.toDp() }).clipToBounds()) {
        BookImage(
            entryId, ref, block.text, source.takeIf { it.startsWith("http") },
            Modifier.padding(vertical = 8.dp), maxHeight = with(density) { body.toDp() }
        )
    }
}

private fun clipSpeech(s: SpeechSegment, block: Int, from: Int, to: Int): SpeechSegment? {
    if (s.block != block || s.end <= from || s.start >= to) return null
    return SpeechSegment(block, max(s.start, from) - from, min(s.end, to) - from, s.text)
}

private fun clipMarks(marks: List<TextMark>, from: Int, to: Int): List<TextMark> =
    marks.mapNotNull { m ->
        val start = max(m.range.first, from) - from
        val end = min(m.range.last + 1, to) - from
        if (end > start) m.copy(range = start until end) else null
    }
