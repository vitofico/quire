package io.theficos.ereader.notify

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.StateFlow

/**
 * The switches behind Settings → Notifications and quiet. Turning a feature on or off also
 * schedules or cancels its background work; with every feature off nothing is scheduled.
 * Permissions are asked by the settings screen before it calls these.
 */
class NotificationFeatures(
    context: Context,
    private val store: NotificationPreferencesStore,
    val quietWhileReading: QuietWhileReading,
) {
    private val appContext = context.applicationContext

    val settings: StateFlow<NotificationSettings> = store.settings

    /** On process start: put Do Not Disturb back if a read was cut short, and keep switched-on work booked. */
    fun onAppStart() {
        quietWhileReading.restoreLeftover()
        val current = store.settings.value
        if (current.newBooks) NewBooksScheduler.schedule(appContext)
        if (current.reminder) ReminderScheduler.ensureScheduled(appContext, current)
    }

    fun setNewBooks(enabled: Boolean) {
        store.setNewBooks(enabled)
        if (enabled) NewBooksScheduler.schedule(appContext) else NewBooksScheduler.cancel(appContext)
    }

    fun setReminder(enabled: Boolean) {
        store.setReminder(enabled)
        if (enabled) ReminderScheduler.reschedule(appContext, store.settings.value) else ReminderScheduler.cancel(appContext)
    }

    fun setReminderTime(time: LocalTime) {
        store.setReminderTime(time)
        if (store.settings.value.reminder) ReminderScheduler.reschedule(appContext, store.settings.value)
    }

    fun setReminderDays(days: Set<DayOfWeek>) {
        if (days.isEmpty()) return
        store.setReminderDays(days)
        if (store.settings.value.reminder) ReminderScheduler.reschedule(appContext, store.settings.value)
    }

    fun setQuietWhileReading(enabled: Boolean) = quietWhileReading.setEnabled(enabled)
}
