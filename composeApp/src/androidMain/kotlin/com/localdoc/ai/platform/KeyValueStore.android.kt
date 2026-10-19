package com.localdoc.ai.platform

import android.content.Context

/** Хранилище контекста приложения для платформенных фабрик без аргументов. */
object AppContextHolder {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

/** Android-реализация KV-хранилища на SharedPreferences. */
actual class KeyValueStore actual constructor() {

    private val prefs =
        AppContextHolder.appContext.getSharedPreferences("localdoc", Context.MODE_PRIVATE)

    actual fun get(key: String): String? = prefs.getString(key, null)

    actual fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    actual fun clearAll() {
        prefs.edit().clear().apply()
    }
}
