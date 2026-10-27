package com.localdoc.ai.sync

/** Заготовка: Google Drive требует OAuth-клиента проекта и Play Services, iCloud на Android нет. */
actual fun platformCloudTransport(provider: CloudProvider): SyncTransport? = null
