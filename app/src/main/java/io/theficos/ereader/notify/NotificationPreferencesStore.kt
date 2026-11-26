package io.theficos.ereader.notify

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
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
)

val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(21, 0)

/** The preferences file of this phone's own notification state, left out of backups. */
internal const val DEVICE_PREFS = "notification_device"

/** The book last opened in the reader on this device, and when. */
data class LastRead(val at: Instant, val bookId: Long)

/**
 * Three SharedPreferences files, none of them the database:
 * - `notification_prefs`: the switches. Backed up, so a new phone keeps the reader's choices.
 * - `new_books_seen`: entry ids remembered per catalog, and which catalogs have no newest-books
 *   feed. Left out of backups: restored elsewhere it would announce every book added since the
 *   backup, where a fresh baseline is right. Read only by the background check and off the main
 *   thread by the settings screen.
 * - `notification_device`: this phone's own facts (the Do Not Disturb rule Quire created, when a
 *   book was last opened here, when the next reminder is booked, the token notification taps
 *   carry). Left out of backups too.
 */
class NotificationPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
    private val seen by lazy { appContext.getSharedPreferences("new_books_seen", Context.MODE_PRIVATE) }
    private val device = appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    fun setNewBooks(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NEW_BOOKS, enabled).apply()
        // Turning it back on records a fresh baseline instead of reporting everything since.
        if (!enabled) seen.edit().clear().apply()
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
        seen.getString(KEY_SEEN_PREFIX + catalogId, null)?.split('\n')?.filter { it.isNotEmpty() }

    fun setSeenIds(catalogId: String, ids: List<String>) {
        // Atom ids are IRIs and download hrefs are URLs: neither contains a newline.
        seen.edit().putString(KEY_SEEN_PREFIX + catalogId, ids.joinToString("\n")).apply()
    }

    /** Saved catalogs that had no newest-books feed at the last check, by id. Reads a file: not on the main thread. */
    fun catalogsWithoutLatest(): Set<String> = seen.getStringSet(KEY_NO_LATEST, null)?.toSet().orEmpty()

    /** Record which catalogs had no newest-books feed, and forget catalogs that are no longer saved. */
    fun recordCheck(savedIds: Set<String>, withoutLatest: Set<String>) {
        val edit = seen.edit().putStringSet(KEY_NO_LATEST, withoutLatest)
        seen.all.keys
            .filter { it.startsWith(KEY_SEEN_PREFIX) && it.removePrefix(KEY_SEEN_PREFIX) !in savedIds }
            .forEach(edit::remove)
        edit.apply()
    }

    /** The id of the Do Not Disturb rule Quire created on this phone, if any. */
    var quietRuleId: String?
        get() = device.getString(KEY_QUIET_RULE, null)
        set(value) = device.edit().putString(KEY_QUIET_RULE, value).apply()

    /** The book last opened in the reader here; the reading reminder's "already read today" and its book. */
    var lastRead: LastRead?
        get() {
            val at = device.getLong(KEY_LAST_READ_AT, -1L).takeIf { it >= 0 } ?: return null
            return LastRead(Instant.ofEpochMilli(at), device.getLong(KEY_LAST_READ_BOOK, -1L))
        }
        set(value) {
            val edit = device.edit()
            if (value == null) edit.remove(KEY_LAST_READ_AT).remove(KEY_LAST_READ_BOOK)
            else edit.putLong(KEY_LAST_READ_AT, value.at.toEpochMilli()).putLong(KEY_LAST_READ_BOOK, value.bookId)
            edit.apply()
        }

    /** When the pending reminder run is due, as booked; null when none is booked. */
    var reminderBookedFor: Instant?
        get() = device.getLong(KEY_REMINDER_NEXT, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        set(value) {
            val edit = device.edit()
            if (value == null) edit.remove(KEY_REMINDER_NEXT) else edit.putLong(KEY_REMINDER_NEXT, value.toEpochMilli())
            edit.apply()
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
        )
    }

    private companion object {
        const val KEY_NEW_BOOKS = "new_books"
        const val KEY_REMINDER = "reminder"
        const val KEY_REMINDER_MINUTE = "reminder_minute"
        const val KEY_REMINDER_DAYS = "reminder_days"
        const val KEY_QUIET = "quiet_while_reading"
        const val KEY_NO_LATEST = "catalogs_without_latest"
        const val KEY_SEEN_PREFIX = "seen_"
        const val KEY_QUIET_RULE = "quiet_rule_id"
        const val KEY_LAST_READ_AT = "last_read_at"
        const val KEY_LAST_READ_BOOK = "last_read_book"
        const val KEY_REMINDER_NEXT = "reminder_next"
        const val ALL_DAYS = 0b111_1111
    }
}
