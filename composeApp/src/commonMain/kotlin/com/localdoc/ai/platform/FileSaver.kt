package com.localdoc.ai.platform

/** Итог сохранения файла пользователем. */
sealed interface SaveResult {
    data class Saved(val location: String?) : SaveResult
    data object Cancelled : SaveResult
    data class Failed(val message: String) : SaveResult
}

/**
 * «Сохранить как…»: системное окно выбора места и имени (Android — создание документа SAF,
 * iOS — экспорт через «Файлы»). Приложению не нужен доступ ко всему хранилищу.
 */
expect class FileSaver() {
    fun save(fileName: String, mime: String, bytes: ByteArray, onResult: (SaveResult) -> Unit)
}
