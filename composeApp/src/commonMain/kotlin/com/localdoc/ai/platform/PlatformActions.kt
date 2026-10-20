package com.localdoc.ai.platform

import androidx.compose.runtime.Composable

/** Системные действия, которых нет в общем коде. */
expect object PlatformActions {
    /** Версия приложения (versionName / CFBundleShortVersionString). */
    val appVersion: String

    /** true — Android: доступны сторонние TTS-движки и установка APK. */
    val supportsTtsEngines: Boolean

    fun openUrl(url: String)

    /** Системные настройки синтеза речи (выбор движка по умолчанию, голоса). */
    fun openTtsSettings()

    /** Установка голосовых данных текущего движка. */
    fun installTtsData()

    /** HTTP GET с заголовками; бросает исключение при ошибке сети или статусе ≠ 2xx. */
    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String
}

/** Системная кнопка «Назад» (Android). На iOS — без действия: там есть кнопки в UI. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
