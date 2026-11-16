package com.localdoc.ai.platform

import platform.UIKit.UIAccessibility

/**
 * iOS: проверка через UIAccessibility.isReduceMotionEnabled.
 */
actual fun isReduceMotionEnabled(): Boolean {
    return UIAccessibility.isReduceMotionEnabled()
}
