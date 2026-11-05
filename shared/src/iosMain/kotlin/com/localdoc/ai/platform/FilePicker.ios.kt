package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypePDF

@OptIn(ExperimentalForeignApi::class)
/** iOS: системный UIDocumentPickerViewController. */
actual class FilePicker actual constructor() {

    private var activeDelegate: DocumentPickerDelegate? = null

    actual fun pick(mime: String, onResult: (String?) -> Unit) {
        val rootViewController = findRootViewController() ?: run {
            onResult(null)
            return
        }

        val picker = UIDocumentPickerViewController(
            // Любые файлы с данными: формат книги (EPUB, FB2, MD, TXT, HTML, PDF) определяет импортёр
            forOpeningContentTypes = listOf(UTTypeData, UTTypePDF),
            asCopy = true
        )
        picker.allowsMultipleSelection = false
        // UIDocumentPickerViewController.delegate — weak-ссылка: держим делегат сами,
        // иначе он будет освобождён до выбора файла и колбэк не придёт
        val delegate = DocumentPickerDelegate { path ->
            activeDelegate = null
            onResult(path)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        rootViewController.presentViewController(picker, animated = true, completion = null)
    }

    private fun findRootViewController(): UIViewController? {
        // connectedScenes — NSSet, windows — NSArray: в Kotlin это нетипизированные коллекции
        val windowScene = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull()
        val windows = windowScene?.windows?.filterIsInstance<UIWindow>().orEmpty()
        val window = windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
        var controller = window?.rootViewController
        // Если поверх уже показан модальный экран — презентуем пикер от него
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }

    private class DocumentPickerDelegate(
        private val onResult: (String?) -> Unit
    ) : NSObject(), UIDocumentPickerDelegateProtocol {

        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>
        ) {
            val url = didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull()
            onResult(url?.path)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onResult(null)
        }
    }
}
