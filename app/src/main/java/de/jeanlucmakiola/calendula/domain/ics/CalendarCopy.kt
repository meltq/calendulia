package de.jeanlucmakiola.calendula.domain.ics

import de.jeanlucmakiola.calendula.domain.EventStatus
import kotlin.time.Duration.Companion.days

/**
 * The events a calendar copy (#325) inserts into its target, in the shape
 * `importEvents` takes: the source's rows flattened as [flattenOverrides] does
 * for a backup, minus cancelled series and one-offs, which would arrive live
 * (an insert can't be cancelled), and declined invitations and occurrences. Attendees and organizer are never read.
 *
 * Idempotent per snapshot: `importEvents` skips a UID the target already holds,
 * and an [IcsEvent.overrideOf] occurrence whose series it holds, so a re-run
 * keeps the earlier copy of an occurrence changed since rather than adding a
 * second one beside it.
 */
fun planCalendarCopy(
    rows: CalendarRows,
    exdateStamp: (occurrenceMillis: Long, isAllDay: Boolean) -> String,
): List<ParsedIcsEvent> {
    val live = CalendarRows(
        series = rows.series.filterNot { it.event.status == EventStatus.Cancelled || it.declined },
        // A declined occurrence is as good as deleted here.
        overrides = rows.overrides.map {
            if (it.declined) it.copy(event = it.event.copy(status = EventStatus.Cancelled)) else it
        },
    )
    return flattenOverrides(live, exdateStamp).map { it.toParsedIcsEvent() }
}

/**
 * [IcsEvent] read back as if [IcsWriter] had written it and [IcsParser] had read
 * it, without the text in between. Unlike that round trip it keeps a one-off's
 * own time zone (the writer pins it to UTC) and the event's colour (not part of
 * the file format).
 */
fun IcsEvent.toParsedIcsEvent(): ParsedIcsEvent = ParsedIcsEvent(
    uid = uid,
    summary = summary,
    start = start,
    // RFC 5545 §3.6.1: an all-day event lasts at least its one day; zero-length
    // it expands into no instance at all.
    end = if (isAllDay && end <= start) start + 1.days else end,
    isAllDay = isAllDay,
    zoneId = zoneId,
    recurrenceRule = recurrenceRule?.takeIf { it.isNotBlank() }?.removePrefix("RRULE:"),
    recurrenceDates = recurrenceDates.filter { it.isNotBlank() }.distinct(),
    location = location,
    description = description,
    // The writer emits only lead times; a negative one would be an alarm after the start.
    reminderMinutes = reminderMinutes.filter { it >= 0 }.distinct(),
    status = status,
    availability = availability,
    accessLevel = accessLevel,
    color = color,
    overrideOf = overrideOf,
    overrideAt = overrideAt,
    exDates = if (recurs) exDates.filter { it.isNotBlank() }.distinct() else emptyList(),
)
