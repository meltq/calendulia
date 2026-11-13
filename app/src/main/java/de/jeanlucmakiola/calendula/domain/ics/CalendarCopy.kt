package de.jeanlucmakiola.calendula.domain.ics

import de.jeanlucmakiola.calendula.domain.EventStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A master or one-off event of the calendar being copied (#325), with the
 * provider keys its exception rows point back through. [color] is the event's
 * own colour, null when it shows its calendar's.
 */
data class CopySeries(
    val eventId: Long,
    val syncId: String?,
    val event: IcsEvent,
    val color: Int? = null,
)

/**
 * An exception row of the calendar being copied: a modified or cancelled
 * occurrence of the series named by [originalId] / [originalSyncId], standing in
 * for the occurrence that would have started at [originalInstance].
 */
data class CopyException(
    val originalId: Long?,
    val originalSyncId: String?,
    val originalInstance: Instant,
    val event: IcsEvent,
    val color: Int? = null,
) {
    val isCancelled: Boolean get() = event.status == EventStatus.Cancelled
}

/** Everything a calendar copy reads from its source: series/one-offs and their exception rows. */
data class CalendarCopyRows(
    val series: List<CopySeries>,
    val exceptions: List<CopyException>,
)

/**
 * The events a calendar copy inserts into its target, in the shape
 * `importEvents` takes.
 *
 * A copied row has no `_sync_id`, and an exception row only attaches to its
 * series through one, so overrides can't be written as exception rows. Every
 * exception therefore excludes its occurrence from the copied series (`EXDATE`),
 * and a modified one comes along as a standalone event at its own times — what
 * Calendula itself writes for "only this event" on such a series. Its UID is
 * derived from the series' UID and the occurrence, so a re-run skips it like
 * any other event.
 *
 * Standalone occurrences come first: a run cut short then leaves at worst an
 * occurrence missing its series, never a series missing an occurrence. A UID
 * that comes up twice is planned once, as a re-run would dedup it anyway.
 * Attendees and organizer are not part of either input.
 *
 * Idempotent per snapshot only: a series already in the target is skipped
 * whole, so an occurrence changed in the source after an earlier run arrives
 * as a standalone event next to the copied series' original one.
 *
 * [exdateStamp] formats an occurrence as an `EXDATE` entry in the provider's
 * shape, the same one Calendula's own exclusions use.
 */
fun planCalendarCopy(
    rows: CalendarCopyRows,
    exdateStamp: (occurrenceMillis: Long, isAllDay: Boolean) -> String,
): List<ParsedIcsEvent> {
    val (series, exceptions) = rows
    val byId = series.associateBy { it.eventId }
    val bySyncId = series.filter { it.syncId != null }.associateBy { it.syncId }
    val excluded = mutableMapOf<Long, MutableList<String>>()
    val detached = mutableListOf<ParsedIcsEvent>()
    for (exception in exceptions) {
        val parent = exception.originalId?.let(byId::get)
            ?: exception.originalSyncId?.let(bySyncId::get)
        if (parent != null) {
            // An exception to a one-off overrides nothing a copy could carry.
            if (!parent.event.recurs) continue
            excluded.getOrPut(parent.eventId) { mutableListOf() } +=
                exdateStamp(exception.originalInstance.toEpochMilliseconds(), parent.event.isAllDay)
        }
        if (exception.isCancelled) continue
        val baseUid = parent?.event?.uid ?: exception.event.uid
        detached += exception.event
            .copy(
                uid = "${exception.originalInstance.toEpochMilliseconds()}-$baseUid",
                recurrenceRule = null,
                recurrenceDates = null,
            )
            .toParsedIcsEvent(exception.color)
    }
    return (detached + series.map { s ->
        val extra = excluded[s.eventId].orEmpty()
        val event = if (extra.isEmpty()) s.event else s.event.copy(exDates = (s.event.exDates + extra).distinct())
        event.toParsedIcsEvent(s.color)
    }).distinctBy { it.uid }
}

/**
 * [IcsEvent] read back as if [IcsWriter] had written it and [IcsParser] had read
 * it, without the text in between. Unlike that round trip it keeps a one-off's
 * own time zone (the writer pins it to UTC) and the event's colour (not part of
 * the file format).
 */
fun IcsEvent.toParsedIcsEvent(color: Int? = null): ParsedIcsEvent = ParsedIcsEvent(
    uid = uid,
    summary = summary,
    start = start,
    // RFC 5545 §3.6.1: an all-day event lasts at least its one day; zero-length
    // it expands into no instance at all.
    end = if (isAllDay && end <= start) start + 1.days else end,
    isAllDay = isAllDay,
    zoneId = zoneId,
    recurrenceRule = recurrenceRule?.takeIf { it.isNotBlank() }?.removePrefix("RRULE:"),
    recurrenceDates = recurrenceDates?.takeIf { it.isNotBlank() },
    location = location,
    description = description,
    // The writer emits only lead times; a negative one would be an alarm after the start.
    reminderMinutes = reminderMinutes.filter { it >= 0 }.distinct(),
    status = status,
    availability = availability,
    accessLevel = accessLevel,
    color = color,
    exDates = if (recurs) exDates.filter { it.isNotBlank() }.distinct() else emptyList(),
)
