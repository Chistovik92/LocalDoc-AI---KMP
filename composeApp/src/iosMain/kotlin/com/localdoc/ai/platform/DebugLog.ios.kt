package com.localdoc.ai.platform

import platform.Foundation.NSLog

actual object DebugLog {
    actual fun d(message: String) {
        NSLog("LocalDocDebug: $message")
    }

    actual fun e(message: String, error: Throwable?) {
        NSLog("LocalDocDebug ERROR: $message${error?.let { " — ${it.message}" }.orEmpty()}")
    }
}
