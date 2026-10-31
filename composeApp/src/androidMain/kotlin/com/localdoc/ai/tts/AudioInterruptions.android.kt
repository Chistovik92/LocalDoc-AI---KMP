package com.localdoc.ai.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.localdoc.ai.platform.AppContextHolder

/**
 * Android: аудиофокус + события наушников.
 * - Транзитная потеря фокуса (звонок, навигатор) — пауза, продолжение по GAIN.
 * - Постоянная потеря (пользователь включил другой плеер) — пауза без автопродолжения.
 * - Наушники: подключение — продолжение чтения; отключение — пауза, чтобы звук не хлынул в динамик.
 *   На Android 8–22 ловим ACTION_HEADSET_PLUG, на 23+ — AudioDeviceCallback (покрывает и Bluetooth).
 */
actual fun createAudioInterruptions(): AudioInterruptions? = AndroidAudioEvents()

private class AndroidAudioEvents : AudioInterruptions {

    private val context = AppContextHolder.appContext
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var onInterrupted: (() -> Unit)? = null
    private var onRegained: (() -> Unit)? = null
    private var onHeadphonesDisconnected: (() -> Unit)? = null
    private var onHeadphonesConnected: (() -> Unit)? = null

    private var resumeOnGain = false
    private var request: AudioFocusRequest? = null
    private var started = false

    // --- Аудиофокус ---

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Другой плеер забрал звук насовсем — продолжать нечего
                resumeOnGain = false
                onInterrupted?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // «Утку» не применяем: обрыв речи лучше приглушённой; звонок закончился — продолжим
                resumeOnGain = true
                onInterrupted?.invoke()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain) {
                    resumeOnGain = false
                    onRegained?.invoke()
                }
            }
        }
    }

    // --- Наушники: Android 8–22 ---

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_HEADSET_PLUG) return
            // state: 1 — подключены, 0 — отключены; приходит и при старте регистрации
            when (intent.getIntExtra("state", -1)) {
                0 -> onHeadphonesDisconnected?.invoke()
                1 -> onHeadphonesConnected?.invoke()
            }
        }
    }

    // --- Наушники: Android 23+ (проводные и Bluetooth) ---

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.any { it.isHeadphonesLike }) onHeadphonesConnected?.invoke()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isHeadphonesLike }) onHeadphonesDisconnected?.invoke()
        }
    }

    private val AudioDeviceInfo.isHeadphonesLike: Boolean
        get() = type in intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

    override fun setListener(
        onInterrupted: () -> Unit,
        onRegained: () -> Unit,
        onHeadphonesDisconnected: () -> Unit,
        onHeadphonesConnected: () -> Unit
    ) {
        this.onInterrupted = onInterrupted
        this.onRegained = onRegained
        this.onHeadphonesDisconnected = onHeadphonesDisconnected
        this.onHeadphonesConnected = onHeadphonesConnected
    }

    override fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= 23) {
            manager.registerAudioDeviceCallback(deviceCallback, null)
        } else {
            // Наушники на Android 8–22: до N AudioDeviceCallback нет, ловим ACTION_HEADSET_PLUG
            context.registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        }
        requestFocus()
    }

    override fun stop() {
        if (!started) return
        started = false
        resumeOnGain = false
        if (Build.VERSION.SDK_INT >= 23) {
            manager.unregisterAudioDeviceCallback(deviceCallback)
        } else {
            runCatching { context.unregisterReceiver(headsetReceiver) }
        }
        request?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
    }

    /** Аудиофокус на всю сессию чтения: запрашивается при старте, отпускается в [stop]. */
    private fun requestFocus() {
        val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { request = it }
        runCatching { manager.requestAudioFocus(req) }
    }
}
