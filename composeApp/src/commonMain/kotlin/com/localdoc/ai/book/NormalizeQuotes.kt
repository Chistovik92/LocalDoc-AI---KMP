package com.localdoc.ai.book

/**
 * «Умные» типографские кавычки U+2018–U+201D (вместо «ёлочек» и прямых) отсутствуют во многих
 * читалочных шрифтах: на экране они рисуются квадратиками-артефактами. Приводим их к обычным
 * кавычкам, которые есть в любом шрифте. Замена один-в-один (по одному code unit), поэтому
 * индексы стилевых диапазонов ([Span]) в тексте не сдвигаются.
 *
 * Применяется на входе в читалку (парсинг HTML/EPUB/FB2/DOCX/TXT), чтобы чистить и исходный
 * текст, и любой последующий перевод.
 */
object NormalizeQuotes {

    /** Замена одного символа; возвращает исходный символ, если заменять нечего. */
    fun map(c: Char): Char = when (c) {
        '\u201C' /* “ left double  */ -> '«'
        '\u201D' /* ” right double */ -> '»'
        '\u2018' /* ‘ left single  */ -> '\''
        '\u2019' /* ’ right single */ -> '\''
        '\u02BC' /* ʼ modifier apostrophe */ -> '\''
        '\u00A0' /* неразрывный пробел */ -> ' '
        else -> c
    }

    /** Приводит все «умные» кавычки и неразрывные пробелы в строке к обычным. */
    fun map(text: String): String {
        if (text.none { it in REPLACEABLE }) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(map(c))
        return sb.toString()
    }

    internal val REPLACEABLE = setOf('\u201C', '\u201D', '\u2018', '\u2019', '\u02BC', '\u00A0')
}
