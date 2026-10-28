package com.localdoc.ai.sync

/**
 * Заготовка: iCloud (контейнер ubiquity, NSFileCoordinator) нужно включать в Xcode-проекте и проверять на
 * устройстве с Apple ID; Google Drive требует OAuth-клиента проекта. Пока доступен только WebDAV.
 */
actual fun platformCloudTransport(provider: CloudProvider): SyncTransport? = null
