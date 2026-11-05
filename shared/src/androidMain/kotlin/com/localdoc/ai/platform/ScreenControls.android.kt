package com.localdoc.ai.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import com.localdoc.ai.domain.OrientationLock
import java.lang.ref.WeakReference

/** Текущая активность — окно нужно для яркости, «не гасить экран» и ориентации. */
object CurrentActivity {
    private var ref: WeakReference<Activity>? = null

    val activity: Activity? get() = ref?.get()

    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }
}

actual object ScreenControls {
    actual val supportsOrientationLock: Boolean = true
    actual val supportsVolumeKeys: Boolean = true

    actual val prefersReducedMotion: Boolean
        get() = runCatching {
            android.provider.Settings.Global.getFloat(
                AppContextHolder.appContext.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)

    actual fun setBrightness(level: Float?) = onWindow { activity ->
        val params = activity.window.attributes
        params.screenBrightness = level?.coerceIn(0.01f, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
    }

    actual fun setKeepScreenOn(on: Boolean) = onWindow { activity ->
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    actual fun setOrientation(lock: OrientationLock) = onWindow { activity ->
        activity.requestedOrientation = when (lock) {
            OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun onWindow(block: (Activity) -> Unit) {
        val activity = CurrentActivity.activity ?: return
        activity.runOnUiThread { block(activity) }
    }
}
