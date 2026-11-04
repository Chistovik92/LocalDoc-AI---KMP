package com.localdoc.ai.platform

import android.util.Log

actual object DebugLog {
    private const val TAG = "LocalDocDebug"
    actual fun d(message: String) {
        RingLog.d(message)
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            println("$TAG: $message")
        }
    }

    actual fun e(message: String, error: Throwable?) {
        RingLog.e(message, error)
        try {
            Log.e(TAG, message, error)
        } catch (_: Throwable) {
            println("$TAG ERROR: $message${error?.let { " — ${it.message}" }.orEmpty()}")
        }
    }
}
