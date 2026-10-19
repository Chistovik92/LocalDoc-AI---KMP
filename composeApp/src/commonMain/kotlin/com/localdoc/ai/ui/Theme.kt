package com.localdoc.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C9EFF)
private val AccentDark = Color(0xFF3D5AFE)
private val Success = Color(0xFF4CAF50)
private val Warning = Color(0xFFFF9800)
private val Error = Color(0xFFE53935)

@Composable
fun LocalDocTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Accent,
                onPrimary = Color.White,
                secondary = Color(0xFF80CBC4),
                background = Color(0xFF101318),
                surface = Color(0xFF161A21),
                surfaceVariant = Color(0xFF1E242E),
                error = Error,
                onBackground = Color(0xFFE1E3E8),
                onSurface = Color(0xFFE1E3E8)
            )
        } else {
            lightColorScheme(
                primary = AccentDark,
                onPrimary = Color.White,
                secondary = Color(0xFF00695C),
                background = Color(0xFFF7F8FC),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFE8EBF4),
                error = Error,
                onBackground = Color(0xFF1A1C1E),
                onSurface = Color(0xFF1A1C1E)
            )
        },
        content = content
    )
}
