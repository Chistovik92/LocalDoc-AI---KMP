package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults
import platform.Foundation.setValue

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
        val domain = NSUserDefaults.standardUserDefaults.dictionaryRepresentation()
        domain.keys.toList().forEach { defaults.removeObjectForKey(it as String) }
    }
}
