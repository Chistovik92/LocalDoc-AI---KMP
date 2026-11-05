package com.localdoc.ai.book

/**
 * Определение кодировки текстовых файлов без платформенных API:
 * BOM (UTF-8 / UTF-16), строгая проверка UTF-8, иначе Windows-1251 (типично для русских TXT/FB2).
 */
object TextDecoder {

    fun decode(bytes: ByteArray, declaredEncoding: String? = null): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.decodeToString(3, bytes.size)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return decodeUtf16(bytes, 2, littleEndian = true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return decodeUtf16(bytes, 2, littleEndian = false)
        }
        val declared = declaredEncoding?.lowercase()?.replace("_", "-")
        if (declared != null && declared in CP1251_NAMES) return decodeCp1251(bytes)
        return if (isValidUtf8(bytes)) bytes.decodeToString() else decodeCp1251(bytes)
    }

    /** Кодировка из XML-пролога: `<?xml version="1.0" encoding="windows-1251"?>`. */
    fun xmlDeclaredEncoding(bytes: ByteArray): String? {
        val head = bytes.copyOf(minOf(bytes.size, 200)).decodeToString()
        return XML_ENCODING.find(head)?.groupValues?.get(1)
    }

    fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size && extra > 0) {
                // Обрезанный хвост файла допустим — не повод считать его cp1251
                return i > 0
            }
            for (k in 1..extra) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }

    fun decodeCp1251(bytes: ByteArray): String {
        val chars = CharArray(bytes.size)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            chars[i] = if (b < 0x80) b.toChar() else CP1251_HIGH[b - 0x80]
        }
        return chars.concatToString()
    }

    private fun decodeUtf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - offset) / 2)
        var i = offset
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            sb.append((if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar())
            i += 2
        }
        return sb.toString()
    }

    private val XML_ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9_\-]+)["']""")
    private val CP1251_NAMES = setOf("windows-1251", "cp1251", "win-1251", "x-cp1251")

    // Символы 0x80..0xFF кодовой страницы Windows-1251
    private val CP1251_HIGH: CharArray = (
        "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏ" +
            "ђ‘’“”•–—\u0098™љ›њќћџ" +
            " ЎўЈ¤Ґ¦§Ё©Є«¬­®Ї" +
            "°±Ііґµ¶·ё№є»јЅѕї" +
            "АБВГДЕЖЗИЙКЛМНОП" +
            "РСТУФХЦЧШЩЪЫЬЭЮЯ" +
            "абвгдежзийклмноп" +
            "рстуфхцчшщъыьэюя"
        ).toCharArray().also { check(it.size == 128) { "Таблица cp1251 должна содержать 128 символов" } }
}
