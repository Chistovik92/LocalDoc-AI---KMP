package com.localdoc.ai.platform

import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.novel.NetworkKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS: задача обновления через BGTaskScheduler. Задача регистрируется один раз при запуске ([register], из Swift),
 * сроки система выбирает сама — не раньше назначенного. Проверить на устройстве можно только вручную из Xcode.
 */
@OptIn(ExperimentalForeignApi::class)
actual object BackgroundUpdates {
    private const val TASK_ID = "com.localdoc.ai.novelupdates"
    private const val FLAG_REACHABLE = 0x2u
    private const val FLAG_CONNECTION_REQUIRED = 0x4u
    private const val FLAG_WWAN = 0x40000u

    /** Вызывается из Swift до окончания запуска приложения: после него регистрировать задачи уже нельзя. */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, null) { task: BGTask? ->
            if (task != null) {
                // Следующий запуск назначается заранее: если система прервёт задачу, расписание не оборвётся
                runCatching { AppGraph.autoUpdate.reschedule() }
                var finished = false
                val job = AppGraph.appScope.launch {
                    runCatching { AppGraph.autoUpdate.runScheduled() }
                    if (!finished) {
                        finished = true
                        task.setTaskCompletedWithSuccess(true)
                    }
                }
                task.expirationHandler = {
                    job.cancel()
                    if (!finished) {
                        finished = true
                        task.setTaskCompletedWithSuccess(false)
                    }
                }
            }
        }
    }

    actual fun schedule(atEpochMs: Long, requireUnmetered: Boolean) {
        val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(atEpochMs / 1000.0)
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_ID)
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    actual fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_ID)
    }

    actual fun currentNetwork(): NetworkKind = memScoped {
        val reachability = SCNetworkReachabilityCreateWithName(null, "apple.com") ?: return@memScoped NetworkKind.NONE
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        val known = SCNetworkReachabilityGetFlags(reachability, flags.ptr)
        CFRelease(reachability)
        if (!known) return@memScoped NetworkKind.NONE
        val value = flags.value
        when {
            value and FLAG_REACHABLE == 0u || value and FLAG_CONNECTION_REQUIRED != 0u -> NetworkKind.NONE
            value and FLAG_WWAN != 0u -> NetworkKind.MOBILE
            else -> NetworkKind.UNMETERED
        }
    }

    actual fun timeZoneOffsetMinutes(): Int = (NSTimeZone.localTimeZone.secondsFromGMT() / 60).toInt()

    actual fun notifyNewChapters(title: String, text: String) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(text)
        val request = UNNotificationRequest.requestWithIdentifier("novel_updates", content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    actual fun requestNotificationPermission() {
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
    }
}
