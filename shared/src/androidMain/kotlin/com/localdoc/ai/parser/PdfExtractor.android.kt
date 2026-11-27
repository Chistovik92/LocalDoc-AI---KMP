package com.localdoc.ai.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.localdoc.ai.book.UnsupportedFormatException
import com.localdoc.ai.book.pdf.PdfRenderSize
import com.localdoc.ai.platform.AppContextHolder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Android: гибридное чтение PDF — текстовый слой (PDFBox) с фоллбэком на OCR (ML Kit). */
actual class PdfExtractor actual constructor() {

    private val context: Context get() = AppContextHolder.appContext

    private val ocrRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    actual suspend fun extract(pathOrUri: String, withOcr: Boolean): ParsedDoc = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val uri = if (pathOrUri.startsWith("/")) Uri.fromFile(java.io.File(pathOrUri)) else Uri.parse(pathOrUri)
        val fullText = StringBuilder()
        var totalPages = 0

        // Раньше недоступный источник молча давал «пустую книгу» — теперь видна причина
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw UnsupportedFormatException("Файл PDF недоступен: источник «$pathOrUri» не открывается.")
        inputStream.use { stream ->
            val pdDoc = try {
                PDDocument.load(stream)
            } catch (e: java.io.IOException) {
                throw UnsupportedFormatException(openFailureReason(e))
            }
            pdDoc.use { doc ->
                totalPages = doc.numberOfPages
                val stripper = PDFTextStripper()

                for (pageIndex in 1..totalPages) {
                    ensureActive() // отмена импорта между страницами (OCR скана — самый долгий этап)
                    stripper.startPage = pageIndex
                    stripper.endPage = pageIndex
                    val pageText = stripper.getText(doc).trim()

                    if (pageText.length > 40 || !withOcr) {
                        // Нормальный текстовый слой (или OCR отложен — только текстовый слой)
                        fullText.append("\n--- [Страница $pageIndex] ---\n").append(pageText)
                    } else {
                        // Скановая страница — локальный OCR через ML Kit
                        val ocrText = runOcrOnPdfPage(uri, pageIndex - 1)
                        fullText.append("\n--- [Страница $pageIndex (OCR)] ---\n").append(ocrText)
                    }
                }
            }
        }

        ParsedDoc(
            title = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Документ.pdf",
            content = fullText.toString().trim(),
            source = pathOrUri,
            pageCount = totalPages
        )
    }

    /**
     * Понятная причина, по которой PDF не открылся, вместо технической ошибки библиотеки:
     * защищённые паролем документы отличаем от повреждённых.
     */
    private fun openFailureReason(e: java.io.IOException): String {
        val message = e.message?.lowercase().orEmpty()
        return if ("password" in message || "decrypt" in message || "encrypt" in message)
            "Документ защищён паролем: снимите защиту и импортируйте снова."
        else "Не удалось открыть PDF: файл повреждён или не поддерживается."
    }

    /** Имя файла для content:// URI (lastPathSegment там вида «document:1234»). */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Рендеринг страницы в Bitmap и офлайн-распознавание через ML Kit (NPU). */
    private suspend fun runOcrOnPdfPage(uri: Uri, zeroBasedPageIndex: Int): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(zeroBasedPageIndex).use { page ->
                    // Рендер в повышенном разрешении для точности OCR мелкого шрифта. Огромные страницы
                    // (плакаты, чертежи) ограничиваются по пикселям — иначе рендер переполняет память
                    val (width, height) = PdfRenderSize.fit(
                        page.width.toDouble(), page.height.toDouble(),
                        (page.width * 2).coerceAtMost(OCR_MAX_WIDTH), OCR_MAX_PIXELS
                    )
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val text = suspendCancellableCoroutine { cont ->
                        ocrRecognizer.process(inputImage)
                            .addOnSuccessListener { cont.resume(it.text) }
                            .addOnFailureListener { cont.resume("") }
                    }
                    bitmap.recycle()
                    text
                }
            }
        }
    }

    private companion object {
        /** Потолок ширины рендера OCR: обычная страница и так рисуется вдвое крупнее, больше не нужно. */
        const val OCR_MAX_WIDTH = 2000
        const val OCR_MAX_PIXELS = 4_000_000L
    }
}
