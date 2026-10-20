package com.localdoc.ai.book

import com.localdoc.ai.parser.PdfExtractor
import com.localdoc.ai.platform.FileAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Понятная пользователю причина, по которой файл нельзя открыть (а не падение приложения). */
class UnsupportedFormatException(message: String) : IllegalArgumentException(message)

/** Определение формата по расширению и сигнатуре содержимого. */
object FormatDetector {

    fun byName(name: String): BookFormat? = when (name.lowercase().substringAfterLast('.', "")) {
        "pdf" -> BookFormat.PDF
        "epub" -> BookFormat.EPUB
        "fb2", "fbz" -> BookFormat.FB2
        "zip" -> if (name.lowercase().endsWith(".fb2.zip")) BookFormat.FB2 else null
        "md", "markdown" -> BookFormat.MARKDOWN
        "txt", "text", "log" -> BookFormat.TEXT
        "html", "htm", "xhtml" -> BookFormat.HTML
        "docx", "docm", "dotx", "dotm" -> BookFormat.DOCX
        "doc", "dot" -> BookFormat.DOC
        "odt", "ott" -> BookFormat.ODT
        "rtf" -> BookFormat.RTF
        "xml" -> BookFormat.XML
        "wps" -> BookFormat.WPS
        "pages" -> BookFormat.PAGES
        else -> null
    }

    /**
     * Формат по содержимому. Сигнатура контейнера (PDF, OLE2, ZIP) важнее расширения; бинарный файл
     * неизвестного вида никогда не принимается за текст — вместо этого [UnsupportedFormatException].
     */
    fun bySignature(bytes: ByteArray): BookFormat {
        if (isPdf(bytes)) return BookFormat.PDF
        if (Ole2File.isOle2(bytes)) return BookFormat.DOC
        if (ZipArchive.isZip(bytes)) return zipFormat(ZipArchive(bytes))
        if (RtfParser.isRtf(bytes)) return BookFormat.RTF
        if (looksBinary(bytes)) throw UnsupportedFormatException(BINARY_MESSAGE)
        return textFormat(bytes) ?: BookFormat.TEXT
    }

    /**
     * Итоговый формат файла [name] с содержимым [bytes]: для PDF/DOC/DOCX/ZIP решает сигнатура,
     * для текстовых форматов — расширение (если внутри не бинарные данные).
     */
    fun resolve(name: String, bytes: ByteArray): BookFormat {
        val byName = byName(name)
        if (isPdf(bytes)) return BookFormat.PDF
        if (Ole2File.isOle2(bytes)) return if (byName == BookFormat.WPS) BookFormat.WPS else BookFormat.DOC
        if (ZipArchive.isZip(bytes)) return zipFormat(ZipArchive(bytes))
        if (RtfParser.isRtf(bytes)) return BookFormat.RTF
        return when (byName) {
            null -> bySignature(bytes)
            BookFormat.EPUB, BookFormat.DOCX, BookFormat.DOC, BookFormat.ODT, BookFormat.PDF, BookFormat.WPS, BookFormat.PAGES ->
                throw UnsupportedFormatException("Файл «$name» повреждён или не является документом ${byName.label}.")
            BookFormat.RTF -> {
                if (!RtfParser.isRtf(bytes)) throw UnsupportedFormatException("Файл «$name» повреждён или не является документом RTF.")
                byName
            }
            BookFormat.XML -> {
                if (looksBinary(bytes)) throw UnsupportedFormatException("Файл «$name» содержит бинарные данные, а не XML.")
                textFormat(bytes) ?: BookFormat.XML
            }
            else -> {
                if (looksBinary(bytes)) throw UnsupportedFormatException("Файл «$name» содержит бинарные данные, а не текст.")
                byName
            }
        }
    }

