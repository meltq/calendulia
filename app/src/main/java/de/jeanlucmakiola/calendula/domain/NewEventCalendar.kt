package de.jeanlucmakiola.calendula.domain

/**
 * What decides a new event's calendar when the user hasn't picked one (#429).
 * [requested] is a calendar named by whoever opened the form (an insert intent,
 * a duplicate's source), [default] the "default calendar" setting and
 * [lastUsed] the calendar the last new event went into.
 */
data class NewEventCalendarHints(
    val requested: Long? = null,
    val default: Long? = null,
    val lastUsed: Long? = null,
)

/**
 * The calendar the event form targets: [picked] when set, otherwise a writable
 * [NewEventCalendarHints.requested] calendar, then the default and the last-used
 * calendar while each is still an event target, then the first target.
 */
fun resolveEventCalendar(
    picked: Long?,
    hints: NewEventCalendarHints,
    calendars: List<CalendarSource>,
): Long? = picked
    ?: hints.requested?.takeIf { id -> calendars.any { it.id == id && it.canModifyContents } }
    ?: effectiveDefaultCalendar(hints.default, calendars)?.id
    ?: hints.lastUsed?.takeIf { id -> calendars.any { it.id == id && it.isEventTarget } }
    ?: calendars.firstOrNull { it.isEventTarget }?.id

/**
 * The default calendar setting as it applies now: null ("Automatic") when unset,
 * or when the stored calendar is gone, read-only or switched off.
 */
fun effectiveDefaultCalendar(defaultId: Long?, calendars: List<CalendarSource>): CalendarSource? =
    defaultId?.let { id -> calendars.firstOrNull { it.id == id && it.isEventTarget } }
