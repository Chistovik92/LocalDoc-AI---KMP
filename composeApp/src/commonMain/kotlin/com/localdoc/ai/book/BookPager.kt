package com.localdoc.ai.book

import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.page_large
import com.localdoc.ai.resources.page_medium
import com.localdoc.ai.resources.page_small
import org.jetbrains.compose.resources.StringResource
import kotlinx.serialization.Serializable

/** Страница книги: полуинтервал блоков [startBlock, endBlock). */
data class BookPage(val index: Int, val startBlock: Int, val endBlock: Int) {
    val blockCount: Int get() = endBlock - startBlock
    operator fun contains(block: Int): Boolean = block in startBlock until endBlock
}

/** Порция текста для озвучки, перевода и ИИ (не экранная страница — та считается в [ScreenPager]). */
@Serializable
enum class PageSize(val label: StringResource, val chars: Int) {
    SMALL(Res.string.page_small, 1_500),
    MEDIUM(Res.string.page_medium, 3_000),
    LARGE(Res.string.page_large, 6_000)
}

/**
 * Разбиение книги на страницы. В нейросеть (чтение, озвучка, перевод, ИИ-суфлёр) отдаётся только
 * текущая страница (и следующая — для озвучки без пауз), а не вся книга целиком.
 *
 * Правила: страница набирается блоками до [targetChars] символов (блок не режется — один длинный
 * блок образует свою страницу); начало главы всегда начинает новую страницу; заголовок не остаётся
 * последним блоком страницы — переносится к своему тексту.
 */
class BookPager private constructor(val pages: List<BookPage>, val blockCount: Int) {

    val size: Int get() = pages.size

    fun page(index: Int): BookPage = pages[index.coerceIn(0, pages.lastIndex)]

    /** Индекс страницы, в которую попадает блок (блок за пределами книги — крайняя страница). */
    fun pageOfBlock(block: Int): Int {
        if (pages.isEmpty()) return 0
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].startBlock <= block) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Доля прочитанного по страницам, 0..1. */
    fun progressOfPage(index: Int): Float = if (pages.size <= 1) 0f else index.coerceIn(0, pages.lastIndex).toFloat() / pages.lastIndex

    companion object {
        const val DEFAULT_CHARS = 3_000

        /** Картинка занимает на странице место примерно трёх абзацев. */
        const val IMAGE_WEIGHT = 600

        fun build(blocks: List<Block>, chapters: List<Chapter> = emptyList(), targetChars: Int = DEFAULT_CHARS): BookPager {
            if (blocks.isEmpty()) return BookPager(emptyList(), 0)
            val chapterStarts = chapters.map { it.startBlock }.filter { it > 0 }.toHashSet()
            val limit = targetChars.coerceAtLeast(200)
            val starts = ArrayList<Int>()
            starts.add(0)
            var chars = 0
            for (i in blocks.indices) {
                val length = if (blocks[i].isImage) IMAGE_WEIGHT else blocks[i].text.length
                val chapterBreak = i > 0 && i in chapterStarts
                if (i > starts.last() && (chapterBreak || chars + length > limit)) {
                    starts.add(i)
                    chars = 0
                }
                chars += length
            }
            // Заголовок в конце страницы уходит на следующую вместе с текстом
            for (k in 1 until starts.size) {
                val start = starts[k]
                val last = start - 1
                if (last > starts[k - 1] && blocks[last].type == BlockType.HEADING && start !in chapterStarts) {
                    starts[k] = last
                }
            }
            val pages = starts.mapIndexed { n, s -> BookPage(n, s, if (n + 1 < starts.size) starts[n + 1] else blocks.size) }
            return BookPager(pages, blocks.size)
        }
    }
}
