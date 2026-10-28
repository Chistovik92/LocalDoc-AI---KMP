package com.localdoc.ai.platform

/** Один HTTP-запрос без автоматических редиректов и без системного хранилища cookie. */
class RawRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = 20_000,
    /** Отпечатки SHA-256 (hex) сертификатов, которым пользователь уже разрешил доверять на этом сайте. */
    val trustedCertificates: Set<String> = emptySet(),
    /** Пользователь разрешил открывать сайты с любым сертификатом (галочка в настройках). */
    val trustAnyCertificate: Boolean = false
)

/** Чем сертификат сайта не прошёл проверку системы. */
enum class CertificateProblem { UNKNOWN_ISSUER, EXPIRED, NOT_YET_VALID, HOSTNAME_MISMATCH, OTHER }

/**
 * Сертификат сайта не прошёл проверку (самоподписанный, неизвестный центр, истёк, чужое имя). [fingerprint] — SHA-256
 * сертификата сайта в hex: по нему пользователь разрешает доверять именно этому сертификату; если сайт его сменит,
 * приложение спросит снова.
 */
class UntrustedCertificateException(
    val host: String,
    val fingerprint: String,
    val problem: CertificateProblem
) : IllegalStateException("Сертификат сайта $host не прошёл проверку ($problem)")

/** SHA-256 в hex, как показывается пользователю и хранится в списке доверенных сертификатов. */
fun sha256Hex(bytes: ByteArray): String = com.localdoc.ai.rag.Sha256().also { it.update(bytes) }.hexDigest()

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
