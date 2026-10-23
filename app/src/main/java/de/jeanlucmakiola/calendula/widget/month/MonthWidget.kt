package de.jeanlucmakiola.calendula.widget.month

import de.jeanlucmakiola.calendula.domain.isDeclined
import de.jeanlucmakiola.calendula.widget.glanceDeclinedDecoration
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.jeanlucmakiola.calendula.MainActivity
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.formatCalendarTitle
import de.jeanlucmakiola.calendula.ui.month.MonthWeek
import de.jeanlucmakiola.calendula.ui.month.layoutMonthWeeks
import de.jeanlucmakiola.calendula.ui.common.eventFill
import de.jeanlucmakiola.calendula.ui.common.eventInk
import de.jeanlucmakiola.calendula.widget.CalendulaWidgetRoot
import de.jeanlucmakiola.calendula.widget.WidgetAppearance
import de.jeanlucmakiola.calendula.widget.widgetAppearance
import de.jeanlucmakiola.floret.glance.themedColor
import de.jeanlucmakiola.floret.glance.wallpaperBacking
import de.jeanlucmakiola.floret.prefs.ThemeMode
import de.jeanlucmakiola.calendula.widget.MonthWidgetSource
import de.jeanlucmakiola.calendula.widget.loadCachedMonthSource
import de.jeanlucmakiola.calendula.widget.monthWindowCenter
import de.jeanlucmakiola.floret.glance.WidgetData
import de.jeanlucmakiola.floret.glance.currentRefreshStamp
import de.jeanlucmakiola.floret.glance.refreshStamp
import de.jeanlucmakiola.floret.glance.updateWithRefresh
import de.jeanlucmakiola.calendula.widget.monthIndexOf
import de.jeanlucmakiola.calendula.widget.systemZone
import de.jeanlucmakiola.calendula.widget.today
import androidx.compose.ui.unit.Dp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Per-widget state: the displayed month as `year * 12 + monthOrdinal`. */
private val MONTH_INDEX_KEY = intPreferencesKey("month_index")

private val LANE_HEIGHT = 14.dp
private val DAY_NUMBER_HEIGHT = 18.dp
private val GRID_HPADDING = 8.dp

private fun currentMonthIndex(zone: TimeZone): Int = monthIndexOf(today(zone))

private fun yearMonthOf(index: Int): YearMonth =
    YearMonth(index / 12, Month(index % 12 + 1))

/**
 * Month-grid widget: a 6×7 calendar with today highlighted, connected multi-day
 * event bars and titled single-day pills (the in-app lane layout via
 * [layoutMonthWeeks]), and prev/next/today navigation.
 *
 * Columns are sized explicitly from [LocalSize] (hence [SizeMode.Exact]) so a
 * multi-day span renders as a single Box spanning its columns — connected, no
 * inter-cell seam, with rounded end caps. The displayed month lives in Glance
 * state and is read reactively in the composition ([currentState]) so the arrows
 * move it via plain recomposition, not a (here-unreliable) widget session reload.
 *
 * Everything here is written to keep the published `RemoteViews` small. A widget
 * update reaches the launcher as a *oneway* binder call, and the host's async
 * buffer is 1 MB for all of it; overrun throws `TransactionTooLargeException` and
 * the system drops the whole host, freezing every widget on the home screen until
 * the launcher rebinds. [SizeMode.Exact] serialises the grid once per size the
 * host reports, so the ceiling arrives at half the view count you would expect: a
 * grid that emitted a view per cell blew past it after three taps (#214). Every
 * row here emits only what it fills.
 */
class MonthWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * Exact rather than [SizeMode.Single] or [SizeMode.Responsive], both of which
     * return early from `GlanceAppWidget.resize` — under those the grid is never
     * recomposed for a new size and a resized widget clips instead of reflowing.
     * Exact costs one serialised copy per host size, which is why a rendering is
     * held to [VIEW_BUDGET] view ids, folding lanes into "+N" on a busy month.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val shown = getAppWidgetState<Preferences>(context, id)[MONTH_INDEX_KEY]
            ?: currentMonthIndex(systemZone())
        val stamp = refreshStamp(context, id)
        val initialCenter = monthWindowCenter(shown)
        // Shared by every size Exact composes, so each loads nothing of its own.
        val widgetData = WidgetData(context.loadCachedMonthSource(id, initialCenter, stamp), initialCenter, stamp)
        provideContent {
            val index = currentState(MONTH_INDEX_KEY) ?: currentMonthIndex(systemZone())
            // Reloads when paging leaves the loaded window, and on every redraw,
            // which a running session would otherwise never see (#420).
            val center = monthWindowCenter(index)
            val currentStamp = currentRefreshStamp()
            val loaded = widgetData.current(center, currentStamp) {
                context.loadCachedMonthSource(id, center, currentStamp)
            }
            val source = loaded.content
            // A jump out of the loaded window keeps the last month it covers
            // until the reload lands, rather than drawing an empty grid.
            var lastShown by remember { mutableIntStateOf(index) }
            val shown = when {
                source !is MonthWidgetSource.Ready || index in source.months -> index
                lastShown in source.months -> lastShown
                else -> index.coerceIn(source.months)
            }
            SideEffect { lastShown = shown }
            MonthWidgetContent(
                source = source,
                index = shown,
                appearance = currentState<Preferences>().widgetAppearance,
                appTheme = loaded.appTheme,
            )
        }
    }
}

/** The whole month widget showing month [index], shared with its config screen's preview (#292). */
@Composable
internal fun MonthWidgetContent(source: MonthWidgetSource, index: Int, appearance: WidgetAppearance, appTheme: ThemeMode) {
    CalendulaWidgetRoot(appearance, appTheme) {
        MonthWidgetBody(source = source, index = index)
    }
}

/**
 * Step the displayed month by the `delta` action parameter (±1).
 *
 * Redrawn through [GlanceAppWidget.update] with the id the callback was handed,
 * never `updateAll`. `updateAll` resolves its targets through the provider-name
 * -> app-widget-id map Glance persists for the *receiver*, and in a process that
 * has done nothing else yet — the one a tap wakes after a reboot — that lookup
 * comes back empty and the redraw is dropped: the state write lands, nothing
 * recomposes, and the arrows read as dead until something else opens the app and
 * starts a session (#18). The tapped widget's own id needs no lookup.
 */
class ShiftMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[deltaKey] ?: 0
        updateAppWidgetState(context, glanceId) { prefs ->
            val cur = prefs[MONTH_INDEX_KEY] ?: currentMonthIndex(systemZone())
            val next = cur + delta
            // Landing back on the current month clears the key, so the widget
            // goes back to *following* the date rather than being pinned to
            // whichever month was current at the tap. Paging out and back is the
            // workaround #228's reporter used, and pinning it there would have
            // stuck them on that month once it stopped being the current one.
            if (next == currentMonthIndex(systemZone())) {
                prefs.remove(MONTH_INDEX_KEY)
            } else {
                prefs[MONTH_INDEX_KEY] = next
            }
        }
        MonthWidget().update(context.applicationContext, glanceId)
    }

    companion object {
        val deltaKey = ActionParameters.Key<Int>("delta")
    }
}

/** Jump the displayed month back to the current month, reloading it. See [ShiftMonthAction]. */
class ResetMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs -> prefs.remove(MONTH_INDEX_KEY) }
        MonthWidget().updateWithRefresh(context.applicationContext, glanceId)
    }
}

