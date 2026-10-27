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

    /** Имена файлов хранилища приложения, начинающиеся с [prefix] (вложенные папки — через «/»). */
    fun listAppFiles(prefix: String): List<String>

    /** Удаляет все файлы и папки хранилища, имя которых начинается с [prefix]. */
    fun deleteAppFiles(prefix: String)

    /** Размер файла хранилища в байтах (0 — файла нет). */
    fun appFileSize(name: String): Long

    /** Потоково копирует внешний файл в хранилище приложения (без загрузки в память); возвращает размер в байтах. */
    suspend fun copyToAppFile(pathOrUri: String, name: String): Long

    /** Абсолютный путь файла хранилища — для платформенных API, которым нужен путь (рендер PDF). */
    fun appFilePath(name: String): String
}
