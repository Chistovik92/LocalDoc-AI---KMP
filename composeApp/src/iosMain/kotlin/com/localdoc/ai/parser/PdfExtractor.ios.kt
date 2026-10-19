package com.localdoc.ai.parser

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextObservation
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRequestTextRecognitionLevelAccurate

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
/** iOS: PDFKit для текстового слоя + Apple Vision OCR (Neural Engine) для сканов. */
actual class PdfExtractor actual constructor() {

    actual suspend fun extract(pathOrUri: String): ParsedDoc = withContext(Dispatchers.IO) {
        val url = NSURL.fileURLWithPath(pathOrUri)
        val pdfDoc = PDFDocument(uRL = url)
            ?: return@withContext ParsedDoc(
                title = pathOrUri.substringAfterLast('/'),
                content = "",
                source = pathOrUri
            )

        val pageCount = pdfDoc.pageCount.toInt()
        val builder = StringBuilder()

        for (i in 0 until pageCount) {
            val page = pdfDoc.pageAtIndex(i.toULong()) ?: continue
            val text = page.string?.trim() ?: ""

            if (text.length > 40) {
                builder.append("\n--- [Страница ${i + 1}] ---\n").append(text)
            } else {
                // Фоллбэк: рендерим страницу в изображение и распознаём через Vision OCR
                val ocrText = recognizePageWithVision(page)
                builder.append("\n--- [Страница ${i + 1} (Vision OCR)] ---\n").append(ocrText)
            }
        }

        ParsedDoc(
            title = url.lastPathComponent ?: "Документ.pdf",
            content = builder.toString().trim(),
            source = pathOrUri,
            pageCount = pageCount
        )
    }

    private fun recognizePageWithVision(page: PDFPage): String {
        return runCatching {
            val (pageWidth, pageHeight) = page.boundsForBox(kPDFDisplayBoxMediaBox).useContents {
                size.width to size.height
            }
            if (pageWidth <= 0.0 || pageHeight <= 0.0) return ""

            val format = UIGraphicsImageRendererFormat().apply {
                scale = 2.0 // повышенное разрешение для точности OCR мелкого шрифта
            }
            val renderer = UIGraphicsImageRenderer(
                size = CGSizeMake(pageWidth, pageHeight),
                format = format
            )
            val image = renderer.imageWithActions { context ->
                page.drawWithBox(kPDFDisplayBoxMediaBox, toContext = context)
            }
            val cgImage = image.CGImage ?: return ""

            val request = VNRecognizeTextRequest().apply {
                recognitionLevel = VNRequestTextRecognitionLevelAccurate
                recognitionLanguages = listOf("ru-RU", "en-US")
                usesLanguageCorrection = true
            }

            val handler = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any>())
            handler.performRequests(listOf(request), error = null)

            val observations = request.results as? List<*> ?: return ""
            observations.mapNotNull { obs ->
                (obs as? VNRecognizedTextObservation)
                    ?.topCandidates(1u)
                    ?.firstOrNull()
                    ?.string
            }.joinToString("\n")
        }.getOrDefault("")
    }
}
