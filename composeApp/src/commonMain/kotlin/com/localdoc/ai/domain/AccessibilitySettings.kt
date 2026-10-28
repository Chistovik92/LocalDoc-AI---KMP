package com.localdoc.ai.domain

import kotlinx.serialization.Serializable

/**
 * Настройки доступности поверх системных: включить можно и без системного переключателя.
 * Размер шрифта отдельной настройки не требует — интерфейс следует системному масштабу шрифта.
 */
@Serializable
data class AccessibilitySettings(
    /** Высококонтрастная тема (чёрное на белом / белое на чёрном, усиленные границы). */
    val highContrast: Boolean = false,
    /** Не анимировать листание и переходы, даже если система этого не просит. */
    val reduceMotion: Boolean = false
)
