package io.theficos.ereader.ui.stats

import io.theficos.ereader.data.local.db.BookProgressRow
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** A day counts towards a streak with at least this much reading. */
internal const val STREAK_DAY_SECONDS = 5 * 60L

/** Skimming or flipping pages, not reading: such sessions count for time, not for speed. */
internal const val MAX_PLAUSIBLE_WPM = 1_500.0

/** A speed is shown only once this much reading backs it. */
internal const val MIN_SPEED_SECONDS = 5 * 60L
internal const val MIN_SPEED_WORDS = 500L

/** A book's own pace is used for its estimate once it has this much reading behind it. */
internal const val MIN_BOOK_PACE_SECONDS = 10 * 60L

internal const val SPEED_WEEKS = 8

data class BookStats(
    val bookKey: String,
    val title: String,
    val author: String?,
    val seconds: Long,
    val sessions: Int,
    val wordsPerMinute: Double?,
    val lastReadAt: Long,
    /** About how long the rest of the book takes at the reader's pace; null when unknown or done. */
    val secondsLeft: Long?,
)

/** Average speed in the week starting [weekStart], or null with too little reading that week. */
data class WeekSpeed(val weekStart: LocalDate, val wordsPerMinute: Double?)

data class ReadingStats(
    val todaySeconds: Long,
    val weekSeconds: Long,
    val monthSeconds: Long,
    val totalSeconds: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    val booksFinished: Int,
    /** Reading seconds per local day, only days with reading. */
    val daily: Map<LocalDate, Long>,
    /** Reading seconds by hour of day, 0..23. */
    val byHour: List<Long>,
    /** Reading seconds by day of week, Monday first. */
    val byWeekday: List<Long>,
    val wordsPerMinute: Double?,
    val weeklySpeed: List<WeekSpeed>,
    val books: List<BookStats>,
    val today: LocalDate,
    val firstDayOfWeek: DayOfWeek,
) {
    val isEmpty: Boolean get() = books.isEmpty()

    /** Reading seconds for each of the [days] days up to today, oldest first. */
    fun lastDays(days: Int): List<Pair<LocalDate, Long>> =
        (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to (daily[it] ?: 0L) } }

    /** Reading seconds for each of the 12 months up to this one, oldest first. */
    fun lastMonths(): List<Pair<YearMonth, Long>> {
        val totals = daily.entries.groupBy({ YearMonth.from(it.key) }, { it.value }).mapValues { it.value.sum() }
        val now = YearMonth.from(today)
        return (11 downTo 0).map { back -> now.minusMonths(back.toLong()).let { it to (totals[it] ?: 0L) } }
    }
}

/**
 * Everything the Statistics screen shows, from the stored sessions.
 *
 * A session is placed in the local time it was read in, from its own stored offset, so a trip
 * or a change of clocks does not move it. "Today" and "this week" are the device's now, in
 * [zone]. A session that runs past the hour or midnight is split there, pro rata.
 */
fun readingStats(
    sessions: List<ReadingSessionEntity>,
    progress: List<BookProgressRow>,
    wordCounts: Map<String, Long>,
    nowMs: Long,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
): ReadingStats {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val daily = HashMap<LocalDate, Long>()
    val byHour = LongArray(24)
    val byWeekday = LongArray(7)
    for (session in sessions) {
        for ((hourStart, seconds) in hourSlices(session)) {
            val day = hourStart.toLocalDate()
            daily[day] = (daily[day] ?: 0L) + seconds
            byHour[hourStart.hour] += seconds
            byWeekday[day.dayOfWeek.value - 1] += seconds
        }
    }
    val (current, longest) = streaks(daily, today)
    val overall = wordsPerMinute(sessions)
    val progressByBook = progress.associateBy { it.bookKey }
    val books = sessions.groupBy { it.bookKey }.map { (key, rows) ->
        val latest = rows.maxBy { it.startedAt }
        BookStats(
            bookKey = key,
            title = latest.title,
            author = latest.author,
            seconds = rows.sumOf { it.activeSeconds },
            sessions = rows.size,
            wordsPerMinute = wordsPerMinute(rows),
            lastReadAt = latest.endedAt,
            secondsLeft = progressByBook[key]?.let { secondsLeft(it, wordCounts[key], rows, overall) },
        )
    }.sortedByDescending { it.lastReadAt }
    return ReadingStats(
        todaySeconds = daily[today] ?: 0L,
        weekSeconds = daily.filterKeys { !it.isBefore(weekStart) && !it.isAfter(today) }.values.sum(),
        monthSeconds = daily.filterKeys { YearMonth.from(it) == YearMonth.from(today) && !it.isAfter(today) }.values.sum(),
        totalSeconds = sessions.sumOf { it.activeSeconds },
        currentStreak = current,
        longestStreak = longest,
        booksFinished = progress.count { it.finishedAt != null },
        daily = daily,
        byHour = byHour.toList(),
        byWeekday = byWeekday.toList(),
        wordsPerMinute = overall,
        weeklySpeed = (SPEED_WEEKS - 1 downTo 0).map { back ->
            val start = weekStart.minusWeeks(back.toLong())
            val end = start.plusWeeks(1)
            WeekSpeed(start, wordsPerMinute(sessions.filter { localStart(it).toLocalDate().let { d -> !d.isBefore(start) && d.isBefore(end) } }))
        },
        books = books,
        today = today,
        firstDayOfWeek = firstDayOfWeek,
    )
}

