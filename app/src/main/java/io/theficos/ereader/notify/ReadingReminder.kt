package io.theficos.ereader.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.theficos.ereader.EReaderApp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.di.AppContainer
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** When the daily reminder comes. Pure, so the clock and zone are the caller's. */
internal object ReminderSchedule {
    /** A run this early still counts for the coming slot: WorkManager may start a little before it. */
    val EARLY: Duration = Duration.ofMinutes(10)

    /** A slot missed by more than this (phone off, Doze) is skipped rather than shown late. */
    val LATE: Duration = Duration.ofHours(3)

    /**
     * The first reminder slot strictly after [now], on one of [days] (every day when empty). A time
     * that a spring-forward gap skips moves forward by the gap, as [ZonedDateTime.of] does.
     */
    fun nextSlot(now: ZonedDateTime, time: LocalTime, days: Set<DayOfWeek>): ZonedDateTime {
        val allowed = days.ifEmpty { DayOfWeek.entries.toSet() }
        var date = now.toLocalDate()
        while (true) {
            val slot = ZonedDateTime.of(date, time, now.zone)
            if (slot.isAfter(now) && slot.dayOfWeek in allowed) return slot
            date = date.plusDays(1)
        }
    }

    /**
     * The slot a run at [now] stands for, or null when none is near: the latest slot no later
     * than [now] + [EARLY], if [now] is at most [LATE] past it. Yesterday's slot counts, so a
     * 23:55 reminder that runs at 00:05 still belongs to the day it was meant for.
     */
    fun dueSlot(now: ZonedDateTime, time: LocalTime, days: Set<DayOfWeek>): ZonedDateTime? {
        val allowed = days.ifEmpty { DayOfWeek.entries.toSet() }
        val horizon = now.plus(EARLY)
        return listOf(now.toLocalDate(), now.toLocalDate().minusDays(1))
            .map { ZonedDateTime.of(it, time, now.zone) }
            .filter { !it.isAfter(horizon) && it.dayOfWeek in allowed }
            .maxOrNull()
            ?.takeIf { !now.isAfter(it.plus(LATE)) }
    }

    /** True when the reader read on the slot's own day, so the reminder can stay away. */
    fun readOnSlotDay(slot: ZonedDateTime, lastReadAt: Instant?): Boolean =
        lastReadAt != null && lastReadAt.atZone(slot.zone).toLocalDate() == slot.toLocalDate()

    /**
     * At app start: whether the booked run ([booked], null when none is known) should be replaced.
     * True when there is none, when it falls at another local time than [time] here (the phone
     * changed time zone: a 21:00 Rome booking would come at 05:00 in Tokyo), or when it is so long
     * overdue that the chain must have broken.
     */
    fun rebookOnStart(booked: Instant?, now: ZonedDateTime, time: LocalTime): Boolean =
        booked == null ||
            booked.atZone(now.zone).toLocalTime() != time ||
            booked.isBefore(now.toInstant().minus(LATE).minus(EARLY))
}

/**
 * Keeps one pending run, timed to the next slot. Each run schedules the next one itself (the
 * new work waits for the running one to finish), so the time stays put instead of drifting the
 * way a 24-hour periodic job does. WorkManager keeps the run across reboots and app updates; app
 * start books it again if the chain broke or the phone moved to another time zone.
 *
 * No exact alarms: under Doze or App Standby the run can come late, and one more than [LATE]
 * behind is skipped. The setting says so.
 */
internal object ReminderScheduler {
    private const val UNIQUE = "reading-reminder"

    /** The time or days changed, or the reminder was switched on: start over from now. */
    fun reschedule(context: Context, store: NotificationPreferencesStore) =
        enqueue(context, store, ExistingWorkPolicy.REPLACE)

    /** App start: book a run unless the one booked is still right. */
    fun ensureScheduled(context: Context, store: NotificationPreferencesStore) {
        val settings = store.settings.value
        if (ReminderSchedule.rebookOnStart(store.reminderBookedFor, ZonedDateTime.now(), settings.reminderTime)) {
            enqueue(context, store, ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * From inside a run: the next one, after this one. Counted from [ReminderSchedule.EARLY] ahead,
     * so a run that came a few minutes early and showed the reminder does not book the same slot.
     */
    fun scheduleNext(context: Context, store: NotificationPreferencesStore) =
        enqueue(context, store, ExistingWorkPolicy.APPEND_OR_REPLACE, ReminderSchedule.EARLY)

    fun cancel(context: Context, store: NotificationPreferencesStore) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
        store.reminderBookedFor = null
    }

    private fun enqueue(
        context: Context,
        store: NotificationPreferencesStore,
        policy: ExistingWorkPolicy,
        skip: Duration = Duration.ZERO,
    ) {
        val settings = store.settings.value
        val now = ZonedDateTime.now()
        val slot = ReminderSchedule.nextSlot(now.plus(skip), settings.reminderTime, settings.reminderDays)
        val request = OneTimeWorkRequestBuilder<ReadingReminderWorker>()
            .setInitialDelay(Duration.between(now, slot).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE, policy, request)
        store.reminderBookedFor = slot.toInstant()
    }
}

/** The reminder's own settings: a change to any of them books the chain again from the settings screen. */
private val NotificationSettings.reminderPart get() = Triple(reminder, reminderTime, reminderDays)

/** Shows the reminder when its slot is due and the reader has not read that day, then books the next. */
class ReadingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? EReaderApp)?.container ?: return Result.success()
        val store = container.notificationPreferencesStore
        val settings = store.settings.value
        if (!settings.reminder) return Result.success()
        try {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val slot = ReminderSchedule.dueSlot(now, settings.reminderTime, settings.reminderDays)
            val lastRead = store.lastRead
            if (slot != null && !ReminderSchedule.readOnSlotDay(slot, lastRead?.at)) {
                val book = lastRead?.bookId?.let { currentBook(container, it) }
                Notifier.showReminder(applicationContext, book?.id, book?.title)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed reminder must not break the chain of reminders after it.
            Log.w(TAG, "Reading reminder run failed", e)
        }
        // Switched off, or the time or days changed while this ran: the settings screen has
        // already cancelled or booked the chain again, and booking here too would double it.
        if (store.settings.value.reminderPart == settings.reminderPart) {
            ReminderScheduler.scheduleNext(applicationContext, store)
        }
        return Result.success()
    }

    /** The book last opened here, while it is still in the library and neither finished nor abandoned. */
    private suspend fun currentBook(container: AppContainer, bookId: Long): Document? {
        val progress = container.progressRepository.get(bookId)
        if (progress?.finishedAt != null || progress?.abandonedAt != null) return null
        return container.documentRepository.findById(bookId)
    }

    private companion object {
        const val TAG = "ReadingReminder"
    }
}
