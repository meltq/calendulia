package de.jeanlucmakiola.calendula.domain.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Which day the daily summary (#459) lists, counted from the day it arrives. */
enum class DigestDay(val daysAhead: Long) { Today(0), Tomorrow(1) }

/** The daily summary's settings. Calendars in [excludedCalendarIds] are left out of it. */
data class DailyDigestSettings(
    val enabled: Boolean = false,
    val timeMinutes: Int = 1_200,
    val day: DigestDay = DigestDay.Tomorrow,
    val excludedCalendarIds: Set<Long> = emptySet(),
)

/**
 * What one scan concluded for the summary: list [dueDay] now (null when nothing
 * is owed), and wake up again at [nextAlarmMillis].
 */
data class DigestSchedule(
    val dueDay: LocalDate?,
    val nextAlarmMillis: Long,
)

/**
 * When the summary is owed, measured against the same watermark as the event
 * reminders: a moment in `(lastFiredMillis, nowMillis]` is due. Only the latest
 * such moment counts — a phone off for three evenings gets one summary, not
 * three — and only while the day it lists has not passed.
 */
fun scheduleDigest(
    timeMinutes: Int,
    day: DigestDay,
    zone: ZoneId,
    lastFiredMillis: Long,
    nowMillis: Long,
): DigestSchedule {
    val time = LocalTime.of(timeMinutes / 60, timeMinutes % 60)
    val fireAt = { date: LocalDate -> date.atTime(time).atZone(zone).toInstant().toEpochMilli() }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val latestDate = if (fireAt(today) <= nowMillis) today else today.minusDays(1)
    val listed = latestDate.plusDays(day.daysAhead)
    val dueDay = listed.takeIf { fireAt(latestDate) > lastFiredMillis && !it.isBefore(today) }
    return DigestSchedule(dueDay = dueDay, nextAlarmMillis = fireAt(latestDate.plusDays(1)))
}

/**
 * The occurrences the summary for [day] lists: all-day ones covering it (their
 * UTC dates), then timed ones overlapping it in [zone], each group in start
 * order. Calendars in [excludedCalendarIds] are left out.
 */
fun digestEntries(
    instances: List<ReminderEventInstance>,
    day: LocalDate,
    zone: ZoneId,
    excludedCalendarIds: Set<Long>,
): List<ReminderEventInstance> {
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val utcDay = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return instances
        .asSequence()
        .filter { it.calendarId !in excludedCalendarIds }
        .filter {
            if (it.isAllDay) {
                it.beginMillis <= utcDay && maxOf(it.endMillis, it.beginMillis + 1) > utcDay
            } else {
                it.beginMillis < dayEnd && maxOf(it.endMillis, it.beginMillis + 1) > dayStart
            }
        }
        .distinctBy { occurrenceKey(it.eventId, it.beginMillis) }
        .sortedWith(compareBy({ !it.isAllDay }, { it.beginMillis }, { it.title }))
        .toList()
}
