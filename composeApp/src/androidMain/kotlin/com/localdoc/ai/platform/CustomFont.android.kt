package com.localdoc.ai.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

actual fun fontFamilyFromBytes(name: String, bytes: ByteArray): FontFamily? = runCatching {
    val dir = File(AppContextHolder.appContext.cacheDir, "fonts").apply { mkdirs() }
    val file = File(dir, name.replace('/', '_'))
    if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
    FontFamily(Font(file))
}.getOrNull()
