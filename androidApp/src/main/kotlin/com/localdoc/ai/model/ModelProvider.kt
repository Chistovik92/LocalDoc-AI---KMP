package com.localdoc.ai.model

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import java.io.File
import java.io.FileNotFoundException

/** Публикует скачанные GGUF-модели для NeuroCode; запись и произвольные пути запрещены. */
class ModelProvider : ContentProvider() {
    private lateinit var export: ModelExport

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        export = ModelExport(
            File(base, "models"),
            ModelCatalog.models.map { ExportedModel(it.id, it.name, it.url, File(File(base, "models"), "${it.id}.gguf")) }
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val requested = projection ?: COLUMNS
        return MatrixCursor(requested).apply {
            export.downloaded().forEach { model ->
                newRow().apply {
                    requested.forEach { column ->
                        when (column) {
                            BaseColumns._ID -> add(model.id)
                            COLUMN_NAME -> add(model.name)
                            COLUMN_SIZE -> add(model.file.length())
                            COLUMN_URL -> add(model.url)
                            else -> add(null)
                        }
                    }
                }
            }
        }
    }

    override fun getType(uri: Uri): String = GGUF_MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Модели доступны только для чтения")
        val file = export.resolve(uri.lastPathSegment.orEmpty())?.file
            ?: throw FileNotFoundException("Неизвестная или не скачанная модель")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, mode), 0L, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val COLUMN_NAME = "name"
        const val COLUMN_SIZE = "size"
        const val COLUMN_URL = "url"
        const val GGUF_MIME_TYPE = "application/octet-stream"
        private val COLUMNS = arrayOf(BaseColumns._ID, COLUMN_NAME, COLUMN_SIZE, COLUMN_URL)
    }
}
