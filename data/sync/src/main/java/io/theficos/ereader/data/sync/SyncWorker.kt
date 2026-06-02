package io.theficos.ereader.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * Required for expedited work on API < 31. There, WorkManager backs an
     * expedited request with a foreground service and calls this to obtain
     * the notification to display; the default `CoroutineWorker`
     * implementation throws `IllegalStateException("Not implemented")`,
     * which crashed the app on Android 9–11 (issue #78). On API 31+
     * expedited work uses JobScheduler and this is never called.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(applicationContext)

    private fun createForegroundInfo(context: Context): ForegroundInfo {
        // minSdk is 26, so the channel API is always available.
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Syncing reading progress")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val deps = SyncDependencies.holder ?: run {
            Log.w(TAG, "doWork: dependencies not initialised")
            return Result.failure()
        }
        Log.i(TAG, "doWork: starting sync")
        val outcome = deps.orchestrator.runOnce()
        Log.i(TAG, "doWork: outcome=$outcome")
        return when (outcome) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NetworkFailure -> Result.retry()
            is SyncResult.HttpFailure -> Result.retry()
            is SyncResult.Unauthorized -> Result.failure()
        }
    }

    private companion object {
        const val TAG = "QuireSync"
        const val CHANNEL_ID = "quire-sync"
        const val NOTIFICATION_ID = 4201
    }
}

object SyncDependencies {
    @Volatile var holder: Holder? = null
    data class Holder(val orchestrator: SyncOrchestrator)
}

object SyncEnqueuer {
    private const val UNIQUE_NAME = "quire-progress-sync"

    /**
     * Enqueue a sync.
     *
     * @param replaceExisting `true` for user-initiated "Sync now" — preempts any pending or
     *  backed-off retry of the same work so the tap actually does something. `false` for
     *  ambient triggers (library resume, reader pause), where deduping is fine.
     */
    fun enqueue(context: Context, expedited: Boolean = false, replaceExisting: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, policy, req)
    }
}
