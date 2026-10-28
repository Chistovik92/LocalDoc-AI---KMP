package com.localdoc.ai.platform

/** Язык интерфейса и системные настройки доступности, зависящие от платформы. */
expect object AppLocale {
    /** Выбранный язык применяется сразу (Android: окно пересоздаётся); false — после перезапуска (iOS). */
    val appliesImmediately: Boolean

    /** Сохранённый выбор языка интерфейса (код вроде `en`); null — как в системе. */
    fun savedTag(): String?

    /** Сохраняет выбор (null — как в системе) и, если платформа позволяет, применяет сразу. */
    fun save(tag: String?)

    /** Код действующего языка интерфейса: выбранный либо системный (`ru`, `en`…). */
    fun effectiveTag(): String

    /** В системе включён режим повышенной контрастности (Android: высококонтрастный текст, iOS: Increase Contrast). */
    val systemHighContrast: Boolean
}

private val DECIMAL_COMMA_LANGUAGES = setOf("ru", "uk", "de", "es")

/** Десятичная дробь с разделителем по языку интерфейса: «1,5» для ru/uk/de/es, «1.5» для en. */
fun formatDecimal(value: Double, digits: Int = 1): String {
    var scaled = value
    repeat(digits) { scaled *= 10 }
    val rounded = kotlin.math.round(scaled).toLong()
    var divisor = 1L
    repeat(digits) { divisor *= 10 }
    val sign = if (rounded < 0) "-" else ""
    val abs = kotlin.math.abs(rounded)
    val separator = if (AppLocale.effectiveTag() in DECIMAL_COMMA_LANGUAGES) "," else "."
    if (digits == 0) return sign + abs
    return sign + (abs / divisor) + separator + (abs % divisor).toString().padStart(digits, '0')
}
