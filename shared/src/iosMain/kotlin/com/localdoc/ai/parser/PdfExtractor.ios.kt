package com.localdoc.ai.parser

import com.localdoc.ai.book.UnsupportedFormatException
import com.localdoc.ai.book.pdf.PdfRenderSize
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    actual suspend fun extract(pathOrUri: String, withOcr: Boolean): ParsedDoc = withContext(Dispatchers.IO) {
        val url = NSURL.fileURLWithPath(pathOrUri)
        // Раньше неоткрывшийся документ молча давал «пустую книгу» — теперь видна причина
        val pdfDoc = PDFDocument(uRL = url)
            ?: throw UnsupportedFormatException(
                "Не удалось открыть PDF: файл повреждён, не поддерживается или защищён паролем."
            )
        if (pdfDoc.isLocked) {
            throw UnsupportedFormatException("Документ защищён паролем: снимите защиту и импортируйте снова.")
        }
        val pageCount = pdfDoc.pageCount.toInt()
        if (pageCount <= 0) {
            throw UnsupportedFormatException("В документе нет страниц: файл повреждён или пуст.")
        }
        val builder = StringBuilder()

        for (i in 0 until pageCount) {
            ensureActive() // отмена импорта между страницами (Vision OCR скана — самый долгий этап)
            val page = pdfDoc.pageAtIndex(i.toULong()) ?: continue
            val text = page.string?.trim() ?: ""

            if (text.length > 40 || !withOcr) {
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

            // Повышенное разрешение для точности OCR; огромные страницы ограничиваются по пикселям,
            // иначе миниатюра плаката переполняет память
            val (width, height) = PdfRenderSize.fit(
                pageWidth, pageHeight,
                (pageWidth * 2).toInt().coerceAtMost(OCR_MAX_WIDTH), OCR_MAX_PIXELS
            )
            val image = page.thumbnailOfSize(
                CGSizeMake(width.toDouble(), height.toDouble()),
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

    private companion object {
        /** Потолок ширины рендера OCR: обычная страница и так рисуется вдвое крупнее, больше не нужно. */
        const val OCR_MAX_WIDTH = 2000
        const val OCR_MAX_PIXELS = 4_000_000L
    }
}
