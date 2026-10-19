package com.localdoc.ai.download

import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.platform.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Android-загрузчик GGUF: внутреннее хранилище приложения + докачка по HTTP Range. */
actual class ModelDownloader actual constructor() {

    private val dir: File by lazy {
        File(AppContextHolder.appContext.filesDir, "models").apply { mkdirs() }
    }

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private fun fileFor(spec: ModelSpec) = File(dir, "${spec.id}.gguf")
    private fun tempFor(spec: ModelSpec) = File(dir, "${spec.id}.gguf.part")

    actual fun modelsDir(): String = dir.absolutePath

    actual fun localPathFor(spec: ModelSpec): String = fileFor(spec).absolutePath

    actual fun isDownloaded(spec: ModelSpec): Boolean = fileFor(spec).exists() && fileFor(spec).length() > 0

    actual fun downloadedBytes(spec: ModelSpec): Long = tempFor(spec).takeIf { it.exists() }?.length() ?: 0L

    actual fun download(spec: ModelSpec): Flow<DownloadState> = flow {
        val target = fileFor(spec)
        val temp = tempFor(spec)
        var connection: HttpURLConnection? = null
        var offset = if (temp.exists()) temp.length() else 0L

        try {
            val url = URL(spec.url)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            activeConnection = connection

            val code = connection.responseCode
            if (code !in 200..299 && code != 206) {
                emit(DownloadState.Failed("HTTP $code"))
                return@flow
            }

            val total = if (code == 206) {
                offset + (connection.contentLengthLong.takeIf { it > 0 } ?: 0L)
            } else {
                connection.contentLengthLong.takeIf { it > 0 } ?: spec.sizeMb * 1024L * 1024L
            }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(temp, offset > 0).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        offset += read
                        if (offset - lastEmit > 1024L * 1024L) {
                            lastEmit = offset
                            emit(DownloadState.Progress(offset, total))
                        }
                    }
                    emit(DownloadState.Progress(offset, total))
                }
            }

            if (temp.renameTo(target)) {
                emit(DownloadState.Done(target.absolutePath))
            } else {
                emit(DownloadState.Failed("Не удалось сохранить файл"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Отмена соединения после cancel() приходит как IOException — трактуем как отмену
            if (offset > 0 && temp.exists()) {
                // Частичный файл сохранён для докачки — выходим без ошибки
            } else {
                emit(DownloadState.Failed(e.message ?: "Ошибка сети"))
            }
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    actual fun cancel(spec: ModelSpec) {
        activeConnection?.disconnect()
        activeConnection = null
    }

    actual suspend fun delete(spec: ModelSpec) = withContext(Dispatchers.IO) {
        fileFor(spec).delete()
        tempFor(spec).delete()
        Unit
    }
}
