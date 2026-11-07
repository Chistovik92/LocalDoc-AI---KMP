package com.localdoc.ai.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
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
        renderer.openPage(index).use { page ->
            val (width, height) = PdfRenderSize.fit(page.width.toDouble(), page.height.toDouble(), widthPx)
            // Системный PdfRenderer выделяет страницу как hardware bitmap на Android 12+.
            // Bitmap.compress и Canvas для такого bitmap запрещены: получаем программную копию,
            // иначе первая страница-обложка может быть видна, а читалка падает на каждой странице.
            val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // Страница PDF прозрачная: без белой подложки текст на тёмной теме был бы не виден
                rendered.eraseColor(Color.WHITE)
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rendered.config == Bitmap.Config.HARDWARE) {
                    rendered.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("Не удалось получить программную копию страницы")
                } else rendered
                try {
                    return ByteArrayOutputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                        output.toByteArray()
                    }
                } finally {
                    if (bitmap !== rendered) bitmap.recycle()
                }
            } finally {
                rendered.recycle()
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