    /** XML-подобное содержимое: FictionBook, XHTML или произвольный XML; null — обычный текст. */
    private fun textFormat(bytes: ByteArray): BookFormat? {
        val head = bytes.copyOf(minOf(bytes.size, 1024)).decodeToString().lowercase()
        val trimmed = head.trimStart()
        return when {
            "<fictionbook" in head -> BookFormat.FB2
            "<html" in head || "<!doctype html" in head -> BookFormat.HTML
            trimmed.startsWith("<?xml") -> BookFormat.XML
            else -> null
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.decodeToString(0, 4) == "%PDF"

    private fun zipFormat(zip: ZipArchive): BookFormat = when {
        zip.contains("META-INF/container.xml") -> BookFormat.EPUB
        zip.contains("word/document.xml") -> BookFormat.DOCX
        OdtParser.isOdt(zip) -> BookFormat.ODT
        PagesParser.isPages(zip) -> BookFormat.PAGES
        zip.names.any { it.endsWith(".fb2", ignoreCase = true) } -> BookFormat.FB2
        else -> throw UnsupportedFormatException("В архиве нет книги: поддерживаются EPUB, FB2.ZIP, DOCX, ODT и Pages.")
    }

    /**
     * Бинарные данные: NUL-байты или много управляющих символов в начале файла.
     * Файлы с BOM UTF-16 (в них NUL — нормальная часть текста) бинарными не считаются.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
        }
        val sample = minOf(bytes.size, BINARY_SAMPLE)
        if (sample == 0) return false
        var control = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B) control++
        }
        return control * 100 > sample * 5
    }

    private const val BINARY_SAMPLE = 8192
    private const val BINARY_MESSAGE =
        "Не удаётся прочитать файл: это не текстовый документ. Поддерживаются PDF, EPUB, FB2, DOCX, DOC, ODT, RTF, WPS, Pages, TXT, Markdown, HTML и XML."
}

/** Импорт файла любого поддерживаемого формата в единую модель [Book]. */
class BookImporter(private val files: FileAccess, private val pdfExtractor: PdfExtractor) {

    suspend fun import(pathOrUri: String): Book {
        val name = files.displayName(pathOrUri) ?: pathOrUri.substringAfterLast('/')
        try {
            return importNamed(pathOrUri, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            // Что бы ни случилось в разборе (в том числе нехватка памяти), пользователь увидит причину, а не вылет
            throw IllegalStateException(friendlyMessage(name, t), t)
        }
    }

    private suspend fun importNamed(pathOrUri: String, name: String): Book {
        val title = name.substringBeforeLast('.').removeSuffix(".fb2").ifBlank { "Без названия" }
        val byName = FormatDetector.byName(name)

        // PDF читается платформенным экстрактором (текстовый слой + OCR) потоково, без загрузки в память целиком
        if (byName == BookFormat.PDF) return importPdf(pathOrUri, title)

        val bytes = files.readBytes(pathOrUri)
        require(bytes.size <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ" }
        return withContext(Dispatchers.Default) {
            when (val format = FormatDetector.resolve(name, bytes)) {
                BookFormat.PDF -> null
                else -> parseBytes(bytes, format, title, pathOrUri)
            }
        } ?: importPdf(pathOrUri, title)
    }

    fun importText(text: String, title: String): Book =
        PlainTextParser.parse(text, title).copy(source = "Вставленный текст")

    private suspend fun importPdf(pathOrUri: String, title: String): Book {
        val parsed = pdfExtractor.extract(pathOrUri)
        return PdfTextAdapter.toBook(parsed.title.substringBeforeLast('.').ifBlank { title }, parsed.content, pathOrUri)
    }

    companion object {
        private const val MAX_FILE_BYTES = 150 * 1024 * 1024

        private fun friendlyMessage(name: String, t: Throwable): String =
            (t as? IllegalStateException)?.message?.takeIf { it.isNotBlank() }
                ?: "Файл «$name» повреждён или слишком велик для разбора."

        fun parseBytes(bytes: ByteArray, format: BookFormat, title: String, source: String): Book {
            val book = when (format) {
                BookFormat.EPUB -> EpubParser.parse(ZipArchive(bytes), title)
                BookFormat.DOCX -> DocxParser.parse(ZipArchive(bytes), title)
                BookFormat.DOC -> DocParser.parse(bytes, title)
                BookFormat.ODT -> OdtParser.parse(ZipArchive(bytes), title)
                BookFormat.RTF -> RtfParser.parse(bytes, title)
                BookFormat.XML -> XmlBookParser.parse(bytes, title)
                BookFormat.WPS -> WpsParser.parse(bytes, title)
                BookFormat.PAGES -> PagesParser.parse(ZipArchive(bytes), title)
                BookFormat.FB2 -> {
                    val xml = if (ZipArchive.isZip(bytes)) {
                        val zip = ZipArchive(bytes)
                        zip.names.firstOrNull { it.endsWith(".fb2", ignoreCase = true) }?.let { zip.read(it) }
                            ?: error("В архиве нет .fb2-файла")
                    } else bytes
                    Fb2Parser.parse(xml, title)
                }
                BookFormat.MARKDOWN -> MarkdownParser.parse(TextDecoder.decode(bytes), title)
                BookFormat.HTML, BookFormat.WEB -> HtmlFileParser.parse(TextDecoder.decode(bytes, TextDecoder.xmlDeclaredEncoding(bytes)), title, source)
                BookFormat.TEXT -> PlainTextParser.parse(TextDecoder.decode(bytes), title)
                BookFormat.PDF -> error("PDF разбирается платформенным экстрактором")
            }
            val clean = book.withoutAds()
            require(clean.blocks.isNotEmpty()) { "В файле не найден текст" }
            return clean.copy(source = source)
        }
    }
}
