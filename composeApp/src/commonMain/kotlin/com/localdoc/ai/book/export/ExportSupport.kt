package com.localdoc.ai.book.export

import com.localdoc.ai.book.Block
import com.localdoc.ai.book.BlockType
import com.localdoc.ai.book.Book
import com.localdoc.ai.book.BookFormat
import com.localdoc.ai.book.Chapter
import com.localdoc.ai.book.ImageFormat
import com.localdoc.ai.book.SpanStyleType

/** Форматы, в которые можно сохранить книгу («Сохранить как…»). */
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    EPUB("EPUB", "epub", "application/epub+zip"),
    FB2("FB2", "fb2", "application/x-fictionbook+xml"),
    FB2_ZIP("FB2.ZIP", "fb2.zip", "application/zip"),
    DOCX("DOCX (Word)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ODT("ODT (OpenDocument)", "odt", "application/vnd.oasis.opendocument.text"),
    RTF("RTF", "rtf", "application/rtf"),
    TXT("TXT", "txt", "text/plain"),
    HTML("HTML", "html", "text/html"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    XML("XML", "xml", "application/xml");

    /** Формат книги, в который превращается файл при обратном импорте. */
    val importFormat: BookFormat
        get() = when (this) {
            EPUB -> BookFormat.EPUB
            FB2, FB2_ZIP -> BookFormat.FB2
            DOCX -> BookFormat.DOCX
            ODT -> BookFormat.ODT
            RTF -> BookFormat.RTF
            TXT -> BookFormat.TEXT
            HTML -> BookFormat.HTML
            MARKDOWN -> BookFormat.MARKDOWN
            XML -> BookFormat.XML
        }
}

/** Единая точка экспорта: импорт → [Book] → запись в выбранный формат. */
object BookExporter {

    fun export(book: Book, format: ExportFormat): ByteArray {
        require(book.blocks.isNotEmpty()) { "Нечего сохранять: в книге нет текста" }
        val prepared = book.withChapterHeadings()
        return when (format) {
            ExportFormat.EPUB -> EpubWriter.write(prepared)
            ExportFormat.FB2 -> Fb2Writer.write(prepared)
            ExportFormat.FB2_ZIP -> ZipWriter().apply { add(fileName(prepared, "fb2"), Fb2Writer.write(prepared)) }.finish()
            ExportFormat.DOCX -> DocxWriter.write(prepared)
            ExportFormat.ODT -> OdtWriter.write(prepared)
            ExportFormat.RTF -> RtfWriter.write(prepared)
            ExportFormat.TXT -> TextWriters.txt(prepared)
            ExportFormat.HTML -> TextWriters.html(prepared)
            ExportFormat.MARKDOWN -> TextWriters.markdown(prepared)
            ExportFormat.XML -> TextWriters.xml(prepared)
        }
    }

    /** Имя файла для сохранения: название книги без недопустимых символов + расширение формата. */
    fun suggestedName(book: Book, format: ExportFormat): String = fileName(book, format.extension)

    private fun fileName(book: Book, extension: String): String {
        val clean = book.title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return "${clean.ifBlank { "Книга" }}.$extension"
    }
}

/**
 * Каждая глава оглавления начинается с заголовка: если в источнике её начало — обычный абзац
 * (страница веб-новеллы, глава TXT), перед ним вставляется заголовок первого уровня, а индексы глав
 * пересчитываются. Страницы PDF — только навигация, заголовков для них не создаём.
 */
internal fun Book.withChapterHeadings(): Book {
    if (format == BookFormat.PDF || chapters.isEmpty()) return this
    val result = ArrayList<Block>(blocks.size + chapters.size)
    val newChapters = ArrayList<Chapter>(chapters.size)
    val byStart = chapters.groupBy { it.startBlock }
    for ((index, block) in blocks.withIndex()) {
        val starting = byStart[index]?.firstOrNull()
        if (starting != null) {
            newChapters.add(Chapter(starting.title, result.size))
            if (block.type != BlockType.HEADING) result.add(Block(BlockType.HEADING, starting.title, 1))
        }
        result.add(block)
    }
    return copy(blocks = result, chapters = newChapters)
}

/** Участок блока с одинаковым выделением. */
internal data class Run(val text: String, val bold: Boolean, val italic: Boolean, val code: Boolean)

/** Разбиение текста блока на участки по границам выделений. */
internal fun Block.runs(): List<Run> {
    if (text.isEmpty()) return emptyList()
    if (spans.isEmpty()) return listOf(Run(text, bold = false, italic = false, code = false))
    val cuts = HashSet<Int>().apply { add(0); add(text.length) }
    for (s in spans) {
        cuts.add(s.start.coerceIn(0, text.length))
        cuts.add(s.end.coerceIn(0, text.length))
    }
    return cuts.sorted().zipWithNext().mapNotNull { (a, b) ->
        if (b <= a) return@mapNotNull null
        fun on(style: SpanStyleType) = spans.any { it.style == style && it.start <= a && it.end >= b }
        Run(text.substring(a, b), on(SpanStyleType.BOLD), on(SpanStyleType.ITALIC), on(SpanStyleType.CODE))
    }
}

/** Экранирование текста для XML (текст и атрибуты); символы, недопустимые в XML 1.0, выбрасываются. */
internal fun xmlEscape(s: String): String {
    val out = StringBuilder(s.length + 16)
    for (c in s) {
        when {
            c == '&' -> out.append("&amp;")
            c == '<' -> out.append("&lt;")
            c == '>' -> out.append("&gt;")
            c == '"' -> out.append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit
            c.code == 0xFFFE || c.code == 0xFFFF -> Unit
            else -> out.append(c)
        }
    }
    return out.toString()
}

/** Картинка, готовая к записи: идентификатор, данные и тип. */
internal class ExportImage(val id: String, val bytes: ByteArray, val mime: String) {
    val extension: String get() = ImageFormat.extensionOf(mime)
    val size: ImageSize? by lazy { ImageSize.of(bytes) }
}

/** Картинки, лежащие внутри книги (внешние http-ссылки остаются ссылками). */
internal fun Book.exportImages(): Map<String, ExportImage> =
    imageData.mapNotNull { (id, bytes) -> ImageFormat.mimeOf(bytes)?.let { id to ExportImage(id, bytes, it) } }.toMap()

internal fun String.isRemoteImage() = startsWith("http://", true) || startsWith("https://", true)

/** Размеры картинки в пикселях по заголовку файла (PNG, JPEG, GIF, BMP). */
internal class ImageSize(val width: Int, val height: Int) {
    companion object {
        fun of(b: ByteArray): ImageSize? {
            fun u8(i: Int) = if (i in b.indices) b[i].toInt() and 0xFF else 0
            fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
            fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
            val size = when (ImageFormat.mimeOf(b)) {
                "image/png" -> if (b.size >= 24) ImageSize(be32(16), be32(20)) else null
                "image/gif" -> ImageSize(le16(6), le16(8))
                "image/bmp" -> if (b.size >= 26) ImageSize(le32(18), kotlin.math.abs(le32(22))) else null
                "image/jpeg" -> jpeg(b)
                else -> null
            }
            return size?.takeIf { it.width in 1..20000 && it.height in 1..20000 }
        }

        private fun jpeg(b: ByteArray): ImageSize? {
            var p = 2
            while (p + 9 < b.size) {
                if (b[p] != 0xFF.toByte()) { p++; continue }
                val marker = b[p + 1].toInt() and 0xFF
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((b[p + 5].toInt() and 0xFF) shl 8) or (b[p + 6].toInt() and 0xFF)
                    val w = ((b[p + 7].toInt() and 0xFF) shl 8) or (b[p + 8].toInt() and 0xFF)
                    return ImageSize(w, h)
                }
                p += 2 + (((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF))
            }
            return null
        }
    }
}

/** Язык книги для метаданных: кириллица в начале текста → «ru», иначе «en». */
internal fun Book.guessLanguage(): String {
    val sample = blocks.asSequence().filter { !it.isImage }.take(20).joinToString(" ") { it.text }.take(2000)
    val cyrillic = sample.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
    return if (cyrillic * 3 > sample.count { it.isLetter() }) "ru" else "en"
}

/** Стабильный идентификатор книги из её названия и размера текста. */
internal fun Book.stableId(): String {
    val h = (title + author + blocks.size + plainText.length).hashCode().toUInt().toString(16).padStart(8, '0')
    return "localdoc-$h"
}
