package com.localdoc.ai.ui

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * iOS: проверка через UIAccessibility.isReduceMotionEnabled.
 */
@Composable
actual fun rememberReduceMotion(): Boolean {
    return UIAccessibilityIsReduceMotionEnabled()
}
