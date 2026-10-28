package com.localdoc.ai.book

import com.localdoc.ai.tts.SentenceSplitter

/** Что именно исправлено: для предпросмотра и подсчёта. */
enum class RepairKind { JOIN, HYPHEN, SPLIT, REMOVE, SPACES }

/** Одно исправление: [before] и [after] — короткие выдержки до и после. */
data class RepairChange(val kind: RepairKind, val block: Int, val before: String, val after: String)

/**
 * Результат исправления: новые блоки и соответствие номеров. [newIndex] говорит, где теперь лежит прежний блок:
 * по нему переносятся прогресс чтения и оглавление; закладки и цитаты ищут место по тексту (`TextAnchor`).
 */
class RepairResult(val blocks: List<Block>, private val map: List<Int>, val changes: List<RepairChange>) {
    fun newIndex(old: Int): Int = if (map.isEmpty()) 0 else map[old.coerceIn(0, map.lastIndex)]

    /** Два шага подряд как один: [next] применён к [blocks]. */
    fun then(next: RepairResult): RepairResult = RepairResult(next.blocks, map.map(next::newIndex), changes + next.changes)

    fun count(kind: RepairKind): Int = changes.count { it.kind == kind }
}

/**
 * Программное исправление «рваного» текста (PDF, TXT, DOC/WPS, веб-страницы): склейка строк, оборванных посреди
 * предложения, перенос слов «сло-\nво», слипшиеся абзацы, лишние пробелы, колонтитулы и номера страниц.
 * Правила консервативные: заголовки, списки, код и реплики диалога («— …») не склеиваются, спорное остаётся
 * как есть — такие места показывает [suspects], их можно проверить нейросетью.
 */
object TextRepair {
    private const val TERMINAL = ".!?…:;»\"”)’'"
    private const val HYPHENS = "-‐­"
    private const val SOFT_HYPHEN = '­'
    private const val EXCERPT = 60
    private const val SUSPECT_MIN_LINE = 40
    private const val HEADER_MIN = 6
    private const val HEADER_MAX = 80
    private const val HEADER_REPEATS = 3
    private const val LONG_PARAGRAPH = 1800
    private const val LONG_MIN_SENTENCES = 6
    private const val ASCENDING_SHARE = 0.8

