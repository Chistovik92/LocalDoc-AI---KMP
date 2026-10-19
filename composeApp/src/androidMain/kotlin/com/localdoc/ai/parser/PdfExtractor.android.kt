package com.localdoc.ai.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.localdoc.ai.platform.AppContextHolder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Android: гибридное чтение PDF — текстовый слой (PDFBox) с фоллбэком на OCR (ML Kit). */
actual class PdfExtractor actual constructor() {

    private val context: Context get() = AppContextHolder.appContext

    private val ocrRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    actual suspend fun extract(pathOrUri: String): ParsedDoc = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val uri = Uri.parse(pathOrUri)
        val fullText = StringBuilder()
        var totalPages = 0

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            PDDocument.load(inputStream).use { pdDoc ->
                totalPages = pdDoc.numberOfPages
                val stripper = PDFTextStripper()

                for (pageIndex in 1..totalPages) {
                    stripper.startPage = pageIndex
                    stripper.endPage = pageIndex
                    val pageText = stripper.getText(pdDoc).trim()

                    if (pageText.length > 40) {
                        // Нормальный текстовый слой
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
                    // Рендер в повышенном разрешении для точности OCR мелкого шрифта
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2,
                        page.height * 2,
                        Bitmap.Config.ARGB_8888
                    )
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
}
