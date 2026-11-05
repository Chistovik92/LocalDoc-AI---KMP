package com.localdoc.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.AppLocale
import com.localdoc.ai.platform.ScreenControls

/** Анимации нужно отключить: так просит система либо включено в настройках доступности. */
@Composable
fun reduceMotionEnabled(): Boolean {
    val settings by AppGraph.accessibilitySettings.value.collectAsState()
    return settings.reduceMotion || ScreenControls.prefersReducedMotion
}

/** Нужна высококонтрастная тема: так просит система либо включено в настройках доступности. */
@Composable
fun highContrastEnabled(): Boolean {
    val settings by AppGraph.accessibilitySettings.value.collectAsState()
    return settings.highContrast || AppLocale.systemHighContrast
}
