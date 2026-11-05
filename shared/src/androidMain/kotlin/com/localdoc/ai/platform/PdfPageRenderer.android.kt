package com.localdoc.ai.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.localdoc.ai.book.pdf.PageRenderer
import com.localdoc.ai.book.pdf.PdfRenderSize
import java.io.ByteArrayOutputStream
import java.io.File

/** Android: системный `PdfRenderer`. Он держит открытой одну страницу, поэтому вызовы последовательны. */
actual class PdfPageRenderer actual constructor(path: String) : PageRenderer {

    private val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (t: Throwable) {
        descriptor.close()
        throw t
    }

    actual override val pageCount: Int = renderer.pageCount

    @Synchronized
    actual override fun render(index: Int, widthPx: Int): ByteArray? {
        if (index !in 0 until pageCount) return null
        return renderer.openPage(index).use { page ->
            val (width, height) = PdfRenderSize.fit(page.width.toDouble(), page.height.toDouble(), widthPx)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // Страница PDF прозрачная: без белой подложки текст на тёмной теме был бы не виден
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Synchronized
    actual override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private companion object {
        const val JPEG_QUALITY = 88
    }
}
