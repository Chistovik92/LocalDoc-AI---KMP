@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.download

import com.localdoc.ai.model.ModelSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

/** iOS-загрузчик GGUF: папка Documents/models, докачка через HTTP Range, NSURLSession. */
actual class ModelDownloader actual constructor() {

    private val modelsDir: String by lazy {
        // Писать можно только внутри Documents/Library/tmp песочницы, а не в её корень
        val dir = "${NSHomeDirectory()}/Documents/models"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        dir
    }

    private fun pathFor(spec: ModelSpec, suffix: String = "") = "$modelsDir/${spec.id}$suffix"

    actual fun modelsDir(): String = modelsDir

    actual fun localPathFor(spec: ModelSpec): String = pathFor(spec, ".gguf")

    actual fun isDownloaded(spec: ModelSpec): Boolean = fileSize(localPathFor(spec)) > 0

    actual fun downloadedBytes(spec: ModelSpec): Long = fileSize(pathFor(spec, ".gguf.part"))

    actual fun download(spec: ModelSpec): Flow<DownloadState> = callbackFlow {
        val targetPath = localPathFor(spec)
        val tempPath = pathFor(spec, ".gguf.part")
        var completed = false

        val nsUrl = NSURL(string = spec.url) ?: run {
            trySend(DownloadState.Failed("Некорректный URL: ${spec.url}"))
            close()
            return@callbackFlow
        }

        val delegate = DownloadDelegate(
            startOffset = fileSize(tempPath),
            minCompleteBytes = spec.sizeMb * 1024L * 1024L * 9 / 10,
            onRestart = { truncateFile(tempPath) },
            onChunk = { data -> appendToFile(tempPath, data) },
            onProgress = { written, total -> trySend(DownloadState.Progress(written, total)) },
            onFinished = { error ->
                if (!completed) {
                    completed = true
                    trySend(if (error != null) DownloadState.Failed(error) else finalize(tempPath, targetPath))
                    close()
                }
            }
        )

        val config = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
            timeoutIntervalForRequest = 60.0
            timeoutIntervalForResource = 3600.0
        }
        val session = NSURLSession.sessionWithConfiguration(config, delegate = delegate, delegateQueue = null)

        val request = NSMutableURLRequest(uRL = nsUrl)
        if (delegate.startOffset > 0) {
            request.setValue("bytes=${delegate.startOffset}-", forHTTPHeaderField = "Range")
        }

        val task = session.dataTaskWithRequest(request)
        delegate.task = task
        task.resume()

        awaitClose {
            if (!completed) delegate.cancelTask()
            session.invalidateAndCancel()
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun finalize(tempPath: String, targetPath: String): DownloadState {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(targetPath, error = null)
        return if (fm.moveItemAtPath(tempPath, toPath = targetPath, error = null)) {
            DownloadState.Done(targetPath)
        } else {
            DownloadState.Failed("Не удалось сохранить файл")
        }
    }

    actual fun cancel(spec: ModelSpec) {
        // Прерывание выполняется отменой собирающей корутины (см. awaitClose)
    }

    actual suspend fun delete(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(localPathFor(spec), error = null)
        fm.removeItemAtPath(pathFor(spec, ".gguf.part"), error = null)
        Unit
    }

    private fun fileSize(path: String): Long {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return 0
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
        return (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0
    }

    private fun truncateFile(path: String) {
        fopen(path, "wb")?.let { fclose(it) }
    }

    private fun appendToFile(path: String, data: NSData) {
        val file = fopen(path, "ab") ?: return
        fwrite(data.bytes, 1uL, data.length, file)
        fflush(file)
        fclose(file)
    }
}

private const val HTTP_OK = 200
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
// Шаг обновления прогресса: не чаще одного события на мегабайт
private const val PROGRESS_STEP_BYTES = 1024L * 1024L

private class DownloadDelegate(
    val startOffset: Long,
    private val minCompleteBytes: Long,
    private val onRestart: () -> Unit,
    private val onChunk: (NSData) -> Unit,
    private val onProgress: (Long, Long) -> Unit,
    private val onFinished: (String?) -> Unit
) : NSObject(), NSURLSessionDataDelegateProtocol {

    var task: NSURLSessionDataTask? = null
    private var written: Long = startOffset
    private var expectedTotal: Long = -1
    private var cancelled = false
    private var alreadyComplete = false
    private var httpError: String? = null
    private var lastReported: Long = 0

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit
    ) {
        val code = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: HTTP_OK
        when {
            // Частичный файл уже содержит весь объём — докачивать нечего
            code == HTTP_RANGE_NOT_SATISFIABLE && startOffset > 0 -> {
                // Если частичный файл заметно меньше ожидаемого — он устарел/повреждён
                if (startOffset >= minCompleteBytes) {
                    alreadyComplete = true
                } else {
                    onRestart()
                    httpError = "Частичный файл повреждён и удалён — нажмите «Скачать» ещё раз"
                }
                completionHandler(NSURLSessionResponseCancel)
            }
            code !in 200..299 -> {
                httpError = "HTTP $code"
                completionHandler(NSURLSessionResponseCancel)
            }
            else -> {
                if (code == HTTP_OK && written > 0) {
                    // Сервер проигнорировал Range и отдаёт файл целиком — начинаем заново
                    onRestart()
                    written = 0
                }
                val length = didReceiveResponse.expectedContentLength
                expectedTotal = if (length > 0) written + length else -1
                completionHandler(NSURLSessionResponseAllow)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData
    ) {
        onChunk(didReceiveData)
        written += didReceiveData.length.toLong()
        if (written - lastReported >= PROGRESS_STEP_BYTES) {
            lastReported = written
            onProgress(written, expectedTotal)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        when {
            alreadyComplete -> onFinished(null)
            httpError != null -> onFinished(httpError)
            cancelled -> onFinished("Загрузка отменена")
            else -> onFinished(didCompleteWithError?.localizedDescription)
        }
    }

    fun cancelTask() {
        cancelled = true
        task?.cancel()
    }
}
