package de.jeanlucmakiola.calendula.domain.ics

import de.jeanlucmakiola.calendula.domain.EventStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
 * occurrence missing its series, never a series missing an occurrence.
 * Attendees and organizer are not part of either input; the copy is the
 * user's own event.
 */
fun planCalendarCopy(rows: CalendarCopyRows): List<ParsedIcsEvent> {
    val (series, exceptions) = rows
    val byId = series.associateBy { it.eventId }
    val bySyncId = series.filter { !it.syncId.isNullOrEmpty() }.associateBy { it.syncId }
    val excluded = mutableMapOf<Long, MutableList<String>>()
    val detached = mutableListOf<ParsedIcsEvent>()
    for (exception in exceptions) {
        val parent = exception.originalId?.let(byId::get)
            ?: exception.originalSyncId?.takeIf { it.isNotEmpty() }?.let(bySyncId::get)
        if (parent != null) {
            // An exception to a one-off overrides nothing a copy could carry.
            if (parent.event.recurrenceRule.isNullOrBlank()) continue
            excluded.getOrPut(parent.eventId) { mutableListOf() } +=
                exdateStamp(exception.originalInstance, parent.event.isAllDay)
        }
        if (exception.isCancelled) continue
        val baseUid = parent?.event?.uid ?: exception.event.uid
        detached += exception.event
            .copy(
                uid = "${exception.originalInstance.toEpochMilliseconds()}-$baseUid",
                recurrenceRule = null,
                exDates = emptyList(),
            )
            .toParsedIcsEvent(exception.color)
    }
    return detached + series.map { s ->
        val extra = excluded[s.eventId].orEmpty()
        val event = if (extra.isEmpty()) s.event else s.event.copy(exDates = (s.event.exDates + extra).distinct())
        event.toParsedIcsEvent(s.color)
    }
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
    location = location,
    description = description,
    // The writer emits only lead times; a negative one would be an alarm after the start.
    reminderMinutes = reminderMinutes.filter { it >= 0 }.distinct(),
    status = status,
    availability = availability,
    color = color,
    exDates = if (recurrenceRule.isNullOrBlank()) emptyList() else exDates.filter { it.isNotBlank() }.distinct(),
)

/** An occurrence as an `EXDATE` entry in the provider's shape: the UTC day for an all-day series, else a UTC stamp. */
internal fun exdateStamp(occurrence: Instant, isAllDay: Boolean): String =
    if (isAllDay) occurrence.toLocalDateTime(TimeZone.UTC).date.toBasic() else occurrence.toUtcBasic()
