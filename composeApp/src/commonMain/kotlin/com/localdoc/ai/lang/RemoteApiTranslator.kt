package com.localdoc.ai.lang

import com.localdoc.ai.net.WebHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Перевод через удалённую OpenAI-compatible модель: POST `{baseUrl}/chat/completions` с
 * JSON-телом `{ model, messages, temperature }` и заголовком `Authorization: Bearer <key>`.
 *
 * Совместим с любым сервером, повторяющим протокол OpenAI Chat Completions (OpenAI, OpenRouter,
 * Groq, локальные vLLM/Ollama через `/v1`). Ответ не потоковый: берём `choices[0].message.content`
 * целиком и отдаём одним блоком — так же, как Google-переводчик страниц.
 *
 * Настройки ([apiUrl], [model], [apiKey]) приходят из TranslationSettings; ключ хранится отдельно
 * в защищённом хранилище (SecureStore) и сюда не попадает.
 */
class RemoteApiTranslator(
    private val http: WebHttp,
    private val apiUrl: String,
    private val model: String,
    private val apiKey: String
) {

    fun translate(text: String, target: Language, source: Language?): Flow<String> = flow {
        if (text.isBlank()) {
            emit(text)
            return@flow
        }
        val base = apiUrl.trimEnd('/')
        require(base.startsWith("https://")) { "URL API должен начинаться с https://" }
        val system = if (source != null) {
            "Translate the text from ${source.english} to ${target.english}. " +
                "Return only the translation, no explanations or commentary."
        } else {
            "Translate the text to ${target.english}. " +
                "Return only the translation, no explanations or commentary."
        }
        val body = JsonObject(
            linkedMapOf(
                "model" to jsonPrimitive(model),
                "temperature" to jsonPrimitive(0.3),
                "messages" to JsonArray(
                    listOf(
                        JsonObject(mapOf("role" to jsonPrimitive("system"), "content" to jsonPrimitive(system))),
                        JsonObject(mapOf("role" to jsonPrimitive("user"), "content" to jsonPrimitive(text)))
                    )
                )
            )
        ).toString()
        val result = http.postJson("$base/chat/completions", body, apiKey)
        check(result.isOk) { "HTTP ${result.status}" }
        emit(parse(result.text()))
    }

    /** Достаёт переведённый текст из `choices[0].message.content`. */
    internal fun parse(response: String): String {
        val root = json.parseToJsonElement(response).jsonObject
        return root["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            ?: error("неожиданный формат ответа API")
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private fun jsonPrimitive(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
        private fun jsonPrimitive(value: Double) = kotlinx.serialization.json.JsonPrimitive(value)
    }
}
