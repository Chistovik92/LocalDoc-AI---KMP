package com.localdoc.ai.platform

/**
 * Проверяет, включён ли режим сниженной анимации в системных настройках.
 * 
 * Android: читает Settings.Global.ANIMATOR_DURATION_SCALE (0.0 = включён).
 * iOS: читает UIAccessibility.isReduceMotionEnabled.
 * 
 * @return true если пользователь включил "Reduce Motion" / "Отключить анимацию"
 */
expect fun isReduceMotionEnabled(): Boolean
