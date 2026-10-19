@file:OptIn(ExperimentalForeignApi::class)

package com.localdoc.ai.download

import com.localdoc.ai.model.ModelSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

/** iOS-загрузчик GGUF: папка Documents, докачка через HTTP Range, NSURLSession. */
actual class ModelDownloader actual constructor() {

    private val modelsDir: String by lazy {
        val docs = NSHomeDirectory()
        val dir = "$docs/models"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        dir
    }

    private fun pathFor(spec: ModelSpec, suffix: String = "") = "$modelsDir/${spec.id}$suffix"

    actual fun modelsDir(): String = modelsDir

    actual fun localPathFor(spec: ModelSpec): String = pathFor(spec, ".gguf")

    actual fun isDownloaded(spec: ModelSpec): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(localPathFor(spec))

    actual fun downloadedBytes(spec: ModelSpec): Long = fileSize(pathFor(spec, ".gguf.part"))

    actual fun download(spec: ModelSpec): Flow<DownloadState> = callbackFlow {
        val targetPath = localPathFor(spec)
        val tempPath = pathFor(spec, ".gguf.part")
        var offset = fileSize(tempPath)
        var completed = false

        val delegate = DownloadDelegate(
            startOffset = offset,
            onProgress = { written, total -> trySend(DownloadState.Progress(written, total)) },
            onChunk = { data ->
                appendToFile(tempPath, data)
                offset += data.length.toLong()
            },
            onFinished = { error ->
                if (!completed) {
                    completed = true
                    if (error != null) {
                        trySend(DownloadState.Failed(error))
                    } else {
                        val fm = NSFileManager.defaultManager
                        fm.removeItemAtPath(targetPath, error = null)
                        if (fm.moveItemAtPath(tempPath, toPath = targetPath, error = null)) {
                            trySend(DownloadState.Done(targetPath))
                        } else {
                            trySend(DownloadState.Failed("Не удалось сохранить файл"))
                        }
                    }
                    close()
                }
            }
        )

        val nsUrl = NSURL(string = spec.url) ?: run {
            trySend(DownloadState.Failed("Некорректный URL: ${spec.url}"))
            close()
            return@callbackFlow
        }

        val config = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
            timeoutIntervalForRequest = 60.0
            timeoutIntervalForResource = 3600.0
        }

        val session = NSURLSession.sessionWithConfiguration(
            config,
            delegate = delegate,
            delegateQueue = null
        )

        val request = NSMutableURLRequest(uRL = nsUrl)
        if (offset > 0) {
            request.setValue("bytes=$offset-", forHTTPHeaderField = "Range")
        }

        val task = session.dataTaskWithRequest(request)
        delegate.task = task
        task.resume()

        awaitClose {
            if (!completed) delegate.cancelTask()
            session.invalidateAndCancel()
        }
    }.flowOn(Dispatchers.IO)

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

    private fun appendToFile(path: String, data: NSData) {
        val file = fopen(path, "ab") ?: return
        fwrite(data.bytes, 1L, data.length.toLong(), file)
        fflush(file)
        fclose(file)
    }
}

private class DownloadDelegate(
    private val startOffset: Long,
    private val onProgress: (Long, Long) -> Unit,
    private val onChunk: (NSData) -> Unit,
    private val onFinished: (String?) -> Unit
) : NSObject(), NSURLSessionDataDelegateProtocol {

    var completionHandler: ((String?) -> Unit)? = null
    var task: NSURLSessionDataTask? = null
    private var expectedTotal: Long = -1
    private var cancelled = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit
    ) {
        expectedTotal = ((didReceiveResponse as? NSHTTPURLResponse)?.expectedContentLength ?: -1).toLong()
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData
    ) {
        onChunk(didReceiveData)
        val total = if (expectedTotal > 0) startOffset + expectedTotal else -1
        onProgress(startOffset + didReceiveData.length.toLong(), total)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        if (cancelled) {
            onFinished("Загрузка отменена")
        } else {
            onFinished(didCompleteWithError?.localizedDescription)
        }
    }

    fun cancelTask() {
        cancelled = true
        task?.cancel()
    }
}
