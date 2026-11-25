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
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Runs [ConversionRepository.step] until every conversion is settled or only waits remain that
 * are better left to a later run. While it moves a PDF or an EPUB it shows itself as a data sync
 * foreground service, so leaving the app does not stop a 100 MiB upload half way. Newer Android
 * refuses that to a run started in the background; an upload then waits for the app to be open
 * rather than go in a run stopped after ten minutes. A run ends after one transfer, so polling
 * the server never keeps the notification up. WorkManager brings it back after process death.
 */
class ConversionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_WAKE_ONLY, false)) {
            ConversionScheduler.now(applicationContext, expedited = false)
            return Result.success()
        }
        val deps = ConversionDependencies.holder ?: return Result.failure()
        val startedAt = SystemClock.elapsedRealtime()
        var shown: Boolean? = null
        var transferred = false
        while (true) {
            val wait = deps.repository.step(beforeTransfer = { upload ->
                val foreground = shown ?: try {
                    setForeground(foregroundInfo())
                    true
                } catch (e: IllegalStateException) {
                    // Not allowed from the background on newer Android.
                    Log.i(TAG, "no foreground service: ${e.javaClass.simpleName}")
                    false
                }.also { shown = it }
                (foreground || !upload).also { transferred = it }
            })
            if (transferred) {
                // A fresh run carries on without the notification.
                wait?.let { ConversionScheduler.later(applicationContext, it) }
                return Result.success()
            }
            if (wait == null) return Result.success()
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

    internal companion object {
        const val KEY_WAKE_ONLY = "wake_only"
        const val TAG = "ConversionWorker"
        const val CHANNEL_ID = "quire-pdf-conversion"
        const val NOTIFICATION_ID = 4203
        const val IN_PROCESS_WAIT_MS = 60_000L

        /** Background work is stopped after ten minutes; hand over to a fresh run before that. */
        const val MAX_RUN_MS = 9 * 60_000L
    }
}

/**
 * Real runs go under one name, one after the other, so none is ever replaced half way through a
 * transfer. A later run is a timer under its own name that only queues one of them.
 */
object ConversionScheduler {
    const val UNIQUE_NOW = "pdf-conversion"
    const val UNIQUE_LATER = "pdf-conversion-later"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * A run as soon as there is a network; queued behind a run already going. [expedited] when the
     * reader just asked for something, so it starts while the app is still open.
     */
    fun now(context: Context, expedited: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>().setConstraints(online).apply {
            if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }.build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Replacing a timer is harmless: it never runs a step itself. */
    fun later(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setConstraints(online)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ConversionWorker.KEY_WAKE_ONLY to true))
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
