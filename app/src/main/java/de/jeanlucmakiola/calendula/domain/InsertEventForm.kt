package de.jeanlucmakiola.calendula.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Build a prefilled [EventForm] from an `ACTION_INSERT` intent's extras (issue
 * #30). External apps and widgets (e.g. the Todo Agenda widget) launch the
 * calendar this way to create a new event, passing the fields as
 * [android.provider.CalendarContract] extras. Any field the intent omits falls
 * back to the same defaults the in-app "new event" uses — a timed start at the
 * next full hour, and a placeholder hour for a missing end that the form then
 * stretches to the default-duration setting (the intent is opened as
 * `ImportSource.InsertOpenEnded`, #54). [EventForm.calendarId] is left null so
 * it resolves to the last-used / first-writable calendar, exactly like the
 * `.ics` single-event and plain new-event paths.
 *
 * Pure (no Android types) so it is unit-testable; the intent parsing that reads
 * the extras lives in `MainActivity.insertFormOrNull`.
 */
fun buildInsertEventForm(
    beginMillis: Long?,
    endMillis: Long?,
    isAllDay: Boolean,
    title: String?,
    description: String?,
    location: String?,
    rrule: String?,
    zone: TimeZone,
    now: Instant,
): EventForm {
    val (start, end) = if (isAllDay) {
        // All-day provider times are UTC midnights with an exclusive end; show
        // the last covered day and keep placeholder wall-clock times in case the
        // user switches the event to timed (mirrors EventDetail.toEditForm).
        val startDate = beginMillis
            ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
            ?: now.toLocalDateTime(zone).date
        val endDate = endMillis
            ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
            ?.let { exclusive -> maxOf(startDate, LocalDate.fromEpochDays(exclusive.toEpochDays() - 1)) }
            ?: startDate
        FormTime(LocalDateTime(startDate, LocalTime(9, 0))) to FormTime(LocalDateTime(endDate, LocalTime(10, 0)))
    } else {
        val startAt = beginMillis?.let(Instant::fromEpochMilliseconds) ?: nextFullHour(now)
        val endAt = endMillis?.let(Instant::fromEpochMilliseconds)?.takeIf { it >= startAt }
            ?: (startAt + 1.hours)
        startAt.toFormTime(zone) to endAt.toFormTime(zone)
    }
    return EventForm(
        calendarId = null,
        title = title.orEmpty(),
        isAllDay = isAllDay,
        start = start,
        end = end,
        location = location.orEmpty(),
        description = description.orEmpty(),
        // Bare RRULE value (Events.RRULE convention); tolerate a leading "RRULE:"
        // some callers include.
        rrule = rrule?.removePrefix("RRULE:")?.takeIf { it.isNotBlank() },
    )
}

/** The first full hour after [now]: may roll into the next day before midnight. */
internal fun nextFullHour(now: Instant): Instant {
    val hourMillis = 3_600_000L
    return Instant.fromEpochMilliseconds((now.toEpochMilliseconds() / hourMillis + 1) * hourMillis)
}
