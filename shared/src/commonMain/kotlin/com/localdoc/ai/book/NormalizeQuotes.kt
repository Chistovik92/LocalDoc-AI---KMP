package com.localdoc.ai.book

/**
 * Типографская пунктуация (`«»`, `“”`, `‘’`, `…`, неразрывный пробел) отсутствует во многих
 * читалочных шрифтах — на экране она рисуется квадратиками-артефактами. Приводим её к обычным
 * ASCII-символам (`"`, `'`, `...`), которые есть в любом шрифте (включая минимальные пользовательские).
 *
 * Применяется на входе в читалку (парсинг HTML/EPUB/FB2/DOCX/TXT), при загрузке из библиотеки и к
 * результатам перевода — чистится и исходный текст, и любой последующий перевод.
 *
 * Кавычки и неразрывный пробел заменяются один-в-один (по одному code unit), поэтому индексы
 * стилевых диапазонов ([Span]) в тексте не сдвигаются. Многоточие `…` раскрывается в `...` —
 * единственная замена с изменением длины (учитывается в [RunTextBuilder.append] и [map]).
 */
object NormalizeQuotes {

    /** Замена одного символа один-в-один; возвращает исходный символ, если заменять нечего. */
    fun map(c: Char): Char = when (c) {
        '\u201C' /* “ left double   */ -> '"'
        '\u201D' /* ” right double  */ -> '"'
        '\u00AB' /* « left guillemet  */ -> '"'
        '\u00BB' /* » right guillemet */ -> '"'
        '\u2018' /* ‘ left single   */ -> '\''
        '\u2019' /* ’ right single  */ -> '\''
        '\u02BC' /* ʼ modifier apostrophe */ -> '\''
        '\u2039' /* ‹ single left guillemet  */ -> '\''
        '\u203A' /* › single right guillemet */ -> '\''
        '\u00A0' /* неразрывный пробел */ -> ' '
        else -> c
    }

    /** Замена с изменением длины (многоточие) или null, если у символа её нет. */
    fun expand(c: Char): String? = when (c) {
        '\u2026' /* … horizontal ellipsis */ -> "..."
        else -> null
    }

    /** Приводит все типографские символы в строке к обычным. */
    fun map(text: String): String {
        if (text.none { it in REPLACEABLE }) return text
        val sb = StringBuilder(text.length)
        for (c in text) expand(c)?.let { sb.append(it) } ?: sb.append(map(c))
        return sb.toString()
    }

    internal val REPLACEABLE = setOf(
        '\u201C', '\u201D', '\u00AB', '\u00BB', '\u2018', '\u2019', '\u02BC', '\u2039', '\u203A',
        '\u2026', '\u00A0'
    )
}
