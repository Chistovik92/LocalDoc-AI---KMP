package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup

/**
 * Mobi / AZW / AZW3 (KF8) без DRM: контейнер PDB → запись 0 (заголовки PalmDOC и MOBI, EXTH) → текстовые записи
 * со сжатием PalmDOC (LZ77) → HTML, который разбирается тем же сборщиком блоков, что и EPUB.
 * Сжатие HUFF/CDIC и шифрование не поддерживаются — для них понятное сообщение.
 */
object MobiParser {

    private const val PDB_HEADER = 78
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024
    private const val EXTH_AUTHOR = 100
    private const val EXTH_UPDATED_TITLE = 503
    private const val EXTH_COVER_OFFSET = 201
    private val FILEPOS = Regex("""filepos\s*=\s*"?(\d+)"?""", RegexOption.IGNORE_CASE)
    private val RECINDEX = Regex("""recindex\s*=\s*"?(\d+)"?""", RegexOption.IGNORE_CASE)
    private val KINDLE_EMBED = Regex("""kindle:embed:([0-9A-Va-v]+)""")
    private val TOC_ANCHOR = Regex(
        """<a\s[^>]*filepos\s*=\s*"?(\d+)"?[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TAG = Regex("<[^>]*>")

    fun isMobi(bytes: ByteArray): Boolean {
        if (bytes.size < PDB_HEADER + 8) return false
        val type = bytes.decodeToString(60, 68)
        return type == "BOOKMOBI" || type == "TEXtREAd"
    }

    private class Header(
        val compression: Int,
        val textLength: Int,
        val textRecords: Int,
        val encryption: Int,
        val utf8: Boolean,
        val firstImage: Int,
        val extraFlags: Int,
        val fullName: String,
        val author: String,
        val coverOffset: Int
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): Book {
        require(isMobi(bytes)) { "Файл не является книгой Mobi" }
        val records = readRecordOffsets(bytes)
        val header = readHeader(bytes, records)
        if (header.encryption != 0) throw UnsupportedFormatException("Книга защищена DRM: открыть её нельзя.")
        if (header.compression != 1 && header.compression != 2) {
            throw UnsupportedFormatException("Сжатие HUFF/CDIC в Mobi не поддерживается.")
        }

        val text = readText(bytes, records, header)
        val images = ImageCollector()
        val imageIds = HashMap<Int, String?>()
        val firstImage = header.firstImage
        fun imageByRecord(record: Int): String? = imageIds.getOrPut(record) {
            if (firstImage <= 0 || record < firstImage || record >= records.size - 1) return@getOrPut null
            images.add(bytes.copyOfRange(records[record], records[record + 1]))
        }
        val resolver = ImageResolver { src ->
            val embed = KINDLE_EMBED.find(src)?.groupValues?.get(1)?.toIntOrNull(32)
            when {
                src.startsWith("mobi:") -> src.removePrefix("mobi:").toIntOrNull()?.let { imageByRecord(firstImage + it - 1) }
                embed != null -> imageByRecord(firstImage + embed - 1)
                else -> null
            }
        }

        val (blocks, chapters) = buildBlocks(text, header, resolver)
        val cover = if (header.coverOffset >= 0) imageByRecord(firstImage + header.coverOffset) else null
        return Book(
            title = header.fullName.ifBlank { fallbackTitle },
            author = header.author,
            format = BookFormat.MOBI,
            blocks = blocks,
            chapters = chapters.ifEmpty { chaptersFromHeadings(blocks) }
        ).withImages(images, cover)
    }

    // ---------- контейнер ----------

    /** Смещения записей PDB; последний элемент — конец файла, поэтому запись i занимает [i], [i + 1]). */
    private fun readRecordOffsets(bytes: ByteArray): IntArray {
        val count = u16(bytes, 76)
        require(count > 1 && PDB_HEADER + count * 8 <= bytes.size) { "Повреждённый файл Mobi: список записей" }
        val result = IntArray(count + 1)
        for (i in 0 until count) result[i] = u32(bytes, PDB_HEADER + i * 8)
        result[count] = bytes.size
        for (i in 0 until count) require(result[i] in 0..bytes.size && result[i] <= result[i + 1]) { "Повреждённый файл Mobi: смещения записей" }
        return result
    }

    private fun readHeader(bytes: ByteArray, records: IntArray): Header {
        val r0 = records[0]
        val size = records[1] - r0
        require(size >= 16) { "Повреждённый файл Mobi: нет заголовка" }
        val compression = u16(bytes, r0)
        val textLength = u32(bytes, r0 + 4)
        val textRecords = u16(bytes, r0 + 8)
        val encryption = u16(bytes, r0 + 12)
        if (size < 24 || bytes.decodeToString(r0 + 16, r0 + 20) != "MOBI") {
            return Header(compression, textLength, textRecords, encryption, false, 0, 0, "", "", -1)
        }

        val mobiLength = u32(bytes, r0 + 20)
        fun field(offset: Int): Int = if (offset + 4 <= size) u32(bytes, r0 + offset) else 0
        val utf8 = field(28) == 65001
        val firstImage = field(108).let { if (it in 1 until records.size - 1) it else 0 }
        val extraFlags = if (mobiLength >= 0xE4 && 0xF2 + 2 <= size) u16(bytes, r0 + 0xF2) else 0
        val nameOffset = field(84)
        val nameLength = field(88)
        var fullName = if (nameOffset > 0 && nameLength > 0 && nameOffset + nameLength <= size) {
            decode(bytes.copyOfRange(r0 + nameOffset, r0 + nameOffset + nameLength), utf8)
        } else ""
        var author = ""
        var coverOffset = -1
        val exthStart = 16 + mobiLength
        if (field(128) and 0x40 != 0 && exthStart + 12 <= size && bytes.decodeToString(r0 + exthStart, r0 + exthStart + 4) == "EXTH") {
            var p = exthStart + 12
            repeat(u32(bytes, r0 + exthStart + 8).coerceIn(0, 1000)) {
                if (p + 8 > size) return@repeat
                val type = u32(bytes, r0 + p)
                val len = u32(bytes, r0 + p + 4)
                if (len < 8 || p + len > size) return@repeat
                val data = bytes.copyOfRange(r0 + p + 8, r0 + p + len)
                when (type) {
                    EXTH_AUTHOR -> if (author.isEmpty()) author = decode(data, utf8).trim()
                    EXTH_UPDATED_TITLE -> decode(data, utf8).trim().takeIf { it.isNotEmpty() }?.let { fullName = it }
                    EXTH_COVER_OFFSET -> if (data.size >= 4) coverOffset = u32(data, 0)
                }
                p += len
            }
        }
        return Header(compression, textLength, textRecords, encryption, utf8, firstImage, extraFlags, fullName, author, coverOffset)
    }

    private fun readText(bytes: ByteArray, records: IntArray, header: Header): ByteArray {
        require(header.textLength in 0..MAX_TEXT_BYTES) { "Текст Mobi слишком велик" }
        val out = IndexedByteBuffer(header.textLength)
        val last = minOf(header.textRecords, records.size - 2)
        for (i in 1..last) {
            val end = records[i + 1] - trailingSize(bytes, records[i], records[i + 1], header.extraFlags)
            val chunk = bytes.copyOfRange(records[i], maxOf(records[i], end))
            if (header.compression == 2) palmDocDecompress(chunk, out, MAX_TEXT_BYTES) else out.append(chunk)
            if (out.size >= MAX_TEXT_BYTES) break
        }
        return out.toByteArray()
    }

    /** Размер «хвоста» записи (флаги extra data): сначала по битам 1.., затем перекрытие многобайтных символов (бит 0). */
    private fun trailingSize(bytes: ByteArray, start: Int, end: Int, flags: Int): Int {
        if (flags == 0) return 0
        var size = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) size += backwardVarint(bytes, start, end - size)
            f = f shr 1
        }
        if (flags and 1 != 0 && end - size - 1 >= start) size += (bytes[end - size - 1].toInt() and 3) + 1
        return size.coerceAtMost(end - start)
    }

    private fun backwardVarint(bytes: ByteArray, start: Int, end: Int): Int {
        var result = 0
        var shift = 0
        var pos = end
        while (pos > start) {
            val v = bytes[pos - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl shift)
            shift += 7
            pos--
            if (v and 0x80 != 0 || shift >= 28) break
        }
        return result
    }

    /** Распаковка PalmDOC (LZ77): литералы, пробел + символ, ссылки назад «расстояние / длина». */
    internal fun palmDocDecompress(input: ByteArray, out: IndexedByteBuffer, limit: Int) {
        var i = 0
        while (i < input.size && out.size < limit) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == 0 || c in 9..0x7F -> out.append(c)
                c in 1..8 -> {
                    val n = minOf(c, input.size - i)
                    for (k in 0 until n) out.append(input[i + k].toInt() and 0xFF)
                    i += n
                }
                c >= 0xC0 -> { out.append(0x20); out.append(c xor 0x80) }
                else -> {
                    if (i >= input.size) return
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (pair and 0x3FFF) shr 3
                    val length = (pair and 7) + 3
                    if (distance == 0 || distance > out.size) return
                    for (k in 0 until length) out.append(out[out.size - distance])
                }
            }
        }
    }

    // ---------- текст → блоки и оглавление ----------

    private fun buildBlocks(text: ByteArray, header: Header, resolver: ImageResolver): Pair<List<Block>, List<Chapter>> {
        // Оглавление MOBI 6: ссылки <a filepos=N> — смещения в байтах распакованного текста
        val raw = latin1(text)
        val targets = FILEPOS.findAll(raw).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1 until text.size }.distinct().sorted().toList()
        val titles = tocTitles(raw, header)

        val blocks = ArrayList<Block>()
        val chapters = ArrayList<Chapter>()
        val cuts = listOf(0) + targets + text.size
        for (k in 0 until cuts.size - 1) {
            if (k > 0) titles[cuts[k]]?.let { chapters.add(Chapter(it.take(120), blocks.size)) }
            val html = prepareHtml(decode(text.copyOfRange(cuts[k], cuts[k + 1]), header.utf8))
            val body = Ksoup.parse(html, "").body() ?: continue
            blocks.addAll(HtmlBlockBuilder(resolver).build(body))
        }
        return blocks to chapters.distinctBy { it.startBlock }
    }

    private fun tocTitles(raw: String, header: Header): Map<Int, String> {
        val result = HashMap<Int, String>()
        for (m in TOC_ANCHOR.findAll(raw)) {
            val pos = m.groupValues[1].toIntOrNull() ?: continue
            val label = m.groupValues[2].replace(TAG, "")
            val title = decode(ByteArray(label.length) { label[it].code.toByte() }, header.utf8).replace(Regex("\\s+"), " ").trim()
            if (title.isNotEmpty() && pos !in result) result[pos] = title
        }
        return result
    }

    private fun prepareHtml(html: String): String =
        html.replace(RECINDEX) { "src=\"mobi:${it.groupValues[1]}\"" }
            .replace(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE), "<hr/>")

    /** Книги со старым заголовком нередко лежат в UTF-8 без пометки: если байты — корректный UTF-8, читаем так. */
    private fun decode(bytes: ByteArray, utf8: Boolean): String =
        if (utf8 || (bytes.any { it < 0 } && TextDecoder.isValidUtf8(bytes))) bytes.decodeToString() else latin1(bytes)

    private fun latin1(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}

/** Растущий буфер байт с доступом по индексу (нужен PalmDOC для ссылок назад). */
internal class IndexedByteBuffer(capacity: Int = 1024) {
    private var data = ByteArray(capacity.coerceIn(16, 1 shl 26))
    var size = 0
        private set

    fun append(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v.toByte()
    }

    fun append(bytes: ByteArray) {
        for (b in bytes) append(b.toInt() and 0xFF)
    }

    operator fun get(index: Int): Int = data[index].toInt() and 0xFF

    fun toByteArray(): ByteArray = data.copyOf(size)
}
