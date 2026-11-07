package com.localdoc.ai.sync

import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.net.asStorage
import com.localdoc.ai.net.asTransport
import com.localdoc.ai.platform.RawHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** iCloud ubiquity container + Google Drive OAuth (тот же общий API, что на Android). */
actual fun platformCloudTransport(provider: CloudProvider): SyncTransport? = when (provider) {
    CloudProvider.ICLOUD -> ICloudTransport()
    CloudProvider.GOOGLE_DRIVE -> {
        val clientId = AppGraph.kv.get("sync_google_client_id").orEmpty()
        if (clientId.isBlank()) null else {
            val http = RawHttp().asTransport()
            val auth = GoogleDriveAuth(http, AppGraph.secure.asStorage(), clientId)
            GoogleDriveTransport(http, auth::accessToken)
        }
    }
}

private class ICloudTransport : SyncTransport {
    private val manager = NSFileManager.defaultManager
    private val url = manager.URLForUbiquityContainerIdentifier(null)?.URLByAppendingPathComponent(SyncFormat.FILE_NAME)

    override suspend fun download(): RemoteFile? {
        val file = url ?: return null
        return suspendCancellableCoroutine { cont ->
            try {
                val data = NSData.dataWithContentsOfURL(file)
                if (data == null) cont.resume(null) else cont.resume(RemoteFile(data.toByteArray(), data.length.toString()))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { cont.resumeWithException(SyncTransportException("iCloud недоступен: ${e.message}")) }
        }
    }

    override suspend fun upload(bytes: ByteArray, previousVersion: String?): String {
        val file = url ?: throw SyncTransportException("Включите iCloud и войдите в Apple ID")
        return suspendCancellableCoroutine { cont ->
            try {
                val data = NSData.create(bytes = bytes, length = bytes.size.toULong())
                if (!data.writeToURL(file, atomically = true)) throw SyncTransportException("iCloud не сохранил файл")
                cont.resume("")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { cont.resumeWithException(e) }
        }
    }
}
