package com.localdoc.ai.platform

import com.localdoc.ai.book.pdf.PageRenderer
import com.localdoc.ai.book.pdf.PdfRenderSize
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImageJPEGRepresentation

/** iOS: PDFKit рисует страницу миниатюрой нужного размера; документ остаётся открытым до [close]. */
@OptIn(ExperimentalForeignApi::class)
actual class PdfPageRenderer actual constructor(path: String) : PageRenderer {

    private var document: PDFDocument? =
        PDFDocument(uRL = NSURL.fileURLWithPath(path)) ?: throw IllegalStateException("PDF не открывается")

    actual override val pageCount: Int = document?.pageCount?.toInt() ?: 0

    actual override fun render(index: Int, widthPx: Int): ByteArray? {
        val page = document?.pageAtIndex(index.toULong()) ?: return null
        val (pageWidth, pageHeight) = page.boundsForBox(kPDFDisplayBoxMediaBox).useContents { size.width to size.height }
        val (width, height) = PdfRenderSize.fit(pageWidth, pageHeight, widthPx)
        val image = page.thumbnailOfSize(CGSizeMake(width.toDouble(), height.toDouble()), forBox = kPDFDisplayBoxMediaBox)
        return UIImageJPEGRepresentation(image, JPEG_QUALITY)?.toByteArray()
    }

    actual override fun close() {
        document = null
    }

    private companion object {
        const val JPEG_QUALITY = 0.88
    }
}
