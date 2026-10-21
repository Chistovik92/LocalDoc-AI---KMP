@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.localdoc.ai.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import kotlin.coroutines.coroutineContext

/**
 * iOS: песочница не даёт обойти всё устройство, поэтому сканируется папка, выбранная через «Файлы»
 * (в том числе iCloud Drive, SMB-сервер, USB-накопитель). Доступ запоминается закладкой (security-scoped bookmark).
 */
actual class DeviceScanner actual constructor() {

    private val kv = KeyValueStore()
    private var delegate: FolderDelegate? = null

    actual val supportsWholeDevice: Boolean = false

    actual fun hasAllFilesAccess(): Boolean = true

    actual fun requestAllFilesAccess() = Unit

    actual fun pickFolder(onResult: (ScanRoot?) -> Unit) {
        val root = topViewController()
        if (root == null) {
            onResult(null)
            return
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder), asCopy = false)
        val folderDelegate = FolderDelegate { url ->
            delegate = null
            if (url == null) {
                onResult(null)
            } else {
                val path = url.path.orEmpty()
                url.startAccessingSecurityScopedResource()
                val bookmark = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
                bookmark?.base64EncodedStringWithOptions(0u)?.let { kv.put(BOOKMARK_PREFIX + path, it) }
                onResult(ScanRoot(path, path.substringAfterLast('/').ifBlank { "Папка" }))
            }
        }
        delegate = folderDelegate
        picker.delegate = folderDelegate
        root.presentViewController(picker, animated = true, completion = null)
    }

    actual suspend fun scan(rootId: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int =
        withContext(Dispatchers.Default) {
            val url = resolve(rootId)
            val scoped = url.startAccessingSecurityScopedResource()
            try {
                walk(url.path.orEmpty(), extensions, onFound)
            } finally {
                if (scoped) url.stopAccessingSecurityScopedResource()
            }
        }

    private fun resolve(rootId: String): NSURL {
        val stored = kv.get(BOOKMARK_PREFIX + rootId)
        if (stored != null) {
            val data = NSData.create(base64EncodedString = stored, options = 0u)
            val resolved = data?.let { NSURL.URLByResolvingBookmarkData(it, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null) }
            if (resolved != null) return resolved
        }
        return NSURL.fileURLWithPath(rootId)
    }

    private suspend fun walk(root: String, extensions: Set<String>, onFound: (ScannedFile) -> Unit): Int {
        val fm = NSFileManager.defaultManager
        var seen = 0
        val all = fm.subpathsOfDirectoryAtPath(root, error = null).orEmpty().filterIsInstance<String>()
        for (relative in all) {
            coroutineContext.ensureActive()
            if (relative.split('/').any { it.startsWith(".") }) continue
            val name = relative.substringAfterLast('/')
            val lower = name.lowercase()
            val ext = if (lower.endsWith(".fb2.zip")) "fb2.zip" else lower.substringAfterLast('.', "")
            if (++seen > MAX_FILES) break
            if (ext !in extensions) continue
            val path = "$root/$relative"
            val attrs = fm.attributesOfItemAtPath(path, error = null) ?: continue
            if (attrs[NSFileType] == NSFileTypeDirectory) continue
            val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
            val modified = ((attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0).toLong() * 1000
            onFound(ScannedFile(path, name, size, modified, path.substringBeforeLast('/')))
        }
        return seen
    }

    private class FolderDelegate(private val onResult: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            onResult(didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onResult(null)
        }
    }

    private companion object {
        const val BOOKMARK_PREFIX = "ios_bookmark:"
        const val MAX_FILES = 200_000
    }
}
