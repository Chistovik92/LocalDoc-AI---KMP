package com.localdoc.ai.storage

/** Минимальный доступ к KV-настройкам для миграций, резервных копий и синхронизации (подменяется в тестах). */
interface KvAccess {
    fun getValue(key: String): String?
    fun putValue(key: String, value: String)
    fun removeValue(key: String)
}
