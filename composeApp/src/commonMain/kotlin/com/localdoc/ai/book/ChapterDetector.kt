package com.localdoc.ai.book

/**
 * Слова, по которым в тексте узнаются начала глав.
 *
 * [needNumber] — «Глава», «Часть», Chapter…: считаются заголовком только с номером (цифрой, римским числом или словом),
 * иначе обычное предложение вроде «Глава города отвернулась» попало бы в оглавление.
 * [standalone] — «Пролог», «Эпилог», «Интерлюдия»…: заголовок и без номера.
 */
data class ChapterKeywords(val needNumber: Set<String>, val standalone: Set<String>) {
    companion object {
        private val NEED_NUMBER = setOf(
            "глава", "часть", "книга", "том", "раздел", "акт", "сезон",
            "chapter", "part", "book", "volume", "section", "act"
        )
        private val STANDALONE = setOf(
            "пролог", "эпилог", "предисловие", "послесловие", "интерлюдия", "введение", "заключение",
            "prologue", "epilogue", "preface", "foreword", "afterword", "interlude", "introduction", "conclusion"
        )

        val DEFAULT = ChapterKeywords(NEED_NUMBER, STANDALONE)

        /**
         * Список слов из настроек: через запятую, точку с запятой или с новой строки. Слова из стандартного «с номером»
         * остаются такими, остальные могут стоять и с номером, и без («Песнь третья», «Сказ»).
         */
        fun parse(text: String): ChapterKeywords {
            val words = text.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val numbered = words.filter { it in NEED_NUMBER }.toSet()
            return ChapterKeywords(numbered, words - numbered)
        }
    }

    /** Список для показа и правки в настройках. */
    fun toText(): String = (needNumber + standalone).joinToString(", ")
}

/**
 * Поиск начал глав прямо в тексте: заголовки верхних уровней, строки «Глава 5. Название», «Часть вторая»,
 * «Пролог», отдельные номера «1», «2», «3»… подряд. Работает без нейросети и для любого формата.
 */
object ChapterDetector {

    /** Заголовок главы короткий: длинный абзац, начинающийся со слова «Глава», — это текст, а не заголовок. */
    private const val MAX_TITLE_CHARS = 90
    private const val MAX_TITLE_WORDS = 12
    private const val MAX_NUMBER_TOKENS = 3
    private const val MIN_BARE_NUMBERS = 3
    private const val MAX_BARE_NUMBER_VALUE = 3999

