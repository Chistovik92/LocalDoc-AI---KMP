package com.localdoc.ai.platform

actual class SyncLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T =
        kotlin.synchronized(monitor, block)
}
