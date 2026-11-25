package io.theficos.ereader.ui.stats

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.BookProgressRow
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReadingStatisticsTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun session(
        start: ZonedDateTime,
        minutes: Long,
        book: String = "a",
        words: Long? = null,
        seconds: Long = minutes * 60,
    ): ReadingSessionEntity {
        val startMs = start.toInstant().toEpochMilli()
        return ReadingSessionEntity(
            bookKey = book,
            title = "Book $book",
            author = null,
            startedAt = startMs,
            endedAt = startMs + seconds * 1_000,
            utcOffsetSeconds = start.offset.totalSeconds,
            activeSeconds = seconds,
            startProgression = 0.0,
            endProgression = 0.1,
            progressionRead = 0.1,
            wordsRead = words,
        )
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: ZoneId = rome) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    private fun stats(
        sessions: List<ReadingSessionEntity>,
        now: ZonedDateTime,
        progress: List<BookProgressRow> = emptyList(),
        words: Map<String, Long> = emptyMap(),
        zone: ZoneId = rome,
    ) = readingStats(sessions, progress, words, now.toInstant().toEpochMilli(), zone, DayOfWeek.MONDAY)

    @Test
    fun `a session over midnight is split between the two days`() {
        val s = stats(listOf(session(at(2026, 11, 24, 23, 40), minutes = 50)), now = at(2026, 11, 25, 9))

        assertThat(s.daily[LocalDate.of(2026, 11, 24)]).isEqualTo(20 * 60L)
        assertThat(s.daily[LocalDate.of(2026, 11, 25)]).isEqualTo(30 * 60L)
        assertThat(s.todaySeconds).isEqualTo(30 * 60L)
        assertThat(s.byHour[23]).isEqualTo(20 * 60L)
        assertThat(s.byHour[0]).isEqualTo(30 * 60L)
        assertThat(s.byWeekday[DayOfWeek.TUESDAY.value - 1]).isEqualTo(20 * 60L)
        assertThat(s.byWeekday[DayOfWeek.WEDNESDAY.value - 1]).isEqualTo(30 * 60L)
        assertThat(s.totalSeconds).isEqualTo(50 * 60L)
    }

    @Test
    fun `hour slices follow the clock hours`() {
        val slices = hourSlices(session(at(2026, 11, 25, 9, 45), minutes = 80))

        assertThat(slices).containsExactly(
            LocalDateTime.of(2026, 11, 25, 9, 0) to 15 * 60L,
            LocalDateTime.of(2026, 11, 25, 10, 0) to 60 * 60L,
            LocalDateTime.of(2026, 11, 25, 11, 0) to 5 * 60L,
        ).inOrder()
    }

    @Test
    fun `a session keeps the hour it was read in, across daylight saving and travel`() {
        // 21:00 in Rome the evening before summer time ends, and 21:00 in New York.
        val beforeDst = session(at(2026, 10, 24, 21), minutes = 30)
        val newYork = session(at(2026, 11, 10, 21, zone = ZoneId.of("America/New_York")), minutes = 30)
        val s = stats(listOf(beforeDst, newYork), now = at(2026, 11, 25, 9))

        assertThat(s.byHour[21]).isEqualTo(60 * 60L)
        assertThat(s.daily[LocalDate.of(2026, 11, 10)]).isEqualTo(30 * 60L)
    }

    @Test
    fun `today, this week and this month use the current zone`() {
        val now = at(2026, 11, 25, 9) // a Wednesday
        val s = stats(
            listOf(
                session(at(2026, 11, 25, 7), minutes = 10),
                session(at(2026, 11, 23, 7), minutes = 20), // Monday, this week
                session(at(2026, 11, 22, 7), minutes = 30), // Sunday, last week
                session(at(2026, 10, 31, 7), minutes = 40), // last month
            ),
            now = now,
        )

        assertThat(s.todaySeconds).isEqualTo(10 * 60L)
        assertThat(s.weekSeconds).isEqualTo(30 * 60L)
        assertThat(s.monthSeconds).isEqualTo(60 * 60L)
        assertThat(s.totalSeconds).isEqualTo(100 * 60L)
    }

    @Test
    fun `streaks need five minutes a day and stay current until today ends`() {
        val today = LocalDate.of(2026, 11, 25)
        fun days(vararg back: Long, seconds: Long = 600) = back.associate { today.minusDays(it) to seconds }

        assertThat(streaks(days(1, 2, 3), today)).isEqualTo(3 to 3)
        assertThat(streaks(days(0, 1, 2, 3), today)).isEqualTo(4 to 4)
        assertThat(streaks(days(2, 3), today)).isEqualTo(0 to 2)
        assertThat(streaks(days(0, 5, 6, 7, 8), today)).isEqualTo(1 to 4)
        assertThat(streaks(days(0, 1, seconds = STREAK_DAY_SECONDS - 1), today)).isEqualTo(0 to 0)
        assertThat(streaks(emptyMap(), today)).isEqualTo(0 to 0)
    }

    @Test
    fun `streaks run across a month end`() {
        val s = stats(
            listOf(30, 31).map { session(at(2026, 10, it, 20), minutes = 6) } +
                session(at(2026, 11, 1, 20), minutes = 6),
            now = at(2026, 11, 1, 22),
        )
        assertThat(s.currentStreak).isEqualTo(3)
        assertThat(s.longestStreak).isEqualTo(3)
    }

    @Test
    fun `speed needs enough reading behind it`() {
        val now = at(2026, 11, 25, 9)
        assertThat(wordsPerMinute(emptyList())).isNull()
        // Long enough, but no word count.
        assertThat(wordsPerMinute(listOf(session(now, minutes = 60)))).isNull()
        // Fast, but too short to tell.
        assertThat(wordsPerMinute(listOf(session(now, minutes = 4, words = 1_000)))).isNull()
        // Long, but too few words.
        assertThat(wordsPerMinute(listOf(session(now, minutes = 10, words = 400)))).isNull()
        // Zero seconds and zero words do not divide.
        assertThat(wordsPerMinute(listOf(session(now, minutes = 0, words = 0, seconds = 0)))).isNull()
        assertThat(wordsPerMinute(listOf(session(now, minutes = 10, words = 2_500)))).isWithin(1e-9).of(250.0)
    }

    @Test
    fun `speed is weighted by time and leaves out skimming`() {
        val now = at(2026, 11, 25, 9)
        val speed = wordsPerMinute(listOf(
            session(now, minutes = 10, words = 2_000),
            session(now, minutes = 30, words = 9_000),
            session(now, minutes = 1, words = 5_000), // 5,000 wpm: flipping pages
        ))
        assertThat(speed).isWithin(1e-9).of(11_000 / 40.0)
    }

    @Test
    fun `time left uses the book's own pace once it has enough reading`() {
        val now = at(2026, 11, 25, 9)
        val progress = BookProgressRow("a", percent = 0.5, finishedAt = null, abandonedAt = null)
        val own = listOf(session(now, minutes = 20, words = 4_000)) // 200 wpm

        assertThat(secondsLeft(progress, 100_000, own, overallWpm = 300.0)).isEqualTo(50_000L * 60 / 200)
        // Too little of its own: the overall pace.
        val short = listOf(session(now, minutes = 6, words = 1_200))
        assertThat(secondsLeft(progress, 100_000, short, overallWpm = 250.0)).isEqualTo(50_000L * 60 / 250)
        // No pace at all, no word count, or a finished book: unknown.
        assertThat(secondsLeft(progress, 100_000, emptyList(), overallWpm = null)).isNull()
        assertThat(secondsLeft(progress, null, own, overallWpm = 300.0)).isNull()
        assertThat(secondsLeft(progress.copy(finishedAt = 1L), 100_000, own, 300.0)).isNull()
        assertThat(secondsLeft(progress.copy(abandonedAt = 1L), 100_000, own, 300.0)).isNull()
    }

    @Test
    fun `books keep their snapshot and count finished ones from progress`() {
        val now = at(2026, 11, 25, 9)
        val s = stats(
            listOf(
                session(at(2026, 11, 20, 9), minutes = 10, book = "gone"),
                session(at(2026, 11, 24, 9), minutes = 20, book = "a"),
                session(at(2026, 11, 25, 8), minutes = 5, book = "a"),
            ),
            now = now,
            progress = listOf(
                BookProgressRow("a", 0.4, null, null),
                BookProgressRow("b", 1.0, finishedAt = 1L, abandonedAt = null),
            ),
        )

        assertThat(s.books.map { it.bookKey }).containsExactly("a", "gone").inOrder()
        assertThat(s.books[0].seconds).isEqualTo(25 * 60L)
        assertThat(s.books[0].sessions).isEqualTo(2)
        assertThat(s.books[1].title).isEqualTo("Book gone")
        assertThat(s.booksFinished).isEqualTo(1)
        assertThat(s.isEmpty).isFalse()
    }

    @Test
    fun `weekly speed covers the recent weeks, oldest first`() {
        val now = at(2026, 11, 25, 9)
        val s = stats(listOf(session(at(2026, 11, 24, 9), minutes = 10, words = 3_000)), now = now)

        assertThat(s.weeklySpeed).hasSize(SPEED_WEEKS)
        assertThat(s.weeklySpeed.last().weekStart).isEqualTo(LocalDate.of(2026, 11, 23))
        assertThat(s.weeklySpeed.last().wordsPerMinute).isWithin(1e-9).of(300.0)
        assertThat(s.weeklySpeed.dropLast(1).all { it.wordsPerMinute == null }).isTrue()
    }

    @Test
    fun `last days and months fill the gaps with zero`() {
        val now = at(2026, 11, 25, 9)
        val s = stats(listOf(session(at(2026, 11, 24, 9), minutes = 10)), now = now)

        val days = s.lastDays(7)
        assertThat(days).hasSize(7)
        assertThat(days.last()).isEqualTo(LocalDate.of(2026, 11, 25) to 0L)
        assertThat(days[5]).isEqualTo(LocalDate.of(2026, 11, 24) to 600L)
        assertThat(s.lastMonths()).hasSize(12)
        assertThat(s.lastMonths().last().second).isEqualTo(600L)
    }
}