    private val RU_CARDINALS = setOf(
        "один", "одна", "два", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
        "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
        "восемнадцать", "девятнадцать", "двадцать", "тридцать", "сорок", "пятьдесят"
    )
    private val RU_ORDINAL_STEMS = listOf(
        "перв", "втор", "трет", "четвёрт", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят", "одиннадцат",
        "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат",
        "двадцат", "тридцат", "сороков", "пятидесят"
    )
    private val EN_NUMBERS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh",
        "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
        "twentieth", "thirtieth"
    )
    private const val ROMAN_DIGITS = "IVXLCDM"
    private const val TITLE_SEPARATORS = ".:)—–-,"
    private const val QUOTES = "«„“\"'"

    /** Начала глав в порядке следования: ключевые слова, заголовки верхних уровней и ряд отдельных номеров. */
    fun detect(blocks: List<Block>, keywords: ChapterKeywords = ChapterKeywords.DEFAULT): List<Chapter> {
        val found = HashMap<Int, String>()
        blocks.forEachIndexed { index, block ->
            if (block.type != BlockType.PARAGRAPH && block.type != BlockType.HEADING) return@forEachIndexed
            val text = block.text.trim()
            val isTopHeading = block.type == BlockType.HEADING && block.level <= 2 && text.isNotEmpty()
            if (isTopHeading || isMarker(text, keywords)) found[index] = text.take(120)
        }
        for ((index, text) in bareNumberRun(blocks)) if (index !in found) found[index] = text
        return found.entries.sortedBy { it.key }.map { Chapter(it.value, it.key) }
    }

    private fun isMarker(text: String, keywords: ChapterKeywords): Boolean {
        val line = text.trim('*', '_', '#', ' ', '[', ']')
        if (line.isEmpty() || line.length > MAX_TITLE_CHARS) return false
        val wordEnd = line.indexOfFirst { !it.isLetter() }.let { if (it < 0) line.length else it }
        if (wordEnd == 0) return false
        val word = line.substring(0, wordEnd).lowercase()
        val needsNumber = word in keywords.needNumber
        if (!needsNumber && word !in keywords.standalone) return false

        val (numbers, after) = takeNumber(line.substring(wordEnd))
        if (needsNumber && numbers == 0) return false
        return isTitleTail(after)
    }

    /** Съедает номер после слова («5», «IV», «двадцать вторая», «№ 7»); возвращает число съеденных слов и остаток строки. */
    private fun takeNumber(rest: String): Pair<Int, String> {
        var s = rest.trimStart()
        if (s.startsWith("№") || s.startsWith("#")) s = s.substring(1).trimStart()
        var count = 0
        while (count < MAX_NUMBER_TOKENS) {
            val end = s.indexOfFirst { !it.isLetterOrDigit() }.let { if (it < 0) s.length else it }
            if (end == 0 || !isNumberToken(s.substring(0, end))) break
            count++
            s = s.substring(end)
            // «двадцать-вторая» — слова через дефис; дефис перед пробелом — уже разделитель названия
            s = if (s.startsWith("-") && s.length > 1 && s[1].isLetter()) s.substring(1) else s.trimStart()
        }
        return count to s.trimStart()
    }

    private fun isNumberToken(token: String): Boolean {
        if (token.all { it.isDigit() }) return token.length <= 4
        if (token.all { it in ROMAN_DIGITS }) return token.length <= 8
        val low = token.lowercase()
        return low in RU_CARDINALS || low in EN_NUMBERS ||
            RU_ORDINAL_STEMS.any { low.startsWith(it) && low.length <= it.length + 6 }
    }

    /** После слова и номера допустимо ничего, разделитель с названием или название с заглавной буквы. */
    private fun isTitleTail(after: String): Boolean {
        if (after.isEmpty()) return true
        if (after.split(' ', '\t').count { it.isNotEmpty() } > MAX_TITLE_WORDS) return false
        val first = after.first()
        if (first in TITLE_SEPARATORS) return true
        return first.isUpperCase() || first.isDigit() || first in QUOTES
    }

    /** Отдельные строки «1», «2», «3»… идущие по порядку с единицы: такой ряд — номера глав, а не номера страниц. */
    private fun bareNumberRun(blocks: List<Block>): List<Pair<Int, String>> {
        val run = ArrayList<Pair<Int, String>>()
        var expected = 1
        blocks.forEachIndexed { index, block ->
            if (block.type != BlockType.PARAGRAPH) return@forEachIndexed
            val text = block.text.trim()
            if (bareNumberValue(text) == expected) {
                run.add(index to text)
                expected++
            }
        }
        return if (run.size >= MIN_BARE_NUMBERS) run else emptyList()
    }

    private fun bareNumberValue(text: String): Int? {
        val core = text.trimEnd('.', ')')
        if (core.isEmpty() || core.length > 8) return null
        return when {
            core.all { it.isDigit() } -> core.toIntOrNull()
            core.all { it in ROMAN_DIGITS } -> romanValue(core)
            else -> null
        }?.takeIf { it in 1..MAX_BARE_NUMBER_VALUE }
    }

    private fun romanValue(roman: String): Int {
        fun digit(c: Char) = when (c) {
            'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; else -> 1000
        }
        var total = 0
        for ((i, c) in roman.withIndex()) {
            val value = digit(c)
            total += if (i + 1 < roman.length && value < digit(roman[i + 1])) -value else value
        }
        return total
    }
}
