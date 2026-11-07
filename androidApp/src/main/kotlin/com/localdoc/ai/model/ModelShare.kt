package com.localdoc.ai.model

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Запасной обмен одной GGUF через ACTION_SEND с временным разрешением на чтение. */
object ModelShare {
    fun intent(context: Context, model: ModelSpec): Intent? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val file = ModelExport(
            File(base, "models"),
            listOf(ExportedModel(model.id, model.name, model.url, File(File(base, "models"), "${model.id}.gguf")))
        ).resolve("${model.id}.gguf")?.file ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.models.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = ModelProvider.GGUF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
