package com.localdoc.ai.sync

import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.platform.RawRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Один файл синхронизации в папке appDataFolder Google Drive.
 * OAuth Bearer-токен получает экран синхронизации; здесь он не сохраняется и не попадает в логи.
 */
class GoogleDriveTransport(
    private val http: HttpTransport,
    private val accessToken: suspend () -> String,
    private val fileName: String = SyncFormat.FILE_NAME
) : SyncTransport {
    override suspend fun download(): RemoteFile? {
        val metadata = findFile() ?: return null
        val response = call(
            RawRequest(
                "$DOWNLOAD_URL/${metadata.id}?alt=media", "GET",
                mapOf("Authorization" to "Bearer ${accessToken()}")
            )
        )
        return when (response.status) {
            in 200..299 -> RemoteFile(response.body, metadata.version)
            else -> throw failure(response.status)
        }
    }

    override suspend fun upload(bytes: ByteArray, previousVersion: String?): String {
        val existing = findFile()
        if (previousVersion == null && existing != null || previousVersion?.isNotEmpty() == true && existing?.version != previousVersion) {
            throw UploadConflictException()
        }
        val boundary = "localdoc-sync-boundary"
        val metadata = if (existing == null)
            """{"name":"$fileName","parents":["appDataFolder"]}""" else "{}"
        val metadataBytes = metadata.encodeToByteArray()
        val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".encodeToByteArray() +
            metadataBytes + "\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        val body = prefix + bytes + suffix
        val url = if (existing == null) UPLOAD_URL else "$UPLOAD_URL/${existing.id}"
        val response = call(
            RawRequest(
                "$url?uploadType=multipart&fields=id,headRevisionId", if (existing == null) "POST" else "PATCH",
                mapOf(
                    "Authorization" to "Bearer ${accessToken()}",
                    "Content-Type" to "multipart/related; boundary=$boundary"
                ),
                body
            )
        )
        if (response.status !in 200..299) throw failure(response.status)
        return parseFile(response.body.decodeToString())?.version.orEmpty()
    }

    private suspend fun findFile(): DriveFile? {
        val query = "name='$fileName' and 'appDataFolder' in parents and trashed=false"
        val response = call(
            RawRequest(
                "$API_URL/files?q=${encode(query)}&spaces=appDataFolder&fields=files(id,headRevisionId)",
                "GET", mapOf("Authorization" to "Bearer ${accessToken()}")
            )
        )
        if (response.status !in 200..299) throw failure(response.status)
        val files = Json.parseToJsonElement(response.body.decodeToString()).jsonObject["files"]
            ?.jsonArray ?: return null
        if (files.size > 1) throw SyncTransportException("В Google Drive найдено несколько файлов синхронизации")
        return files.firstOrNull()?.let { parseFile(it.toString()) }
    }

    private suspend fun call(request: RawRequest) = try {
        http.execute(request, MAX_BYTES)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncTransportException("Нет связи с Google Drive: ${e.message ?: "ошибка сети"}")
    }

    private fun failure(status: Int) = SyncTransportException(
        when (status) {
            401, 403 -> "Google Drive не принял авторизацию (HTTP $status)"
            404 -> "Файл синхронизации Google Drive не найден"
            else -> "Google Drive ответил ошибкой HTTP $status"
        }, status
    )

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() || c in "-_.~") append(c) else append('%').append((byte.toInt() and 255).toString(16).uppercase().padStart(2, '0'))
        }
    }

    companion object {
        internal fun parseFile(json: String): DriveFile? {
            val obj = Json.parseToJsonElement(json).jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return DriveFile(id, obj["headRevisionId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        private const val API_URL = "https://www.googleapis.com/drive/v3"
        private const val DOWNLOAD_URL = API_URL
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}

internal data class DriveFile(val id: String, val version: String)
