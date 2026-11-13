package de.jeanlucmakiola.calendula.domain.reminders

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A timed event's reminder "[daysBefore] days before, at [minuteOfDay]" (#459).
 *
 * The provider only knows lead times, so it is stored as an ordinary `Reminders`
 * row whose offset is the distance from that wall-clock moment to the start
 * ([offsetMinutes]) — it fires on every device and in every app. The pattern is
 * only known to Calendula while it can be read back ([splitTimedReminders]); the
 * editor then re-derives the offset whenever the start moves.
 */
data class FixedTimeReminder(val daysBefore: Int, val minuteOfDay: Int) {

    init {
        require(daysBefore in 1..MAX_DAYS_BEFORE) { "daysBefore out of range: $daysBefore" }
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
    }

    /** The moment this reminder names for an event starting at [start], read in [zone]. */
    fun alarmAt(start: Instant, zone: TimeZone): Instant {
        val day = start.toLocalDateTime(zone).date.minus(daysBefore, DateTimeUnit.DAY)
        return LocalDateTime(day, LocalTime(minuteOfDay / 60, minuteOfDay % 60)).toInstant(zone)
    }

    /** The provider offset to store for an event starting at [start]. */
    fun offsetMinutes(start: Instant, zone: TimeZone): Int =
        (start - alarmAt(start, zone)).inWholeMinutes.toInt()

    /** The stored form, `days@minuteOfDay`. */
    fun toToken(): String = "$daysBefore$TOKEN_SEP$minuteOfDay"

    companion object {
        const val MAX_DAYS_BEFORE = 28
        private const val MINUTES_PER_DAY = 1_440
        private const val TOKEN_SEP = "@"

        /** [toToken] read back; null for anything malformed or out of range. */
        fun parse(token: String?): FixedTimeReminder? {
            val parts = token?.trim()?.split(TOKEN_SEP) ?: return null
            if (parts.size != 2) return null
            val days = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (days !in 1..MAX_DAYS_BEFORE || minute !in 0 until MINUTES_PER_DAY) return null
            return FixedTimeReminder(days, minute)
        }
    }
}

/**
 * The "N days before at HH:MM" a raw offset lands on for an event starting at
 * [start], or null when it fires on the start's own day (or after it), or
 * further back than [FixedTimeReminder.MAX_DAYS_BEFORE].
 */
fun fixedTimeReminderAt(offsetMinutes: Int, start: Instant, zone: TimeZone): FixedTimeReminder? {
    if (offsetMinutes <= 0) return null
    val alarm = (start - offsetMinutes.minutes).toLocalDateTime(zone)
    val startDate = start.toLocalDateTime(zone).date
    val days = startDate.toEpochDays() - alarm.date.toEpochDays()
    if (days !in 1..FixedTimeReminder.MAX_DAYS_BEFORE) return null
    return FixedTimeReminder(days.toInt(), alarm.hour * 60 + alarm.minute)
}

/** A timed event's reminders, read back: plain lead times and the fixed-time ones. */
data class TimedReminders(
    val leadMinutes: List<Int>,
    val fixed: List<FixedTimeReminder>,
)

/**
 * Sort a timed event's raw offsets into lead times and "N days before at HH:MM"
 * reminders. The provider can't say which an offset was meant as, so:
 *
 * - one landing on a pattern in [known] (the configured defaults) is fixed-time;
 * - otherwise one reaching back past the start's own day is fixed-time, unless it
 *   is a whole number of days or one of [plainLeadMinutes] — those read as the
 *   lead time they look like.
 *
 * The cost of guessing wrong is small: the reminder fires at the same moment
 * either way, and only a later change of the start's time of day treats it
 * differently. A hand-entered "3 hours before" on an event at 01:00 does read as
 * "1 day before at 22:00".
 */
fun splitTimedReminders(
    offsets: List<Int>,
    start: Instant,
    zone: TimeZone,
    known: Set<FixedTimeReminder>,
    plainLeadMinutes: Collection<Int> = PLAIN_LEAD_MINUTES,
): TimedReminders {
    val leads = mutableListOf<Int>()
    val fixed = mutableListOf<FixedTimeReminder>()
    offsets.distinct().forEach { offset ->
        val pattern = fixedTimeReminderAt(offset, start, zone)
        val isFixed = pattern != null && (
            pattern in known ||
                (offset % MINUTES_PER_DAY != 0 && offset !in plainLeadMinutes)
            )
        if (isFixed) fixed += pattern!! else leads += offset
    }
    return TimedReminders(leads.sorted(), fixed.distinct().sortedWith(FIXED_ORDER))
}

/** Most days before first, then by time — the order they fire in. */
val FIXED_ORDER: Comparator<FixedTimeReminder> =
    compareByDescending<FixedTimeReminder> { it.daysBefore }.thenBy { it.minuteOfDay }

/** Lead times offered as presets: always read as what they look like. */
private val PLAIN_LEAD_MINUTES = setOf(5, 10, 15, 30, 45, 60, 90, 120)

private const val MINUTES_PER_DAY = 1_440
