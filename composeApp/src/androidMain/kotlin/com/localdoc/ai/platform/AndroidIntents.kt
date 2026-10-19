package com.localdoc.ai.platform

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.domain.IncomingDoc

/**
 * Мост для системных интеграций Android:
 * - выбор файла (SAF) через ActivityResultLauncher, зарегистрированный в MainActivity;
 * - приём Share Intent из браузеров и проводника.
 */
object AndroidIntents {

    private var pdfPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pickCallback: ((String?) -> Unit)? = null

    fun registerPdfPicker(launcher: ActivityResultLauncher<Array<String>>) {
        pdfPickerLauncher = launcher
    }

    fun launchPdfPicker(onResult: (String?) -> Unit) {
        pickCallback = onResult
        pdfPickerLauncher?.launch(arrayOf("application/pdf"))
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
                if (intent.type == "application/pdf") {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
                    if (uri != null) {
                        AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.PDF, uri.toString()))
                    }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        val type = if (text.startsWith("http")) {
                            IncomingDoc.IncomingType.URL
                        } else {
                            IncomingDoc.IncomingType.TEXT
                        }
                        AppGraph.acceptIncoming(IncomingDoc(type, text))
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                when (intent.type ?: data.scheme) {
                    "application/pdf", "content", "file" ->
                        AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.PDF, data.toString()))
                    "http", "https" ->
                        AppGraph.acceptIncoming(IncomingDoc(IncomingDoc.IncomingType.URL, data.toString()))
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
