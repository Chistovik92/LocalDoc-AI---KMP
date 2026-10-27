package com.localdoc.ai.platform

import com.localdoc.ai.domain.OrientationLock

/** Системные настройки экрана для читалки; всё возвращается на место при выходе из читалки. */
expect object ScreenControls {
    /** Блокировка ориентации доступна (Android). На iOS ориентации задаёт Info.plist. */
    val supportsOrientationLock: Boolean

    /** Листание кнопками громкости доступно (Android). */
    val supportsVolumeKeys: Boolean

    /** В системе включено «уменьшить анимацию» (Android: масштаб анимации 0, iOS: Reduce Motion). */
    val prefersReducedMotion: Boolean

    /** Яркость окна 0..1; null — вернуть яркость системы. */
    fun setBrightness(level: Float?)

    fun setKeepScreenOn(on: Boolean)

    fun setOrientation(lock: OrientationLock)
}

/**
 * Кнопки громкости как кнопки листания. Читалка ставит [handler] на время показа; пока он задан,
 * платформа отдаёт ему нажатия (Android) вместо изменения громкости.
 */
object VolumeKeys {
    var handler: ((forward: Boolean) -> Unit)? = null

    /** true — нажатие обработано читалкой и системе его передавать не нужно. */
    fun onKey(forward: Boolean, repeat: Boolean): Boolean {
        val current = handler ?: return false
        if (!repeat) current(forward)
        return true
    }
}