    private val PAGE_NUMBER = Regex(
        "^[\\[(\\-–—\\s]*(?:(?:страница|стр|с|page|p|pág|seite|сторінка)\\.?\\s*)?(\\d{1,4})" +
            "(?:\\s*(?:/|из|of|von|de|з)\\s*\\d{1,4})?[\\])\\-–—\\s]*$",
        RegexOption.IGNORE_CASE
    )
    private val CHAPTER_START = Regex(
        "^(глава|часть|раздел|пролог|эпилог|книга|том|chapter|part|book|prologue|epilogue|section|volume|kapitel|teil|capítulo|parte|розділ|частина)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DIGITS = Regex("\\d+")
    private val WHITESPACE = Regex("\\s+")
    private val WRAPPED_HYPHEN = Regex("(\\p{L}+)[-‐][ \\t]*\\r?\\n[ \\t]*(\\p{Ll}\\p{L}*)")
    private val LINE_BREAK = Regex("[ \\t]*\\r?\\n\\s*")
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val SPACE_BEFORE_MARK = Regex("(?<=[\\p{L}\\p{N}»”\"')\\]])[ ]+(?=[,;:!?….](?:[\\s»”\"')\\]]|$))")
    private val BRACKET_INNER = Regex("(?<=\\()\\s+|\\s+(?=\\))")
    private val GLUED = Regex("(?<![\\p{L}\\p{N}./@:_\\-#])(\\p{L}{2,})([.!?…])(\\p{Lu}\\p{Ll}{2,})")

    // Дефис на конце строки остаётся, если слово — составное. Без словаря берём только надёжные случаи.
    private val KEEP_BEFORE = setOf("self", "mother", "father", "half", "cross", "long", "short", "high", "low", "well")
    private val ENGLISH_ENDINGS = setOf(
        "ing", "ings", "ed", "er", "ers", "est", "ly", "ness", "ment", "ments", "tion", "tions", "ous", "ful", "less",
        "able", "ible", "ity", "ies", "es", "ish", "ance", "ence", "al", "ive"
    )
    private val KEEP_AFTER = setOf("то", "либо", "нибудь", "таки", "ка", "де", "тка", "за", "под", "первых", "вторых", "третьих")
    private val PO_ADVERBS = setOf("другому", "новому", "старому", "прежнему", "иному", "разному", "доброму")
    private val NOT_A_SENTENCE_END = setOf("prof", "mrs", "mr", "dr", "etc", "fig", "inc", "ltd", "vol", "ave", "рис", "стр", "проф", "акад")
    private val DOMAINS = setOf("com", "org", "net", "ru", "uk", "de", "es", "io", "info", "edu", "gov", "su", "ua", "app", "dev", "рф")

    /** Блоки после программного исправления. */
    fun repair(blocks: List<Block>): RepairResult {
        val changes = ArrayList<RepairChange>()
        var items = blocks.mapIndexed { i, b -> Item(b, listOf(i)) }
        items = dropNoise(items, changes)
        items = mergeCuts(items, changes)
        items = fixInline(items, changes)
        return finish(blocks.size, items, changes)
    }

    /** Исправление всей книги: блоки и оглавление; книга без правок возвращается как есть. */
    fun repairBook(book: Book): Book {
        val result = repair(book.blocks)
        if (result.changes.isEmpty()) return book
        return book.copy(blocks = result.blocks, chapters = remapChapters(book.chapters, result))
    }

    fun remapChapters(chapters: List<Chapter>, result: RepairResult): List<Chapter> =
        chapters.map { Chapter(it.title, result.newIndex(it.startBlock)) }

    /**
     * Спорные места: номера блоков i, после которых разрыв между i и i+1 может быть переносом строки внутри
     * предложения (строка длинная, без точки, а следующая начинается с заглавной — например, с имени).
     */
    fun suspects(blocks: List<Block>): List<Int> =
        (0 until blocks.size - 1).filter { i ->
            val a = blocks[i]
            val b = blocks[i + 1]
            a.type == BlockType.PARAGRAPH && b.type == BlockType.PARAGRAPH && a.text.length >= SUSPECT_MIN_LINE &&
                !a.text.trimEnd().endsWithTerminal() && b.text.trimStart().firstOrNull()?.isUpperCase() == true
        }

    /** Очень длинные абзацы: возможно, в них слиплось несколько. */
    fun longParagraphs(blocks: List<Block>): List<Int> =
        blocks.indices.filter { i ->
            val b = blocks[i]
            plain(b) && b.text.length > LONG_PARAGRAPH && SentenceSplitter.ranges(b.text).size >= LONG_MIN_SENTENCES
        }

    /**
     * Изменение по подсказке нейросети: склеить блок из [joinAfter] со следующим и разрезать блоки по
     * смещениям [splits] (номер блока → места в его тексте).
     */
    fun edit(blocks: List<Block>, joinAfter: Set<Int> = emptySet(), splits: Map<Int, List<Int>> = emptyMap()): RepairResult {
        val changes = ArrayList<RepairChange>()
        val out = ArrayList<Item>()
        blocks.forEachIndexed { i, block ->
            val parts = cut(block, splits[i].orEmpty())
            if (parts.size > 1) changes.add(RepairChange(RepairKind.SPLIT, i, excerpt(block.text), parts.joinToString(" ¶ ") { excerpt(it.text) }))
            parts.forEachIndexed { k, part ->
                val item = Item(part, if (k == 0) listOf(i) else emptyList())
                val last = out.lastOrNull()
                if (k == 0 && last != null && (i - 1) in joinAfter) out[out.lastIndex] = joinItems(last, item, changes, i - 1)
                else out.add(item)
            }
        }
        return finish(blocks.size, out, changes)
    }

    private class Item(val block: Block, val origins: List<Int>)

    private fun plain(b: Block) = b.type == BlockType.PARAGRAPH && b.spans.isEmpty()

    private fun String.endsWithTerminal() = isNotEmpty() && last() in TERMINAL

    private fun excerpt(text: String): String = if (text.length <= EXCERPT * 2) text else text.take(EXCERPT) + "…" + text.takeLast(EXCERPT)

    private fun finish(oldCount: Int, items: List<Item>, changes: List<RepairChange>): RepairResult {
        val map = IntArray(oldCount) { -1 }
        items.forEachIndexed { k, item -> item.origins.forEach { map[it] = k } }
        for (i in oldCount - 1 downTo 0) {
            if (map[i] == -1) map[i] = if (i + 1 < oldCount) map[i + 1] else items.lastIndex.coerceAtLeast(0)
        }
        return RepairResult(items.map { it.block }, map.toList(), changes)
    }

    // ---- колонтитулы и номера страниц ----

    private fun dropNoise(items: List<Item>, changes: MutableList<RepairChange>): List<Item> {
        val drop = pageNumbers(items) + runningHeaders(items)
        if (drop.isEmpty()) return items
        drop.sorted().forEach { i -> changes.add(RepairChange(RepairKind.REMOVE, items[i].origins.firstOrNull() ?: 0, items[i].block.text, "")) }
        return items.filterIndexed { i, _ -> i !in drop }
    }

    private fun pageNumbers(items: List<Item>): Set<Int> {
        val numbers = HashMap<Int, Int>()
        items.forEachIndexed { i, item ->
            if (plain(item.block)) PAGE_NUMBER.matchEntire(item.block.text.trim())?.let { numbers[i] = it.groupValues[1].toInt() }
        }
        if (numbers.isEmpty()) return emptySet()
        // Номер посреди оборванного предложения — точно номер страницы
        val result = HashSet<Int>()
        for (i in numbers.keys) {
            val before = items.getOrNull(i - 1)?.block
            val after = items.getOrNull(i + 1)?.block
            if (before != null && after != null && cutAt(before, after)) result.add(i)
        }
        // Иначе — только ряд возрастающих одиночных номеров, не идущих подряд
        val separate = numbers.keys.filter { (it - 1) !in numbers && (it + 1) !in numbers }.sorted()
        if (separate.size >= 2) {
            val values = separate.map { numbers.getValue(it) }
            val ascending = values.zipWithNext().count { (a, b) -> b >= a }
            if (ascending >= ASCENDING_SHARE * (values.size - 1)) result.addAll(separate)
        }
        return result
    }

    private fun runningHeaders(items: List<Item>): Set<Int> {
        val groups = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, item ->
            val text = item.block.text.trim()
            if (!plain(item.block) || text.length !in HEADER_MIN..HEADER_MAX || text.count { it.isLetter() } < 3) return@forEachIndexed
            if (text.endsWithTerminal() || text[0] in "—–-•*" || CHAPTER_START.containsMatchIn(text)) return@forEachIndexed
            groups.getOrPut(text.replace(DIGITS, "#").lowercase().replace(WHITESPACE, " ")) { ArrayList() }.add(i)
        }
        return groups.values
            .filter { it.size >= HEADER_REPEATS && it.zipWithNext().all { (a, b) -> b - a >= 2 } }
            .flatten().toSet()
    }

