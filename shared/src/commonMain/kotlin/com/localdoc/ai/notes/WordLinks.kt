package com.localdoc.ai.notes

import com.localdoc.ai.lang.Languages

/** Ссылки «Найти в интернете» и «Википедия» для слова из книги. */
object WordLinks {
    private const val HEX = "0123456789ABCDEF"
    private const val UNRESERVED = "-._~"

    fun webSearch(word: String): String = "https://duckduckgo.com/?q=" + encode(word.trim())

    /** Поиск в Википедии на языке слова; язык неизвестен или некорректен — английская. */
    fun wikipedia(word: String, languageCode: String?): String {
        val lang = Languages.baseCode(languageCode)?.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } } ?: "en"
        return "https://$lang.wikipedia.org/w/index.php?search=" + encode(word.trim())
    }

    /** Процентное кодирование UTF-8 (кириллица и пробелы безопасны в адресе). */
    internal fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED) {
                append(c)
            } else {
                append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
            }
        }
    }
}
