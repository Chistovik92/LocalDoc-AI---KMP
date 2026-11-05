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

    /** Запускает системную установку скачанного APK (Android); на iOS — [InstallResult.Unsupported]. */
    fun installApk(path: String): InstallResult

    /** HTTP GET с заголовками; бросает исключение при ошибке сети или статусе ≠ 2xx. */
    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String
}

/** Системная кнопка «Назад» (Android). На iOS — без действия: там есть кнопки в UI. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Итог попытки установить APK. */
enum class InstallResult {
    /** Системный установщик открыт. */
    Started,

    /** Нужно разрешить установку из этого приложения в настройках; экран настроек уже открыт. */
    NeedsPermission,

    /** Установка не поддерживается или установщик не найден. */
    Unsupported
}
