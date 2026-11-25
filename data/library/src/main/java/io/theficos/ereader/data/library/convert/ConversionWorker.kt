package io.theficos.ereader.data.library.convert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Runs [ConversionRepository.step] until every conversion is settled or only waits remain that
 * are better left to a later run. While it moves a PDF or an EPUB it shows itself as a data sync
 * foreground service, so leaving the app does not stop a 100 MiB upload half way; polling the
 * server needs no notification. WorkManager brings it back after process death.
 */
class ConversionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        val deps = ConversionDependencies.holder ?: return Result.failure()
        val startedAt = SystemClock.elapsedRealtime()
        var shown = false
        while (true) {
            val wait = deps.repository.step(beforeTransfer = {
                if (!shown) {
                    shown = true
                    try {
                        setForeground(foregroundInfo())
                    } catch (e: IllegalStateException) {
                        // Not allowed from the background on newer Android: the transfer still runs.
                        Log.i(TAG, "no foreground service: ${e.javaClass.simpleName}")
                    }
                }
            }) ?: return Result.success()
            if (wait > IN_PROCESS_WAIT_MS || SystemClock.elapsedRealtime() - startedAt + wait > MAX_RUN_MS) {
                ConversionScheduler.later(applicationContext, wait)
                return Result.success()
            }
            delay(wait)
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        val title = ConversionDependencies.holder?.notificationTitle?.invoke() ?: ""
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "ConversionWorker"
        const val CHANNEL_ID = "quire-pdf-conversion"
        const val NOTIFICATION_ID = 4203
        const val IN_PROCESS_WAIT_MS = 60_000L

        /** Background work is stopped after ten minutes; hand over to a fresh run before that. */
        const val MAX_RUN_MS = 9 * 60_000L
    }
}

object ConversionScheduler {
    const val UNIQUE_NOW = "pdf-conversion"
    const val UNIQUE_LATER = "pdf-conversion-later"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A run as soon as there is a network; queued behind a run already going. */
    fun now(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>().setConstraints(online).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun later(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setConstraints(online)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_LATER, ExistingWorkPolicy.REPLACE, request)
    }
}

/** Set once by the app; the worker reads it on each run. */
object ConversionDependencies {
    @Volatile var holder: Holder? = null

    class Holder(val repository: ConversionRepository, val notificationTitle: () -> String)
}
