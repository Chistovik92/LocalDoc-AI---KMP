package com.localdoc.ai.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localdoc.ai.model.ModelCatalog
import com.localdoc.ai.platform.AppContextHolder
import java.io.File
import java.io.FileNotFoundException

/**
 * Отдаёт GGUF-модели NeuroCode (`com.secrethero.neurocode`) без повторной загрузки из сети.
 *
 * Контракт (см. NeuroCode `docs/PLAN-0.9-shared-models.md`):
 * - `query(content://com.localdoc.ai.models)` → курсор со скачанными моделями:
 *   `_ID` (= id), `name` (из [ModelCatalog]), `size` (байт), `url`;
 * - `openFile`/`openAssetFile(content://com.localdoc.ai.models/<id>)` → байты GGUF.
 *
 * Провайдер read-only: `insert`/`update`/`delete` бросают [UnsupportedOperationException].
 * Доступ только к `.gguf` внутри каталога моделей — проверка в [ModelShare.resolve].
 */
class ModelProvider : ContentProvider() {

    private val modelsDir: File by lazy {
        val ctx = AppContextHolder.appContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(base, "models")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("_ID", "name", "size", "url"))
        for (file in ModelShare.listDownloaded(modelsDir)) {
            val id = file.name.removeSuffix(ModelShare.EXTENSION)
            val spec = ModelCatalog.byId(id)
            cursor.addRow(
                arrayOf(
                    id,
                    spec?.name ?: id,
                    file.length(),
                    spec?.url ?: ""
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireReadOnly(mode)
        return ParcelFileDescriptor.open(resolveOrThrow(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        requireReadOnly(mode)
        val file = resolveOrThrow(uri)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    private fun resolveOrThrow(uri: Uri): File {
        val id = uri.lastPathSegment ?: throw FileNotFoundException("пустой id модели")
        return ModelShare.resolve(modelsDir, id)
            ?: throw FileNotFoundException("модель не найдена или вне каталога: $id")
    }

    private fun requireReadOnly(mode: String) {
        if ('w' in mode) {
            throw SecurityException("провайдер моделей доступен только на чтение")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("провайдер моделей только на чтение")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = throw UnsupportedOperationException("провайдер моделей только на чтение")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("провайдер моделей только на чтение")
}
