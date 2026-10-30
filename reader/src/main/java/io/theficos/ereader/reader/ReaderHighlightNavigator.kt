package io.theficos.ereader.reader

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** One fragment's exact highlight navigation; transient staging positions are not bookmarks. */
interface HighlightNavigator {
    val isAlive: Boolean
    val safeToPublish: Boolean
    val currentLocator: Locator?
    suspend fun jump(locator: Locator, isCurrent: () -> Boolean): Boolean
    fun viewportChanged() {}
}

class ReaderHighlightNavigator(
    context: Context,
    private val navigator: EpubNavigatorFragment,
    parentScope: CoroutineScope,
) : HighlightNavigator {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val anchors = StructuralSelectionAnchors(context)
    private val pages = MutableStateFlow<ReadyPage?>(null)
    private var closed = false
    private var sequence = 0L
    private val transaction = HighlightJumpTransaction(object : HighlightJumpDriver {
        override val isAlive get() = !closed && navigator.isAdded
        override suspend fun origin(): Locator? {
            awaitPage(navigator.currentLocator.value.href.toString())
            return anchors.viewport(navigator)
        }
        override suspend fun show(locator: Locator) = this@ReaderHighlightNavigator.show(locator, restoringOrigin = false)
        override suspend fun restore(locator: Locator) = this@ReaderHighlightNavigator.show(locator, restoringOrigin = true)
    })

    private suspend fun show(locator: Locator, restoringOrigin: Boolean): Boolean {
        if (navigator.currentLocator.value.href != locator.href) {
            if (!navigator.go(resourceStart(locator), animated = false)) return false
        }
        awaitPage(locator.href.toString())
        val before = sequence
        val result = anchors.navigate(navigator, locator, apply = true, restoringOrigin = restoringOrigin) ?: return false
        if (!result.available) return false
        // Native paging must observe the JS scroll before success can become persisted progress.
        if (result.moved || !result.visible) awaitPage(locator.href.toString(), before)
        return navigator.currentLocator.value.href == locator.href &&
            anchors.navigate(navigator, locator, apply = false, restoringOrigin = restoringOrigin)?.let { it.available && it.visible } == true
    }

    override val isAlive get() = !closed && navigator.isAdded
    override val safeToPublish get() = transaction.safeToPublish
    override val currentLocator get() = if (isAlive) navigator.currentLocator.value else null

    fun pageReady(locator: Locator) {
        if (closed || navigator.currentLocator.value.href != locator.href) return
        pages.value = ReadyPage(++sequence, locator.href.toString())
    }

    override fun viewportChanged() { pages.value = null }

    override suspend fun jump(locator: Locator, isCurrent: () -> Boolean): Boolean {
        val work = scope.async { transaction.jump(locator, isCurrent) }
        return try { work.await() } catch (cancelled: CancellationException) {
            work.cancel()
            // Await the rollback before the caller can uncover the book or resume progress writes.
            withContext(NonCancellable) { work.join() }
            throw cancelled
        }
    }

    fun close() { closed = true; scope.cancel() }

    private suspend fun awaitPage(href: String, after: Long = -1L) {
        pages.first { it != null && it.href == href && it.sequence > after }
    }

    private data class ReadyPage(val sequence: Long, val href: String)
}

internal interface HighlightJumpDriver {
    val isAlive: Boolean
    suspend fun origin(): Locator?
    suspend fun show(locator: Locator): Boolean
    suspend fun restore(locator: Locator): Boolean = show(locator)
}

/** Serializes staging and rollback, including cancellation and replacement requests. */
internal class HighlightJumpTransaction(
    private val driver: HighlightJumpDriver,
    private val timeoutMs: Long = 10_000L,
    private val rollbackTimeoutMs: Long = 5_000L,
) {
    private val mutex = Mutex()
    var safeToPublish: Boolean = true
        private set

    suspend fun jump(target: Locator, isCurrent: () -> Boolean): Boolean = mutex.withLock {
        // The current page is unverified after failed rollback. Only a new navigator can
        // establish an origin again; retry must not bless that staged page as a bookmark.
        if (!safeToPublish || !isHighlightDestination(target)) return@withLock false
        var origin: Locator? = null
        var moved = false
        var success = false
        try {
            withTimeout(timeoutMs) {
                if (!driver.isAlive || !isCurrent()) return@withTimeout false
                origin = driver.origin() ?: return@withTimeout false
                if (!driver.isAlive || !isCurrent()) return@withTimeout false
                safeToPublish = false
                moved = true
                success = driver.show(target) && driver.isAlive && isCurrent()
                success
            }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            safeToPublish = if (success) true else if (moved && driver.isAlive) {
                withContext(NonCancellable) {
                    try { withTimeout(rollbackTimeoutMs) { driver.restore(checkNotNull(origin)) } }
                    catch (_: Exception) { false }
                }
            } else !moved
        }
    }
}

/** Chapter staging never gives Readium a quote, a selector, or an old font-dependent position. */
internal fun resourceStart(locator: Locator): Locator = locator.copy(
    text = Locator.Text(),
    locations = Locator.Locations(progression = 0.0),
)
