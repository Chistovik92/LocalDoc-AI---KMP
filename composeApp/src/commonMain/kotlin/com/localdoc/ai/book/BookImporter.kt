package com.localdoc.ai.book

import com.localdoc.ai.parser.PdfExtractor
import com.localdoc.ai.platform.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Определение формата по расширению и сигнатуре содержимого. */
object FormatDetector {

    fun byName(name: String): BookFormat? = when (name.lowercase().substringAfterLast('.', "")) {
        "pdf" -> BookFormat.PDF
        "epub" -> BookFormat.EPUB
        "fb2" -> BookFormat.FB2
        "zip" -> if (name.lowercase().endsWith(".fb2.zip")) BookFormat.FB2 else null
        "md", "markdown" -> BookFormat.MARKDOWN
        "txt", "text" -> BookFormat.TEXT
        "html", "htm", "xhtml" -> BookFormat.HTML
        else -> null
    }

    fun bySignature(bytes: ByteArray): BookFormat {
        if (bytes.size >= 4 && bytes.decodeToString(0, 4) == "%PDF") return BookFormat.PDF
        if (ZipArchive.isZip(bytes)) {
            val zip = ZipArchive(bytes)
            return if (zip.names.any { it.endsWith(".fb2", ignoreCase = true) } && !zip.contains("META-INF/container.xml")) {
                BookFormat.FB2
            } else BookFormat.EPUB
        }
        val head = bytes.copyOf(minOf(bytes.size, 1024)).decodeToString().lowercase()
        return when {
            "<fictionbook" in head -> BookFormat.FB2
            "<html" in head || "<!doctype html" in head -> BookFormat.HTML
            else -> BookFormat.TEXT
        }
    }
}

/** Импорт файла любого поддерживаемого формата в единую модель [Book]. */
class BookImporter(private val files: FileAccess, private val pdfExtractor: PdfExtractor) {

    suspend fun import(pathOrUri: String): Book {
        val name = files.displayName(pathOrUri) ?: pathOrUri.substringAfterLast('/')
        val title = name.substringBeforeLast('.').removeSuffix(".fb2").ifBlank { "Без названия" }
        val byName = FormatDetector.byName(name)

        // PDF читается платформенным экстрактором (текстовый слой + OCR) потоково, без загрузки в память целиком
        if (byName == BookFormat.PDF) return importPdf(pathOrUri, title)

        val bytes = files.readBytes(pathOrUri)
        require(bytes.size <= MAX_FILE_BYTES) { "Файл больше ${MAX_FILE_BYTES / (1024 * 1024)} МБ" }
        return withContext(Dispatchers.Default) {
            when (val format = byName ?: FormatDetector.bySignature(bytes)) {
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

        fun parseBytes(bytes: ByteArray, format: BookFormat, title: String, source: String): Book {
            val book = when (format) {
                BookFormat.EPUB -> EpubParser.parse(ZipArchive(bytes), title)
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
            require(book.blocks.isNotEmpty()) { "В файле не найден текст" }
            return book.copy(source = source)
        }
    }
}
