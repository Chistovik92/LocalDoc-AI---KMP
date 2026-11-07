package com.localdoc.ai.sync

import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.net.SecretStorage
import com.localdoc.ai.platform.RawRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** OAuth для installed app: пользователь открывает URL, входит в Google и вставляет код авторизации. */
class GoogleDriveAuth(
    private val http: HttpTransport,
    private val secrets: SecretStorage,
    private val clientId: String,
    private val clientSecret: String = ""
) {
    val configured get() = clientId.isNotBlank()
    val isAuthorized get() = configured && !secrets.get(REFRESH_TOKEN).isNullOrBlank()

    fun authorizationUrl(): String =
        "https://accounts.google.com/o/oauth2/v2/auth?client_id=${urlEncode(clientId)}" +
            "&redirect_uri=${urlEncode(REDIRECT_URI)}&response_type=code&access_type=offline&prompt=consent" +
            "&scope=${urlEncode("https://www.googleapis.com/auth/drive.appdata")}"

    suspend fun signIn(code: String) {
        val fields = listOf(
            "client_id" to clientId, "client_secret" to clientSecret, "code" to code.trim(),
            "grant_type" to "authorization_code", "redirect_uri" to REDIRECT_URI
        )
        val response = request(fields)
        val refresh = json(response)["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw SyncTransportException("Google не выдал постоянный доступ. Повторите вход и разрешите доступ к данным приложения")
        secrets.put(REFRESH_TOKEN, refresh)
    }

    suspend fun accessToken(): String {
        val refresh = secrets.get(REFRESH_TOKEN) ?: throw SyncTransportException("Сначала войдите в Google Drive")
        val response = request(listOf(
            "client_id" to clientId, "client_secret" to clientSecret,
            "refresh_token" to refresh, "grant_type" to "refresh_token"
        ))
        return json(response)["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw SyncTransportException("Google не выдал токен доступа")
    }

    fun signOut() = secrets.remove(REFRESH_TOKEN)

    private suspend fun request(fields: List<Pair<String, String>>): String = try {
        val body = fields.joinToString("&") { "${urlEncode(it.first)}=${urlEncode(it.second)}" }
        val response = http.execute(RawRequest(TOKEN_URL, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"), body.encodeToByteArray()), 1024 * 1024)
        if (response.status !in 200..299) throw SyncTransportException("Вход Google отклонён (HTTP ${response.status})", response.status)
        response.body.decodeToString()
    } catch (e: CancellationException) { throw e } catch (e: SyncTransportException) { throw e } catch (e: Exception) {
        throw SyncTransportException("Нет связи со службой входа Google: ${e.message ?: "ошибка сети"}")
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    companion object {
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
        private const val REFRESH_TOKEN = "sync_google_refresh_token"
        internal fun urlEncode(value: String) = buildString {
            value.encodeToByteArray().forEach {
                val c = it.toInt().toChar()
                if (c.isLetterOrDigit() || c in "-_.~") append(c) else append('%').append((it.toInt() and 255).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
