package com.localdoc.ai.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

// На iOS картинка декодируется целиком при отрисовке, поэтому предел ниже, чем на Android
private const val MAX_PIXELS = 24_000_000L

/** Декодирует картинку и уменьшает большую сторону до [maxDimension], чтобы обложка не занимала память полного кадра. */
actual fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    val longest = maxOf(image.width, image.height)
    when {
        image.width.toLong() * image.height > MAX_PIXELS -> null
        longest <= maxDimension -> image.toComposeImageBitmap()
        else -> {
            val scale = maxDimension.toFloat() / longest
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(width, height)
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }
} catch (e: Exception) {
    null
}
