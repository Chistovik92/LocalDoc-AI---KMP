package com.localdoc.ai.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Android: «всё устройство» — обход файловой системы при разрешении «Доступ ко всем файлам»;
 * папка или подключённый ресурс (SD-карта, USB, облачный провайдер) — обход дерева SAF с постоянным
 * разрешением на чтение. Системные и скрытые папки пропускаются.
 */
actual class DeviceScanner actual constructor() {

    private val context get() = AppContextHolder.appContext

    actual val supportsWholeDevice: Boolean = true

    actual fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    actual fun requestAllFilesAccess() {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(specific)
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    actual fun pickFolder(onResult: (ScanRoot?) -> Unit) {
        AndroidIntents.launchFolderPicker { uri ->
            if (uri == null) {
                onResult(null)
                return@launchFolderPicker
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val title = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').ifBlank { "Папка" }
            onResult(ScanRoot(uri.toString(), title))
        }
    }

    actual suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int =
        withContext(Dispatchers.IO) {
            if (rootId == DEVICE) scanFileSystem(extensions, onFound) else scanTree(Uri.parse(rootId), extensions, onFound)
        }

    private suspend fun scanFileSystem(extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int {
        var seen = 0
        val stack = ArrayDeque<File>(storageRoots())
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (!isSystemFolder(child)) stack.addLast(child)
                } else {
                    if (++seen > MAX_FILES) return seen
                    if (extensionOf(child.name) in extensions) {
                        onFound(ScannedFile(child.absolutePath, child.name, child.length(), child.lastModified(), child.parent.orEmpty()))
                    }
                }
            }
        }
        return seen
    }

    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots.add(Environment.getExternalStorageDirectory())
        // SD-карты и USB: путь каталога приложения на внешнем томе начинается с корня тома (/storage/XXXX-XXXX)
        context.getExternalFilesDirs(null).orEmpty().filterNotNull().forEach { dir ->
            val volume = dir.absolutePath.substringBefore("/Android/", "")
            if (volume.isNotEmpty()) roots.add(File(volume))
        }
        return roots.filter { it.isDirectory }
    }

    private fun isSystemFolder(dir: File): Boolean {
        val path = dir.absolutePath
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb") || path.endsWith("/Android/media") || dir.name == "node_modules"
    }

    private suspend fun scanTree(tree: Uri, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int {
        var seen = 0
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // Стек: идентификатор документа-папки и её путь для показа
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to Uri.decode(rootId).substringAfter(':').ifBlank { "/" })
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dirId, dirPath) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val cursor = try {
                resolver.query(children, columns, null, null, null)
            } catch (e: Exception) {
                null
            } ?: continue
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1).orEmpty()
                    if (name.startsWith(".")) continue
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.addLast(id to "$dirPath/$name")
                    } else {
                        if (++seen > MAX_FILES) return seen
                        if (extensionOf(name) in extensions) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            onFound(ScannedFile(uri.toString(), name, it.getLong(3), it.getLong(4), dirPath))
                        }
                    }
                }
            }
        }
        return seen
    }

    private fun extensionOf(name: String): String {
        val lower = name.lowercase()
        return if (lower.endsWith(".fb2.zip")) "fb2.zip" else lower.substringAfterLast('.', "")
    }

    private companion object {
        const val DEVICE = "device"
        const val MAX_FILES = 400_000
    }
}
