package com.localdoc.ai.platform

/**
 * Системный плеер озвучки: Android — уведомление-медиаплеер в шторке (foreground-сервис,
 * чтение не обрывается в фоне) и виджет; iOS — «Пункт управления» и экран блокировки.
 * Вызывается один раз при старте UI.
 */
expect object SystemPlayer {
    fun start()
}
