package io.theficos.ereader.notify

import android.content.Context
import io.theficos.ereader.auth.SavedCatalog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The switches behind Settings → Notifications and quiet, and the reader's two hooks. Turning a
 * feature on or off also schedules or cancels its background work; with every feature off nothing
 * is scheduled. Permissions are asked by the settings screen before it calls these.
 */
class NotificationFeatures(
    context: Context,
    private val store: NotificationPreferencesStore,
    private val quiet: QuietWhileReading,
    private val catalogs: StateFlow<List<SavedCatalog>>,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var catalogWatch: Job? = null

    val settings: StateFlow<NotificationSettings> = store.settings

    /** On process start: put Do Not Disturb back if a read was cut short, and keep switched-on work booked. */
    fun onAppStart() {
        quiet.restoreLeftover()
        val current = store.settings.value
        if (current.newBooks) watchCatalogs()
        if (current.reminder) ReminderScheduler.ensureScheduled(appContext, store)
    }

    /** A reader screen ([key], its back-stack entry) came on screen with [bookId]. */
    fun onReaderShown(key: String, bookId: Long) {
        // What the reading reminder goes by: a book actually opened here, not progress writes
        // such as marking a book finished or abandoned.
        store.lastRead = LastRead(Instant.now(), bookId)
        quiet.onReaderShown(key)
    }

    fun onReaderHidden(key: String) = quiet.onReaderHidden(key)

    /** Saved catalogs the last check found no newest-books feed in. Reads a file: not on the main thread. */
    fun catalogsWithoutLatest(): Set<String> = store.catalogsWithoutLatest()

    internal fun quietSafetyCheck() = quiet.onSafetyCheck()

    fun setNewBooks(enabled: Boolean) {
        store.setNewBooks(enabled)
        if (enabled) {
            watchCatalogs()
        } else {
            catalogWatch?.cancel()
            catalogWatch = null
            NewBooksScheduler.cancel(appContext)
        }
    }

    fun setReminder(enabled: Boolean) {
        store.setReminder(enabled)
        if (enabled) ReminderScheduler.reschedule(appContext, store) else ReminderScheduler.cancel(appContext, store)
    }

    fun setReminderTime(time: LocalTime) {
        store.setReminderTime(time)
        if (store.settings.value.reminder) ReminderScheduler.reschedule(appContext, store)
    }

    fun setReminderDays(days: Set<DayOfWeek>) {
        if (days.isEmpty()) return
        store.setReminderDays(days)
        if (store.settings.value.reminder) ReminderScheduler.reschedule(appContext, store)
    }

    fun setQuietWhileReading(enabled: Boolean) = quiet.setEnabled(enabled)

    /** The 12-hour check runs only while there is a catalog to check: none saved, or signed out, cancels it. */
    private fun watchCatalogs() {
        if (catalogWatch?.isActive == true) return
        catalogWatch = scope.launch {
            catalogs.map { it.isNotEmpty() }.distinctUntilChanged().collect { any ->
                if (any) NewBooksScheduler.schedule(appContext) else NewBooksScheduler.cancel(appContext)
            }
        }
    }
}
