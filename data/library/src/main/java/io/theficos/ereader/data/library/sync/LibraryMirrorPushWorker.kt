package io.theficos.ereader.data.library.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibrarySyncEntry
import io.theficos.ereader.data.library.LibrarySyncRequest
import io.theficos.ereader.data.library.LibrarySyncStatus
import io.theficos.ereader.data.library.parseAuthors
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * WorkManager job that POSTs the user's full library mirror to
 * `POST /library/v1/sync` — Phase 0 task A-4.
 *
 * Trigger model (callers in [LibraryMirrorPushScheduler]):
 * - **App start** — one-time expedited (with non-expedited fallback so
 *   quota exhaustion never silently drops the sync).
 * - **After library changes (sideload import)** — one-time. Callers can
 *   use [LibraryMirrorPushScheduler.enqueueAfterLibraryChange]. Wiring
 *   from A-3's `SideloadImporter` is deferred to reconciliation time
 *   because A-3 lives on a parallel branch.
 * - **Daily periodic** — best-effort heartbeat with `NetworkType.CONNECTED`.
 *
 * Failure semantics:
 * - **No credentials** → `Result.success()` no-op. Don't keep retrying
 *   when there's no account to push under.
 * - **401** → `Result.failure()`. WorkManager won't retry; user needs to
 *   re-auth (the next app-start enqueue will pick up new credentials).
 * - **403 / 4xx (other than 401/429)** → log + drop the offending chunk
 *   and continue with the rest of the batches. Returns `Result.success()`
 *   if no chunk forced a retry. The server explicitly rejected this
 *   payload; retrying forever just spams logs.
 * - **429 / 5xx / IOException** → `Result.retry()`. WorkManager handles
 *   exponential backoff. We do not honour `Retry-After` headers in v1.
 *
 * Atomicity & ordering:
 * - The library is read as a single Room snapshot (`observeAll().first()`).
 *   A sideload finishing mid-job doesn't disturb the in-flight snapshot —
 *   it enqueues a follow-up sync via the library-change hook.
 * - Entries are sorted by `identity_hash` (stable string ordering) so
 *   replays send the same chunk boundaries. Identical chunks make replay
 *   safe under the server's diff-skip semantics.
 * - `last_seen_at` is computed once per run and reused across all chunks
 *   so logs and server-side debugging see one cursor per run rather than
 *   per chunk.
 *
 * Privacy: this worker logs counts, HTTP status codes, chunk indices, and
 * an 8-char hash prefix only. Never titles, authors, file paths, or full
 * identity hashes.
 */
class LibraryMirrorPushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = LibraryMirrorPushDependencies.holder ?: run {
            // DI not initialised (Application.onCreate() not run yet, or
            // process restart between WorkManager scheduling and execution
            // in a way that lost the holder). Failing here is preferable
            // to retrying forever against a missing dependency.
            Log.w(TAG, "doWork: dependencies not initialised")
            return Result.failure()
        }

        // Snapshot credentials once — mid-run credential changes (logout
        // etc.) should not flip the worker's behaviour part-way through.
        val hasAccount = deps.credentials.hasAccount()
        if (!hasAccount) {
            Log.i(TAG, "doWork: no account configured; skipping push")
            return Result.success()
        }

        val runStartedAt = Instant.now()
        val lastSeenAt = ISO_FORMATTER.format(runStartedAt)

        // Snapshot the library as a single Room read. `.first()` on the
        // Flow returns the current value immediately; we do not stay
        // subscribed across chunks (a mid-run library change would
        // otherwise produce inconsistent chunks).
        val docs: List<DocumentEntity> = deps.dao.observeAll().first()
        if (docs.isEmpty()) {
            Log.i(TAG, "doWork: library is empty; nothing to push")
            return Result.success()
        }

        // Stable order across replays. The diff-skip in the server's
        // `_row_matches_payload` check means an unchanged chunk replay is
        // a free no-op for the DB — but only if we send the same chunk
        // boundaries each time. Sorting by identity_hash gives us that.
        val sorted = docs.sortedBy { it.contentHash }
        val chunks = sorted.chunked(MAX_BATCH_SIZE)
        Log.i(
            TAG,
            "doWork: starting push books=${sorted.size} chunks=${chunks.size} runStartedAt=$lastSeenAt",
        )

        var droppedChunks = 0
        for ((index, chunk) in chunks.withIndex()) {
            val payload = LibrarySyncRequest(items = chunk.map { it.toSyncEntry(lastSeenAt) })
            val chunkLabel = "chunk=${index + 1}/${chunks.size} size=${chunk.size}"
            try {
                val summary = deps.client.syncBatch(payload)
                Log.i(
                    TAG,
                    "doWork: $chunkLabel ok received=${summary.received} processed=${summary.processed} " +
                        "created=${summary.created} updated=${summary.updated} reactivated=${summary.reactivated} " +
                        "deleted=${summary.deleted} skipped=${summary.skipped} " +
                        "missing_deleted=${summary.missingDeleted}",
                )
            } catch (e: LibraryHttpException) {
                when {
                    e.code == HTTP_UNAUTHORIZED -> {
                        Log.w(TAG, "doWork: $chunkLabel 401; failing without retry", e)
                        return Result.failure()
                    }
                    e.code == HTTP_TOO_MANY_REQUESTS || e.code in HTTP_5XX_RANGE -> {
                        // Transient: bail out and let WorkManager retry
                        // the whole run with exponential backoff. We do
                        // NOT continue to subsequent chunks because
                        // there's no server signal that they'd succeed
                        // and a partial-progress retry double-pushes the
                        // earlier chunks (harmless under diff-skip, but
                        // wasted bytes).
                        Log.w(TAG, "doWork: $chunkLabel code=${e.code}; scheduling retry", e)
                        return Result.retry()
                    }
                    e.code in HTTP_4XX_RANGE -> {
                        // The server explicitly rejected this chunk. No
                        // retry — keep going with subsequent chunks so a
                        // single poison entry doesn't block the rest of
                        // the library from syncing.
                        Log.w(
                            TAG,
                            "doWork: $chunkLabel rejected code=${e.code}; dropping chunk and continuing",
                            e,
                        )
                        droppedChunks += 1
                    }
                    else -> {
                        // Defensive: a non-2xx that didn't fit any known
                        // bucket. Treat as transient.
                        Log.w(TAG, "doWork: $chunkLabel unexpected code=${e.code}; scheduling retry", e)
                        return Result.retry()
                    }
                }
            } catch (e: IOException) {
                // Network failure, TLS error, DNS, etc. The current
                // chunk's bytes may or may not have reached the server;
                // future runs send the full mirror again, so a duplicate
                // POST is idempotent.
                Log.w(TAG, "doWork: $chunkLabel network failure; scheduling retry", e)
                return Result.retry()
            }
        }

        if (droppedChunks > 0) {
            Log.w(TAG, "doWork: complete with droppedChunks=$droppedChunks; not retrying")
        }
        return Result.success()
    }

    private fun DocumentEntity.toSyncEntry(lastSeenAt: String): LibrarySyncEntry =
        LibrarySyncEntry(
            identityHash = contentHash,
            identityHashVersion = identityHashVersion,
            status = LibrarySyncStatus.PRESENT,
            lastSeenAt = lastSeenAt,
            metadataId = metadataId,
            title = title,
            authors = parseAuthors(author),
            seriesName = seriesName,
            seriesIndex = seriesIndex,
            // Not stored on DocumentEntity in Phase 0 — wire spec allows
            // them to be null/empty. Server treats absent metadata
            // fields as "no value", same as the per-item PUT endpoint.
            isbn = null,
            language = null,
            subjects = emptyList(),
            opdsHref = downloadUrl,
        )

    private companion object {
        const val TAG = "LibraryMirrorPush"
        // Mirrors `library_sync_max_items` (default 500) in the server's
        // Settings — we chunk client-side rather than catching a 422 on
        // oversize batches.
        const val MAX_BATCH_SIZE = 500
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_4XX_RANGE = 400..499
        val HTTP_5XX_RANGE = 500..599
        // ISO-8601 with explicit `Z` (UTC). Matches Pydantic's tz-aware
        // parse rule on the server side.
        val ISO_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ISO_INSTANT
    }
}

/**
 * Process-wide DI holder for [LibraryMirrorPushWorker]. Set once in
 * `AppContainer.init`; the worker reads it from each `doWork` call. This
 * mirrors `SyncDependencies` over in `:data:sync` so the two
 * WorkManager-driven workers share a single bootstrapping pattern.
 *
 * `CredentialsProvider` is a tiny indirection so tests can stub the
 * credential check without instantiating `CalibreCredentialStore` (which
 * touches the Android KeyStore).
 */
object LibraryMirrorPushDependencies {
    @Volatile var holder: Holder? = null
    data class Holder(
        val client: LibraryClient,
        val dao: DocumentDao,
        val credentials: CredentialsProvider,
    )
}

/**
 * Minimal credential surface the worker needs — just "is an account
 * configured right now". The shared `OkHttpClient` already injects the
 * Authorization header from the same store, so this worker doesn't need
 * to read the password itself.
 */
fun interface CredentialsProvider {
    fun hasAccount(): Boolean
}
