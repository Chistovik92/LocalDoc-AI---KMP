package com.localdoc.ai.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/** iOS: файлы из UIDocumentPicker (копии в tmp) и Documents/books для кэша книг. */
@OptIn(ExperimentalForeignApi::class)
actual class FileAccess actual constructor() {

    private val booksDir: String by lazy {
        val dir = "${NSHomeDirectory()}/Documents/books"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }

    actual suspend fun readBytes(pathOrUri: String): ByteArray = withContext(Dispatchers.IO) {
        val path = pathOrUri.removePrefix("file://")
        val data = NSData.dataWithContentsOfFile(path) ?: throw IllegalStateException("Файл недоступен: $path")
        data.toByteArray()
    }

    actual fun displayName(pathOrUri: String): String? = pathOrUri.substringAfterLast('/').takeIf { it.isNotBlank() }

    /** Путь внутри хранилища: без «..» и абсолютных путей. */
    private fun pathOf(name: String): String {
        require(name.isNotBlank() && !name.startsWith("/") && ".." !in name.split('/')) { "Недопустимое имя файла: $name" }
        return "$booksDir/$name"
    }

    actual fun writeAppFile(name: String, bytes: ByteArray) {
        val path = pathOf(name)
        if ('/' in name) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                path.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null
            )
        }
        bytes.toNSData().writeToFile(path, atomically = true)
    }

    actual fun readAppFile(name: String): ByteArray? =
        NSData.dataWithContentsOfFile(pathOf(name))?.toByteArray()

    actual fun deleteAppFile(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(pathOf(name), error = null)
    }

    actual fun listAppFiles(prefix: String): List<String> {
        val fm = NSFileManager.defaultManager
        val all = fm.subpathsOfDirectoryAtPath(booksDir, error = null).orEmpty().filterIsInstance<String>()
        return all.filter { it.startsWith(prefix) && !it.endsWith(".tmp") && !isDirectory("$booksDir/$it") }.sorted()
    }

    actual fun deleteAppFiles(prefix: String) {
        val fm = NSFileManager.defaultManager
        val all = fm.subpathsOfDirectoryAtPath(booksDir, error = null).orEmpty().filterIsInstance<String>()
        // Глубокие пути первыми: папка удаляется вместе с содержимым, повторное удаление безвредно
        all.filter { it.startsWith(prefix) }.sortedByDescending { it.length }.forEach { fm.removeItemAtPath("$booksDir/$it", error = null) }
    }

    actual fun appFileSize(name: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(pathOf(name), error = null) ?: return 0L
        return (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
    }

    actual suspend fun copyToAppFile(pathOrUri: String, name: String): Long = withContext(Dispatchers.IO) {
        val target = pathOf(name)
        val fm = NSFileManager.defaultManager
        if ('/' in name) fm.createDirectoryAtPath(target.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
        fm.removeItemAtPath(target, error = null)
        val source = pathOrUri.removePrefix("file://")
        if (!fm.copyItemAtPath(source, toPath = target, error = null)) throw IllegalStateException("Файл недоступен: $source")
        appFileSize(name)
    }

    actual fun appFilePath(name: String): String = pathOf(name)

    private fun isDirectory(path: String): Boolean {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return false
        return attrs[NSFileType] == NSFileTypeDirectory
    }
}
