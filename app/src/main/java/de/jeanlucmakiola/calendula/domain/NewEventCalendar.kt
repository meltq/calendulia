package de.jeanlucmakiola.calendula.domain

/**
 * The calendar chosen as default for new events (#429). Provider ids are
 * device-local and get reassigned when an account is re-added or a backup is
 * restored onto another device, so the account and name ride along: see [find].
 */
data class DefaultCalendarRef(
    val id: Long,
    val accountType: String,
    val accountName: String,
    val displayName: String,
) {
    /**
     * The calendar this refers to: the exact one, else the account's calendar of
     * that name (the id changed), else the id within the account (it was renamed).
     */
    fun find(calendars: List<CalendarSource>): CalendarSource? {
        val inAccount = calendars.filter { it.accountType == accountType && it.accountName == accountName }
        return inAccount.firstOrNull { it.id == id && it.displayName == displayName }
            ?: inAccount.singleOrNull { it.displayName == displayName }
            ?: inAccount.firstOrNull { it.id == id }
    }

    fun encode(): String = listOf(id.toString(), accountType, accountName, displayName).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001F"

        fun of(calendar: CalendarSource) =
            DefaultCalendarRef(calendar.id, calendar.accountType, calendar.accountName, calendar.displayName)

        fun decode(stored: String?): DefaultCalendarRef? {
            val parts = stored?.split(SEPARATOR, limit = 4)?.takeIf { it.size == 4 } ?: return null
            val id = parts[0].toLongOrNull() ?: return null
            return DefaultCalendarRef(id, parts[1], parts[2], parts[3])
        }
    }
}

/**
 * What decides a new event's calendar when the user hasn't picked one (#429).
 * [requested] is a calendar named by whoever opened the form (an insert intent,
 * a duplicate's source), [default] the "default calendar" setting and
 * [lastUsed] the calendar the last new event went into.
 */
data class NewEventCalendarHints(
    val requested: Long? = null,
    val default: DefaultCalendarRef? = null,
    val lastUsed: Long? = null,
)

/**
 * The calendar the event form targets: [picked] when set, otherwise the first of
 * the requested, default and last-used calendar that is still an event target,
 * then the first target.
 */
fun resolveEventCalendar(
    picked: Long?,
    hints: NewEventCalendarHints,
    calendars: List<CalendarSource>,
): Long? {
    fun Long.ifTarget() = takeIf { id -> calendars.any { it.id == id && it.isEventTarget } }
    return picked
        ?: hints.requested?.ifTarget()
        ?: effectiveDefaultCalendar(hints.default, calendars)?.id
        ?: hints.lastUsed?.ifTarget()
        ?: calendars.firstOrNull { it.isEventTarget }?.id
}

/**
 * The default calendar setting as it applies now: null ("Automatic") when unset,
 * or when the stored calendar is gone, read-only or switched off.
 */
fun effectiveDefaultCalendar(default: DefaultCalendarRef?, calendars: List<CalendarSource>): CalendarSource? =
    default?.find(calendars)?.takeIf { it.isEventTarget }
