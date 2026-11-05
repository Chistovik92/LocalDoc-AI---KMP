package com.localdoc.ai.platform

import platform.Foundation.NSLog

actual object DebugLog {
    actual fun d(message: String) {
        RingLog.d(message)
        NSLog("LocalDocDebug: $message")
    }

    actual fun e(message: String, error: Throwable?) {
        RingLog.e(message, error)
        NSLog("LocalDocDebug ERROR: $message${error?.let { " — ${it.message}" }.orEmpty()}")
    }
}
