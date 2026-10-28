package com.localdoc.ai.tts

/**
 * Деление текста на предложения (русский, английский и родственные языки) для озвучки и подсветки.
 *
 * Знак конца предложения — точка, «!», «?», многоточие; вместе с ним в предложение входят закрывающие кавычки
 * и скобки («Привет.» Она…). Граница ставится, только если дальше пробел и начало нового предложения: заглавная
 * буква, цифра, открывающая кавычка/скобка или тире реплики. Не границы: сокращения («т. е.», «г.», «Mr.»),
 * инициалы («А. С. Пушкин»), номера глав и пунктов («Глава 5.», «1.»), десятичные числа, версии и ссылки
 * (после их точки нет пробела) и продолжение со строчной буквы («— Куда? — спросил он.»).
 */
object SentenceSplitter {
    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'»”“’)]"
    private const val OPENERS = "\"'«„“‘(["
    private const val DASHES = "—–-"

    /** Сокращения, после которых предложение не заканчивается даже перед заглавной буквой. */
    private val NEVER_END = setOf(
        "г", "гг", "ул", "им", "пл", "пр", "просп", "пер", "обл", "рис", "стр", "табл", "см", "ср", "напр", "англ", "нем",
        "фр", "лат", "рус", "проф", "акад", "доц", "ген", "полк", "лейт", "ст", "тов", "гр", "св", "оз", "пос", "дер",
        "кв", "корп", "тел", "илл", "т", "е", "н", "э", "ч", "п", "д", "с", "в", "вв", "к", "м", "о", "изд", "ред", "сб",
        "т.е", "т.к", "т.н", "т.ч", "н.э", "и.о", "т.о",
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "no", "fig", "vol", "pp", "ed", "inc", "ltd", "co",
        "corp", "e.g", "i.e", "approx", "dept", "gen", "col", "capt", "lt", "sgt", "rev", "hon", "mt", "ave", "blvd",
        "cf", "al", "bzw", "usw", "ca", "hr", "nr", "str", "sra", "sres", "ud", "uds"
    )

    /** Сокращения, которыми предложение может заканчиваться («и т. д.»): перед заглавной буквой это граница. */
    private val MAY_END = setOf("т.д", "т.п", "etc", "др", "руб", "коп", "тыс", "млн", "млрд")

    private val CHAPTER_WORDS = setOf(
        "глава", "часть", "раздел", "книга", "том", "пункт", "параграф", "chapter", "part", "section", "book", "volume",
        "kapitel", "teil", "abschnitt", "capítulo", "parte", "sección", "розділ", "частина", "§"
    )

    /** Диапазоны [начало, конец) предложений в [text], без краевых пробелов. */
    fun ranges(text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) { i++; continue }
            var runEnd = i
            while (runEnd + 1 < text.length && text[runEnd + 1] in TERMINATORS) runEnd++
            var end = runEnd + 1
            while (end < text.length && text[end] in CLOSERS) end++
            if (end >= text.length) break
            if (!text[end].isWhitespace()) { i = end; continue }
            var next = end
            while (next < text.length && text[next].isWhitespace()) next++
            if (next >= text.length) break
            val singleDot = runEnd == i && text[i] == '.'
            if (startsSentence(text, next) && !(singleDot && dotIsNotAnEnd(text, i, start))) {
                addTrimmed(result, text, start, end)
                start = next
            }
            i = end
        }
        addTrimmed(result, text, start, text.length)
        return result
    }

    private fun addTrimmed(out: MutableList<Pair<Int, Int>>, text: String, from: Int, to: Int) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s) out.add(s to e)
    }

    /** С позиции [at] начинается новое предложение? */
    private fun startsSentence(text: String, at: Int): Boolean {
        val c = text[at]
        if (c in DASHES) {
            // «— Куда? — спросил он.»: после тире со строчной буквы продолжается то же предложение
            var j = at + 1
            while (j < text.length && (text[j].isWhitespace() || text[j] in DASHES)) j++
            return j >= text.length || !text[j].isLowerCase()
        }
        return c.isUpperCase() || c.isDigit() || c in OPENERS
    }

    /** Одиночная точка [dot] не завершает предложение: сокращение, инициал, номер главы или пункта. */
    private fun dotIsNotAnEnd(text: String, dot: Int, sentenceStart: Int): Boolean {
        val tokenStart = tokenStart(text, dot, sentenceStart)
        val token = text.substring(tokenStart, dot)
        if (token.isEmpty()) return false
        var previousEnd = tokenStart
        while (previousEnd > sentenceStart && text[previousEnd - 1].isWhitespace()) previousEnd--
        val previous = text.substring(tokenStart(text, previousEnd, sentenceStart), previousEnd)

        val lower = token.lowercase()
        // «т. д.», «т. е.»: сокращение из двух частей, записанное с пробелом
        val pair = if (previous.endsWith(".")) (previous + token).lowercase() else null
        if (pair != null) {
            if (pair in MAY_END) return false
            if (pair in NEVER_END) return true
        }
        if (lower in MAY_END) return false
        if (lower in NEVER_END && !(token.length == 1 && token[0].isUpperCase())) return true
        if (token.length == 1 && token[0].isUpperCase() && token[0] != 'I') return true // инициал
        val atStart = text.substring(sentenceStart, tokenStart).isBlank()
        val afterChapterWord = previous.lowercase() in CHAPTER_WORDS
        val numbering = token.all { it.isDigit() } || token.all { it in "IVXLCDM" }
        return numbering && (atStart || afterChapterWord)
    }

    /** Начало слова, заканчивающегося в [end]: до пробела или открывающей кавычки. */
    private fun tokenStart(text: String, end: Int, floor: Int): Int {
        var s = end
        while (s > floor && !text[s - 1].isWhitespace() && text[s - 1] !in OPENERS) s--
        return s
    }
}
