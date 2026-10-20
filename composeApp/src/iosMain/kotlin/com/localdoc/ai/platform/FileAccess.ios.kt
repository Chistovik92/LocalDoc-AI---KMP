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
import platform.Foundation.NSHomeDirectory
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

    actual fun writeAppFile(name: String, bytes: ByteArray) {
        bytes.toNSData().writeToFile("$booksDir/$name", atomically = true)
    }

    actual fun readAppFile(name: String): ByteArray? =
        NSData.dataWithContentsOfFile("$booksDir/$name")?.toByteArray()

    actual fun deleteAppFile(name: String) {
        NSFileManager.defaultManager.removeItemAtPath("$booksDir/$name", error = null)
    }
}
