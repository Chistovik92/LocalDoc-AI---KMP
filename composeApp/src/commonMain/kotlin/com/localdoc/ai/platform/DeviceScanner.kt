package com.localdoc.ai.platform

/** Файл, найденный при сканировании. [uri] открывается через [FileAccess.readBytes]. */
data class ScannedFile(
    val uri: String,
    val name: String,
    val size: Long,
    /** Время изменения, мс от эпохи (0 — неизвестно). */
    val modified: Long,
    /** Человекочитаемый путь папки, в которой лежит файл. */
    val folder: String
)

/** Выбранная пользователем папка или подключённый ресурс (SD-карта, USB, облако, сетевая папка). */
data class ScanRoot(val id: String, val title: String)

/**
 * Поиск файлов книг: на всём устройстве (Android, нужен «доступ ко всем файлам»), в выбранной папке
 * или на подключённом ресурсе. Обход потоковый: найденные файлы отдаются по одному, отмена — отменой корутины.
 */
expect class DeviceScanner() {
    /** true — платформа позволяет обойти всё устройство (Android). */
    val supportsWholeDevice: Boolean

    fun hasAllFilesAccess(): Boolean

    /** Открывает системные настройки, где пользователь разрешает доступ ко всем файлам. */
    fun requestAllFilesAccess()

    /** Системный выбор папки / ресурса; null — отмена. Доступ запоминается между запусками. */
    fun pickFolder(onResult: (ScanRoot?) -> Unit)

    /**
     * Обходит [rootId] (`device` — всё устройство, иначе идентификатор из [pickFolder]); для каждого файла
     * с расширением из [extensions] вызывает [onFound]. Возвращает число просмотренных файлов.
     */
    suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int
}
