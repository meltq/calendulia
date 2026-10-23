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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.AndroidRemoteViews
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
import de.jeanlucmakiola.floret.glance.LocalWidgetThemeMode
import de.jeanlucmakiola.floret.glance.WidgetActionStyle
import de.jeanlucmakiola.floret.glance.WidgetHeader
import de.jeanlucmakiola.floret.glance.WidgetHeaderAction
import de.jeanlucmakiola.floret.glance.WidgetHeaderActionGap
import de.jeanlucmakiola.floret.glance.WidgetHeaderTextAction
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

private val GRID_HPADDING = 8.dp

/** Height around the week rows: body padding, header, its spacer and the weekday row. */
private val GRID_CHROME_HEIGHT = 62.dp

private fun currentMonthIndex(zone: TimeZone): Int = monthIndexOf(today(zone))

private fun yearMonthOf(index: Int): YearMonth =
    YearMonth(index / 12, Month(index % 12 + 1))

/**
 * Month-grid widget: a 6×7 calendar with today highlighted, connected multi-day
 * event bars and titled single-day pills (the in-app lane layout via
 * [layoutMonthWeeks]), and prev/next/today navigation.
 *
 * The week rows are one plain RemoteViews tree ([monthGridViews]) embedded in
 * the Glance widget, so a busy month isn't held to Glance's view-id pool (#372);
 * the lanes each week shows come from the widget's height. The displayed month lives in Glance
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
 * row here emits only what it fills, and lanes stop at what the height shows.
 */
class MonthWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * Exact rather than [SizeMode.Single] or [SizeMode.Responsive], both of which
     * return early from `GlanceAppWidget.resize` — under those the grid is never
     * recomposed for a new size and a resized widget clips instead of reflowing.
     * Exact costs one serialised copy per host size, so the grid draws only the
     * lanes the size can show, folding the rest into "+N".
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
                MonthHeader(label = "Calendula", today = today(systemZone()))
                PermissionMessage()
            }
            is MonthWidgetSource.Ready -> {
                val zone = systemZone()
                val ym = yearMonthOf(index)
                // Column width from the live widget size, minus our H padding.
                val colW = (LocalSize.current.width - GRID_HPADDING * 2) / 7
                val weeks = layoutMonthWeeks(ym, source.weekStart, source.instances, zone)
                val weekHeight = (LocalSize.current.height - GRID_CHROME_HEIGHT) / weeks.size
                val layouts = fitWeeks(weeks, lanesFitting(weekHeight))
                val context = LocalContext.current
                val mode = LocalWidgetThemeMode.current
                val colors = GridColors(
                    onSurface = GlanceTheme.colors.onSurface.dayNight(context, mode),
                    onSurfaceVariant = GlanceTheme.colors.onSurfaceVariant.dayNight(context, mode),
                    primary = GlanceTheme.colors.primary.dayNight(context, mode),
                    onPrimary = GlanceTheme.colors.onPrimary.dayNight(context, mode),
                )

                MonthHeader(label = monthLabel(ym, source.today.year), today = source.today)
                Spacer(GlanceModifier.height(4.dp))
                // One backing for the whole grid on a transparent widget (#292).
                Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight().wallpaperBacking()) {
                    WeekdayHeader(weekStart = source.weekStart, colW = colW)
                    AndroidRemoteViews(
                        remoteViews = monthGridViews(context, layouts, ym.month, source.today, source.soften, colors, mode),
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(label: String, today: LocalDate) {
    val context = LocalContext.current
    // Tap the month/year title to open the app on the month view (issue #18),
    // rooted over the default home so backing out returns there.
    WidgetHeader(
        title = label,
        titleAction = actionStartActivity(MainActivity.openViewIntent(context, CalendarView.Month)),
        modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp),
    ) {
        WidgetHeaderAction(
            icon = ImageProvider(R.drawable.ic_widget_chevron_left),
            contentDescription = context.getString(R.string.widget_prev_month),
            action = actionRunCallback<ShiftMonthAction>(actionParametersOf(ShiftMonthAction.deltaKey to -1)),
            style = WidgetActionStyle.TONAL,
        )
        WidgetHeaderActionGap()
        // The "today" pill carries today's number like the app icon, and snaps
        // the grid back to the current month in place.
        WidgetHeaderTextAction(
            text = today.day.toString(),
            contentDescription = context.getString(R.string.widget_today),
            action = actionRunCallback<ResetMonthAction>(),
        )
        WidgetHeaderActionGap()
        WidgetHeaderAction(
            icon = ImageProvider(R.drawable.ic_widget_chevron_right),
            contentDescription = context.getString(R.string.widget_next_month),
            action = actionRunCallback<ShiftMonthAction>(actionParametersOf(ShiftMonthAction.deltaKey to 1)),
            style = WidgetActionStyle.TONAL,
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
