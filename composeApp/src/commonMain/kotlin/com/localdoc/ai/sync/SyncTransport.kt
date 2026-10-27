package com.localdoc.ai.sync

import com.localdoc.ai.net.HttpTransport
import com.localdoc.ai.platform.RawRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Содержимое файла синхронизации в облаке и его «версия» (ETag; пусто, если сервер её не отдаёт). */
class RemoteFile(val bytes: ByteArray, val version: String)

/** Облако перезаписано другим устройством между нашей загрузкой и отправкой. */
class UploadConflictException : Exception("Файл в облаке изменился во время отправки")

/** Сервер ответил ошибкой или связь оборвалась. */
class SyncTransportException(message: String, val status: Int = 0) : Exception(message)

/** Где лежит один зашифрованный файл синхронизации. Транспорт ничего не знает о содержимом. */
interface SyncTransport {
    /** Текущий файл или null, если его ещё нет. */
    suspend fun download(): RemoteFile?

    /**
     * Записывает файл. [previousVersion]: null — файла быть не должно, пустая строка — без проверки (сервер не
     * отдавал версию), иначе перезапись только если версия не изменилась. Возвращает новую версию (или пусто).
     */
    suspend fun upload(bytes: ByteArray, previousVersion: String?): String
}

/**
 * WebDAV (Nextcloud, ownCloud, Яндекс.Диск и др.) поверх общего HTTP-клиента. [folderUrl] — существующая папка;
 * файл — [SyncFormat.FILE_NAME] в ней. Проверка перезаписи — `If-Match` / `If-None-Match: *`.
 */
class WebDavTransport(
    private val http: HttpTransport,
    folderUrl: String,
    private val user: String,
    private val password: String,
    private val fileName: String = SyncFormat.FILE_NAME
) : SyncTransport {

    private val fileUrl = folderUrl.trimEnd('/') + "/" + fileName

    @OptIn(ExperimentalEncodingApi::class)
    private val auth = if (user.isEmpty()) emptyMap() else
        mapOf("Authorization" to "Basic " + Base64.encode("$user:$password".encodeToByteArray()))

    override suspend fun download(): RemoteFile? {
        val response = call(RawRequest(fileUrl, "GET", auth))
        return when (response.status) {
            404 -> null
            in 200..299 -> RemoteFile(response.body, response.headers["etag"].orEmpty())
            else -> throw failure(response.status)
        }
    }

    override suspend fun upload(bytes: ByteArray, previousVersion: String?): String {
        val condition = when {
            previousVersion == null -> mapOf("If-None-Match" to "*")
            previousVersion.isEmpty() -> emptyMap()
            else -> mapOf("If-Match" to previousVersion)
        }
        val response = call(RawRequest(fileUrl, "PUT", auth + condition + ("Content-Type" to "application/octet-stream"), bytes))
        return when (response.status) {
            in 200..299 -> response.headers["etag"].orEmpty()
            412 -> throw UploadConflictException()
            else -> throw failure(response.status)
        }
    }

    private suspend fun call(request: RawRequest) = try {
        http.execute(request, MAX_BYTES)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SyncTransportException("Нет связи с сервером: ${e.message ?: "ошибка сети"}")
    }

    private fun failure(status: Int) = SyncTransportException(
        when (status) {
            401, 403 -> "Сервер не принял логин или пароль (HTTP $status)"
            404, 409 -> "Папка синхронизации не найдена на сервере (HTTP $status)"
            507 -> "На сервере нет места (HTTP 507)"
            else -> "Сервер ответил ошибкой HTTP $status"
        },
        status
    )

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}

enum class CloudProvider(val label: String) { ICLOUD("iCloud"), GOOGLE_DRIVE("Google Drive") }

/**
 * Транспорт облака платформы или null, если он недоступен. iCloud — только iOS (контейнер ubiquity требует
 * entitlement и проверки на устройстве), Google Drive — OAuth-клиент проекта; пока обе реализации — заготовки.
 */
expect fun platformCloudTransport(provider: CloudProvider): SyncTransport?
