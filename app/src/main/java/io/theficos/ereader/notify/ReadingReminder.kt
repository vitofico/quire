package io.theficos.ereader.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.theficos.ereader.EReaderApp
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
}

/**
 * Keeps one pending run, timed to the next slot. Each run schedules the next one itself (the
 * new work waits for the running one to finish), so the time stays put instead of drifting the
 * way a 24-hour periodic job does. WorkManager keeps the run across reboots and app updates; app
 * start schedules it again if the chain ever broke.
 */
internal object ReminderScheduler {
    private const val UNIQUE = "reading-reminder"

    /** The time or days changed, or the reminder was switched on: start over from now. */
    fun reschedule(context: Context, settings: NotificationSettings) =
        enqueue(context, settings, ExistingWorkPolicy.REPLACE)

    /** App start: make sure a run is pending, keeping the one there is. */
    fun ensureScheduled(context: Context, settings: NotificationSettings) =
        enqueue(context, settings, ExistingWorkPolicy.KEEP)

    /**
     * From inside a run: the next one, after this one. Counted from [ReminderSchedule.EARLY] ahead,
     * so a run that came a few minutes early and showed the reminder does not book the same slot.
     */
    fun scheduleNext(context: Context, settings: NotificationSettings) =
        enqueue(context, settings, ExistingWorkPolicy.APPEND_OR_REPLACE, ReminderSchedule.EARLY)

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
    }

    private fun enqueue(
        context: Context,
        settings: NotificationSettings,
        policy: ExistingWorkPolicy,
        skip: Duration = Duration.ZERO,
    ) {
        val now = ZonedDateTime.now()
        val slot = ReminderSchedule.nextSlot(now.plus(skip), settings.reminderTime, settings.reminderDays)
        val request = OneTimeWorkRequestBuilder<ReadingReminderWorker>()
            .setInitialDelay(Duration.between(now, slot).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE, policy, request)
    }
}

/** Shows the reminder when its slot is due and the reader has not read that day, then books the next. */
class ReadingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? EReaderApp)?.container ?: return Result.success()
        val settings = container.notificationPreferencesStore.settings.value
        if (!settings.reminder) return Result.success()
        try {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val slot = ReminderSchedule.dueSlot(now, settings.reminderTime, settings.reminderDays)
            if (slot != null) {
                val lastReadAt = container.progressRepository.lastReadAt()?.let(Instant::ofEpochMilli)
                if (!ReminderSchedule.readOnSlotDay(slot, lastReadAt)) {
                    val book = container.progressRepository.currentBookId()
                        ?.let { container.documentRepository.findById(it) }
                    Notifier.showReminder(applicationContext, book?.id, book?.title)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed reminder must not break the chain of reminders after it.
            Log.w(TAG, "Reading reminder run failed", e)
        }
        ReminderScheduler.scheduleNext(applicationContext, settings)
        return Result.success()
    }

    private companion object {
        const val TAG = "ReadingReminder"
    }
}
