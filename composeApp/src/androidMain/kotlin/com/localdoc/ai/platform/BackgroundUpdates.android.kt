package com.localdoc.ai.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.localdoc.ai.MainActivity
import com.localdoc.ai.R
import com.localdoc.ai.domain.AppGraph
import com.localdoc.ai.novel.NetworkKind
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Android: единственный отложенный запуск в WorkManager; сеть и часовой пояс — из системных служб. */
actual object BackgroundUpdates {
    private const val WORK_NAME = "novel_updates"
    private const val CHANNEL_ID = "novel_updates"
    private const val NOTIFICATION_ID = 1601

    actual fun schedule(atEpochMs: Long, requireUnmetered: Boolean) {
        val delay = (atEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NovelUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(AppContextHolder.appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    actual fun cancel() {
        WorkManager.getInstance(AppContextHolder.appContext).cancelUniqueWork(WORK_NAME)
    }

    actual fun currentNetwork(): NetworkKind {
        val manager = AppContextHolder.appContext.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetworkKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkKind.UNMETERED else NetworkKind.MOBILE
    }

    actual fun timeZoneOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    actual fun notifyNewChapters(title: String, text: String) {
        val context = AppContextHolder.appContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Новые главы", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("localdoc://continue")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Без разрешения на уведомления (Android 13+) система просто ничего не покажет
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** На Android разрешение на уведомления запрашивается при запуске приложения ([MainActivity]). */
    actual fun requestNotificationPermission() = Unit
}

/** Воркер WorkManager: проверка новых глав и назначение следующего запуска. */
class NovelUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.autoUpdate.runScheduled()
        return Result.success()
    }
}
