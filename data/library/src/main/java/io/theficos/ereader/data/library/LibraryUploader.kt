package io.theficos.ereader.data.library

import android.util.Log
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends downloaded books to `/library/v1/items` so the server-side Library
 * Stats / catalog can reflect what the user actually has.
 *
 * Trigger model (v1, intentionally minimal):
 * - **App start** — caller invokes [runOnce] from the same scope that fires
 *   `SyncOrchestrator.runOnce()`. Backfills every doc with `librarySyncedAt
 *   IS NULL`, which on the first post-update boot is every doc.
 * - **Post-download** — [CatalogViewModel] calls [enqueueOne] right after the
 *   row is inserted. Fire-and-forget so the download UI doesn't block.
 *
 * Failure handling (each row treated independently):
 * - **401** aborts the entire batch — the caller's credentials are bad and
 *   nothing else will succeed either. No `markLibrarySynced` writes for any
 *   row in this pass.
 * - Any other HTTP error or network failure logs and CONTINUES to the next
 *   row. The row stays `librarySyncedAt = NULL`, so the next pass retries.
 *   No in-uploader retry loop: the next scheduled pass is the retry.
 * - **409** (metadata_id_conflict) is treated like any other non-401 error:
 *   logged and skipped. PR1 identity-aliases will fix this properly; until
 *   then we accept that the row stays "unsynced" forever, which is harmless
 *   (just a no-op on each pass).
 */
class LibraryUploader(
    private val client: LibraryClient,
    private val dao: DocumentDao,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val singleFlight = Mutex()

    /**
     * Walk all unsynced documents, PUT each, mark each successful row.
     *
     * Single-flight via a Mutex: if app-start and post-download fire at the
     * same time, the second call waits for the first to drain before running
     * its own pass. This avoids redundant PUTs without complex coordination.
     */
    suspend fun runOnce(): UploadResult = singleFlight.withLock {
        val unsynced = dao.findUnsyncedToLibrary()
        if (unsynced.isEmpty()) {
            return@withLock UploadResult(attempted = 0, succeeded = 0, abortedOnAuth = false)
        }

        var succeeded = 0
        for (doc in unsynced) {
            val payload = doc.toLibraryItemRequest()
            try {
                client.putItem(payload)
                dao.markLibrarySynced(doc.id, nowMillis())
                succeeded += 1
            } catch (e: LibraryHttpException) {
                if (e.code == HTTP_UNAUTHORIZED) {
                    Log.w(TAG, "401 from /library/v1/items; aborting batch", e)
                    return@withLock UploadResult(
                        attempted = succeeded + 1,
                        succeeded = succeeded,
                        abortedOnAuth = true,
                    )
                }
                Log.w(
                    TAG,
                    "library PUT failed for documentId=${doc.id} (code=${e.code}); will retry on next pass",
                    e,
                )
            } catch (e: Exception) {
                // Network failures, JSON parse errors, etc. Same policy: leave
                // the row unsynced and try the next one.
                Log.w(
                    TAG,
                    "library PUT failed for documentId=${doc.id} (${e.javaClass.simpleName}); will retry on next pass",
                    e,
                )
            }
        }

        UploadResult(
            attempted = unsynced.size,
            succeeded = succeeded,
            abortedOnAuth = false,
        )
    }

    /**
     * Convenience: launch a one-row backfill in the uploader's scope. Used
     * by the download path so a freshly-landed book hits the server promptly
     * without bouncing through the next app-start. Fire-and-forget.
     */
    fun enqueueOne(@Suppress("UNUSED_PARAMETER") documentId: Long): Job = scope.launch {
        // Cheap to just run the whole unsynced pass — there's a Mutex
        // upstream, and the per-row work is gated on `librarySyncedAt IS
        // NULL`, so this naturally picks up the new row plus any earlier
        // failures. Keeps the call shape trivial: no `findById` lookup, no
        // race between insert + enqueue.
        runOnce()
    }

    private fun DocumentEntity.toLibraryItemRequest(): LibraryItemRequest =
        LibraryItemRequest(
            contentHash = contentHash,
            title = title,
            authors = parseAuthors(author),
            metadataId = metadataId,
            seriesName = seriesName,
            seriesIndex = seriesIndex,
            isbn = null,        // v2: parse from OPF
            language = null,    // v2: parse from OPF
            subjects = emptyList(), // v2: parse from OPF
            opdsHref = downloadUrl,
        )

    private companion object {
        const val TAG = "LibraryUploader"
        const val HTTP_UNAUTHORIZED = 401
    }
}

/**
 * Outcome of a single [LibraryUploader.runOnce] pass.
 *
 * - [attempted] counts rows we tried to PUT (including the one that triggered
 *   the 401 abort, if any).
 * - [succeeded] counts rows the server accepted.
 * - [abortedOnAuth] is true iff a 401 short-circuited the batch — caller
 *   typically wants to surface a re-auth prompt.
 */
data class UploadResult(
    val attempted: Int,
    val succeeded: Int,
    val abortedOnAuth: Boolean,
)

/**
 * Split `author` strings the way calibre tends to emit them. Calibre joins
 * multi-author books with " & ", but OPF feeds in the wild also use commas.
 * Empty / whitespace-only fragments are dropped.
 */
internal fun parseAuthors(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',', '&').map { it.trim() }.filter { it.isNotEmpty() }
}
