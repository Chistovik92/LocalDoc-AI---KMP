package com.localdoc.ai.domain

import com.localdoc.ai.resources.Res
import com.localdoc.ai.resources.theme_auto
import com.localdoc.ai.resources.theme_dark
import com.localdoc.ai.resources.theme_light
import com.localdoc.ai.resources.theme_night
import com.localdoc.ai.resources.theme_sepia
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

/** Цвет темы оформления всего приложения (не только читалки). */
@Serializable
enum class AppTheme(val label: StringResource) {
    SYSTEM(Res.string.theme_auto),
    LIGHT(Res.string.theme_light),
    SEPIA(Res.string.theme_sepia),
    DARK(Res.string.theme_dark),
    BLACK(Res.string.theme_night)
}

/**
 * Настройки доступности поверх системных: включить можно и без системного переключателя.
 * Размер шрифта отдельной настройки не требует — интерфейс следует системному масштабу шрифта.
 */
@Serializable
data class AccessibilitySettings(
    /** Тема оформления всего приложения; [AppTheme.SYSTEM] — как в системе (поведение по умолчанию). */
    val appTheme: AppTheme = AppTheme.SYSTEM,
    /** Высококонтрастная тема (чёрное на белом / белое на чёрном, усиленные границы). */
    val highContrast: Boolean = false,
    /** Не анимировать листание и переходы, даже если система этого не просит. */
    val reduceMotion: Boolean = false
)
