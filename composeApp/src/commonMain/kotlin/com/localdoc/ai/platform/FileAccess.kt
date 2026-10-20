package com.localdoc.ai.platform

/**
 * Файлы: чтение выбранного пользователем документа (Android — content:// Uri,
 * iOS — путь) и приватное хранилище приложения для кэша разобранных книг.
 */
expect class FileAccess() {
    /** Содержимое внешнего файла; бросает исключение, если файл недоступен. */
    suspend fun readBytes(pathOrUri: String): ByteArray

    /** Имя файла для показа и определения формата по расширению. */
    fun displayName(pathOrUri: String): String?

    fun writeAppFile(name: String, bytes: ByteArray)

    fun readAppFile(name: String): ByteArray?

    fun deleteAppFile(name: String)
}
