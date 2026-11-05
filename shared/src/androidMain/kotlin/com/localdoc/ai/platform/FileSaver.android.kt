package com.localdoc.ai.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Параметры окна «Сохранить как…»: предлагаемое имя и тип файла. */
class SaveRequest(val fileName: String, val mime: String)

/** Контракт создания документа с произвольным типом (стандартный фиксирует тип при создании). */
class CreateDocumentContract : ActivityResultContract<SaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

/** Android: системное окно создания документа (SAF); запись идёт в выбранный пользователем файл. */
actual class FileSaver actual constructor() {

    actual fun save(fileName: String, mime: String, bytes: ByteArray, onResult: (SaveResult) -> Unit) {
        AndroidIntents.launchSave(SaveRequest(fileName, mime)) { uri ->
            if (uri == null) {
                onResult(SaveResult.Cancelled)
                return@launchSave
            }
            // Запись большой книги — не на главном потоке
            CoroutineScope(Dispatchers.IO).launch {
                val result = try {
                    val stream = AppContextHolder.appContext.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл для записи")
                    stream.use { it.write(bytes) }
                    SaveResult.Saved(uri.toString())
                } catch (e: Exception) {
                    SaveResult.Failed("Не удалось сохранить файл: ${e.message ?: "ошибка записи"}")
                }
                onResult(result)
            }
        }
    }
}
