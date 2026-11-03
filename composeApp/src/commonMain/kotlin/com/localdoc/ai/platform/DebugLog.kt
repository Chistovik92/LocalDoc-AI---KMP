package com.localdoc.ai.platform

expect object DebugLog {
    fun d(message: String)
    fun e(message: String, error: Throwable? = null)
}
