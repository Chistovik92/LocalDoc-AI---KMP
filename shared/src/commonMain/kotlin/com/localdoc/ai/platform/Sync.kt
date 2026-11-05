package com.localdoc.ai.platform

expect class SyncLock() {
    fun <T> withLock(block: () -> T): T
}
