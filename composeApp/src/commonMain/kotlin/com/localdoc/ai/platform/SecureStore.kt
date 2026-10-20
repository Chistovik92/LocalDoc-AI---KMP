package com.localdoc.ai.platform

/**
 * Защищённое хранилище секретов (пароли, cookie сессий). Android — ключ в Android Keystore
 * (AES-256-GCM), значения зашифрованы; iOS — Keychain (только на этом устройстве, без iCloud и
 * резервных копий, доступно при разблокированном устройстве). Значения не попадают в логи и обычные prefs.
 */
expect class SecureStore() {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
