package com.localdoc.ai.book

/**
 * Распаковка raw DEFLATE (RFC 1951, без zlib-заголовка):
 * Android — java.util.zip.Inflater, iOS — NSData (libcompression).
 * Распаковка дальше expectedSize — ошибка (защита от zip-бомб).
 */
expect fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray

/**
 * Минимальный читатель ZIP-контейнеров (EPUB, FB2.ZIP) в общем коде:
 * центральный каталог + методы Stored/Deflate. ZIP64 не нужен для книг.
 */
class ZipArchive(private val bytes: ByteArray) {

    private class Entry(val name: String, val method: Int, val compressedSize: Int, val size: Int, val localOffset: Int)

    private val entries: Map<String, Entry> = readCentralDirectory()
    private var inflatedTotal = 0L

    val names: Set<String> get() = entries.keys

    fun contains(name: String): Boolean = name in entries

    fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        return try {
            readEntry(name, entry)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            // Выход за границы массива, ошибка распаковщика: для вызывающего это просто повреждённый архив
            throw IllegalArgumentException("Повреждённый ZIP: «$name» не читается", e)
        }
    }

    private fun readEntry(name: String, entry: Entry): ByteArray {
        val off = entry.localOffset
        require(off >= 0 && off + 30 <= bytes.size) { "Повреждённый ZIP: «$name» вне файла" }
        require(u32(off) == LOCAL_SIG) { "Повреждённый ZIP: нет локального заголовка «$name»" }
        require(entry.compressedSize >= 0) { "Повреждённый ZIP: размер «$name»" }
        val dataStart = off + 30 + u16(off + 26) + u16(off + 28)
        require(dataStart + entry.compressedSize <= bytes.size) { "Повреждённый ZIP: «$name» обрезан" }
        require(entry.size in 0..MAX_ENTRY_BYTES) { "Слишком большой файл в архиве: «$name»" }
        require(inflatedTotal + entry.size <= MAX_TOTAL_BYTES) { "Архив распаковывается в слишком большой объём" }
        val raw = bytes.copyOfRange(dataStart, dataStart + entry.compressedSize)
        return when (entry.method) {
            METHOD_STORED -> {
                require(entry.compressedSize == entry.size) { "Повреждённый ZIP: размеры «$name» не совпадают" }
                inflatedTotal += raw.size
                raw
            }
            METHOD_DEFLATE -> inflateRaw(raw, entry.size).also {
                require(it.size <= entry.size) { "Повреждённый ZIP: «$name» больше заявленного размера" }
                inflatedTotal += it.size
            }
            else -> throw IllegalArgumentException("Неподдерживаемое сжатие ZIP (метод ${entry.method})")
        }
    }

    fun readText(name: String): String? = read(name)?.let { TextDecoder.decode(it) }

    private fun readCentralDirectory(): Map<String, Entry> {
        val eocd = findEndOfCentralDirectory()
        val count = u16(eocd + 10)
        val start = u32(eocd + 16)
        require(start in 0 until bytes.size) { "Повреждённый ZIP: центральный каталог вне файла" }
        // Запись каталога занимает не меньше 46 байт: больше записей, чем помещается, — заведомо порча
        require(count.toLong() * 46 <= bytes.size - start) { "Повреждённый ZIP: число записей не сходится с каталогом" }
        var p = start.toInt()
        val result = LinkedHashMap<String, Entry>(count)
        repeat(count) {
            require(p + 46 <= bytes.size && u32(p) == CENTRAL_SIG) { "Повреждённый ZIP: центральный каталог" }
            val nameLen = u16(p + 28)
            require(p + 46 + nameLen <= bytes.size) { "Повреждённый ZIP: имя записи выходит за файл" }
            val name = bytes.decodeToString(p + 46, p + 46 + nameLen)
            result[name] = Entry(
                name = name,
                method = u16(p + 10),
                compressedSize = u32(p + 20).toInt(),
                size = u32(p + 24).toInt(),
                localOffset = u32(p + 42).toInt()
            )
            p += 46 + nameLen + u16(p + 30) + u16(p + 32)
        }
        return result
    }

    private fun findEndOfCentralDirectory(): Int {
        // EOCD — минимум 22 байта в конце, плюс комментарий до 64 КБ
        val minPos = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (pos in bytes.size - 22 downTo minPos) {
            if (u32(pos) == EOCD_SIG) return pos
        }
        throw IllegalArgumentException("Файл не является ZIP-архивом")
    }

    private fun u16(pos: Int): Int = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)

    private fun u32(pos: Int): Long =
        (u16(pos).toLong()) or (u16(pos + 2).toLong() shl 16)

    companion object {
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8
        // Глава EPUB или FB2 крупнее 64 МБ — почти наверняка подделка; на всю книгу — 512 МБ
        private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

        fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
    }
}

/** Нормализация пути внутри архива: «OEBPS/text/../img.png» → «OEBPS/img.png», %20 → пробел. */
internal fun resolveZipPath(baseDir: String, href: String): String {
    val decoded = percentDecode(href.substringBefore('#'))
    return normalizePath(if (decoded.startsWith("/")) decoded.drop(1) else baseDir + decoded)
}

/** Схлопывание «.» и «..» в относительном пути (без декодирования). */
internal fun normalizePath(path: String): String {
    val parts = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 <= s.lastIndex) {
            val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                out.add(hex.toByte())
                i += 3
                continue
            }
        }
        c.toString().encodeToByteArray().forEach { out.add(it) }
        i++
    }
    return out.toByteArray().decodeToString()
}
