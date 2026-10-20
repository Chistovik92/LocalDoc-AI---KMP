package com.localdoc.ai.platform

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.IncomingDoc

/**
 * Мост для системных интеграций Android:
 * - выбор файла (SAF) через ActivityResultLauncher, зарегистрированный в MainActivity;
 * - приём Share Intent из браузеров и проводника.
 */
object AndroidIntents {

    private val URL_REGEX = Regex("""https?://\S+""")
    private const val MAX_SHARED_LINK_TEXT = 500

    private var pdfPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pickCallback: ((String?) -> Unit)? = null

    fun registerPdfPicker(launcher: ActivityResultLauncher<Array<String>>) {
        pdfPickerLauncher = launcher
    }

    /** Все форматы читалки: часть провайдеров не знает MIME для fb2/md — поэтому и octet-stream. */
    private val BOOK_MIME_TYPES = arrayOf(
        "application/pdf", "application/epub+zip", "application/x-fictionbook+xml", "application/x-fictionbook",
        "application/zip", "text/plain", "text/markdown", "text/x-markdown", "text/html", "application/xhtml+xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
        "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf", "application/xml", "text/xml",
        "application/octet-stream", "*/*"
    )

    private var saveLauncher: ActivityResultLauncher<SaveRequest>? = null
    private var saveCallback: ((Uri?) -> Unit)? = null
    private var folderLauncher: ActivityResultLauncher<Uri?>? = null
    private var folderCallback: ((Uri?) -> Unit)? = null

    fun registerSaver(launcher: ActivityResultLauncher<SaveRequest>) { saveLauncher = launcher }
    fun registerFolderPicker(launcher: ActivityResultLauncher<Uri?>) { folderLauncher = launcher }

    fun launchSave(request: SaveRequest, onResult: (Uri?) -> Unit) {
        saveCallback = onResult
        saveLauncher?.launch(request) ?: onResult(null)
    }

    fun onSaved(uri: Uri?) {
        saveCallback?.invoke(uri)
        saveCallback = null
    }

    fun launchFolderPicker(onResult: (Uri?) -> Unit) {
        folderCallback = onResult
        folderLauncher?.launch(null) ?: onResult(null)
    }

    fun onFolderPicked(uri: Uri?) {
        folderCallback?.invoke(uri)
        folderCallback = null
    }

    fun launchPdfPicker(onResult: (String?) -> Unit) {
        pickCallback = onResult
        pdfPickerLauncher?.launch(BOOK_MIME_TYPES)
    }

    fun onPdfPicked(uriString: String?) {
        pickCallback?.invoke(uriString)
        pickCallback = null
    }

    /** Разбор Share Intent: ссылки из браузера, текст или PDF. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
                if (stream != null) {
                    AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.FILE, stream.toString()))
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        // Многие приложения делятся «Заголовок + ссылка» — достаём ссылку из короткого текста
                        val url = URL_REGEX.find(text)?.value?.takeIf { text.length <= MAX_SHARED_LINK_TEXT }
                        val item = if (url != null) {
                            IncomingDoc(IncomingDoc.IncomingType.URL, url)
                        } else {
                            IncomingDoc(IncomingDoc.IncomingType.TEXT, text)
                        }
                        AppGraph.acceptIncoming(item)
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                when (data.scheme) {
                    "localdoc" -> AppGraph.handleDeepLink(data.toString())
                    "http", "https" -> AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.URL, data.toString()))
                    else -> AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.FILE, data.toString()))
                }
            }
        }
    }
}

/** Android-реализация выбора файла. */
actual class FilePicker actual constructor() {
    actual fun pick(mime: String, onResult: (String?) -> Unit) {
        AndroidIntents.launchPdfPicker(onResult)
    }
}
