package com.localdoc.ai.book

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * Apple Pages (`.pages`) — ZIP-пакет двух поколений:
 *  - Pages '09 и старше: `index.xml`, абзацы в `sf:p`;
 *  - Pages 2013+: `Index/Document.iwa` — Snappy-блоки с protobuf-архивами iWork; текст лежит в
 *    `TSWP.StorageArchive` (тип сообщения 2001, поле `text`).
 * Оформление (шрифты, стили) не переносится — только текст и абзацы.
 */
object PagesParser {

    private const val IWA_DOCUMENT = "Index/Document.iwa"
    private const val OLD_INDEX = "index.xml"
    private const val TYPE_TEXT_STORAGE = 2001L

    fun isPages(zip: ZipArchive): Boolean =
        zip.contains(IWA_DOCUMENT) || (zip.contains(OLD_INDEX) && zip.names.any { it.startsWith("QuickLook/") || it.endsWith(".plist") })

    fun parse(zip: ZipArchive, fallbackTitle: String): Book {
        val paragraphs = when {
            zip.contains(IWA_DOCUMENT) -> readNew(zip.read(IWA_DOCUMENT) ?: ByteArray(0))
            zip.contains(OLD_INDEX) -> readOld(zip.readText(OLD_INDEX).orEmpty())
            else -> throw IllegalArgumentException("Это не документ Pages: нет Index/Document.iwa и index.xml")
        }
        val text = paragraphs.map { Block(BlockType.PARAGRAPH, it) }
        val blocks = text
        require(blocks.isNotEmpty()) {
            "В документе Pages не найден текст. Откройте его в Pages и экспортируйте в DOCX, PDF или TXT."
        }
        val images = ImageCollector()
        val cover = listOf("QuickLook/Thumbnail.jpg", "QuickLook/Thumbnail.png", "preview.jpg")
            .firstNotNullOfOrNull { name -> zip.read(name)?.let(images::add) }
        // Место картинки в тексте в пакете Pages не хранится удобно — показываем их после текста
        val pictures = zip.names.filter { it.startsWith("Data/") && ImageFormat.mimeOfExtension(it) != null }.sorted()
            .mapNotNull { name -> runCatching { zip.read(name) }.getOrNull()?.let(images::add) }.distinct()
            .filter { it != cover }
        return Book(
            title = fallbackTitle,
            format = BookFormat.PAGES,
            blocks = blocks + pictures.map { Block(BlockType.IMAGE, "", image = it) },
            chapters = PlainTextParser.detectChapters(blocks)
        ).withImages(images, cover)
    }

    // ---------- Pages '09 ----------

    private fun readOld(xml: String): List<String> {
        val doc = Ksoup.parse(xml, Parser.xmlParser(), "")
        return doc.getElementsByTag("sf:p").map { HtmlBlockBuilder.normalizeSpaces(it.text()) }.filter { it.isNotEmpty() }
    }

    // ---------- Pages 2013+ ----------

    private fun readNew(iwa: ByteArray): List<String> {
        val data = try {
            Snappy.decompressIwa(iwa)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Повреждённый документ Pages: данные обрезаны")
        }
        val bodies = ArrayList<String>()
        val others = ArrayList<String>()
        var pos = 0
        while (pos < data.size) {
            val infoLen = Proto.varint(data, pos) ?: break
            pos = infoLen.next
            val infoEnd = pos + infoLen.value.toInt()
            if (infoEnd > data.size || infoLen.value < 0) break
            // ArchiveInfo: поле 2 (повторяющееся) — MessageInfo { 1: type, 3: length }
            val messages = ArrayList<Pair<Long, Int>>()
            Proto.fields(data, pos, infoEnd).filter { it.number == 2 && it.bytes != null }.forEach { mi ->
                val inner = Proto.fields(mi.bytes!!, 0, mi.bytes.size)
                val type = inner.firstOrNull { it.number == 1 }?.value ?: return@forEach
                val length = inner.firstOrNull { it.number == 3 }?.value ?: return@forEach
                messages.add(type to length.toInt())
            }
            pos = infoEnd
            for ((type, length) in messages) {
                if (length < 0 || pos + length > data.size) return bodies + others
                if (type == TYPE_TEXT_STORAGE) {
                    val fields = Proto.fields(data, pos, pos + length)
                    val kind = fields.firstOrNull { it.number == 1 }?.value ?: 0L
                    val texts = fields.filter { it.number == 3 && it.bytes != null }.map { it.bytes!!.decodeToString() }
                    (if (kind == 0L) bodies else others).addAll(texts)
                }
                pos += length
            }
        }
        val chosen = bodies.ifEmpty { others }
        return chosen.flatMap { it.split('\n', '\u2029') }
            .map { HtmlBlockBuilder.normalizeSpaces(it.replace("\uFFFC", "").replace('\u2028', ' ')) }
            .filter { it.isNotEmpty() }
    }
}

