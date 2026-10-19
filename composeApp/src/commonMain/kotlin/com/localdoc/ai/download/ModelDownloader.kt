package com.localdoc.ai.download

import com.localdoc.ai.model.ModelSpec
import kotlinx.coroutines.flow.Flow

/** Состояние загрузки GGUF-модели. */
sealed interface DownloadState {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Done(val path: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Кроссплатформенный загрузчик моделей с докачкой при обрыве (HTTP Range).
 * Android: внутреннее хранилище приложения; iOS: папка Documents.
 */
expect class ModelDownloader() {
    fun modelsDir(): String
    fun localPathFor(spec: ModelSpec): String
    fun isDownloaded(spec: ModelSpec): Boolean
    fun downloadedBytes(spec: ModelSpec): Long
    fun download(spec: ModelSpec): Flow<DownloadState>
    fun cancel(spec: ModelSpec)
    suspend fun delete(spec: ModelSpec)
}
