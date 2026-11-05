package com.localdoc.ai.book

import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min

/** Место в книге: блок и смещение символа внутри него. Переживает перерасчёт страниц при другом размере экрана. */
data class PagePos(val block: Int, val offset: Int = 0) : Comparable<PagePos> {
    override fun compareTo(other: PagePos): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)
}

/** Экранная страница: полуинтервал [start, end) — end это начало следующей страницы. */
data class ScreenPage(val start: PagePos, val end: PagePos) {
    operator fun contains(pos: PagePos): Boolean = pos >= start && pos < end
}

/** Часть блока на странице: символы [from, to); [first]/[last] — это начало/конец самого блока. */
data class BlockSlice(val block: Int, val from: Int, val to: Int, val first: Boolean, val last: Boolean)

/** Из каких кусков блоков состоит страница. */
fun ScreenPage.slices(blocks: List<Block>): List<BlockSlice> {
    val out = ArrayList<BlockSlice>()
    var b = start.block
    while (b <= end.block && b < blocks.size) {
        if (b == end.block && end.offset == 0) break
        val length = blocks[b].text.length
        val from = if (b == start.block) start.offset.coerceIn(0, length) else 0
        val to = if (b == end.block) end.offset.coerceIn(from, length) else length
        out.add(BlockSlice(b, from, to, first = from == 0, last = to >= length))
        b++
    }
    return out
}

/** Кусок блока [from, to): текст обрезан, выделения (span) сдвинуты и обрезаны по границам куска. */
fun Block.slice(from: Int, to: Int): Block {
    if (from <= 0 && to >= text.length) return this
    val a = from.coerceIn(0, text.length)
    val b = to.coerceIn(a, text.length)
    val clipped = spans.mapNotNull { s ->
        val start = max(s.start, a) - a
        val end = min(s.end, b) - a
        if (end > start) Span(start, end, s.style) else null
    }
    return copy(text = text.substring(a, b), spans = clipped)
}

/** Область страницы в пикселях: ширина и высота текста без полей. */
data class PageSpec(val width: Float, val height: Float)

/**
 * Раскладка одного блока при заданной ширине: высоты строк и смещения символов, с которых строки начинаются.
 * [top]/[bottom] — отступы вокруг блока (заголовок, цитата, код). [atomic] — блок не делится (картинка).
 */
class BlockMeasure(
    val top: Float,
    val bottom: Float,
    val lineHeights: FloatArray,
    val lineStarts: IntArray,
    val atomic: Boolean = false
) {
    init {
        require(lineHeights.isNotEmpty() && lineHeights.size == lineStarts.size) { "У блока должна быть хотя бы одна строка" }
    }
}

/** Измеритель текста: платформа даёт раскладку блока, чистая логика вёрстки от неё не зависит. */
fun interface PageMeasurer {
    fun measure(block: Block, spec: PageSpec): BlockMeasure
}

/**
 * Экранная вёрстка: страница — столько текста, сколько помещается в область [PageSpec].
 * Абзацы переносятся между страницами по строкам; на краю страницы не остаётся одиночной строки абзаца
 * (минимум [MIN_EDGE_LINES] вверху и внизу, если абзац позволяет); заголовок не бывает последним
 * на странице; глава начинается с новой страницы; картинка не делится и ужимается до высоты страницы.
 */
object ScreenPager {
    const val MIN_EDGE_LINES = 2
    private const val EPS = 0.5f
    private const val YIELD_EVERY = 32

    /** Главы-отрезки: [0, c1), [c1, c2)… — страницы не пересекают границы между ними. */
    fun segments(blockCount: Int, chapters: List<Chapter>): List<IntRange> {
        if (blockCount <= 0) return emptyList()
        val starts = (listOf(0) + chapters.map { it.startBlock }.filter { it in 1 until blockCount }).distinct().sorted()
        return starts.mapIndexed { i, s -> s until (starts.getOrNull(i + 1) ?: blockCount) }
    }

    /** Раскладывает всю книгу подряд. */
    suspend fun layoutAll(blocks: List<Block>, chapters: List<Chapter>, spec: PageSpec, measurer: PageMeasurer): List<ScreenPage> {
        val pages = ArrayList<ScreenPage>()
        for (range in segments(blocks.size, chapters)) layoutSegment(blocks, range, spec, measurer) { pages.add(it) }
        return pages
    }

