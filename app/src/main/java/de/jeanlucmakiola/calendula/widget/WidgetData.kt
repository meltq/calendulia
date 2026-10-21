package de.jeanlucmakiola.calendula.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.jeanlucmakiola.calendula.data.prefs.PastEventDisplay
import de.jeanlucmakiola.calendula.data.prefs.is24Hour
import de.jeanlucmakiola.calendula.data.prefs.resolveFirstDay
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.agenda.AgendaDay
import de.jeanlucmakiola.calendula.ui.agenda.agendaRange
import de.jeanlucmakiola.calendula.ui.agenda.dayCount
import de.jeanlucmakiola.calendula.ui.agenda.groupAgendaDays
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Months either side of the displayed one the month widget loads, so a few arrow
 * taps page without a re-query. Kept small: every load expands recurrences across
 * the whole window, and on slow devices a wide one held the widget on "loading"
 * (#363).
 */
private const val MONTH_WIDGET_PAD_MONTHS = 3

internal fun systemZone(): TimeZone = TimeZone.currentSystemDefault()

internal fun today(zone: TimeZone): LocalDate =
    Clock.System.now().toLocalDateTime(zone).date

internal fun Context.hasCalendarPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

/** Snapshot rendered by the agenda widget. */
sealed interface AgendaWidgetData {
    /** Calendar permission not granted — the widget can't read events. */
    data object NeedsPermission : AgendaWidgetData
    data class Ready(
        val today: LocalDate,
        /** Days with events across the configured range, unfiltered; hiding finished ones happens in the composition. */
        val days: List<AgendaDay>,
        /** Resolved clock convention for event time labels (the time-format pref). */
        val is24Hour: Boolean,
        /** Whether event colours are softened to pastels, or shown raw (#36). */
        val soften: Boolean,
        val pastDisplay: PastEventDisplay,
        /** "Always show today" (#35): anchor today at the top even with no events left. */
        val showToday: Boolean,
        val widgetSize: WidgetSize,
        /** Snapshot instant the data was read at, for "has this event ended?" tests. */
        val now: Instant,
    ) : AgendaWidgetData
}

/**
 * Source data for the month widget: a window of instances plus the week-start
 * preference and today. The widget computes each displayed month's grid from
 * this in-memory list (via `layoutMonthWeeks`) as the user pages, so paging
 * inside the loaded window is pure recomposition.
 */
sealed interface MonthWidgetSource {
    data object NeedsPermission : MonthWidgetSource
    data class Ready(
        val today: LocalDate,
        val weekStart: DayOfWeek,
        val soften: Boolean,
        val instances: List<EventInstance>,
    ) : MonthWidgetSource
}

/**
 * One-shot read of the upcoming agenda for the widget. Reuses the app's
 * [agendaRange] window and [groupAgendaDays] grouping, and the repository's
 * [first]-emitted snapshot already has hidden calendars filtered out.
 */
internal suspend fun Context.loadAgendaWidgetData(): AgendaWidgetData {
    if (!hasCalendarPermission()) return AgendaWidgetData.NeedsPermission
    val zone = systemZone()
    val anchor = today(zone)
    val ep = widgetEntryPoint()
    val prefs = ep.settingsPrefs()
    val range = prefs.agendaWidgetRange.first()
    val weekStart = prefs.weekStart.first().resolveFirstDay(Locale.getDefault())
    val dayCount = range.dayCount(anchor, weekStart)
    val window = agendaRange(anchor, dayCount - 1, zone)
    val windowEnd = anchor.plus(dayCount - 1, DateTimeUnit.DAY)
    val instances = ep.calendarRepository().instances(window).first()
    return AgendaWidgetData.Ready(
        today = anchor,
        days = groupAgendaDays(anchor, windowEnd, instances, zone),
        is24Hour = prefs.timeFormat.first()
            .is24Hour(android.text.format.DateFormat.is24HourFormat(this)),
        soften = prefs.softenCalendarColors.first(),
        pastDisplay = prefs.pastEventDisplay.first(),
        showToday = prefs.agendaShowToday.first(),
        widgetSize = prefs.widgetSize.first(),
        now = Clock.System.now(),
    )
}

internal fun monthIndexOf(date: LocalDate): Int = date.year * 12 + date.month.ordinal

private fun firstDayOfMonthIndex(index: Int): LocalDate = LocalDate(index / 12, index % 12 + 1, 1)

/**
 * The month the widget loads its window around while [index] is shown. Snapped
 * to steps of three, so paging only reloads every third month, and the window
 * always reaches at least two months past [index] either way.
 */
internal fun monthWindowCenter(index: Int): Int = Math.floorDiv(index, 3) * 3 + 1

/** Read backing the month widget's grid for the window around [centerIndex]. */
internal suspend fun Context.loadMonthWidgetSource(centerIndex: Int): MonthWidgetSource {
    if (!hasCalendarPermission()) return MonthWidgetSource.NeedsPermission
    val zone = systemZone()
    val ep = widgetEntryPoint()
    val prefs = ep.settingsPrefs()
    val weekStart = prefs.weekStart.first().resolveFirstDay(Locale.getDefault())
    val months = (centerIndex - MONTH_WIDGET_PAD_MONTHS)..(centerIndex + MONTH_WIDGET_PAD_MONTHS)
    // Padded by the grid's spill into the neighbouring months.
    val from = firstDayOfMonthIndex(months.first)
        .minus(1, DateTimeUnit.WEEK)
        .atStartOfDayIn(zone)
    val to = firstDayOfMonthIndex(months.last + 1)
        .plus(2, DateTimeUnit.WEEK)
        .atTime(23, 59, 59)
        .toInstant(zone)
    return MonthWidgetSource.Ready(
        today = today(zone),
        weekStart = weekStart,
        soften = prefs.softenCalendarColors.first(),
        instances = ep.calendarRepository().instances(from..to).first(),
    )
}
