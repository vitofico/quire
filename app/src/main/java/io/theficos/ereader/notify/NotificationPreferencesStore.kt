package io.theficos.ereader.notify

import android.annotation.SuppressLint
import android.content.Context
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The three optional features, all off until the reader turns them on. */
data class NotificationSettings(
    val newBooks: Boolean = false,
    val reminder: Boolean = false,
    val reminderTime: LocalTime = DEFAULT_REMINDER_TIME,
    /** Days the reminder may come. Every day unless the reader narrowed it. */
    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val quietWhileReading: Boolean = false,
    /** Saved catalogs that have no newest-books feed to check, by id. */
    val catalogsWithoutLatest: Set<String> = emptySet(),
)

val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(21, 0)

/**
 * The settings and the entry ids remembered per catalog, in SharedPreferences
 * (`notification_prefs`). The quiet-mode marker has a file of its own (`quiet_state`), left out of
 * backups: restored on another phone it would describe a Do Not Disturb change that never happened
 * there. Nothing here touches the database.
 */
class NotificationPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
    private val quietState = context.applicationContext.getSharedPreferences("quiet_state", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    fun setNewBooks(enabled: Boolean) {
        val edit = prefs.edit().putBoolean(KEY_NEW_BOOKS, enabled)
        if (!enabled) {
            // Turning it back on records a fresh baseline instead of reporting everything since.
            prefs.all.keys.filter { it.startsWith(KEY_SEEN_PREFIX) }.forEach(edit::remove)
            edit.remove(KEY_NO_LATEST)
        }
        edit.apply()
        reload()
    }

    fun setReminder(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMINDER, enabled).apply()
        reload()
    }

    fun setReminderTime(time: LocalTime) {
        prefs.edit().putInt(KEY_REMINDER_MINUTE, time.hour * 60 + time.minute).apply()
        reload()
    }

    fun setReminderDays(days: Set<DayOfWeek>) {
        prefs.edit().putInt(KEY_REMINDER_DAYS, days.fold(0) { mask, day -> mask or (1 shl day.ordinal) }).apply()
        reload()
    }

    fun setQuietWhileReading(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET, enabled).apply()
        reload()
    }

    /** Entry ids remembered from the last check of [catalogId], newest first; null before its first check. */
    fun seenIds(catalogId: String): List<String>? =
        prefs.getString(KEY_SEEN_PREFIX + catalogId, null)?.split('\n')?.filter { it.isNotEmpty() }

    fun setSeenIds(catalogId: String, ids: List<String>) {
        // Atom ids are IRIs and download hrefs are URLs: neither contains a newline.
        prefs.edit().putString(KEY_SEEN_PREFIX + catalogId, ids.joinToString("\n")).apply()
    }

    /** Record which catalogs had no newest-books feed, and forget catalogs that are no longer saved. */
    fun recordCheck(savedIds: Set<String>, withoutLatest: Set<String>) {
        val edit = prefs.edit().putStringSet(KEY_NO_LATEST, withoutLatest)
        prefs.all.keys
            .filter { it.startsWith(KEY_SEEN_PREFIX) && it.removePrefix(KEY_SEEN_PREFIX) !in savedIds }
            .forEach(edit::remove)
        edit.apply()
        reload()
    }

    /**
     * The interruption filter Quire set when it silenced the phone, or null when it did not.
     * Written with commit() before the filter changes, so it survives the process dying mid-read.
     */
    fun quietApplied(): Int? =
        if (quietState.contains(KEY_QUIET_APPLIED)) quietState.getInt(KEY_QUIET_APPLIED, 0) else null

    // Synchronous on purpose: the marker must be on disk before Do Not Disturb changes.
    @SuppressLint("ApplySharedPref")
    fun setQuietApplied(filter: Int?) {
        val edit = quietState.edit()
        if (filter == null) edit.remove(KEY_QUIET_APPLIED) else edit.putInt(KEY_QUIET_APPLIED, filter)
        edit.commit()
    }

    private fun reload() {
        _settings.value = load()
    }

    private fun load(): NotificationSettings {
        val minute = prefs.getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_TIME.hour * 60)
        val mask = prefs.getInt(KEY_REMINDER_DAYS, ALL_DAYS)
        return NotificationSettings(
            newBooks = prefs.getBoolean(KEY_NEW_BOOKS, false),
            reminder = prefs.getBoolean(KEY_REMINDER, false),
            reminderTime = LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59)),
            reminderDays = DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet(),
            quietWhileReading = prefs.getBoolean(KEY_QUIET, false),
            catalogsWithoutLatest = prefs.getStringSet(KEY_NO_LATEST, null)?.toSet().orEmpty(),
        )
    }

    private companion object {
        const val KEY_NEW_BOOKS = "new_books"
        const val KEY_REMINDER = "reminder"
        const val KEY_REMINDER_MINUTE = "reminder_minute"
        const val KEY_REMINDER_DAYS = "reminder_days"
        const val KEY_QUIET = "quiet_while_reading"
        const val KEY_QUIET_APPLIED = "quiet_applied_filter"
        const val KEY_NO_LATEST = "catalogs_without_latest"
        const val KEY_SEEN_PREFIX = "seen_"
        const val ALL_DAYS = 0b111_1111
    }
}
