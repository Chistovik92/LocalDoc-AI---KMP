package com.localdoc.ai.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private const val MAX_PIXELS = 60_000_000L

actual fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    if (image.width.toLong() * image.height > MAX_PIXELS) null else image.toComposeImageBitmap()
} catch (e: Exception) {
    null
}
