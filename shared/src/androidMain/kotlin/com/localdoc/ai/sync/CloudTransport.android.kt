package com.localdoc.ai.sync

import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.platform.RawHttp
import com.localdoc.ai.net.asStorage
import com.localdoc.ai.net.asTransport

/** Google Drive работает через OAuth installed-app; iCloud на Android недоступен. */
actual fun platformCloudTransport(provider: CloudProvider): SyncTransport? = when (provider) {
    CloudProvider.GOOGLE_DRIVE -> {
        val clientId = AppGraph.kv.get("sync_google_client_id").orEmpty()
        if (clientId.isBlank()) null else {
            val http = RawHttp().asTransport()
            val auth = GoogleDriveAuth(http, AppGraph.secure.asStorage(), clientId)
            GoogleDriveTransport(http, auth::accessToken)
        }
    }
    CloudProvider.ICLOUD -> null
}
