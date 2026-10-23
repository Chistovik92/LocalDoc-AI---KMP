package com.localdoc.ai.notes

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.Chapter
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Привязка к месту в тексте, не зависящая от разбивки на страницы и шрифта: страницы строятся поверх
 * блоков, а позиция хранится в блоках и символах. Чтобы место нашлось и после повторного разбора
 * книги (блоки сдвинулись), вместе с позицией хранится «якорь» — сам фрагмент, текст вокруг него и глава.
 */
@Serializable
data class TextAnchor(
    /** Блок, в котором начинается фрагмент, и смещение в нём (на момент создания). */
    val block: Int,
    val start: Int = 0,
    /** Блок и смещение конца; у выделения в одном блоке [endBlock] == [block]. */
    val endBlock: Int = block,
    val end: Int = start,
    /** Выделенный текст целиком (абзацы разделены переводом строки). */
    val quote: String = "",
    /** Текст перед фрагментом и после него — отличает повторы одной и той же фразы. */
    val prefix: String = "",
    val suffix: String = "",
    /** Глава на момент создания и расстояние от её начала в блоках — запасной путь, если текст изменился. */
    val chapter: String = "",
    val chapterOffset: Int = 0
) {
    /** Первая строка фрагмента: по ней ищется место (многоабзацное выделение ищется по началу). */
    internal val head: String get() = quote.substringBefore('\n')

    companion object {
        const val CONTEXT_CHARS = 32
        private const val BOOKMARK_QUOTE_CHARS = 80

        /** Якорь выделения от ([startBlock], [start]) до ([endBlock], [end]). */
        fun create(
            blocks: List<Block>,
            chapters: List<Chapter>,
            startBlock: Int,
            start: Int,
            endBlock: Int,
            end: Int
        ): TextAnchor {
            val first = startBlock.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
            val last = endBlock.coerceIn(first, (blocks.size - 1).coerceAtLeast(first))
            val firstText = blocks.getOrNull(first)?.text.orEmpty()
            val s = start.coerceIn(0, firstText.length)
            val lastText = blocks.getOrNull(last)?.text.orEmpty()
            val e = if (last == first) end.coerceIn(s, firstText.length) else end.coerceIn(0, lastText.length)
            val quote = buildString {
                for (i in first..last) {
                    val t = blocks.getOrNull(i)?.text.orEmpty()
                    val from = if (i == first) s else 0
                    val to = if (i == last) e else t.length
                    if (i > first) append('\n')
                    append(t.substring(from.coerceAtMost(t.length), to.coerceIn(from.coerceAtMost(t.length), t.length)))
                }
            }
            val chapterIndex = chapters.indexOfLast { it.startBlock <= first }
            return TextAnchor(
                block = first, start = s, endBlock = last, end = e, quote = quote,
                prefix = firstText.substring((s - CONTEXT_CHARS).coerceAtLeast(0), s),
                suffix = lastText.substring(e, (e + CONTEXT_CHARS).coerceAtMost(lastText.length)),
                chapter = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                chapterOffset = first - (chapters.getOrNull(chapterIndex)?.startBlock ?: 0)
            )
        }

        /** Якорь начала блока — для закладки: фрагмент берётся из первых символов блока. */
        fun ofBlock(blocks: List<Block>, chapters: List<Chapter>, block: Int): TextAnchor {
            val text = blocks.getOrNull(block)?.text.orEmpty()
            return create(blocks, chapters, block, 0, block, minOf(text.length, BOOKMARK_QUOTE_CHARS))
        }

        /**
         * Якорь выделения, о котором известен только сам текст (системное меню выделения не говорит, где оно
         * стоит): фрагмент ищется в блоках [candidates]. null — в этих блоках его нет.
         */
        fun locate(blocks: List<Block>, chapters: List<Chapter>, selected: String, candidates: IntRange): TextAnchor? {
            val lines = selected.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.firstOrNull() ?: return null
            for (i in candidates) {
                val text = blocks.getOrNull(i)?.text ?: continue
                val at = text.indexOf(head)
                if (at < 0) continue
                if (lines.size == 1) return create(blocks, chapters, i, at, i, at + head.length)
                // Выделение через несколько абзацев: конец — в блоке, где закончилась последняя строка
                val tail = lines.last()
                val endIndex = (i + 1..minOf(i + lines.size + 2, blocks.lastIndex))
                    .firstOrNull { blocks[it].text.contains(tail) } ?: i
                val endAt = blocks[endIndex].text.indexOf(tail).let { if (it < 0) 0 else it + tail.length }
                return create(blocks, chapters, i, at, endIndex, endAt)
            }
            return locateAcrossBlocks(blocks, chapters, selected, candidates)
        }

        /**
         * Выделение через несколько абзацев, текст которого пришёл без переводов строки (абзацы склеены):
         * ищем блок, чей хвост совпадает с началом выделения, и дальше идём по блокам, пока выделение не кончится.
         * Пробелы при сравнении не учитываются.
         */
        private fun locateAcrossBlocks(blocks: List<Block>, chapters: List<Chapter>, selected: String, candidates: IntRange): TextAnchor? {
            val flat = selected.filterNot { it.isWhitespace() }
            if (flat.length < MIN_ACROSS_CHARS) return null
            for (i in candidates) {
                val text = blocks.getOrNull(i)?.text ?: continue
                val kept = text.indices.filterNot { text[it].isWhitespace() }
                val squeezed = kept.map { text[it] }.joinToString("")
                val tailAt = (0 until squeezed.length).firstOrNull { flat.startsWith(squeezed.substring(it)) && squeezed.length - it < flat.length } ?: continue
                var rest = flat.substring(squeezed.length - tailAt)
                var j = i + 1
                while (rest.isNotEmpty() && j < blocks.size) {
                    val next = blocks[j].text.filterNot { it.isWhitespace() }
                    if (rest.startsWith(next)) {
                        rest = rest.substring(next.length)
                        j++
                        continue
                    }
                    if (!next.startsWith(rest)) return null
                    // Конец выделения внутри блока j: переводим позицию в «сжатом» тексте обратно в обычную
                    val keptNext = blocks[j].text.indices.filterNot { blocks[j].text[it].isWhitespace() }
                    return create(blocks, chapters, i, kept[tailAt], j, keptNext[rest.length - 1] + 1)
                }
                if (rest.isNotEmpty()) return null
                // Выделение кончилось ровно на конце последнего блока
                return create(blocks, chapters, i, kept[tailAt], j - 1, blocks[j - 1].text.length)
            }
            return null
        }

        private const val MIN_ACROSS_CHARS = 8
    }
}

