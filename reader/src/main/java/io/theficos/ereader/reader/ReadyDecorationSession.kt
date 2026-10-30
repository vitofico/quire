package io.theficos.ereader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns queued decoration work for exactly one navigator, including work waiting for its page. */
internal class ReadyDecorationSession<T>(
    val navigator: T,
    val generation: Int,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val firstReadyPage = CompletableDeferred<Unit>()
    private val submissions = Mutex()

    var readyHref: String? = null
        private set
    var pendingSubmissions: Int = 0
        private set

    val isReady: Boolean get() = job.isActive && readyHref != null

    fun pageReady(href: String) {
        if (!job.isActive) return
        readyHref = href
        firstReadyPage.complete(Unit)
    }

    suspend fun submit(
        isCurrent: () -> Boolean,
        apply: suspend (T, String) -> Unit,
    ): Boolean {
        pendingSubmissions++
        val submission = scope.async {
            firstReadyPage.await()
            submissions.withLock {
                val href = readyHref
                if (!job.isActive || href == null || !isCurrent()) return@withLock false
                apply(navigator, href)
                true
            }
        }
        try {
            return submission.await()
        } catch (cancelled: CancellationException) {
            submission.cancel()
            // Closing an old navigator releases its callers without cancelling the Activity's
            // capture/reopen coroutine. Cancellation of the caller itself must still propagate.
            currentCoroutineContext().ensureActive()
            if (job.isActive) throw cancelled
            return false
        } finally {
            pendingSubmissions--
        }
    }

    fun close() {
        scope.cancel()
    }
}
