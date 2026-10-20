@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

/** iOS: файл пишется во временную папку и отдаётся в системное окно экспорта «Файлы» (копия в выбранное место). */
actual class FileSaver actual constructor() {

    // Делегат у UIDocumentPickerViewController слабый — держим сами, пока окно открыто
    private var delegate: ExportDelegate? = null

    actual fun save(fileName: String, mime: String, bytes: ByteArray, onResult: (SaveResult) -> Unit) {
        val root = topViewController()
        if (root == null) {
            onResult(SaveResult.Failed("Нет экрана для окна сохранения"))
            return
        }
        val path = NSTemporaryDirectory() + fileName.replace('/', '_')
        if (!bytes.toNSData().writeToFile(path, atomically = true)) {
            onResult(SaveResult.Failed("Не удалось подготовить файл к сохранению"))
            return
        }
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true)
        val exportDelegate = ExportDelegate { result ->
            delegate = null
            onResult(result)
        }
        delegate = exportDelegate
        picker.delegate = exportDelegate
        root.presentViewController(picker, animated = true, completion = null)
    }

    private class ExportDelegate(private val onResult: (SaveResult) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            onResult(SaveResult.Saved(didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull()?.path))
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onResult(SaveResult.Cancelled)
        }
    }
}
