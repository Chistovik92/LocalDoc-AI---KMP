package com.localdoc.ai.domain

import com.localdoc.ai.download.ModelDownloader
import com.localdoc.ai.engine.LlamaEngine
import com.localdoc.ai.model.ModelSpec
import com.localdoc.ai.parser.ParsedDoc
import com.localdoc.ai.parser.PdfExtractor
import com.localdoc.ai.parser.WebArticleParser
import com.localdoc.ai.platform.KeyValueStore
import com.localdoc.ai.rag.HashingEmbedder
import com.localdoc.ai.rag.RagRepository
import com.localdoc.ai.rag.VectorStore
import com.localdoc.ai.tts.Speaker
import kotlinx.coroutines.flow.MutableStateFlow

/** Сообщение чата. */
data class ChatMessage(
    val role: Role,
    val text: String
) {
    enum class Role { USER, ASSISTANT }
}

/** Документ, переданный извне (Share Intent / файловый менеджер). */
data class IncomingDoc(val type: IncomingType, val payload: String) {
    enum class IncomingType { URL, TEXT, PDF }
}

/**
 * Единая точка доступа к движку и состоянию приложения (DI-контейнер).
 */
object AppGraph {

    val kv: KeyValueStore = KeyValueStore()
    val engine: LlamaEngine = LlamaEngine()
    val downloader: ModelDownloader = ModelDownloader()
    val speaker: Speaker = Speaker()
    val webParser: WebArticleParser = WebArticleParser()
    val pdfExtractor: PdfExtractor = PdfExtractor()

    private val ragRepository: RagRepository by lazy {
        RagRepository(VectorStore(kv), HashingEmbedder())
    }
    val pipeline: SmartPipeline by lazy { SmartPipeline(engine, ragRepository) }

    // --- Состояние UI ---
    val currentDoc = MutableStateFlow<ParsedDoc?>(null)
    val incoming = MutableStateFlow<IncomingDoc?>(null)
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val loadedModelId = MutableStateFlow(kv.get(KEY_LOADED_MODEL))
    val isGenerating = MutableStateFlow(false)
    val statsTokensPerSec = MutableStateFlow(0f)

    var activeSpec: ModelSpec? = null
        private set

    fun acceptIncoming(item: IncomingDoc?) {
        incoming.value = item
    }

    fun setLoaded(spec: ModelSpec?) {
        activeSpec = spec
        loadedModelId.value = spec?.id
        if (spec != null) kv.put(KEY_LOADED_MODEL, spec.id) else kv.remove(KEY_LOADED_MODEL)
    }

    fun addMessage(message: ChatMessage) {
        messages.value = messages.value + message
    }

    fun updateLastAssistant(text: String) {
        val list = messages.value.toMutableList()
        if (list.isNotEmpty() && list.last().role == ChatMessage.Role.ASSISTANT) {
            list[list.lastIndex] = list.last().copy(text = text)
            messages.value = list
        }
    }

    fun clearChat() {
        messages.value = emptyList()
    }

    private const val KEY_LOADED_MODEL = "loaded_model_id"
}
