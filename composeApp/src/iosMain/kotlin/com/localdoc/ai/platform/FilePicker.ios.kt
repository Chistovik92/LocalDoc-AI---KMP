package com.localdoc.ai.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypePDF

@OptIn(ExperimentalForeignApi::class)
/** iOS: системный UIDocumentPickerViewController. */
actual class FilePicker actual constructor() {

    actual fun pick(mime: String, onResult: (String?) -> Unit) {
        val rootViewController = findRootViewController() ?: run {
            onResult(null)
            return
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypePDF),
            asCopy = true
        )
        picker.allowsMultipleSelection = false
        picker.delegate = DocumentPickerDelegate(onResult)
        rootViewController.presentViewController(picker, animated = true, completion = null)
    }

    private fun findRootViewController() = runCatching {
        val windowScene = UIApplication.sharedApplication.connectedScenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
        windowScene?.windows?.firstOrNull { it.isKeyWindow }?.rootViewController
            ?: windowScene?.windows?.firstOrNull()?.rootViewController
    }.getOrNull()

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
