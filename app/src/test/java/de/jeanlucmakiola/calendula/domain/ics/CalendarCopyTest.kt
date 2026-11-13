package de.jeanlucmakiola.calendula.domain.ics

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.data.calendar.formatExdateStamp
import de.jeanlucmakiola.calendula.domain.EventStatus
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** What a calendar copy (#325) inserts for a source calendar's rows. */
class CalendarCopyTest {

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
    )

    private fun occurrence(n: Int) = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + n * week)

    @Test
    fun `a plain event copies as itself, keeping its zone and colour`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("a", reminders = listOf(10, -5, 10)), color = 0xFF112233.toInt())),
                exceptions = emptyList(),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        val copied = plan.single()
        assertThat(copied.uid).isEqualTo("a")
        assertThat(copied.zoneId).isEqualTo("Europe/Berlin")
        assertThat(copied.color).isEqualTo(0xFF112233.toInt())
        assertThat(copied.reminderMinutes).containsExactly(10)
    }

    @Test
    fun `a cancelled occurrence becomes an EXDATE on the series`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, "sync-1", event("s", rrule = "FREQ=WEEKLY", exDates = listOf("20260112T080000Z")))),
                exceptions = listOf(
                    CopyException(
                        originalId = null,
                        originalSyncId = "sync-1",
                        originalInstance = occurrence(2),
                        event = event("s", at = occurrence(2), status = EventStatus.Cancelled),
                    ),
                ),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        val series = plan.single()
        assertThat(series.exDates).containsExactly("20260112T080000Z", "20260119T090000Z").inOrder()
    }

    @Test
    fun `a modified occurrence is excluded from the series and copied on its own, first`() {
        val moved = Instant.fromEpochMilliseconds(occurrence(1).toEpochMilliseconds() + 3_600_000L)
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=WEEKLY"))),
                exceptions = listOf(
                    CopyException(
                        originalId = 1,
                        originalSyncId = null,
                        originalInstance = occurrence(1),
                        event = event("s", rrule = "FREQ=WEEKLY", at = moved),
                    ),
                ),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        assertThat(plan).hasSize(2)
        val detached = plan[0]
        assertThat(detached.uid).isEqualTo("${occurrence(1).toEpochMilliseconds()}-s")
        assertThat(detached.recurrenceRule).isNull()
        assertThat(detached.start).isEqualTo(moved)
        assertThat(plan[1].exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `an all-day series excludes the occurrence by its day`() {
        val day = Instant.parse("2026-01-05T00:00:00Z")
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=DAILY", allDay = true, at = day))),
                exceptions = listOf(
                    CopyException(
                        originalId = 1,
                        originalSyncId = null,
                        originalInstance = Instant.parse("2026-01-07T00:00:00Z"),
                        event = event("s", allDay = true, status = EventStatus.Cancelled),
                    ),
                ),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        assertThat(plan.single().exDates).containsExactly("20260107")
    }

    @Test
    fun `exceptions without their series are left out`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = emptyList(),
                exceptions = listOf(
                    CopyException(5, null, occurrence(1), event("x")),
                    CopyException(5, null, occurrence(2), event("y", status = EventStatus.Cancelled)),
                ),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `a cancelled series is left out with its exceptions`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=WEEKLY", status = EventStatus.Cancelled))),
                exceptions = listOf(CopyException(1, null, occurrence(1), event("x"))),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `a series already in the target brings no overrides along`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=WEEKLY"))),
                exceptions = listOf(CopyException(1, null, occurrence(1), event("x"))),
            ),
            setOf("s"),
            ::formatExdateStamp,
        )

        assertThat(plan.map { it.uid }).containsExactly("s")
        assertThat(plan.single().exDates).isEmpty()
    }

    @Test
    fun `the plan is stable, so a re-run dedups every event`() {
        val rows = CalendarCopyRows(
            series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=WEEKLY"))),
            exceptions = listOf(CopyException(1, null, occurrence(1), event("s"))),
        )

        assertThat(planCalendarCopy(rows, emptySet(), ::formatExdateStamp).map { it.uid }).isEqualTo(planCalendarCopy(rows, emptySet(), ::formatExdateStamp).map { it.uid })
    }

    @Test
    fun `a zero-length all-day event lasts its day`() {
        val day = Instant.parse("2026-01-05T00:00:00Z")
        val parsed = event("a", allDay = true, at = day).copy(end = day).toParsedIcsEvent()

        assertThat(parsed.end).isEqualTo(Instant.parse("2026-01-06T00:00:00Z"))
    }

    @Test
    fun `an RDATE-only series keeps its dates and takes its exceptions`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s").copy(recurrenceDates = "20260112T090000Z"))),
                exceptions = listOf(CopyException(1, null, occurrence(1), event("s", status = EventStatus.Cancelled))),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        val series = plan.single()
        assertThat(series.recurrenceDates).isEqualTo("20260112T090000Z")
        assertThat(series.exDates).containsExactly("20260112T090000Z")
    }

    @Test
    fun `a UID that comes up twice is planned once`() {
        val plan = planCalendarCopy(
            CalendarCopyRows(
                series = listOf(CopySeries(1, null, event("s", rrule = "FREQ=WEEKLY"))),
                exceptions = listOf(
                    CopyException(1, null, occurrence(1), event("x")),
                    CopyException(1, null, occurrence(1), event("y")),
                ),
            ),
            emptySet(),
            ::formatExdateStamp,
        )

        assertThat(plan.map { it.summary }).containsExactly("x", "s").inOrder()
    }
}