    // ---- склейка строк, оборванных посреди предложения ----

    private fun cutAt(a: Block, b: Block): Boolean =
        a.type == BlockType.PARAGRAPH && b.type == BlockType.PARAGRAPH && a.text.isNotBlank() &&
            !a.text.trimEnd().endsWithTerminal() && b.text.trimStart().firstOrNull()?.isLowerCase() == true

    private fun mergeCuts(items: List<Item>, changes: MutableList<RepairChange>): List<Item> {
        val out = ArrayList<Item>()
        for (item in items) {
            val last = out.lastOrNull()
            if (last != null && cutAt(last.block, item.block)) out[out.lastIndex] = joinItems(last, item, changes, last.origins.firstOrNull() ?: 0)
            else out.add(item)
        }
        return out
    }

    private fun joinItems(a: Item, b: Item, changes: MutableList<RepairChange>, at: Int): Item {
        val left = a.block.text.trimEnd()
        val right = b.block.text.trimStart()
        val wrapped = left.length >= 2 && left.last() in HYPHENS && left[left.length - 2].isLetter()
        val dropHyphen = wrapped && (left.last() == SOFT_HYPHEN || !keepsHyphen(left.dropLast(1), right))
        val head = if (dropHyphen) left.dropLast(1) else left
        val separator = if (wrapped) "" else " "
        val text = head + separator + right
        val shift = head.length + separator.length - (b.block.text.length - right.length)
        val spans = a.block.spans.map { it.copy(end = it.end.coerceAtMost(head.length)) } +
            b.block.spans.map { Span(it.start + shift, it.end + shift, it.style) }
        val kind = if (wrapped) RepairKind.HYPHEN else RepairKind.JOIN
        changes.add(RepairChange(kind, at, left.takeLast(EXCERPT) + " ⏎ " + right.take(EXCERPT), head.takeLast(EXCERPT) + separator + right.take(EXCERPT)))
        return Item(a.block.copy(text = text, spans = spans), a.origins + b.origins)
    }

