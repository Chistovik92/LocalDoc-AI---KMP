package com.localdoc.ai.parser

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRequest
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

            val image = page.thumbnailOfSize(
                CGSizeMake(pageWidth * 2.0, pageHeight * 2.0),
                forBox = kPDFDisplayBoxMediaBox
            )
            val cgImage = image.CGImage ?: return ""

            val request = VNRecognizeTextRequest().apply {
                recognitionLevel = VNRequestTextRecognitionLevelAccurate
                recognitionLanguages = listOf("ru-RU", "en-US")
                usesLanguageCorrection = true
            }

            val handler = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any>())
            handler.performRequests(listOf(request), error = null)

            // ObjC NSArray приходят в Kotlin как List<*> — приводим элементы явно
            val observations = request.results?.filterIsInstance<VNRecognizedTextObservation>()
                ?: return ""
            observations.mapNotNull { obs ->
                obs.topCandidates(1u)
                    .filterIsInstance<VNRecognizedText>()
                    .firstOrNull()
                    ?.string
            }.joinToString("\n")
        }.getOrDefault("")
    }
}
