package com.localdoc.ai.platform

import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable

/**
 * Android: проверка через Settings.Global.ANIMATOR_DURATION_SCALE.
 * Значение 0.0 означает, что анимация отключена системно.
 */
actual fun isReduceMotionEnabled(): Boolean {
    return false // Заглушка: требует Context, реализация в @Composable rememberReduceMotion()
}

/**
 * Composable-версия для Android: читает ANIMATOR_DURATION_SCALE через LocalContext.
 */
@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        ) == 0.0f
    } catch (_: Exception) {
        false
    }
}
