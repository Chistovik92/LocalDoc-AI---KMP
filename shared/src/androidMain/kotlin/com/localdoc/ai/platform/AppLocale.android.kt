package com.localdoc.ai.platform

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

private const val PREFS = "localdoc_ui"
private const val KEY_LANGUAGE = "language"

actual object AppLocale {
    /** Язык системы, запомненный до первой подмены `Locale.setDefault`. */
    private var systemLocale: Locale? = null

    actual val appliesImmediately: Boolean = true

    actual fun savedTag(): String? =
        AppContextHolder.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    actual fun save(tag: String?) {
        AppContextHolder.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, tag)
        }.apply()
        // Язык берётся при создании окна (attachBaseContext) — пересоздаём его, состояние хранится в AppGraph
        CurrentActivity.activity?.let { activity -> activity.runOnUiThread { activity.recreate() } }
    }

    actual fun effectiveTag(): String = savedTag() ?: Locale.getDefault().language

    actual val systemHighContrast: Boolean
        get() = runCatching {
            android.provider.Settings.Secure.getInt(AppContextHolder.appContext.contentResolver, "high_text_contrast_enabled", 0) == 1
        }.getOrDefault(false)

    /** Контекст окна с выбранным языком; вызывается из `attachBaseContext` активности. */
    fun wrap(base: Context): Context {
        val system = systemLocale ?: Locale.getDefault().also { systemLocale = it }
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        if (tag == null) {
            // Выбор сброшен: возвращаем язык системы, иначе процесс остаётся на прежнем выбранном
            Locale.setDefault(system)
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
