package de.jeanlucmakiola.calendula.widget.month

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.unit.ColorProvider
import de.jeanlucmakiola.calendula.MainActivity
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.domain.isDeclined
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.eventFill
import de.jeanlucmakiola.calendula.ui.common.eventInk
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/** A colour for the light and the dark widget theme; equal when the widget pins one. */
internal data class DayNight(val day: Int, val night: Int) {
    fun current(context: Context): Int =
        if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) night else day
}

/** The theme colours the plain-RemoteViews grid draws with, resolved out of Glance's providers. */
internal data class GridColors(
    val onSurface: DayNight,
    val onSurfaceVariant: DayNight,
    val primary: DayNight,
    val onPrimary: DayNight,
)

internal fun ColorProvider.dayNight(context: Context, mode: WidgetThemeMode): DayNight {
    fun at(night: Boolean): Int {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return getColor(context.createConfigurationContext(config)).toArgb()
    }
    return when (mode) {
        WidgetThemeMode.SYSTEM -> DayNight(day = at(false), night = at(true))
        WidgetThemeMode.LIGHT -> at(false).let { DayNight(it, it) }
        WidgetThemeMode.DARK -> at(true).let { DayNight(it, it) }
    }
}

private fun pinned(day: Color, night: Color, mode: WidgetThemeMode): DayNight = when (mode) {
    WidgetThemeMode.SYSTEM -> DayNight(day.toArgb(), night.toArgb())
    WidgetThemeMode.LIGHT -> DayNight(day.toArgb(), day.toArgb())
    WidgetThemeMode.DARK -> DayNight(night.toArgb(), night.toArgb())
}

/**
 * The month's week rows as one plain RemoteViews tree, embedded in the Glance
 * widget so its views don't draw on Glance's view-id pool (#372): every event
 * gets its bar, and only what can't fit the widget's height folds into "+N".
 *
 * A host may reapply a new rendering onto the views of the last one rather than
 * inflate it afresh, so every container is cleared before it's filled and every
 * view state is set both ways, never just when it differs from the layout.
 */
internal fun monthGridViews(
    context: Context,
    layouts: List<WeekLayout>,
    currentMonth: Month,
    today: LocalDate,
    soften: Boolean,
    colors: GridColors,
    mode: WidgetThemeMode,
): RemoteViews {
    val grid = RemoteViews(context.packageName, R.layout.widget_month_grid)
    grid.removeAllViews(R.id.month_grid)
    layouts.forEach { grid.addView(R.id.month_grid, weekViews(context, it, currentMonth, today, soften, colors, mode)) }
    return grid
}

private fun weekViews(
    context: Context,
    layout: WeekLayout,
    currentMonth: Month,
    today: LocalDate,
    soften: Boolean,
    colors: GridColors,
    mode: WidgetThemeMode,
): RemoteViews {
    val week = RemoteViews(context.packageName, R.layout.widget_month_week)
    week.removeAllViews(R.id.week_lanes)
    layout.week.days.forEachIndexed { col, date ->
        week.setOnClickPendingIntent(STRIP_IDS[col], openDayIntent(context, date))
        val dayId = DAY_IDS[col]
        week.setTextViewText(dayId, date.day.toString())
        val isToday = date == today
        week.setColor(context, dayId, "setTextColor", when {
            isToday -> colors.onPrimary
            date.month == currentMonth -> colors.onSurface
            else -> colors.onSurfaceVariant
        })
        week.setViewVisibility(TODAY_IDS[col], if (isToday) View.VISIBLE else View.GONE)
        if (isToday) week.setColor(context, TODAY_IDS[col], "setColorFilter", colors.primary)
    }
    layout.lanes.forEach { cells ->
        val lane = laneViews(context)
        cells.forEach { cell ->
            val event = cell.item
            lane.addView(R.id.lane, if (event == null) gap(context, cell.cols) else bar(context, event, cell.cols, soften, mode))
        }
        week.addView(R.id.week_lanes, lane)
    }
    if (layout.overflow.isNotEmpty()) {
        val lane = laneViews(context)
        layout.overflow.forEach { cell ->
            val count = cell.item
            if (count == null) {
                lane.addView(R.id.lane, gap(context, cell.cols))
            } else {
                val more = RemoteViews(context.packageName, R.layout.widget_month_more)
                more.setTextViewText(R.id.more, "+$count")
                more.setColor(context, R.id.more, "setTextColor", colors.onSurfaceVariant)
                lane.addView(R.id.lane, more)
            }
        }
        week.addView(R.id.week_lanes, lane)
    }
    return week
}

private fun laneViews(context: Context) =
    RemoteViews(context.packageName, R.layout.widget_month_lane).apply { removeAllViews(R.id.lane) }

private fun gap(context: Context, cols: Int) = RemoteViews(context.packageName, GAP_LAYOUTS[cols - 1])

private fun bar(context: Context, event: EventInstance, cols: Int, soften: Boolean, mode: WidgetThemeMode): RemoteViews {
    val views = RemoteViews(context.packageName, BAR_LAYOUTS[cols - 1])
    val dayFill = eventFill(event.color, dark = false, soften)
    val nightFill = eventFill(event.color, dark = true, soften)
    views.setColor(context, R.id.bar_bg, "setColorFilter", pinned(dayFill, nightFill, mode))
    views.setTextViewText(R.id.bar_title, event.title.ifBlank { context.getString(R.string.event_untitled) })
    views.setColor(context, R.id.bar_title, "setTextColor", pinned(eventInk(dayFill), eventInk(nightFill), mode))
    val strike = if (event.isDeclined) Paint.STRIKE_THRU_TEXT_FLAG else 0
    views.setInt(R.id.bar_title, "setPaintFlags", strike or Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    return views
}

private fun RemoteViews.setColor(context: Context, id: Int, method: String, color: DayNight) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && color.day != color.night) {
        setColorInt(id, method, color.day, color.night)
    } else {
        setInt(id, method, color.current(context))
    }
}

private fun openDayIntent(context: Context, date: LocalDate): PendingIntent = PendingIntent.getActivity(
    context,
    date.toEpochDays().toInt(),
    MainActivity.openDateIntent(context, date, CalendarView.Month),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private val STRIP_IDS = intArrayOf(R.id.strip_0, R.id.strip_1, R.id.strip_2, R.id.strip_3, R.id.strip_4, R.id.strip_5, R.id.strip_6)
private val DAY_IDS = intArrayOf(R.id.day_0, R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4, R.id.day_5, R.id.day_6)
private val TODAY_IDS = intArrayOf(
    R.id.today_bg_0, R.id.today_bg_1, R.id.today_bg_2, R.id.today_bg_3, R.id.today_bg_4, R.id.today_bg_5, R.id.today_bg_6,
)
private val BAR_LAYOUTS = intArrayOf(
    R.layout.widget_month_bar_1, R.layout.widget_month_bar_2, R.layout.widget_month_bar_3, R.layout.widget_month_bar_4,
    R.layout.widget_month_bar_5, R.layout.widget_month_bar_6, R.layout.widget_month_bar_7,
)
private val GAP_LAYOUTS = intArrayOf(
    R.layout.widget_month_gap_1, R.layout.widget_month_gap_2, R.layout.widget_month_gap_3, R.layout.widget_month_gap_4,
    R.layout.widget_month_gap_5, R.layout.widget_month_gap_6, R.layout.widget_month_gap_7,
)