/** Snappy (формат блоков) и контейнер IWA: `00 | длина (3 байта LE) | сжатый блок`, блоки склеиваются. */
internal object Snappy {

    fun decompressIwa(iwa: ByteArray): ByteArray {
        val out = ByteArrayBuilder()
        var pos = 0
        while (pos + 4 <= iwa.size) {
            require(iwa[pos].toInt() == 0) { "Повреждённый IWA: неверный заголовок блока" }
            val length = (iwa[pos + 1].toInt() and 0xFF) or ((iwa[pos + 2].toInt() and 0xFF) shl 8) or ((iwa[pos + 3].toInt() and 0xFF) shl 16)
            pos += 4
            require(pos + length <= iwa.size) { "Повреждённый IWA: блок обрезан" }
            out.append(decompress(iwa, pos, pos + length))
            pos += length
        }
        return out.toByteArray()
    }

    fun decompress(src: ByteArray, from: Int, to: Int): ByteArray {
        var p = from
        var size = 0L
        var shift = 0
        while (true) {
            val b = src[p++].toInt() and 0xFF
            size = size or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 35) { "Повреждённый блок Snappy" }
        }
        require(size in 0..MAX_BLOCK) { "Слишком большой блок Snappy" }
        val out = ByteArray(size.toInt())
        var o = 0
        while (p < to) {
            val tag = src[p++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    var len = (tag shr 2) + 1
                    if (len > 60) {
                        val extra = len - 60
                        len = 0
                        for (k in 0 until extra) len = len or ((src[p++].toInt() and 0xFF) shl (8 * k))
                        len += 1
                    }
                    require(o + len <= out.size && p + len <= to) { "Повреждённый блок Snappy: литерал" }
                    src.copyInto(out, o, p, p + len)
                    p += len
                    o += len
                }
                else -> {
                    val len: Int
                    val offset: Int
                    when (tag and 3) {
                        1 -> {
                            len = 4 + ((tag shr 2) and 7)
                            offset = ((tag shr 5) shl 8) or (src[p++].toInt() and 0xFF)
                        }
                        2 -> {
                            len = 1 + (tag shr 2)
                            offset = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                            p += 2
                        }
                        else -> {
                            len = 1 + (tag shr 2)
                            offset = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                                ((src[p + 2].toInt() and 0xFF) shl 16) or ((src[p + 3].toInt() and 0xFF) shl 24)
                            p += 4
                        }
                    }
                    require(offset in 1..o && o + len <= out.size) { "Повреждённый блок Snappy: ссылка" }
                    for (k in 0 until len) out[o + k] = out[o + k - offset]
                    o += len
                }
            }
        }
        require(o == out.size) { "Повреждённый блок Snappy: размер не совпал" }
        return out
    }

    private const val MAX_BLOCK = 64L * 1024 * 1024
}

/** Минимальный разбор protobuf: поля верхнего уровня (varint и length-delimited). */
internal object Proto {

    class Field(val number: Int, val value: Long, val bytes: ByteArray?)
    class VarInt(val value: Long, val next: Int)

    fun varint(b: ByteArray, from: Int): VarInt? {
        var p = from
        var result = 0L
        var shift = 0
        while (p < b.size && shift < 64) {
            val x = b[p++].toInt() and 0xFF
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return VarInt(result, p)
            shift += 7
        }
        return null
    }

    fun fields(b: ByteArray, from: Int, to: Int): List<Field> {
        val result = ArrayList<Field>()
        var p = from
        while (p < to) {
            val key = varint(b, p) ?: break
            p = key.next
            val number = (key.value shr 3).toInt()
            when ((key.value and 7).toInt()) {
                0 -> { val v = varint(b, p) ?: return result; p = v.next; result.add(Field(number, v.value, null)) }
                1 -> p += 8
                5 -> p += 4
                2 -> {
                    val len = varint(b, p) ?: return result
                    p = len.next
                    val end = p + len.value.toInt()
                    if (len.value < 0 || end > to) return result
                    result.add(Field(number, len.value, b.copyOfRange(p, end)))
                    p = end
                }
                else -> return result
            }
        }
        return result
    }
}

/** Накопитель байтов без зависимости от платформы. */
internal class ByteArrayBuilder {
    private var data = ByteArray(1024)
    var size = 0
        private set

    fun append(src: ByteArray) {
        if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
        src.copyInto(data, size)
        size += src.size
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}
