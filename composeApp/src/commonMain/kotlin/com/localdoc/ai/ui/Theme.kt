package com.localdoc.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C9EFF)
private val AccentDark = Color(0xFF3D5AFE)

@Composable
fun LocalDocTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Accent,
                background = Color(0xFF101318),
                surface = Color(0xFF161A21),
                surfaceVariant = Color(0xFF1E242E)
            )
        } else {
            lightColorScheme(
                primary = AccentDark,
                background = Color(0xFFF7F8FC),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFE8EBF4)
            )
        },
        content = content
    )
}
