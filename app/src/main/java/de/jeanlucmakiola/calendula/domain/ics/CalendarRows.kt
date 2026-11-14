package de.jeanlucmakiola.calendula.domain.ics

import de.jeanlucmakiola.calendula.domain.EventStatus
import kotlin.time.Instant

/**
 * A master or one-off Events row of a calendar being backed up or copied, with
 * the provider keys its exception rows point back through.
 */
data class MasterRow(
    val eventId: Long,
    val calendarId: Long,
    val syncId: String?,
    val event: IcsEvent,
)

/**
 * An exception row: a modified or cancelled occurrence of the series named by
 * [originalId] / [originalSyncId] in [calendarId], standing in for the
 * occurrence that would have started at [originalInstance].
 */
data class OverrideRow(
    val calendarId: Long,
    val originalId: Long?,
    val originalSyncId: String?,
    val originalInstance: Instant,
    val event: IcsEvent,
) {
    val isCancelled: Boolean get() = event.status == EventStatus.Cancelled
}

/** Every event row a backup or copy reads: series/one-offs and their exception rows. */
data class CalendarRows(
    val series: List<MasterRow>,
    val overrides: List<OverrideRow>,
)

/**
 * [rows] as plain events with no exception rows left, the shape both a backup
 * file and a copied calendar need: neither can hold an exception row, since one
 * only attaches to its series through a `_sync_id` (and an `.ics` restore skips
 * `RECURRENCE-ID` overrides).
 *
 * Every override excludes its occurrence from the series (`EXDATE`), and a
 * modified one comes along as a standalone event at its own times, marked
 * [IcsEvent.overrideOf] its series — what Calendula itself writes for "only this
 * event" on an unsynced series. Its UID is derived from the series' UID and the
 * occurrence, so it is the same on every run. Overrides whose series isn't
 * among the rows are left out; the provider doesn't show them either.
 *
 * Standalone occurrences come first, so an import cut short leaves at worst an
 * occurrence missing its series, never a series missing an occurrence.
 *
 * [exdateStamp] formats an occurrence as an `EXDATE` entry in the provider's
 * shape, the same one Calendula's own exclusions use.
 */
fun flattenOverrides(
    rows: CalendarRows,
    exdateStamp: (occurrenceMillis: Long, isAllDay: Boolean) -> String,
): List<IcsEvent> {
    val byId = rows.series.associateBy { it.eventId }
    // Sync ids are only unique within an account, so the link is per calendar.
    val bySyncId = rows.series.filter { it.syncId != null }.associateBy { it.calendarId to it.syncId }
    val excluded = mutableMapOf<Long, MutableList<String>>()
    val detached = mutableListOf<IcsEvent>()
    for (override in rows.overrides) {
        val parent = override.originalId?.let(byId::get)?.takeIf { it.calendarId == override.calendarId }
            ?: override.originalSyncId?.let { bySyncId[override.calendarId to it] }
            ?: continue
        // An exception to a one-off overrides nothing a plain event could carry.
        if (!parent.event.recurs) continue
        val occurrence = override.originalInstance.toEpochMilliseconds()
        excluded.getOrPut(parent.eventId) { mutableListOf() } += exdateStamp(occurrence, parent.event.isAllDay)
        if (override.isCancelled) continue
        detached += override.event.copy(
            uid = "$occurrence-${parent.event.uid}",
            recurrenceRule = null,
            recurrenceDates = null,
            exDates = emptyList(),
            overrideOf = parent.event.uid,
        )
    }
    // Two exception rows for one occurrence would derive the same UID.
    return detached.distinctBy { it.uid } + rows.series.map { s ->
        val extra = excluded[s.eventId].orEmpty()
        if (extra.isEmpty()) s.event else s.event.copy(exDates = (s.event.exDates + extra).distinct())
    }
}
