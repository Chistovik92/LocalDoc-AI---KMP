package com.localdoc.ai.platform

/** Один HTTP-запрос без автоматических редиректов и без системного хранилища cookie. */
class RawRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = 20_000
)

/**
 * Ответ сервера. [setCookies] — отдельные заголовки `Set-Cookie` (на iOS собираются из разобранных
 * системой cookie), [headers] — остальные заголовки с именами в нижнем регистре.
 */
class RawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val setCookies: List<String>,
    val body: ByteArray
)

/** Слишком большой ответ: сервер отдал больше, чем разрешено вызывающим кодом. */
class ResponseTooLargeException(val limit: Long) : IllegalStateException("Ответ больше ${limit / (1024 * 1024)} МБ")

/**
 * Платформенный HTTP-клиент (Android — HttpURLConnection, iOS — NSURLSession). Редиректы и cookie
 * обрабатывает общий код ([com.localdoc.ai.net.WebHttp]), чтобы они вели себя одинаково и cookie
 * сессии никогда не уходили на чужие домены.
 */
expect class RawHttp() {
    /** User-Agent встроенного браузера платформы: сайты привязывают сессию к нему. */
    val userAgent: String

    /** Выполняет запрос; тело читается не больше [maxBytes], иначе [ResponseTooLargeException]. */
    suspend fun execute(request: RawRequest, maxBytes: Long): RawResponse
}
