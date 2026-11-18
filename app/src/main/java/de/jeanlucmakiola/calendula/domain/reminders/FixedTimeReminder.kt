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
 * only known to Calendula while it can be read back ([classifyTimedReminder]); the
 * editor then re-derives the offset whenever the start moves.
 */
data class FixedTimeReminder(val daysBefore: Int, val minuteOfDay: Int) {

    init {
        require(daysBefore in 1..MAX_DAYS_BEFORE) { "daysBefore out of range: $daysBefore" }
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
    }

    /** The wall clock this reminder names for an event starting at [start], read in [zone]. */
    private fun alarmWallClock(start: Instant, zone: TimeZone): LocalDateTime {
        val day = start.toLocalDateTime(zone).date.minus(daysBefore, DateTimeUnit.DAY)
        return LocalDateTime(day, LocalTime(minuteOfDay / 60, minuteOfDay % 60))
    }

    /**
     * The provider offset to store for an event starting at [start]: the real
     * minutes to that moment, or with [wallClock] the minutes on the clock face.
     * A recurring series stores one offset for every occurrence, so it takes the
     * wall-clock reading — otherwise an occurrence opened next to a DST change
     * would bake that hour into all the others.
     */
    fun offsetMinutes(start: Instant, zone: TimeZone, wallClock: Boolean = false): Int {
        val alarm = alarmWallClock(start, zone)
        return if (wallClock) {
            (start.toLocalDateTime(zone).toInstant(TimeZone.UTC) - alarm.toInstant(TimeZone.UTC)).inWholeMinutes.toInt()
        } else {
            (start - alarm.toInstant(zone)).inWholeMinutes.toInt()
        }
    }

    /** The stored form, `days@minuteOfDay`. */
    fun toToken(): String = "$daysBefore$TOKEN_SEP$minuteOfDay"

    companion object {
        /** Two weeks, well inside the four weeks Google Calendar takes for a reminder. */
        const val MAX_DAYS_BEFORE = 14
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
 * further back than [FixedTimeReminder.MAX_DAYS_BEFORE]. [wallClock] reads the
 * offset as [FixedTimeReminder.offsetMinutes] wrote it.
 */
fun fixedTimeReminderAt(
    offsetMinutes: Int,
    start: Instant,
    zone: TimeZone,
    wallClock: Boolean = false,
): FixedTimeReminder? {
    if (offsetMinutes <= 0) return null
    val startLocal = start.toLocalDateTime(zone)
    val alarm = if (wallClock) {
        (startLocal.toInstant(TimeZone.UTC) - offsetMinutes.minutes).toLocalDateTime(TimeZone.UTC)
    } else {
        (start - offsetMinutes.minutes).toLocalDateTime(zone)
    }
    val startDate = startLocal.date
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
 * The fixed-time reminder [offsetMinutes] stands for on an event starting at
 * [start], or null when it reads as a plain lead time. The provider can't say
 * which an offset was meant as, so only the patterns in [known] — the configured
 * defaults, past and present, and the ones saved by hand on this device — are
 * recognised: guessing from the offset's shape alone would turn ordinary leads
 * ("12 hours before") into fixed-time ones. Where a lead and a known pattern
 * coincide ("1 day before" on an event at the default's own time) the pattern
 * wins. A wrong reading fires at the same moment; only a later change of the
 * start's time of day then treats the reminder the other way.
 */
fun classifyTimedReminder(
    offsetMinutes: Int,
    start: Instant,
    zone: TimeZone,
    known: Set<FixedTimeReminder>,
    wallClock: Boolean = false,
): FixedTimeReminder? = fixedTimeReminderAt(offsetMinutes, start, zone, wallClock)?.takeIf { it in known }

/** Sort a timed event's raw offsets into lead times and fixed-time reminders ([classifyTimedReminder]). */
fun splitTimedReminders(
    offsets: List<Int>,
    start: Instant,
    zone: TimeZone,
    known: Set<FixedTimeReminder>,
    wallClock: Boolean = false,
): TimedReminders {
    val leads = mutableListOf<Int>()
    val fixed = mutableListOf<FixedTimeReminder>()
    offsets.distinct().forEach { offset ->
        val pattern = classifyTimedReminder(offset, start, zone, known, wallClock)
        if (pattern != null) fixed += pattern else leads += offset
    }
    return TimedReminders(leads.sorted(), fixed.distinct().sortedWith(FIXED_ORDER))
}

/** Most days before first, then by time — the order they fire in. */
val FIXED_ORDER: Comparator<FixedTimeReminder> =
    compareByDescending<FixedTimeReminder> { it.daysBefore }.thenBy { it.minuteOfDay }

private const val MINUTES_PER_DAY = 1_440