@Composable
private fun MonthWidgetBody(source: MonthWidgetSource, index: Int) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = GRID_HPADDING, vertical = 6.dp),
    ) {
        when (source) {
            MonthWidgetSource.NeedsPermission -> {
                MonthHeader(label = "Calendula")
                PermissionMessage()
            }
            is MonthWidgetSource.Ready -> {
                val zone = systemZone()
                val ym = yearMonthOf(index)
                // Column width from the live widget size, minus our H padding.
                val colW = (LocalSize.current.width - GRID_HPADDING * 2) / 7
                val weeks = layoutMonthWeeks(ym, source.weekStart, source.instances, zone)

                MonthHeader(label = monthLabel(ym, source.today.year))
                Spacer(GlanceModifier.height(2.dp))
                // One backing for the whole grid on a transparent widget (#292).
                Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight().wallpaperBacking()) {
                    WeekdayHeader(weekStart = source.weekStart, colW = colW)
                    layoutWeeks(weeks, source.today).forEach { layout ->
                        WeekRow(
                            layout = layout,
                            currentMonth = ym.month,
                            today = source.today,
                            soften = source.soften,
                            colW = colW,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(label: String) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().wallpaperBacking(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIcon(
            resId = R.drawable.ic_widget_chevron_left,
            contentDescription = context.getString(R.string.widget_prev_month),
            onClick = GlanceModifier.clickable(
                actionRunCallback<ShiftMonthAction>(
                    actionParametersOf(ShiftMonthAction.deltaKey to -1),
                ),
            ),
        )
        // Tap the month/year title to open the app on the month view (issue #18),
        // rooted over the default home so backing out returns there.
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(
                    actionStartActivity(
                        MainActivity.openViewIntent(context, CalendarView.Month),
                    ),
                ),
        )
        // The "today" button snaps the grid back to the current month in place.
        HeaderIcon(
            resId = R.drawable.ic_widget_today,
            contentDescription = context.getString(R.string.widget_today),
            onClick = GlanceModifier.clickable(actionRunCallback<ResetMonthAction>()),
        )
        HeaderIcon(
            resId = R.drawable.ic_widget_chevron_right,
            contentDescription = context.getString(R.string.widget_next_month),
            onClick = GlanceModifier.clickable(
                actionRunCallback<ShiftMonthAction>(
                    actionParametersOf(ShiftMonthAction.deltaKey to 1),
                ),
            ),
        )
    }
}

@Composable
private fun HeaderIcon(resId: Int, contentDescription: String, onClick: GlanceModifier) {
    Box(
        modifier = GlanceModifier.size(40.dp).then(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(20.dp),
        )
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek, colW: Dp) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        weekdayNarrowNames(weekStart).forEach { name ->
            Text(
                text = name,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.width(colW),
            )
        }
    }
}

/** Narrow weekday initials starting at [weekStart], in the device locale.
 * Computed outside the composable so the locale read stays observable-safe. */
private fun weekdayNarrowNames(weekStart: DayOfWeek): List<String> {
    val locale = Locale.getDefault()
    return (0 until 7).map { i ->
        java.time.DayOfWeek.of((weekStart.ordinal + i) % 7 + 1)
            .getDisplayName(JavaTextStyle.NARROW, locale)
    }
}

@Composable
private fun WeekRow(
    layout: WeekLayout,
    currentMonth: Month,
    today: LocalDate,
    soften: Boolean,
    colW: Dp,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val week = layout.week
    Box(modifier = modifier.fillMaxWidth()) {
        // Day-open targets, one full-height strip per column, sitting *under* the
        // content: a tap anywhere in a day still opens it, as in the app, without
        // every cell in the column carrying a click target of its own. The content
        // above is not clickable, event bars included, so touches fall through to
        // this layer and a day opens wherever it is tapped (#386).
        Row(modifier = GlanceModifier.fillMaxSize()) {
            week.days.forEach { date ->
                Box(
                    modifier = GlanceModifier
                        .width(colW)
                        .fillMaxHeight()
                        .clickable(openDayAction(context, date)),
                ) {}
            }
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // Day numbers. Fixed height so every week's number row lines up whether
            // or not it holds today's larger pill.
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(DAY_NUMBER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                week.days.forEach { date ->
                    DayNumber(
                        date = date,
                        isToday = date == today,
                        inMonth = date.month == currentMonth,
                        colW = colW,
                    )
                }
            }
            Spacer(GlanceModifier.height(2.dp))
            // One lane row per event row. A multi-day span is a single Box spanning
            // its columns (colW * n) so it's connected with no seam and rounded ends.
            // Lanes past the last one this week fills emit nothing at all.
            // The grid's view budget caps the lanes per week (#372); what doesn't
            // fit folds into the "+N" row. Counted the same way by WeekLayout.viewIds.
            layout.lanes.forEach { cells ->
                LaneRow(cells = cells, soften = soften, colW = colW)
                Spacer(GlanceModifier.height(1.dp))
            }
            if (layout.overflow.isNotEmpty()) OverflowRow(cells = layout.overflow, colW = colW)
        }
    }
}

/**
 * Open [date]'s day view rooted in the month view (so back returns to the month
 * grid) — the same target the in-app month grid uses when a day cell is tapped.
 * The only tap target in a day column, so a tap anywhere on a day opens it.
 */
private fun openDayAction(context: Context, date: LocalDate) =
    actionStartActivity(MainActivity.openDateIntent(context, date, CalendarView.Month))

/**
 * A day's number. Every day but today is a bare centred [Text] carrying the column
 * width itself — a wrapping Box costs three views, and there are 42 of these.
 * Today keeps its filled circle, which does need the Box.
 */
@Composable
private fun DayNumber(date: LocalDate, isToday: Boolean, inMonth: Boolean, colW: Dp) {
    val style = TextStyle(
        color = when {
            isToday -> GlanceTheme.colors.onPrimary
            inMonth -> GlanceTheme.colors.onSurface
            else -> GlanceTheme.colors.onSurfaceVariant
        },
        fontSize = 11.sp,
        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    if (!isToday) {
        Text(text = date.day.toString(), style = style, modifier = GlanceModifier.width(colW))
        return
    }
    Box(modifier = GlanceModifier.width(colW), contentAlignment = Alignment.Center) {
        Box(
            modifier = GlanceModifier
                .size(DAY_NUMBER_HEIGHT)
                .cornerRadius(DAY_NUMBER_HEIGHT / 2)
                .background(GlanceTheme.colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = date.day.toString(), style = style)
        }
    }
}

@Composable
private fun LaneRow(cells: List<GridCell<EventInstance>>, soften: Boolean, colW: Dp) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        cells.forEach { cell ->
            val event = cell.item
            if (event == null) {
                Spacer(GlanceModifier.width(colW * cell.cols).height(LANE_HEIGHT))
            } else {
                SpanBar(
                    event = event,
                    soften = soften,
                    width = colW * cell.cols,
                )
            }
        }
    }
}

/** A single connected, rounded event bar [width] wide with its clipped title. */
@Composable
private fun SpanBar(event: EventInstance, soften: Boolean, width: Dp) {
    val context = LocalContext.current
    val dayFill = eventFill(event.color, dark = false, soften)
    val nightFill = eventFill(event.color, dark = true, soften)
    Box(
        modifier = GlanceModifier
            .width(width)
            .height(LANE_HEIGHT)
            .padding(horizontal = 1.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(4.dp)
                .background(themedColor(day = dayFill, night = nightFill)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = event.title.ifBlank { context.getString(R.string.event_untitled) },
                maxLines = 1,
                style = TextStyle(
                    color = themedColor(day = eventInk(dayFill), night = eventInk(nightFill)),
                    fontSize = 9.sp,
                    textDecoration = glanceDeclinedDecoration(event.isDeclined),
                ),
                modifier = GlanceModifier.padding(horizontal = 3.dp),
            )
        }
    }
}

/** The "+N" row, drawn only for weeks that hide something, gaps merged as in a lane. */
@Composable
private fun OverflowRow(cells: List<GridCell<Int>>, colW: Dp) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        cells.forEach { cell ->
            if (cell.item == null) {
                Spacer(GlanceModifier.width(colW * cell.cols).height(LANE_HEIGHT))
            } else {
                Text(
                    text = "+${cell.item}",
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
                    modifier = GlanceModifier.width(colW).padding(start = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionMessage() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = context.getString(R.string.widget_needs_permission),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = GlanceModifier.wallpaperBacking().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// Locale.getDefault() rather than currentLocale(): Glance has no
// LocalConfiguration, and the widget is re-rendered on a configuration change
// anyway (same convention as AgendaWidget).
private fun monthLabel(month: YearMonth, currentYear: Int): String =
    formatCalendarTitle(
        date = java.time.LocalDate.of(month.year, month.month.ordinal + 1, 1),
        locale = Locale.getDefault(),
        currentYear = currentYear,
        skeleton = "LLLL",
    )
