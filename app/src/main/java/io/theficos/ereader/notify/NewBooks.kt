package io.theficos.ereader.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.theficos.ereader.EReaderApp
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.OpdsFeed
import io.theficos.ereader.data.opds.OpdsHttpException
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.ereader.data.opds.cleartextBlockedMessage
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** At most this many entry ids are remembered per catalog. */
internal const val MAX_REMEMBERED_IDS = 300

/** What one check of a catalog's newest feed found. */
internal data class NewBooksDiff(
    /** Books not seen before, in feed order. Empty on the first check, which only records. */
    val added: List<OpdsPublication>,
    /** Ids to remember for next time: this check's first, then older ones, at most [MAX_REMEMBERED_IDS]. */
    val remembered: List<String>,
)

/** The id a book keeps across fetches: its Atom id, or its download address when the feed has none. */
internal val OpdsPublication.stableId: String get() = entryId ?: epubDownloadHref

/**
 * Compare a fetch of the newest feed with the ids remembered from the last one ([previous], null
 * on the first check). A book that drops off the feed and comes back is not new while it is still
 * remembered.
 */
internal fun diffNewBooks(previous: List<String>?, current: List<OpdsPublication>): NewBooksDiff {
    val currentIds = current.map { it.stableId }
    val remembered = (currentIds + previous.orEmpty()).distinct().take(MAX_REMEMBERED_IDS)
    if (previous == null) return NewBooksDiff(emptyList(), remembered)
    val seen = previous.toHashSet()
    return NewBooksDiff(current.filter { it.stableId !in seen }.distinctBy { it.stableId }, remembered)
}

/**
 * One pass over every saved catalog. Failures stay quiet: a catalog that refuses the login, is
 * gone, or answers with something unreadable is skipped until the next run; a timeout, a dropped
 * connection or a server error asks WorkManager to retry with its usual backoff. Logs carry the
 * catalog id and status codes only, never an address or a login.
 */
internal class NewBooksCheck(
    private val catalogs: () -> List<SavedCatalog>,
    private val fetch: suspend (String) -> OpdsFeed,
    private val store: NotificationPreferencesStore,
    private val notify: (SavedCatalog, List<OpdsPublication>) -> Unit,
) {
    /** False when a catalog failed in a way worth retrying soon. */
    suspend fun run(): Boolean {
        val saved = catalogs()
        val withoutLatest = mutableSetOf<String>()
        var retry = false
        for (catalog in saved) {
            val address = catalog.catalog?.baseUrl?.takeIf { it.isNotBlank() && !catalog.needsLogin } ?: continue
            try {
                val latest = fetch(address).latestHref
                if (latest == null) {
                    withoutLatest += catalog.id
                    continue
                }
                val diff = diffNewBooks(store.seenIds(catalog.id), fetch(latest).publications)
                store.setSeenIds(catalog.id, diff.remembered)
                if (diff.added.isNotEmpty()) notify(catalog, diff.added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpdsHttpException) {
                Log.i(TAG, "catalog=${catalog.id} answered ${e.code}")
                if (e.code == HTTP_TOO_MANY_REQUESTS || e.code >= HTTP_SERVER_ERROR) retry = true
            } catch (e: IOException) {
                // Android refuses plain http outright: that will not change by retrying.
                if (cleartextBlockedMessage(e) == null) retry = true
                Log.i(TAG, "catalog=${catalog.id} unreachable: ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.i(TAG, "catalog=${catalog.id} unreadable: ${e.javaClass.simpleName}")
            }
        }
        store.recordCheck(saved.map { it.id }.toSet(), withoutLatest)
        return !retry
    }

    private companion object {
        const val TAG = "NewBooksCheck"
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * About every 12 hours, on any network with the battery not low. Any network rather than
 * unmetered: a check is a couple of small XML pages per catalog, and waiting for Wi-Fi would hold
 * the news back for days from a reader who is mostly on mobile data.
 */
internal object NewBooksScheduler {
    private const val UNIQUE = "new-books-check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewBooksWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
    }
}

class NewBooksWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? EReaderApp)?.container ?: return Result.success()
        if (!container.notificationPreferencesStore.settings.value.newBooks) return Result.success()
        val check = NewBooksCheck(
            catalogs = { container.credentialStore.catalogs.value },
            fetch = container.opdsClient::fetch,
            store = container.notificationPreferencesStore,
            notify = { catalog, added -> Notifier.showNewBooks(applicationContext, catalog, added) },
        )
        return if (check.run()) Result.success() else Result.retry()
    }
}
