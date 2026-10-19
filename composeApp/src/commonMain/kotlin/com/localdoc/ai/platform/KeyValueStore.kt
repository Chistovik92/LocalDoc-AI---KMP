package com.localdoc.ai.platform

/** Простой строковый KV-стор (Android: SharedPreferences, iOS: NSUserDefaults). */
expect class KeyValueStore() {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clearAll()
}
