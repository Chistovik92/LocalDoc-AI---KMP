package com.localdoc.ai.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled

private const val KEY_APPLE_LANGUAGES = "AppleLanguages"
private const val KEY_LANGUAGE = "localdoc.ui.language"

actual object AppLocale {
    /** iOS читает язык при запуске процесса: смена вступает в силу после перезапуска приложения. */
    actual val appliesImmediately: Boolean = false

    actual fun savedTag(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY_LANGUAGE)

    actual fun save(tag: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (tag == null) {
            defaults.removeObjectForKey(KEY_LANGUAGE)
            defaults.removeObjectForKey(KEY_APPLE_LANGUAGES)
        } else {
            defaults.setObject(tag, KEY_LANGUAGE)
            defaults.setObject(listOf(tag), KEY_APPLE_LANGUAGES)
        }
    }

    actual fun effectiveTag(): String =
        savedTag() ?: (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') ?: "en"

    actual val systemHighContrast: Boolean
        get() = UIAccessibilityDarkerSystemColorsEnabled()
}
