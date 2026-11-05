package com.localdoc.ai.platform

import com.localdoc.ai.domain.OrientationLock
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

actual object ScreenControls {
    actual val supportsOrientationLock: Boolean = false
    actual val supportsVolumeKeys: Boolean = false

    actual val prefersReducedMotion: Boolean
        get() = UIAccessibilityIsReduceMotionEnabled()

    /** Яркость, которая была до первого изменения: при null возвращается она. */
    private var originalBrightness: Double? = null

    actual fun setBrightness(level: Float?) {
        val screen = UIScreen.mainScreen
        if (level != null) {
            if (originalBrightness == null) originalBrightness = screen.brightness
            screen.brightness = level.coerceIn(0.01f, 1f).toDouble()
        } else {
            originalBrightness?.let { screen.brightness = it }
            originalBrightness = null
        }
    }

    actual fun setKeepScreenOn(on: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = on
    }

    /** Блокировка ориентации на iOS не поддерживается: ориентации задаёт Info.plist. */
    actual fun setOrientation(lock: OrientationLock) = Unit
}
