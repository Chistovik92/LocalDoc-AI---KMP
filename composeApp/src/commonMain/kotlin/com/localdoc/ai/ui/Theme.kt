package com.localdoc.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра Google Material 3 (как в Gmail, Drive, Play Книгах). На Android 12+
 * используются динамические цвета обоев (Material You).
 */
object GoogleColors {
    val Blue = Color(0xFF4285F4)
    val Red = Color(0xFFEA4335)
    val Yellow = Color(0xFFFBBC04)
    val Green = Color(0xFF34A853)

    /** Градиент Gemini для акцентов ИИ. */
    val AiGradient = Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570)))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF146C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EED0),
    onTertiaryContainer = Color(0xFF072711),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8FAFD),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E1),
    onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFFA8C7FA)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C),
    onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiaryContainer = Color(0xFFC4EED0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444746),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF303030),
    inversePrimary = Color(0xFF0B57D0)
)

/** Высококонтрастные схемы: текст и границы не слабее 7:1 к фону. */
private val HighContrastLight = lightColorScheme(
    primary = Color(0xFF002D9C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E0FF), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF00334E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9FF), onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF0B4A1E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4EED0), onTertiaryContainer = Color.Black,
    error = Color(0xFF8C0009), onError = Color.White,
    errorContainer = Color(0xFFFFD9D6), onErrorContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color.Black,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFE8E8E8), surfaceContainerHighest = Color(0xFFDEDEDE),
    outline = Color.Black, outlineVariant = Color(0xFF3C3C3C),
    inverseSurface = Color.Black, inverseOnSurface = Color.White, inversePrimary = Color(0xFFB5CBFF)
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFFCFE0FF), onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F3F8F), onPrimaryContainer = Color.White,
    secondary = Color(0xFFBFE6FF), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1B4D6E), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFB4F0C5), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF1F5A31), onTertiaryContainer = Color.White,
    error = Color(0xFFFFB4AB), onError = Color.Black,
    errorContainer = Color(0xFF7A1009), onErrorContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color.White,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1E1E1E), surfaceContainerHighest = Color(0xFF282828),
    outline = Color.White, outlineVariant = Color(0xFFC8C8C8),
    inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color(0xFF002D9C)
)

private val GoogleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun googleTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Normal, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium)
    )
}

/** Динамическая схема Material You (Android 12+) или null. */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?

@Composable
fun LocalDocTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        highContrastEnabled() -> if (dark) HighContrastDark else HighContrastLight
        else -> dynamicColorScheme(dark) ?: if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = GoogleShapes,
        typography = googleTypography(),
        content = content
    )
}

/** Заголовок с градиентом Gemini («Привет! Чем помочь?»). */
fun gradientTextStyle(base: TextStyle): TextStyle = base.copy(brush = GoogleColors.AiGradient)
