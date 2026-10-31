package com.localdoc.ai.lang

import com.localdoc.ai.net.WebHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Перевод веб-страниц через Google Translate (открытый эндпоинт `translate_a/single`, без ключа):
 * тот же сервис, которым сайты встраивают «свой» переводчик (пример — ranobes). Работает мгновенно
 * и не занимает локальную модель — она подключается только для документов без сети.
 *
 * Ответ эндпоинта — вложенные массивы `[[["перевод", "оригинал", null, null, …], …], …]`;
 * собираем первые элементы каждого сегмента. Ответ не потоковый: блок целиком приходит
 * одним куском, что и отдаётся в [translate] (совместимо с потоковым API переводчика страниц).
 */
class GoogleWebTranslator(private val http: WebHttp) {

    /** Переводит [text] с [source] (null — определить автоматически) на [target] одним запросом. */
    fun translate(text: String, target: Language, source: Language?): Flow<String> = flow {
        if (text.isBlank()) {
            emit(text)
            return@flow
        }
        val result = http.get(
            url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&dt=t" +
                "&sl=${source?.code ?: "auto"}&tl=${target.code}" +
                "&q=" + encode(text),
            headers = mapOf("Accept" to "application/json"),
            maxBytes = MAX_RESPONSE_BYTES
        )
        // Негативный ответ сервиса — обычная ошибка перевода: страница остаётся в оригинале,
        // но читалка не падает; следующий блок пробуется снова
        check(result.isOk) { "HTTP ${result.status}" }
        emit(parse(result.text()))
    }

    /** Достаёт переведённый текст из ответа `[[["перевод","оригинал",…],…], null, …]`. */
    internal fun parse(response: String): String {
        val root = json.parseToJsonElement(response)
        val segments = (root as? JsonArray)?.firstOrNull() as? JsonArray
            ?: error("неожиданный формат ответа")
        return segments.mapNotNull { segment ->
            ((segment as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content)
        }.joinToString("")
    }

    private fun encode(text: String): String = buildString(text.length * 3) {
        for (ch in text) when {
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' ||
                ch == '_' || ch == '~' -> append(ch)
            else -> {
                for (byte in ch.toString().encodeToByteArray()) {
                    append('%')
                    val hex = byte.toInt().toUInt().toString(16)
                    if (hex.length < 2) append('0')
                    append(hex.uppercase())
                }
            }
        }
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** Ответ вмещает перевод длинного блока целиком; обрезается по превышению. */
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}
