package io.theficos.ereader.notify

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class ReminderScheduleTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val everyDay = DayOfWeek.entries.toSet()
    private val nine = LocalTime.of(21, 0)

    private fun at(text: String, zone: ZoneId = rome) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test fun `the next slot is later today when the time has not passed`() {
        assertThat(ReminderSchedule.nextSlot(at("2026-11-26T18:00"), nine, everyDay)).isEqualTo(at("2026-11-26T21:00"))
    }

    @Test fun `the next slot crosses midnight once today's has passed`() {
        assertThat(ReminderSchedule.nextSlot(at("2026-11-26T21:00"), nine, everyDay)).isEqualTo(at("2026-11-27T21:00"))
        assertThat(ReminderSchedule.nextSlot(at("2026-11-26T23:59"), LocalTime.of(0, 5), everyDay))
            .isEqualTo(at("2026-11-27T00:05"))
    }

    @Test fun `only chosen weekdays get a slot`() {
        // 2026-11-26 is a Thursday.
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        assertThat(ReminderSchedule.nextSlot(at("2026-11-26T08:00"), nine, weekend)).isEqualTo(at("2026-11-28T21:00"))
        assertThat(ReminderSchedule.nextSlot(at("2026-11-29T22:00"), nine, weekend)).isEqualTo(at("2026-12-05T21:00"))
        // An empty set means every day rather than never.
        assertThat(ReminderSchedule.nextSlot(at("2026-11-26T08:00"), nine, emptySet())).isEqualTo(at("2026-11-26T21:00"))
    }

    @Test fun `the delay across a spring-forward night is one hour short of a day`() {
        // Rome moves from 02:00 to 03:00 on 2026-03-29.
        val now = at("2026-03-28T21:00:00").plusSeconds(1)
        val slot = ReminderSchedule.nextSlot(now, nine, everyDay)
        assertThat(slot).isEqualTo(at("2026-03-29T21:00"))
        assertThat(Duration.between(now, slot)).isEqualTo(Duration.ofHours(23).minusSeconds(1))
    }

    @Test fun `a time skipped by spring forward moves forward by the gap`() {
        val slot = ReminderSchedule.nextSlot(at("2026-03-29T00:30"), LocalTime.of(2, 30), everyDay)
        assertThat(slot.toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-03-29T03:30"))
    }

    @Test fun `a time repeated by fall back fires once, at its first occurrence`() {
        // Rome moves from 03:00 back to 02:00 on 2026-10-25.
        val first = ReminderSchedule.nextSlot(at("2026-10-25T00:30"), LocalTime.of(2, 30), everyDay)
        assertThat(first.offset.totalSeconds).isEqualTo(2 * 3600)
        val next = ReminderSchedule.nextSlot(first.plusMinutes(11), LocalTime.of(2, 30), everyDay)
        assertThat(next.toLocalDate()).isEqualTo(java.time.LocalDate.parse("2026-10-26"))
    }

    @Test fun `a run near its slot is due, early or late within bounds`() {
        assertThat(ReminderSchedule.dueSlot(at("2026-11-26T21:02"), nine, everyDay)).isEqualTo(at("2026-11-26T21:00"))
        assertThat(ReminderSchedule.dueSlot(at("2026-11-26T20:55"), nine, everyDay)).isEqualTo(at("2026-11-26T21:00"))
        assertThat(ReminderSchedule.dueSlot(at("2026-11-26T20:40"), nine, everyDay)).isNull()
        assertThat(ReminderSchedule.dueSlot(at("2026-11-27T00:30"), nine, everyDay)).isNull()
    }

    @Test fun `a late run after midnight still belongs to yesterday's slot`() {
        val late = LocalTime.of(23, 55)
        assertThat(ReminderSchedule.dueSlot(at("2026-11-27T00:10"), late, everyDay)).isEqualTo(at("2026-11-26T23:55"))
    }

    @Test fun `a run on a day that is not chosen is not due`() {
        assertThat(ReminderSchedule.dueSlot(at("2026-11-26T21:00"), nine, setOf(DayOfWeek.FRIDAY))).isNull()
    }

    @Test fun `reading earlier on the slot's day skips it, reading yesterday does not`() {
        val slot = at("2026-11-26T21:00")
        assertThat(ReminderSchedule.readOnSlotDay(slot, at("2026-11-26T07:30").toInstant())).isTrue()
        assertThat(ReminderSchedule.readOnSlotDay(slot, at("2026-11-25T23:30").toInstant())).isFalse()
        assertThat(ReminderSchedule.readOnSlotDay(slot, null)).isFalse()
        // The day is the reader's local day, not UTC's: 00:30 in Rome is still the 25th in UTC.
        assertThat(ReminderSchedule.readOnSlotDay(slot, at("2026-11-26T00:30").toInstant())).isTrue()
    }

    @Test fun `app start keeps a booking that is still right`() {
        val booked = at("2026-11-26T21:00").toInstant()
        assertThat(ReminderSchedule.rebookOnStart(booked, at("2026-11-26T18:00"), nine)).isFalse()
        // Just missed (WorkManager is about to run it): still right.
        assertThat(ReminderSchedule.rebookOnStart(booked, at("2026-11-26T22:00"), nine)).isFalse()
    }

    @Test fun `app start rebooks after a time zone change`() {
        // Booked for 21:00 in Rome, which is 05:00 the next morning in Tokyo.
        val booked = at("2026-11-26T21:00").toInstant()
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertThat(ReminderSchedule.rebookOnStart(booked, at("2026-11-26T18:00", tokyo), nine)).isTrue()
    }

    @Test fun `app start rebooks when nothing is booked or the chain broke`() {
        assertThat(ReminderSchedule.rebookOnStart(null, at("2026-11-26T18:00"), nine)).isTrue()
        val longAgo = at("2026-11-20T21:00").toInstant()
        assertThat(ReminderSchedule.rebookOnStart(longAgo, at("2026-11-26T18:00"), nine)).isTrue()
    }
}