    private fun keepsHyphen(before: String, after: String): Boolean {
        val prefix = before.takeLastWhile { it.isLetter() }.lowercase()
        val suffix = after.takeWhile { it.isLetter() }.lowercase()
        return when {
            prefix == "по" -> suffix.endsWith("ски") || suffix.endsWith("ему") || suffix in PO_ADVERBS
            prefix in KEEP_BEFORE -> suffix.length >= 3 && suffix !in ENGLISH_ENDINGS
            else -> suffix in KEEP_AFTER
        }
    }

    // ---- пробелы, переносы внутри блока, слипшиеся абзацы ----

    private fun fixInline(items: List<Item>, changes: MutableList<RepairChange>): List<Item> {
        val out = ArrayList<Item>()
        for (item in items) {
            val block = item.block
            val editable = (block.type == BlockType.PARAGRAPH || block.type == BlockType.QUOTE) && block.spans.isEmpty()
            if (!editable) { out.add(item); continue }
            val origin = item.origins.firstOrNull() ?: 0
            val unwrapped = unwrapHyphens(block.text)
            val spaced = fixSpaces(unwrapped)
            if (unwrapped != block.text) changes.add(RepairChange(RepairKind.HYPHEN, origin, excerpt(block.text), excerpt(unwrapped)))
            if (spaced != unwrapped) changes.add(RepairChange(RepairKind.SPACES, origin, excerpt(unwrapped), excerpt(spaced)))
            val pieces = splitGlued(spaced)
            if (pieces.size > 1) changes.add(RepairChange(RepairKind.SPLIT, origin, excerpt(spaced), pieces.joinToString(" ¶ ") { excerpt(it) }))
            pieces.forEachIndexed { k, piece -> out.add(Item(block.copy(text = piece), if (k == 0) item.origins else emptyList())) }
        }
        return out
    }

    private fun unwrapHyphens(text: String): String {
        val s = WRAPPED_HYPHEN.replace(text.replace(SOFT_HYPHEN.toString(), "")) { m ->
            val first = m.groupValues[1]
            val second = m.groupValues[2]
            if (keepsHyphen(first, second)) "$first-$second" else first + second
        }
        return LINE_BREAK.replace(s, " ")
    }

    private fun fixSpaces(text: String): String =
        BRACKET_INNER.replace(SPACE_BEFORE_MARK.replace(MULTI_SPACE.replace(text, " "), ""), "")

    private fun splitGlued(text: String): List<String> {
        val cuts = ArrayList<Int>()
        for (m in GLUED.findAll(text)) {
            val word = m.groupValues[1]
            val mark = m.groupValues[2]
            val next = m.groupValues[3]
            if (mark == "." && (word.length < 3 || word.lowercase() in NOT_A_SENTENCE_END)) continue
            if (next.lowercase() in DOMAINS) continue
            cuts.add(m.groups[3]!!.range.first)
        }
        return if (cuts.isEmpty()) listOf(text) else cut(Block(BlockType.PARAGRAPH, text), cuts).map { it.text }
    }

    private fun cut(block: Block, offsets: List<Int>): List<Block> {
        val points = offsets.filter { it in 1 until block.text.length }.distinct().sorted()
        if (points.isEmpty() || block.spans.isNotEmpty()) return listOf(block)
        val bounds = listOf(0) + points + block.text.length
        return bounds.zipWithNext().map { (s, e) -> block.copy(text = block.text.substring(s, e).trim()) }.filter { it.text.isNotEmpty() }
    }
}
