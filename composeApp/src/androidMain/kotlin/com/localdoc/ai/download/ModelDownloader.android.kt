package com.localdoc.ai.download

import com.localdoc.ai.platform.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Android-загрузчик: внутреннее хранилище приложения + докачка по HTTP Range. */
actual class ModelDownloader actual constructor(
    private val dirName: String,
    private val extension: String
) {

    private val dir: File by lazy {
        val ctx = AppContextHolder.appContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(base, dirName).apply { mkdirs() }
    }

    // Соединения и флаги отмены — по id: параллельные загрузки (несколько голосов)
    // не должны обрывать друг друга
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelledIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun fileFor(spec: Downloadable) = File(dir, "${spec.id}$extension")
    private fun tempFor(spec: Downloadable) = File(dir, "${spec.id}$extension.part")

    actual fun modelsDir(): String = dir.absolutePath

    actual fun localPathFor(spec: Downloadable): String = fileFor(spec).absolutePath

    actual fun isDownloaded(spec: Downloadable): Boolean = fileFor(spec).exists() && fileFor(spec).length() > 0

    actual fun downloadedBytes(spec: Downloadable): Long = tempFor(spec).takeIf { it.exists() }?.length() ?: 0L

    actual fun download(spec: Downloadable): Flow<DownloadState> = flow {
        val target = fileFor(spec)
        val temp = tempFor(spec)
        var connection: HttpURLConnection? = null
        var offset = if (temp.exists()) temp.length() else 0L
        cancelledIds.remove(spec.id)

        try {
            val url = URL(spec.url)
            connection = (url.openConnection() as? HttpURLConnection)?.apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            } ?: run {
                emit(DownloadState.Failed("Неподдерживаемый протокол: ${url.protocol}"))
                return@flow
            }
            activeConnections[spec.id] = connection

            val code = connection.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE && offset > 0) {
                // Сервер сообщает полный размер в «Content-Range: bytes */N». Файл готов,
                // только если частичный файл ровно такого размера; иначе (в т.ч. раздутый
                // старой версией с ошибкой дозаписи) — он повреждён: удаляем.
                val remoteSize = parseTotalFromContentRange(connection.getHeaderField("Content-Range"))
                if (remoteSize != null && offset == remoteSize) {
                    emit(finalize(temp, target))
                } else {
                    temp.delete()
                    emit(DownloadState.Failed("Частичный файл повреждён и удалён — нажмите «Скачать» ещё раз"))
                }
                return@flow
            }
            if (code !in 200..299) {
                emit(DownloadState.Failed("HTTP $code"))
                return@flow
            }
            if (code == HttpURLConnection.HTTP_OK && offset > 0) {
                // Сервер проигнорировал Range и отдаёт файл целиком — начинаем заново,
                // иначе полный файл допишется в конец частичного и модель будет испорчена
                offset = 0L
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0 }
            val total = when {
                code == HttpURLConnection.HTTP_PARTIAL -> contentLength?.let { offset + it } ?: -1L
                else -> contentLength ?: spec.sizeBytes
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
                        if (offset - lastEmit > PROGRESS_STEP_BYTES) {
                            lastEmit = offset
                            emit(DownloadState.Progress(offset, total))
                        }
                    }
                    emit(DownloadState.Progress(offset, total))
                }
            }

            emit(finalize(temp, target))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // cancel() закрывает соединение — это приходит как IOException.
            // Частичный файл сохраняется в обоих случаях и позволит докачать модель позже.
            val message = if (spec.id in cancelledIds) "Загрузка отменена" else "Загрузка прервана: ${e.message ?: "ошибка сети"}"
            emit(DownloadState.Failed(message))
        } finally {
            connection?.let { activeConnections.remove(spec.id, it) }
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun finalize(temp: File, target: File): DownloadState {
        if (target.exists()) target.delete()
        return if (temp.renameTo(target)) {
            DownloadState.Done(target.absolutePath)
        } else {
            DownloadState.Failed("Не удалось сохранить файл")
        }
    }

    actual fun cancel(spec: Downloadable) {
        cancelledIds.add(spec.id)
        activeConnections.remove(spec.id)?.disconnect()
    }

    actual suspend fun delete(spec: Downloadable) = withContext(Dispatchers.IO) {
        fileFor(spec).delete()
        tempFor(spec).delete()
        Unit
    }
}

private const val HTTP_RANGE_NOT_SATISFIABLE = 416

// Полный размер из заголовка Content-Range («bytes */N» или «bytes a-b/N»); null, если неизвестен
internal fun parseTotalFromContentRange(header: String?): Long? =
    header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
// Шаг обновления прогресса: не чаще одного события на мегабайт
private const val PROGRESS_STEP_BYTES = 1024L * 1024L