    /** Раскладывает блоки [range] (одна глава); готовые страницы отдаются по одной и по порядку. */
    suspend fun layoutSegment(blocks: List<Block>, range: IntRange, spec: PageSpec, measurer: PageMeasurer, onPage: (ScreenPage) -> Unit) {
        if (range.isEmpty()) return
        val end = range.last + 1
        val height = max(spec.height, 1f)
        var pageStart = PagePos(range.first, 0)
        var block = range.first
        var line = 0
        var y = 0f
        var slices = 0
        // Подряд идущие заголовки в конце страницы уходят на следующую вместе с текстом
        var headingRun = -1
        var slicesBeforeRun = 0
        var cachedBlock = -1
        var cached: BlockMeasure? = null
        var steps = 0

        fun close(at: PagePos) {
            onPage(ScreenPage(pageStart, at))
            pageStart = at
            y = 0f
            slices = 0
            headingRun = -1
        }

        while (block < end) {
            if (++steps % YIELD_EVERY == 0) yield()
            if (cachedBlock != block) {
                cached = measurer.measure(blocks[block], spec)
                cachedBlock = block
            }
            val m = cached ?: break
            val n = m.lineHeights.size
            val top = if (line == 0) m.top else 0f
            val avail = height - y - top
            var fit = 0
            var used = 0f
            while (line + fit < n && used + m.lineHeights[line + fit] <= avail + EPS) {
                used += m.lineHeights[line + fit]
                fit++
            }
            val remaining = n - line
            if (fit == remaining) {
                y += top + used + m.bottom
                slices++
                if (blocks[block].type == BlockType.HEADING) {
                    if (headingRun < 0) { headingRun = block; slicesBeforeRun = slices - 1 }
                } else headingRun = -1
                block++
                line = 0
                continue
            }
            val splittable = !m.atomic && remaining > 1
            var take = if (splittable) fit else 0
            if (splittable && remaining - take == 1) take--
            if (take < MIN_EDGE_LINES) take = 0
            if (take == 0) {
                if (slices > 0) {
                    if (headingRun >= 0 && slicesBeforeRun > 0) {
                        val heading = headingRun
                        close(PagePos(heading, 0))
                        block = heading
                        line = 0
                    } else {
                        close(PagePos(block, if (line == 0) 0 else m.lineStarts[line]))
                    }
                    continue
                }
                // Пустая страница, а блок не влезает: ставим насильно, иначе вёрстка не сдвинется
                if (!splittable) {
                    y += top + m.lineHeights.sum(line, n) + m.bottom
                    slices++
                    headingRun = -1
                    block++
                    line = 0
                    continue
                }
                take = max(1, fit)
                if (remaining - take == 1 && take > 1) take--
            }
            y += top + m.lineHeights.sum(line, line + take)
            slices++
            headingRun = -1
            line += take
            close(PagePos(block, m.lineStarts[min(line, n - 1)]))
        }
        onPage(ScreenPage(pageStart, PagePos(end, 0)))
    }

    private fun FloatArray.sum(from: Int, to: Int): Float {
        var total = 0f
        for (i in from until to) total += this[i]
        return total
    }
}

/** Страница, найденная в раскладке: глава и номер внутри неё. */
data class PageRef(val segment: Int, val index: Int)

/** Страницы одной главы; [complete] — глава разложена до конца. */
class SegmentPages(val pages: List<ScreenPage>, val complete: Boolean)

/**
 * Раскладка книги по главам. Главы считаются в любом порядке (сначала та, где читатель), поэтому часть
 * из них может быть ещё не готова: общее число страниц тогда оценивается, а номер страницы — приблизительный.
 */
class ScreenLayout(val segments: List<IntRange>, private val parts: List<SegmentPages?>) {

    val isComplete: Boolean get() = segments.indices.all { parts.getOrNull(it)?.complete == true }

    fun page(ref: PageRef): ScreenPage = parts[ref.segment]!!.pages[ref.index]

