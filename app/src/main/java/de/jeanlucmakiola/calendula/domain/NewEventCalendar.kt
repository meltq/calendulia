package de.jeanlucmakiola.calendula.domain

/**
 * The calendar chosen as default for new events (#429). Provider ids are
 * device-local and can be handed out again, so the account and name ride along
 * and the choice only holds while the calendar under [id] still matches them,
 * e.g. not after a backup restore onto another device.
 */
data class DefaultCalendarRef(
    val id: Long,
    val accountType: String,
    val accountName: String,
    val displayName: String,
) {
    fun matches(calendar: CalendarSource): Boolean =
        calendar.id == id &&
            calendar.accountType == accountType &&
            calendar.accountName == accountName &&
            calendar.displayName == displayName

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
): Long? = picked
    ?: hints.requested?.takeIf { id -> calendars.any { it.id == id && it.isEventTarget } }
    ?: effectiveDefaultCalendar(hints.default, calendars)?.id
    ?: hints.lastUsed?.takeIf { id -> calendars.any { it.id == id && it.isEventTarget } }
    ?: calendars.firstOrNull { it.isEventTarget }?.id

/**
 * The default calendar setting as it applies now: null ("Automatic") when unset,
 * or when the stored calendar is gone, read-only or switched off.
 */
fun effectiveDefaultCalendar(default: DefaultCalendarRef?, calendars: List<CalendarSource>): CalendarSource? =
    default?.let { ref -> calendars.firstOrNull { ref.matches(it) && it.isEventTarget } }

/**
 * The stored default calendar while it still exists but can't take new events
 * (switched off, read-only, managed), so the setting can say why it reads
 * Automatic. Null when it applies, is unset or is gone.
 */
fun unavailableDefaultCalendar(default: DefaultCalendarRef?, calendars: List<CalendarSource>): CalendarSource? =
    default?.let { ref -> calendars.firstOrNull { ref.matches(it) && !it.isEventTarget } }
