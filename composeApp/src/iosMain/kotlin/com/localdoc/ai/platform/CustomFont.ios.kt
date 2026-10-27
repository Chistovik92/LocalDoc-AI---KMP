package com.localdoc.ai.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

actual fun fontFamilyFromBytes(name: String, bytes: ByteArray): FontFamily? =
    runCatching { FontFamily(Font(identity = name, data = bytes)) }.getOrNull()
