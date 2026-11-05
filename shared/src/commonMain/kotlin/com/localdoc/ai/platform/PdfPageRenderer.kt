package com.localdoc.ai.platform

import com.localdoc.ai.book.pdf.PageRenderer

/**
 * Постраничный рендер PDF: Android — `PdfRenderer`, iOS — PDFKit. [path] — файл в хранилище приложения
 * (абсолютный путь из [FileAccess.appFilePath]). Открывает документ сразу; при ошибке бросает исключение.
 */
expect class PdfPageRenderer(path: String) : PageRenderer {
    override val pageCount: Int
    override fun render(index: Int, widthPx: Int): ByteArray?
    override fun close()
}
