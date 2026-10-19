package com.localdoc.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Палитра совпадает с иконкой приложения: индиго-градиент + янтарная «искра» ИИ
private val Indigo900 = Color(0xFF1A237E)
private val Indigo500 = Color(0xFF3D5AFE)
private val Indigo200 = Color(0xFF9FA8FF)
private val Amber = Color(0xFFFFC400)
private val ErrorRed = Color(0xFFE53935)

private val DarkColors = darkColorScheme(
    primary = Indigo200,
    onPrimary = Indigo900,
    primaryContainer = Color(0xFF2B3480),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Amber,
    onSecondary = Color(0xFF2A1F00),
    secondaryContainer = Color(0xFF4A3A00),
    onSecondaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0F1220),
    onBackground = Color(0xFFE3E4F0),
    surface = Color(0xFF151929),
    onSurface = Color(0xFFE3E4F0),
    surfaceVariant = Color(0xFF1F2438),
    onSurfaceVariant = Color(0xFFB9BDD3),
    surfaceContainer = Color(0xFF181C2D),
    outline = Color(0xFF3A4060),
    error = ErrorRed
)

private val LightColors = lightColorScheme(
    primary = Indigo500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Indigo900,
    secondary = Color(0xFF8A6A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color(0xFF2A1F00),
    background = Color(0xFFF6F7FD),
    onBackground = Color(0xFF1A1C24),
    surface = Color.White,
    onSurface = Color(0xFF1A1C24),
    surfaceVariant = Color(0xFFE9EBF7),
    onSurfaceVariant = Color(0xFF4A4E63),
    surfaceContainer = Color(0xFFF0F1FA),
    outline = Color(0xFFC4C7DA),
    error = ErrorRed
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun LocalDocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
