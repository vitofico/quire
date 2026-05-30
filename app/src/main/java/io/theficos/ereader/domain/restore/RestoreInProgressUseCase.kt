package io.theficos.ereader.domain.restore

import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.library.LibraryItemResponse
import io.theficos.ereader.data.sync.ProgressItemDto

/** Outcome counters for one restore run, rendered as a snackbar summary. */
data class RestoreSummary(
    val requested: Int,
    val downloaded: Int,
    val skippedExisting: Int,
    val skippedUnfetchable: Int,
    val failed: Int,
)

/** One book selected for restore: an in-progress position joined to its mirror row. */
data class RestoreCandidate(
    val progress: ProgressItemDto,
    val opdsHref: String,
    val title: String,
    val authors: List<String>,
) {
    val identity: DocumentIdentity
        get() = DocumentIdentity(
            metadataId = progress.document.metadataId,
            contentHash = progress.document.contentHash,
            identityHashVersion = progress.document.identityHashVersion,
        )
}

/**
 * Best-effort restore of in-progress books after a fresh install / data wipe.
 *
 * Collaborators are injected as suspend lambdas so the join/filter logic is
 * unit-testable without OkHttp/Room. Real wiring lives in [AppContainer].
 *
 * Flow: pull mirror -> pull progress -> keep in-progress (not finished, not
 * abandoned, percent > [minPercentExclusive]) -> join on content_hash for the
 * download href -> per book: skip if unfetchable or already present, else
 * download+insert -> finally apply the pulled positions so restored books open
 * where the user left off.
 */
class RestoreInProgressUseCase(
    private val fetchLibraryItems: suspend () -> List<LibraryItemResponse>,
    private val fetchInProgress: suspend () -> List<ProgressItemDto>,
    private val isPresent: suspend (DocumentIdentity) -> Boolean,
    private val downloadAndInsert: suspend (RestoreCandidate) -> Unit,
    private val applyPositions: suspend (List<ProgressItemDto>) -> Unit,
    private val minPercentExclusive: Double = 0.0,
) {
    suspend fun run(): RestoreSummary {
        val mirrorByHash = fetchLibraryItems().associateBy { it.contentHash }
        val inProgress = fetchInProgress().filter {
            it.finishedAt == null && it.abandonedAt == null && it.percent > minPercentExclusive
        }
        val candidates = inProgress.mapNotNull { p ->
            val item = mirrorByHash[p.document.contentHash] ?: return@mapNotNull null
            val href = item.opdsHref ?: return@mapNotNull null
            RestoreCandidate(progress = p, opdsHref = href, title = item.title, authors = item.authors)
        }

        var downloaded = 0
        var skippedExisting = 0
        var skippedUnfetchable = 0
        var failed = 0
        for (c in candidates) {
            when {
                !isHttp(c.opdsHref) -> skippedUnfetchable++
                isPresent(c.identity) -> skippedExisting++
                else -> try {
                    downloadAndInsert(c)
                    downloaded++
                } catch (t: Throwable) {
                    failed++
                }
            }
        }

        // Restore positions for every in-progress item; items whose document
        // isn't present are silently skipped inside applyPositions. Best-effort:
        // a failure here must not flip an otherwise-successful restore.
        runCatching { applyPositions(inProgress) }

        return RestoreSummary(
            requested = candidates.size,
            downloaded = downloaded,
            skippedExisting = skippedExisting,
            skippedUnfetchable = skippedUnfetchable,
            failed = failed,
        )
    }

    private fun isHttp(href: String): Boolean =
        href.startsWith("http://") || href.startsWith("https://")
}
