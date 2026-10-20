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

    actual fun displayName(pathOrUri: String): String? {
        if (pathOrUri.startsWith("/")) return pathOrUri.substringAfterLast('/')
        val uri = Uri.parse(pathOrUri)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    actual fun writeAppFile(name: String, bytes: ByteArray) {
        val target = File(booksDir, name)
        val temp = File(booksDir, "$name.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    actual fun readAppFile(name: String): ByteArray? =
        File(booksDir, name).takeIf { it.exists() }?.readBytes()

    actual fun deleteAppFile(name: String) {
        File(booksDir, name).delete()
    }
}
