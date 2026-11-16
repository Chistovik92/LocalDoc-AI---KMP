package com.localdoc.ai.ui

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibility

/**
 * iOS: проверка через UIAccessibility.isReduceMotionEnabled.
 */
@Composable
actual fun rememberReduceMotion(): Boolean {
    return UIAccessibility.isReduceMotionEnabled()
}
