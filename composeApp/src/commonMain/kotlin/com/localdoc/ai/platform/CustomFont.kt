package com.localdoc.ai.platform

import androidx.compose.ui.text.font.FontFamily

/** Шрифт из байт файла TTF/OTF; null — файл не удалось прочитать как шрифт. Android — файл в кэше, iOS — Skia. */
expect fun fontFamilyFromBytes(name: String, bytes: ByteArray): FontFamily?
