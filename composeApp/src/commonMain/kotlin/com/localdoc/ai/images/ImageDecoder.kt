package com.localdoc.ai.images

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Декодирует PNG/JPEG/GIF/WebP/BMP, уменьшая большую сторону до [maxDimension] (Android — BitmapFactory с
 * inSampleSize, iOS — Skia). Слишком большие картинки (> 60 Мпикс) и повреждённые данные дают null, а не OOM или сбой.
 * [preferRgb565] — два байта на пиксель вместо четырёх: для PDF-страниц без прозрачности хватает с запасом,
 * картинки маленьких страниц меньше забивают память слабых устройств.
 */
expect fun decodeImageBitmap(bytes: ByteArray, maxDimension: Int, preferRgb565: Boolean = false): ImageBitmap?
