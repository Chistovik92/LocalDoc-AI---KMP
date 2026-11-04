package com.localdoc.ai.platform

import platform.Foundation.NSProcessInfo

/**
 * На iOS точного предела кучи нет — памятью управляет система. Берём физическую память
 * устройства; для PDF-рендера этого достаточно, чтобы выйти на полную ширину (`MAX_RENDER_WIDTH`).
 */
actual val appMaxMemoryBytes: Long
    get() = NSProcessInfo.processInfo.physicalMemory.toLong()
