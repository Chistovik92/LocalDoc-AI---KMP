package com.localdoc.ai.download

import kotlinx.coroutines.flow.Flow

/** Состояние загрузки файла (GGUF-модели или архива голоса). */
sealed interface DownloadState {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Done(val path: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** То, что можно скачать: id даёт имя файла, размер — запасной итог для прогресса. */
interface Downloadable {
    val id: String
    val url: String
    val sizeBytes: Long
}

/**
 * Кроссплатформенный загрузчик с докачкой при обрыве (HTTP Range).
 * Файлы лежат в [dirName] (Android — внутреннее хранилище приложения, iOS — Documents)
 * под именем «id + [extension]».
 */
expect class ModelDownloader(dirName: String = "models", extension: String = ".gguf") {
    fun modelsDir(): String
    fun localPathFor(spec: Downloadable): String
    fun isDownloaded(spec: Downloadable): Boolean
    fun downloadedBytes(spec: Downloadable): Long
    fun download(spec: Downloadable): Flow<DownloadState>
    fun cancel(spec: Downloadable)
    suspend fun delete(spec: Downloadable)
}
