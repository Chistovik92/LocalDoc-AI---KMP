package com.localdoc.ai.rag

import com.localdoc.ai.download.DownloadState
import com.localdoc.ai.download.Downloadable
import com.localdoc.ai.download.ModelDownloader
import kotlinx.coroutines.flow.Flow

/** Файл модели эмбеддингов: размер и SHA-256 известны заранее и проверяются после скачивания. */
data class SemanticArtifact(
    override val id: String,
    override val url: String,
    override val sizeBytes: Long,
    val sha256: String
) : Downloadable

/**
 * multilingual-e5-small (MIT), ONNX-версия Xenova с int8-квантованием: ≈118 МБ.
 * Ссылки привязаны к ревизии репозитория, чтобы файл не менялся под ногами.
 */
object SemanticModelCatalog {
    private const val BASE = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78"

    val model = SemanticArtifact(
        id = "e5-small-model",
        url = "$BASE/onnx/model_quantized.onnx",
        sizeBytes = 118_308_185L,
        sha256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
    )
    val tokenizer = SemanticArtifact(
        id = "e5-small-tokenizer",
        url = "$BASE/tokenizer.json",
        sizeBytes = 17_082_730L,
        sha256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    )
    val all = listOf(model, tokenizer)
    val totalBytes: Long = all.sumOf { it.sizeBytes }
}

/** Файлы модели на диске; в приложении — обёртка над [ModelDownloader], в тестах — фейк. */
interface SemanticFiles {
    fun isDownloaded(artifact: SemanticArtifact): Boolean
    fun pathFor(artifact: SemanticArtifact): String
    fun download(artifact: SemanticArtifact): Flow<DownloadState>
    fun cancel(artifact: SemanticArtifact)
    suspend fun delete(artifact: SemanticArtifact)
}

class DownloaderFiles(private val downloader: ModelDownloader) : SemanticFiles {
    override fun isDownloaded(artifact: SemanticArtifact) = downloader.isDownloaded(artifact)
    override fun pathFor(artifact: SemanticArtifact) = downloader.localPathFor(artifact)
    override fun download(artifact: SemanticArtifact) = downloader.download(artifact)
    override fun cancel(artifact: SemanticArtifact) = downloader.cancel(artifact)
    override suspend fun delete(artifact: SemanticArtifact) = downloader.delete(artifact)
}

/** Превращает файлы модели в рабочий [EmbeddingProvider] и освобождает его. */
interface SemanticRuntime {
    /** Бросает исключение, если модель или токенизатор не открылись. */
    fun load(modelPath: String, tokenizerPath: String): EmbeddingProvider
    fun unload(provider: EmbeddingProvider)
}

/** Рабочая реализация: токенизатор из tokenizer.json + ONNX Runtime платформы. */
object E5Runtime : SemanticRuntime {
    override fun load(modelPath: String, tokenizerPath: String): EmbeddingProvider {
        var bytes = ByteArray(SemanticModelCatalog.tokenizer.sizeBytes.toInt())
        var size = 0
        check(readFileChunks(tokenizerPath, 1 shl 16) { buf, len ->
            if (size + len > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + len))
            buf.copyInto(bytes, size, 0, len)
            size += len
        }) { "не открылся tokenizer.json" }
        val tokenizer = UnigramTokenizer.fromTokenizerJson(bytes.decodeToString(0, size))
        return E5Embedder(tokenizer, createOnnxTextEncoder(modelPath))
    }

    override fun unload(provider: EmbeddingProvider) {
        (provider as? E5Embedder)?.close()
    }
}

/** SHA-256 файла в hex; null — файл не открылся. Читает блоками, модель целиком в память не попадает. */
fun fileSha256(path: String): String? {
    val sha = Sha256()
    return if (readFileChunks(path, 1 shl 20) { buffer, length -> sha.update(buffer, length) }) sha.hexDigest() else null
}