/** Где якорь стоит в текущем разборе книги; [exact] == false — текст не нашёлся, место определено приблизительно. */
data class ResolvedAnchor(val block: Int, val start: Int, val endBlock: Int, val end: Int, val exact: Boolean)

object AnchorResolver {

    private const val SCORE_WEIGHT = 3
    private const val MAX_DISTANCE_PENALTY = 1_000

    /**
     * Находит место якоря. Порядок: то же место, что при создании → ближайшее совпадение фрагмента с похожим
     * окружением → глава и расстояние от её начала → прежний номер блока (в пределах книги).
     */
    fun resolve(anchor: TextAnchor, blocks: List<Block>, chapters: List<Chapter>): ResolvedAnchor {
        if (blocks.isEmpty()) return ResolvedAnchor(0, 0, 0, 0, exact = false)
        val head = anchor.head
        if (head.isNotEmpty() && matchesAt(blocks, anchor.block, anchor.start, head)) {
            return shifted(anchor, 0, anchor.start, blocks)
        }
        val expected = expectedBlock(anchor, blocks, chapters)
        val found = if (head.isNotEmpty()) search(anchor, head, blocks, expected) else null
        if (found != null) return shifted(anchor, found.first - anchor.block, found.second, blocks)
        return ResolvedAnchor(expected, 0, expected, 0, exact = false)
    }

    /** Блок, в котором место должно быть, судя по главе и расстоянию от её начала (иначе — прежний номер). */
    private fun expectedBlock(anchor: TextAnchor, blocks: List<Block>, chapters: List<Chapter>): Int {
        val chapter = anchor.chapter.takeIf { it.isNotBlank() }?.let { title -> chapters.firstOrNull { it.title == title } }
        val base = chapter?.startBlock?.plus(anchor.chapterOffset) ?: anchor.block
        return base.coerceIn(0, blocks.lastIndex)
    }

    private fun search(anchor: TextAnchor, head: String, blocks: List<Block>, expected: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestScore = Int.MIN_VALUE
        for (i in blocks.indices) {
            val text = blocks[i].text
            var from = 0
            while (true) {
                val at = text.indexOf(head, from)
                if (at < 0) break
                from = at + 1
                val score = score(anchor, text, at, head.length) - minOf(abs(i - expected), MAX_DISTANCE_PENALTY)
                if (score > bestScore) {
                    bestScore = score
                    best = i to at
                }
            }
        }
        return best
    }

    /** Чем длиннее совпадение окружения слева и справа, тем выше оценка. */
    private fun score(anchor: TextAnchor, text: String, at: Int, length: Int): Int {
        val left = commonSuffix(text.substring(0, at), anchor.prefix)
        val right = if (anchor.endBlock == anchor.block) commonPrefix(text.substring(at + length), anchor.suffix) else 0
        return (left + right) * SCORE_WEIGHT
    }

    private fun commonSuffix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }

    private fun commonPrefix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }

    private fun matchesAt(blocks: List<Block>, block: Int, offset: Int, head: String): Boolean {
        val text = blocks.getOrNull(block)?.text ?: return false
        return offset >= 0 && text.startsWith(head, offset)
    }

    private fun shifted(anchor: TextAnchor, delta: Int, start: Int, blocks: List<Block>): ResolvedAnchor {
        val block = (anchor.block + delta).coerceIn(0, blocks.lastIndex)
        val endBlock = (anchor.endBlock + delta).coerceIn(block, blocks.lastIndex)
        val end = if (endBlock == block) start + (anchor.end - anchor.start) else anchor.end
        return ResolvedAnchor(block, start, endBlock, end.coerceIn(0, blocks[endBlock].text.length), exact = true)
    }
}
