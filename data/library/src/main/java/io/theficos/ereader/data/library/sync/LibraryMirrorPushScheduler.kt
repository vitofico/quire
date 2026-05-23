package io.theficos.ereader.data.library.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling surface for [LibraryMirrorPushWorker] — Phase 0
 * task A-4.
 *
 * Three trigger entrypoints:
 * - [enqueueOneTime] — used at app start (with `expedited = true`) and
 *   for ad-hoc "sync now" semantics. Coalesces via unique work so
 *   concurrent triggers don't fire overlapping workers.
 * - [enqueueAfterLibraryChange] — alias for a non-expedited one-time
 *   push. Called from any code path that materially changes the local
 *   library (sideload import, future bulk-delete flow). Sideload
 *   integration with A-3 is deferred to reconciliation time.
 * - [enqueuePeriodic] — daily heartbeat. Best-effort under Doze /
 *   battery-optimization buckets; the daily cadence is sufficient
 *   because the worker is fully idempotent (a no-op replay just bumps
 *   the server's `skipped` counter and returns).
 */
object LibraryMirrorPushScheduler {

    const val UNIQUE_ONE_TIME = "library-mirror-push-now"
    const val UNIQUE_PERIODIC = "library-mirror-push-periodic"

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Enqueue a one-time push.
     *
     * `KEEP` policy: if a previous one-time push is still pending or
     * running, leave it alone — the new trigger reads the same Room
     * snapshot at execution time, so kicking off a second worker would
     * just duplicate the POST.
     *
     * `expedited = true` opts into [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]
     * so quota-exhaustion downgrades gracefully rather than dropping the
     * sync entirely. Use only for triggers where promptness matters
     * (app start), not for ambient triggers.
     */
    fun enqueueOneTime(context: Context, expedited: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<LibraryMirrorPushWorker>()
            .setConstraints(networkConstraints())
            .apply {
                if (expedited) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Convenience entry point for callers that just learned the library
     * changed (e.g. a sideload import landed). Non-expedited because the
     * caller is already on the foreground UI path; promptness is "next
     * available network window" rather than "right now".
     */
    fun enqueueAfterLibraryChange(context: Context) {
        enqueueOneTime(context, expedited = false)
    }

    /**
     * Enqueue the daily periodic push. Idempotent — calling this every
     * `Application.onCreate` is intentional. `KEEP` means a second call
     * is a no-op while the previous periodic registration is alive.
     */
    fun enqueuePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<LibraryMirrorPushWorker>(
            1,
            TimeUnit.DAYS,
        )
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
