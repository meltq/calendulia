package de.jeanlucmakiola.calendula.domain.ics

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.data.calendar.formatExdateStamp
import de.jeanlucmakiola.calendula.domain.EventStatus
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** How a calendar's exception rows are written out for a backup (#147) or a copy (#325). */
class CalendarRowsTest {

    private val start = Instant.parse("2026-01-05T09:00:00Z")
    private val week = 7 * 24 * 3_600_000L

    private fun event(
        uid: String,
        rrule: String? = null,
        allDay: Boolean = false,
        at: Instant = start,
        status: EventStatus = EventStatus.Confirmed,
        exDates: List<String> = emptyList(),
        reminders: List<Int> = emptyList(),
        color: Int? = null,
    ) = IcsEvent(
        uid = uid,
        summary = uid,
        start = at,
        end = Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + 3_600_000L),
        isAllDay = allDay,
        zoneId = "Europe/Berlin",
        recurrenceRule = rrule,
        exDates = exDates,
        reminderMinutes = reminders,
        status = status,
        color = color,
        calendarName = "Work",
    )

    private fun occurrence(n: Int) = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + n * week)

    private fun series(uid: String, rrule: String? = "FREQ=WEEKLY", id: Long = 1, calendar: Long = 1, syncId: String? = null, status: EventStatus = EventStatus.Confirmed) =
        MasterRow(id, calendar, syncId, event(uid, rrule = rrule, status = status))

    private fun flatten(series: List<MasterRow>, overrides: List<OverrideRow>) =
        flattenOverrides(CalendarRows(series, overrides), ::formatExdateStamp)

    @Test
    fun `a cancelled occurrence becomes an EXDATE on the series`() {
        val flat = flatten(
            listOf(MasterRow(1, 1, "sync-1", event("s", rrule = "FREQ=WEEKLY", exDates = listOf("20260112T080000Z")))),
            listOf(OverrideRow(1, null, "sync-1", occurrence(2), event("s", at = occurrence(2), status = EventStatus.Cancelled))),
        )

        assertThat(flat.single().exDates).containsExactly("20260112T080000Z", "20260119T090000Z").inOrder()
    }

    @Test
    fun `a modified occurrence is excluded from the series and written on its own, first`() {
        val moved = Instant.fromEpochMilliseconds(occurrence(1).toEpochMilliseconds() + 3_600_000L)
        val flat = flatten(
            listOf(series("s")),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("s", rrule = "FREQ=WEEKLY", at = moved))),
        )

        assertThat(flat).hasSize(2)
        val detached = flat[0]
        assertThat(detached.uid).isEqualTo("${occurrence(1).toEpochMilliseconds()}-s")
        assertThat(detached.overrideOf).isEqualTo("s")
        assertThat(detached.recurrenceRule).isNull()
        assertThat(detached.start).isEqualTo(moved)
        assertThat(detached.calendarName).isEqualTo("Work")
        assertThat(flat[1].exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `an all-day series excludes the occurrence by its day`() {
        val day = Instant.parse("2026-01-05T00:00:00Z")
        val flat = flatten(
            listOf(MasterRow(1, 1, null, event("s", rrule = "FREQ=DAILY", allDay = true, at = day))),
            listOf(OverrideRow(1, 1, null, Instant.parse("2026-01-07T00:00:00Z"), event("s", allDay = true, status = EventStatus.Cancelled))),
        )

        assertThat(flat.single().exDates).containsExactly("20260107")
    }

    @Test
    fun `exceptions without their series are left out`() {
        val flat = flatten(
            emptyList(),
            listOf(
                OverrideRow(1, 5, null, occurrence(1), event("x")),
                OverrideRow(1, 5, null, occurrence(2), event("y", status = EventStatus.Cancelled)),
            ),
        )

        assertThat(flat).isEmpty()
    }

    @Test
    fun `a sync id links an exception only within its own calendar`() {
        val flat = flatten(
            listOf(series("a", calendar = 1, syncId = "same"), series("b", id = 2, calendar = 2, syncId = "same")),
            listOf(OverrideRow(2, null, "same", occurrence(1), event("x", status = EventStatus.Cancelled))),
        )

        assertThat(flat.first { it.uid == "a" }.exDates).isEmpty()
        assertThat(flat.first { it.uid == "b" }.exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `an exception to a one-off is ignored`() {
        val flat = flatten(
            listOf(series("o", rrule = null)),
            listOf(OverrideRow(1, 1, null, occurrence(0), event("x"))),
        )

        assertThat(flat.map { it.uid }).containsExactly("o")
    }

    @Test
    fun `an RDATE-only series keeps its dates and takes its exceptions`() {
        val flat = flatten(
            listOf(MasterRow(1, 1, null, event("s").copy(recurrenceDates = listOf("20260112T090000Z")))),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("s", status = EventStatus.Cancelled))),
        )

        assertThat(flat.single().recurrenceDates).containsExactly("20260112T090000Z")
        assertThat(flat.single().exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `two exception rows for one occurrence are written once`() {
        val flat = flatten(
            listOf(series("s")),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("x")), OverrideRow(1, 1, null, occurrence(1), event("y"))),
        )

        assertThat(flat.map { it.summary }).containsExactly("x", "s").inOrder()
    }

    @Test
    fun `the output is stable, so a re-run dedups every event`() {
        val rows = CalendarRows(listOf(series("s")), listOf(OverrideRow(1, 1, null, occurrence(1), event("x"))))

        assertThat(flattenOverrides(rows, ::formatExdateStamp).map { it.uid })
            .isEqualTo(flattenOverrides(rows, ::formatExdateStamp).map { it.uid })
    }

    @Test
    fun `a backup keeps a cancelled series, a copy leaves it out with its exceptions`() {
        val rows = CalendarRows(
            listOf(series("s", status = EventStatus.Cancelled)),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("x"))),
        )

        assertThat(flattenOverrides(rows, ::formatExdateStamp).map { it.uid }).contains("s")
        assertThat(planCalendarCopy(rows, ::formatExdateStamp)).isEmpty()
    }

    @Test
    fun `a copy keeps the zone, colour and override link`() {
        val rows = CalendarRows(
            listOf(MasterRow(1, 1, null, event("s", rrule = "FREQ=WEEKLY", reminders = listOf(10, -5, 10), color = 0xFF112233.toInt()))),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("x"))),
        )

        val (detached, copied) = planCalendarCopy(rows, ::formatExdateStamp)
        assertThat(detached.overrideOf).isEqualTo("s")
        assertThat(copied.zoneId).isEqualTo("Europe/Berlin")
        assertThat(copied.color).isEqualTo(0xFF112233.toInt())
        assertThat(copied.reminderMinutes).containsExactly(10)
    }

    @Test
    fun `a zero-length all-day event lasts its day`() {
        val day = Instant.parse("2026-01-05T00:00:00Z")
        val parsed = event("a", allDay = true, at = day).copy(end = day).toParsedIcsEvent()

        assertThat(parsed.end).isEqualTo(Instant.parse("2026-01-06T00:00:00Z"))
    }

    @Test
    fun `the override link survives the file`() {
        val text = IcsWriter().writeCalendar(listOf(event("d").copy(overrideOf = "s;1", overrideAt = occurrence(1))), start)

        val parsed = IcsParser().parse(text).events.single()
        assertThat(parsed.overrideOf).isEqualTo("s;1")
        assertThat(parsed.overrideAt).isEqualTo(occurrence(1))
    }

    @Test
    fun `a cancelled series' moved occurrence isn't brought back`() {
        val flat = flatten(
            listOf(series("s", status = EventStatus.Cancelled)),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("x"))),
        )

        assertThat(flat.map { it.uid }).containsExactly("s")
    }

    @Test
    fun `the same series in two calendars keeps each calendar's moved occurrence`() {
        val flat = flatten(
            listOf(series("s", id = 1, calendar = 1), series("s", id = 2, calendar = 2)),
            listOf(
                OverrideRow(1, 1, null, occurrence(1), event("x")),
                OverrideRow(2, 2, null, occurrence(1), event("x")),
            ),
        )

        assertThat(flat.count { it.overrideOf == "s" }).isEqualTo(2)
    }

    @Test
    fun `a copy leaves declined invitations out but keeps an occurrence accepted on its own`() {
        val rows = CalendarRows(
            listOf(series("d").copy(declined = true), series("k", id = 2)),
            listOf(
                OverrideRow(1, 1, null, occurrence(1), event("accepted"), accepted = true),
                OverrideRow(1, 1, null, occurrence(2), event("moved by the organizer")),
            ),
        )

        val plan = planCalendarCopy(rows, ::formatExdateStamp)
        assertThat(plan.map { it.summary }).containsExactly("accepted", "k")
    }

    @Test
    fun `a copy treats a declined occurrence as deleted`() {
        val rows = CalendarRows(
            listOf(series("s")),
            listOf(OverrideRow(1, 1, null, occurrence(1), event("x"), declined = true)),
        )

        val plan = planCalendarCopy(rows, ::formatExdateStamp)
        assertThat(plan.map { it.uid }).containsExactly("s")
        assertThat(plan.single().exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `RDATE and its exclusions survive the file`() {
        val timed = event("t").copy(
            recurrenceDates = listOf("20260112T090000Z", "20260120T090000Z"),
            exDates = listOf("20260112T090000Z"),
        )
        val day = Instant.parse("2026-01-05T00:00:00Z")
        val allDay = event("a", allDay = true, at = day).copy(
            end = Instant.parse("2026-01-06T00:00:00Z"),
            recurrenceDates = listOf("20260110"),
            exDates = listOf("20260110"),
        )
        val text = IcsWriter().writeCalendar(listOf(timed, allDay), start)

        assertThat(text).contains("RDATE;VALUE=DATE:20260110")
        assertThat(text).contains("DTSTART;TZID=Europe/Berlin:20260105T100000")
        val (t, a) = IcsParser().parse(text).events
        assertThat(t.recurrenceDates).containsExactly("20260112T090000Z", "20260120T090000Z")
        assertThat(t.exDates).containsExactly("20260112T090000Z")
        assertThat(a.recurrenceDates).containsExactly("20260110")
        assertThat(a.exDates).containsExactly("20260110")
    }

    @Test
    fun `a foreign RDATE is read in its own zone, a period by its start`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:f
            DTSTART;TZID=Europe/Berlin:20260105T100000
            DTEND;TZID=Europe/Berlin:20260105T110000
            RDATE;TZID=America/New_York:20260110T090000
            RDATE;VALUE=PERIOD:20260111T090000Z/PT1H
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        assertThat(IcsParser().parse(text).events.single().recurrenceDates)
            .containsExactly("20260110T140000Z", "20260111T090000Z")
    }
}
