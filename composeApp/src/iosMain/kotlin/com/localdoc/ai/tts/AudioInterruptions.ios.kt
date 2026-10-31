package com.localdoc.ai.tts

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortHeadset
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeOldRouteKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteDescription
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * iOS: события аудиосессии.
 * - Прерывание (телефонный звонок, Siri, будильник): начало — пауза, окончание — продолжение.
 * - Наушники: смена маршрута на проводные/Bluetooth — продолжение чтения; исчезновение
 *   наушников из маршрута (выделили провод, выключили Bluetooth) — пауза, чтобы звук
 *   не хлынул в динамик.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createAudioInterruptions(): AudioInterruptions? = IosAudioEvents()

/** Типы портов вывода, считающиеся наушниками. */
private val HEADPHONE_PORTS =
    setOf(AVAudioSessionPortHeadphones, AVAudioSessionPortHeadset, AVAudioSessionPortBluetoothA2DP)

@OptIn(ExperimentalForeignApi::class)
private class IosAudioEvents : AudioInterruptions {

    private var onInterrupted: (() -> Unit)? = null
    private var onRegained: (() -> Unit)? = null
    private var onHeadphonesDisconnected: (() -> Unit)? = null
    private var onHeadphonesConnected: (() -> Unit)? = null
    private val observers = ArrayList<Any>(2)

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
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            AVAudioSessionInterruptionNotification,
            AVAudioSession.sharedInstance(),
            NSOperationQueue.mainQueue
        ) { note -> dispatchInterruption(note) }.let { observers.add(it) }
        center.addObserverForName(
            AVAudioSessionRouteChangeNotification,
            AVAudioSession.sharedInstance(),
            NSOperationQueue.mainQueue
        ) { note -> dispatchRouteChange(note) }.let { observers.add(it) }
    }

    override fun stop() {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
    }

    private fun dispatchInterruption(note: NSNotification?) {
        val info = note?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.ulongValue ?: return
        when (type) {
            AVAudioSessionInterruptionTypeBegan -> onInterrupted?.invoke()
            AVAudioSessionInterruptionTypeEnded -> onRegained?.invoke()
        }
    }

    private fun dispatchRouteChange(note: NSNotification?) {
        val info = note?.userInfo ?: return
        val reason = (info[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.ulongValue ?: return
        when (reason) {
            AVAudioSessionRouteChangeReasonNewDeviceAvailable ->
                if (hasHeadphoneOutput()) onHeadphonesConnected?.invoke()
            AVAudioSessionRouteChangeReasonOldDeviceUnavailable ->
                if (oldRouteHadHeadphones(info)) onHeadphonesDisconnected?.invoke()
        }
    }

    private fun hasHeadphoneOutput(): Boolean =
        AVAudioSession.sharedInstance().currentRoute
            .outputs.any { (it as? AVAudioSessionPortDescription)?.portType in HEADPHONE_PORTS }

    private fun oldRouteHadHeadphones(info: Map<Any?, *>): Boolean {
        val old = info[AVAudioSessionRouteChangeOldRouteKey] as? AVAudioSessionRouteDescription ?: return false
        return old.outputs.any { (it as? AVAudioSessionPortDescription)?.portType in HEADPHONE_PORTS }
    }
}
