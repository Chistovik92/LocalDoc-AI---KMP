package com.localdoc.ai.platform

/** Кроссплатформенный выбор файла документа. */
expect class FilePicker() {
    /**
     * Открывает системный выбор файла.
     * onResult: путь/Uri выбранного файла или null при отмене.
     */
    fun pick(mime: String, onResult: (String?) -> Unit)
}