    /** Страница, содержащая место [pos]; null — эта часть книги ещё не разложена. */
    fun locate(pos: PagePos): PageRef? {
        if (segments.isEmpty()) return null
        var lo = 0
        var hi = segments.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segments[mid].first <= pos.block) lo = mid else hi = mid - 1
        }
        val part = parts.getOrNull(lo) ?: return null
        val pages = part.pages
        if (pages.isEmpty()) return null
        var a = 0
        var b = pages.lastIndex
        while (a < b) {
            val mid = (a + b + 1) ushr 1
            if (pages[mid].start <= pos) a = mid else b = mid - 1
        }
        // За последней готовой страницей незавершённой главы места ещё нет
        if (pos >= pages[a].end && !part.complete) return null
        return PageRef(lo, a)
    }

    fun next(ref: PageRef): PageRef? {
        val pages = parts.getOrNull(ref.segment)?.pages ?: return null
        if (ref.index + 1 < pages.size) return PageRef(ref.segment, ref.index + 1)
        val following = parts.getOrNull(ref.segment + 1)?.pages
        return if (following != null && following.isNotEmpty()) PageRef(ref.segment + 1, 0) else null
    }

    fun previous(ref: PageRef): PageRef? {
        if (ref.index > 0) return PageRef(ref.segment, ref.index - 1)
        val before = parts.getOrNull(ref.segment - 1)?.pages ?: return null
        return if (before.isNotEmpty()) PageRef(ref.segment - 1, before.lastIndex) else null
    }

    /** Есть ли что-то после страницы: следующая страница или ещё не разложенная часть книги. */
    fun hasMoreAfter(ref: PageRef): Boolean {
        val part = parts.getOrNull(ref.segment) ?: return false
        return ref.index + 1 < part.pages.size || !part.complete || ref.segment + 1 < segments.size
    }

    /** Страниц на блок по уже разложенному — для оценки неразложенной части. */
    private fun pagesPerBlock(): Float {
        var pages = 0
        var blocks = 0
        for ((i, part) in parts.withIndex()) {
            if (part == null || part.pages.isEmpty()) continue
            pages += part.pages.size
            blocks += if (part.complete) segments[i].count() else part.pages.last().start.block - segments[i].first + 1
        }
        return if (blocks == 0) DEFAULT_PAGES_PER_BLOCK else pages.toFloat() / blocks
    }

    private fun estimate(segment: Int, ratio: Float): Int {
        val part = parts.getOrNull(segment)
        if (part != null && part.complete) return part.pages.size
        val estimated = (segments[segment].count() * ratio).toInt().coerceAtLeast(1)
        return max(part?.pages?.size ?: 0, estimated)
    }

    /** Номер страницы с нуля по всей книге; вторая часть пары — точен ли он (все предыдущие главы разложены). */
    fun ordinal(ref: PageRef): Pair<Int, Boolean> {
        val ratio = pagesPerBlock()
        var total = 0
        var exact = true
        for (s in 0 until ref.segment) {
            if (parts.getOrNull(s)?.complete != true) exact = false
            total += estimate(s, ratio)
        }
        return total + ref.index to exact
    }

    /** Число страниц книги (оценка, пока не всё разложено). */
    val total: Int
        get() {
            val ratio = pagesPerBlock()
            return segments.indices.sumOf { estimate(it, ratio) }.coerceAtLeast(1)
        }

    /** Страница с порядковым номером [ordinal]; если раскладка до неё не дошла — ближайшая готовая. */
    fun refAtOrdinal(ordinal: Int): PageRef? {
        if (segments.isEmpty()) return null
        val ratio = pagesPerBlock()
        var rest = ordinal.coerceAtLeast(0)
        var lastReady: PageRef? = null
        for (s in segments.indices) {
            val size = estimate(s, ratio)
            val pages = parts.getOrNull(s)?.pages.orEmpty()
            if (rest < size) {
                if (rest < pages.size) return PageRef(s, rest)
                return if (pages.isNotEmpty()) PageRef(s, pages.lastIndex) else lastReady
            }
            rest -= size
            if (pages.isNotEmpty()) lastReady = PageRef(s, pages.lastIndex)
        }
        return lastReady
    }

    /** Новая раскладка с готовыми страницами главы [segment]. */
    fun with(segment: Int, pages: SegmentPages): ScreenLayout {
        val next = ArrayList<SegmentPages?>(segments.size)
        for (i in segments.indices) next.add(if (i == segment) pages else parts.getOrNull(i))
        return ScreenLayout(segments, next)
    }

    companion object {
        private const val DEFAULT_PAGES_PER_BLOCK = 0.25f
        val EMPTY = ScreenLayout(emptyList(), emptyList())

        fun empty(blockCount: Int, chapters: List<Chapter>): ScreenLayout =
            ScreenLayout(ScreenPager.segments(blockCount, chapters), emptyList())
    }
}
