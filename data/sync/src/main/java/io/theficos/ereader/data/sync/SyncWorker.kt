package io.theficos.ereader.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

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
