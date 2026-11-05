package com.localdoc.ai.platform

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android: SAF/content:// для внешних файлов, filesDir/books для кэша книг. */
actual class FileAccess actual constructor() {

    private val context get() = AppContextHolder.appContext

    private val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }

    actual suspend fun readBytes(pathOrUri: String): ByteArray = withContext(Dispatchers.IO) {
        if (pathOrUri.startsWith("/")) return@withContext File(pathOrUri).readBytes()
        val stream = context.contentResolver.openInputStream(Uri.parse(pathOrUri))
            ?: throw IllegalStateException("Файл недоступен: $pathOrUri")
        stream.use { it.readBytes() }
    }

    actual suspend fun sizeOf(pathOrUri: String): Long? = withContext(Dispatchers.IO) {
        if (pathOrUri.startsWith("/")) return@withContext File(pathOrUri).length()
        runCatching {
            context.contentResolver.query(Uri.parse(pathOrUri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    actual fun displayName(pathOrUri: String): String? {
        if (pathOrUri.startsWith("/")) return pathOrUri.substringAfterLast('/')
        val uri = Uri.parse(pathOrUri)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Имя внутри хранилища: без «..» и абсолютных путей, чтобы файл не вышел за пределы папки приложения. */
    private fun resolve(name: String): File {
        require(name.isNotBlank() && !name.startsWith("/") && ".." !in name.split('/')) { "Недопустимое имя файла: $name" }
        return File(booksDir, name)
    }

    actual fun writeAppFile(name: String, bytes: ByteArray) {
        val target = resolve(name)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    actual fun readAppFile(name: String): ByteArray? =
        resolve(name).takeIf { it.isFile }?.readBytes()

    actual fun deleteAppFile(name: String) {
        resolve(name).delete()
    }

    actual fun listAppFiles(prefix: String): List<String> {
        val root = booksDir
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { it.startsWith(prefix) }.sorted().toList()
    }

    actual fun deleteAppFiles(prefix: String) {
        val root = booksDir
        // Сначала файлы, затем опустевшие папки по префиксу
        root.walkBottomUp().filter { it != root && it.relativeTo(root).path.replace(File.separatorChar, '/').startsWith(prefix) }
            .forEach { if (it.isFile || it.list().isNullOrEmpty()) it.delete() }
    }

    actual fun appFileSize(name: String): Long = resolve(name).takeIf { it.isFile }?.length() ?: 0L

    actual suspend fun copyToAppFile(pathOrUri: String, name: String): Long = withContext(Dispatchers.IO) {
        val target = resolve(name)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        val input = if (pathOrUri.startsWith("/")) File(pathOrUri).inputStream() else context.contentResolver.openInputStream(Uri.parse(pathOrUri))
        val copied = (input ?: throw IllegalStateException("Файл недоступен: $pathOrUri")).use { source ->
            temp.outputStream().use { source.copyTo(it) }
        }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        copied
    }

    actual fun appFilePath(name: String): String = resolve(name).absolutePath
}
