package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
/** iOS-реализация KV-хранилища на NSUserDefaults. */
actual class KeyValueStore actual constructor() {

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? =
        defaults.stringForKey(key)

    actual fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    actual fun clearAll() {
        // dictionaryRepresentation() включает и системные ключи (NSGlobalDomain) —
        // очищаем только домен приложения
        val bundleId = NSBundle.mainBundle.bundleIdentifier ?: return
        defaults.removePersistentDomainForName(bundleId)
    }
}
