package de.jeanlucmakiola.calendula.domain.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DailyDigestTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val minute = 60_000L
    private val eight = 20 * 60

    private fun millis(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `due once the moment passes the watermark`() {
        val schedule = scheduleDigest(eight, DigestDay.Tomorrow, zone, millis("2026-06-10T19:59"), millis("2026-06-10T20:00"))
        assertThat(schedule.dueDay).isEqualTo(LocalDate.of(2026, 6, 11))
        assertThat(schedule.nextAlarmMillis).isEqualTo(millis("2026-06-11T20:00"))
    }

    @Test
    fun `a second scan does not post again`() {
        val schedule = scheduleDigest(eight, DigestDay.Tomorrow, zone, millis("2026-06-10T20:00"), millis("2026-06-10T20:05"))
        assertThat(schedule.dueDay).isNull()
    }

    @Test
    fun `a day already listed is not listed again`() {
        // Posted at 20:00, then the time moved to 21:00 the same evening.
        val schedule = scheduleDigest(
            21 * 60, DigestDay.Tomorrow, zone, millis("2026-06-10T20:30"), millis("2026-06-10T21:00"),
            lastListedDay = LocalDate.of(2026, 6, 11),
        )
        assertThat(schedule.dueDay).isNull()
    }

    @Test
    fun `before the moment it only arms the alarm`() {
        val schedule = scheduleDigest(eight, DigestDay.Tomorrow, zone, millis("2026-06-10T08:00"), millis("2026-06-10T09:00"))
        assertThat(schedule.dueDay).isNull()
        assertThat(schedule.nextAlarmMillis).isEqualTo(millis("2026-06-10T20:00"))
    }

    @Test
    fun `a late catch-up still lists a day that has not passed`() {
        // Off overnight: last scan before 20:00, back on the next morning.
        val schedule = scheduleDigest(eight, DigestDay.Tomorrow, zone, millis("2026-06-10T18:00"), millis("2026-06-11T07:00"))
        assertThat(schedule.dueDay).isEqualTo(LocalDate.of(2026, 6, 11))
    }

    @Test
    fun `a today summary missed until the next day is dropped`() {
        val seven = 7 * 60
        val schedule = scheduleDigest(seven, DigestDay.Today, zone, millis("2026-06-10T06:00"), millis("2026-06-11T06:30"))
        assertThat(schedule.dueDay).isNull()
        assertThat(schedule.nextAlarmMillis).isEqualTo(millis("2026-06-11T07:00"))
    }

    @Test
    fun `entries list all-day first, keep the day's own, and skip excluded calendars`() {
        val day = LocalDate.of(2026, 6, 11)
        val utcMidnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDay = instance(1, utcMidnight, utcMidnight + 24 * 60 * minute, isAllDay = true)
        val previousAllDay = instance(2, utcMidnight - 24 * 60 * minute, utcMidnight, isAllDay = true)
        val morning = instance(3, millis("2026-06-11T09:00"), millis("2026-06-11T10:00"))
        val early = instance(4, millis("2026-06-11T07:00"), millis("2026-06-11T08:00"))
        val nextDay = instance(5, millis("2026-06-12T00:00"), millis("2026-06-12T01:00"))
        val excluded = instance(6, millis("2026-06-11T12:00"), millis("2026-06-11T13:00"), calendarId = 9)
        val entries = digestEntries(
            listOf(morning, previousAllDay, nextDay, early, allDay, excluded),
            day,
            zone,
            excludedCalendarIds = setOf(9),
        )
        assertThat(entries.map { it.eventId }).containsExactly(1L, 4L, 3L).inOrder()
    }

    private fun instance(
        eventId: Long,
        begin: Long,
        end: Long,
        isAllDay: Boolean = false,
        calendarId: Long = 1,
    ) = ReminderEventInstance(eventId, calendarId, begin, end, "Event $eventId", null, isAllDay)
}
