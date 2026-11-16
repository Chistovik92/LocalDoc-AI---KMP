package com.localdoc.ai.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android: проверка через Settings.Global.ANIMATOR_DURATION_SCALE.
 * Значение 0.0 означает, что анимация отключена системно.
 */
@Composable
actual fun rememberReduceMotion(): Boolean {
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
