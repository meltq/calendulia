package de.jeanlucmakiola.calendula.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import de.jeanlucmakiola.calendula.data.prefs.PastEventDisplay
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.TimeFormatPref
import de.jeanlucmakiola.calendula.data.prefs.WeekStartPref
import de.jeanlucmakiola.calendula.data.prefs.is24Hour
import de.jeanlucmakiola.calendula.data.prefs.resolveFirstDay
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.agenda.AgendaDay
import de.jeanlucmakiola.calendula.ui.agenda.AgendaRange
import de.jeanlucmakiola.calendula.ui.agenda.agendaRange
import de.jeanlucmakiola.calendula.ui.agenda.dayCount
import de.jeanlucmakiola.calendula.ui.agenda.groupAgendaDays
import de.jeanlucmakiola.floret.prefs.ThemeMode
import de.jeanlucmakiola.floret.prefs.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Months either side of the displayed one the month widget loads, so a few arrow
 * taps page without a re-query. Kept small: every load expands recurrences across
 * the whole window, and on slow devices a wide one held the widget on "loading"
 * (#363).
 */
internal const val MONTH_WIDGET_PAD_MONTHS = 3

/**
 * Every app setting the widgets draw with: what the loads read, and what
 * [WidgetRefreshCoordinator] watches. The agenda fields are only the defaults
 * for a widget that has no options of its own yet.
 */
internal data class WidgetSettings(
    val soften: Boolean,
    val weekStart: WeekStartPref,
    val timeFormat: TimeFormatPref,
    val themeMode: ThemeMode,
    val pastDisplay: PastEventDisplay,
    val agendaRange: AgendaRange,
    val widgetSize: WidgetSize,
    val showToday: Boolean,
) {
    /** What every widget draws with; the agenda defaults aside. */
    val shared: SharedWidgetSettings get() = SharedWidgetSettings(soften, weekStart, timeFormat, themeMode)

    val agendaDefaults: AgendaWidgetOptions
        get() = AgendaWidgetOptions(agendaRange, widgetSize, showToday, pastDisplay)
}

/** The [WidgetSettings] a configured widget still takes from the app. */
internal data class SharedWidgetSettings(
    val soften: Boolean,
    val weekStart: WeekStartPref,
    val timeFormat: TimeFormatPref,
    val themeMode: ThemeMode,
)

/** A widget's loaded [content], with the app theme a [WidgetTheme.FOLLOW_APP] widget resolves against. */
internal data class Themed<out T>(val content: T, val appTheme: ThemeMode)

internal val SettingsPrefs.widgetSettings: Flow<WidgetSettings>
    get() = data.map {
        WidgetSettings(
            soften = it[SettingsKeys.softenCalendarColors],
            weekStart = it[SettingsKeys.weekStart],
            timeFormat = it[SettingsKeys.timeFormat],
            themeMode = it[SettingsKeys.themeMode],
            pastDisplay = it[SettingsKeys.pastEventDisplay],
            agendaRange = it[SettingsKeys.agendaWidgetRange],
            widgetSize = it[SettingsKeys.widgetSize],
            showToday = it[SettingsKeys.agendaShowToday],
        )
    }.distinctUntilChanged()

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
        val textSize: WidgetSize,
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
        /** Month indices (`year * 12 + monthOrdinal`) whose grids [instances] covers. */
        val months: IntRange,
    ) : MonthWidgetSource
}

/** [loadAgendaWidgetData] with agenda widget [id]'s own options. */
internal suspend fun Context.loadAgendaWidget(id: GlanceId): Themed<AgendaWidgetData> {
    val settings = widgetEntryPoint().settingsPrefs().widgetSettings.first()
    val options = widgetState(id).agendaWidgetOptions(settings.agendaDefaults)
    return Themed(loadAgendaWidgetData(settings, options), settings.themeMode)
}

/**
 * One-shot read of the upcoming agenda for the widget. Reuses the app's
 * [agendaRange] window and [groupAgendaDays] grouping, and the repository's
 * [first]-emitted snapshot already has hidden calendars filtered out.
 */
internal suspend fun Context.loadAgendaWidgetData(
    settings: WidgetSettings,
    options: AgendaWidgetOptions,
): AgendaWidgetData {
    if (!hasCalendarPermission()) return AgendaWidgetData.NeedsPermission
    val zone = systemZone()
    val anchor = today(zone)
    val ep = widgetEntryPoint()
    val weekStart = settings.weekStart.resolveFirstDay(Locale.getDefault())
    val dayCount = options.range.dayCount(anchor, weekStart)
    val window = agendaRange(anchor, dayCount - 1, zone)
    val windowEnd = anchor.plus(dayCount - 1, DateTimeUnit.DAY)
    val instances = ep.calendarRepository().instances(window).first()
    return AgendaWidgetData.Ready(
        today = anchor,
        days = groupAgendaDays(anchor, windowEnd, instances, zone),
        is24Hour = settings.timeFormat.is24Hour(android.text.format.DateFormat.is24HourFormat(this)),
        soften = settings.soften,
        pastDisplay = options.pastDisplay,
        showToday = options.showToday,
        textSize = options.textSize,
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

/**
 * The last window each month widget loaded, under its refresh stamp, so a new
 * session for the same widget (a tap after the last one timed out) draws
 * without a query. Every redraw moves the stamp, so an entry is never served
 * stale.
 */
private object MonthSourceCache {
    private class Entry(val center: Int, val stamp: Long, val source: Themed<MonthWidgetSource.Ready>)

    private val entries = ConcurrentHashMap<GlanceId, Entry>()

    fun get(id: GlanceId, center: Int, stamp: Long): Themed<MonthWidgetSource>? =
        entries[id]?.takeIf { it.center == center && it.stamp == stamp && it.source.content.today == today(systemZone()) }
            ?.source

    fun put(id: GlanceId, center: Int, stamp: Long, source: Themed<MonthWidgetSource>) {
        val ready = source.content as? MonthWidgetSource.Ready ?: return
        entries[id] = Entry(center, stamp, Themed(ready, source.appTheme))
    }
}

/** [loadThemedMonthSource] for widget [id], served from [MonthSourceCache] while its [stamp] holds. */
internal suspend fun Context.loadCachedMonthSource(id: GlanceId, centerIndex: Int, stamp: Long): Themed<MonthWidgetSource> =
    MonthSourceCache.get(id, centerIndex, stamp)
        ?: loadThemedMonthSource(centerIndex).also { MonthSourceCache.put(id, centerIndex, stamp, it) }

/** Read backing the month widget's grid for the window around [centerIndex], with the app theme. */
internal suspend fun Context.loadThemedMonthSource(centerIndex: Int): Themed<MonthWidgetSource> {
    val settings = widgetEntryPoint().settingsPrefs().widgetSettings.first()
    return Themed(queryMonthSource(centerIndex, settings), settings.themeMode)
}

private suspend fun Context.queryMonthSource(centerIndex: Int, settings: WidgetSettings): MonthWidgetSource {
    if (!hasCalendarPermission()) return MonthWidgetSource.NeedsPermission
    val zone = systemZone()
    val ep = widgetEntryPoint()
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
        weekStart = settings.weekStart.resolveFirstDay(Locale.getDefault()),
        soften = settings.soften,
        instances = ep.calendarRepository().instances(from..to).first(),
        months = months,
    )
}