private fun localStart(session: ReadingSessionEntity): LocalDateTime =
    LocalDateTime.ofEpochSecond(session.startedAt.floorDiv(1_000), 0, ZoneOffset.ofTotalSeconds(session.utcOffsetSeconds))

/** The session's reading seconds, by the local hour they fell in. */
internal fun hourSlices(session: ReadingSessionEntity): List<Pair<LocalDateTime, Long>> {
    val total = session.activeSeconds
    if (total <= 0) return emptyList()
    val start = localStart(session)
    val slices = ArrayList<Pair<LocalDateTime, Long>>()
    var hour = start.truncatedTo(ChronoUnit.HOURS)
    var cursor = start
    var left = total
    while (left > 0) {
        val next = hour.plusHours(1)
        val seconds = minOf(left, ChronoUnit.SECONDS.between(cursor, next))
        slices += hour to seconds
        left -= seconds
        cursor = next
        hour = next
    }
    return slices
}

/** The current run of streak days (today, or up to yesterday while today is still open), and the longest. */
internal fun streaks(daily: Map<LocalDate, Long>, today: LocalDate): Pair<Int, Int> {
    val days = daily.filterValues { it >= STREAK_DAY_SECONDS }.keys
    var longest = 0
    for (day in days) {
        if (day.minusDays(1) in days) continue
        var length = 1
        while (day.plusDays(length.toLong()) in days) length++
        longest = maxOf(longest, length)
    }
    var cursor = if (today in days) today else today.minusDays(1)
    var current = 0
    while (cursor in days) {
        current++
        cursor = cursor.minusDays(1)
    }
    return current to longest
}

/**
 * Words per minute over [sessions], weighted by time; null until enough reading backs it.
 * Sessions without a word count, with nothing read, or faster than anyone reads are left out.
 */
internal fun wordsPerMinute(sessions: List<ReadingSessionEntity>): Double? {
    var words = 0L
    var seconds = 0L
    for (s in sessions) {
        val w = s.wordsRead ?: continue
        if (w <= 0 || s.activeSeconds <= 0) continue
        if (w * 60.0 / s.activeSeconds > MAX_PLAUSIBLE_WPM) continue
        words += w
        seconds += s.activeSeconds
    }
    if (seconds < MIN_SPEED_SECONDS || words < MIN_SPEED_WORDS) return null
    return words * 60.0 / seconds
}

/**
 * About how long the rest of a book takes: the words left at the book's own pace, or the
 * reader's overall pace while the book has too little reading of its own. Null for a book
 * finished or put aside, without a word count, or before any pace is known.
 */
internal fun secondsLeft(
    progress: BookProgressRow,
    words: Long?,
    bookSessions: List<ReadingSessionEntity>,
    overallWpm: Double?,
): Long? {
    if (progress.finishedAt != null || progress.abandonedAt != null) return null
    if (words == null || words <= 0) return null
    val ownTime = bookSessions.filter { (it.wordsRead ?: 0) > 0 }.sumOf { it.activeSeconds }
    val pace = wordsPerMinute(bookSessions)?.takeIf { ownTime >= MIN_BOOK_PACE_SECONDS } ?: overallWpm ?: return null
    val left = (1.0 - progress.percent.coerceIn(0.0, 1.0)) * words
    return (left / pace * 60).roundToLong()
}
