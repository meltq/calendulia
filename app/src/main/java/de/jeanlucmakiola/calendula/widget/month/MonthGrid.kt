package de.jeanlucmakiola.calendula.widget.month

import androidx.compose.ui.unit.Dp
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.month.DaySeating
import de.jeanlucmakiola.calendula.ui.month.MonthWeek
import de.jeanlucmakiola.calendula.ui.month.seating
import de.jeanlucmakiola.calendula.ui.month.spanAt

/** A cell [cols] columns wide holding [item], or a merged gap where [item] is null. */
internal data class GridCell<T : Any>(val item: T?, val cols: Int)

/**
 * The seven columns as cells and the gaps between them, consecutive empty
 * columns merged into one: the day-open taps come from the strip underneath,
 * so a gap needs no per-column view. [cellAt] gives what starts at a column
 * and how many columns it covers.
 */
internal fun <T : Any> mergeGaps(columns: Int, cellAt: (Int) -> GridCell<T>?): List<GridCell<T>> {
    val cells = mutableListOf<GridCell<T>>()
    var gap = 0
    var col = 0
    while (col < columns) {
        val cell = cellAt(col)
        if (cell == null) {
            gap += 1
            col += 1
            continue
        }
        if (gap > 0) cells += GridCell(null, gap)
        gap = 0
        cells += cell
        col += cell.cols
    }
    if (gap > 0) cells += GridCell(null, gap)
    return cells
}

/**
 * One week of the grid seated with [laneCap] lanes, drawn [compact] when the
 * row is too short for a full one. [showsOverflow] is off when the week has no
 * room for the "+N" row.
 */
internal class WeekLayout(
    val week: MonthWeek,
    val laneCap: Int,
    val showsOverflow: Boolean = true,
    val compact: Boolean = false,
) {
    private val seating: List<DaySeating> = week.days.indices.map { week.seating(it, laneCap) }

    /** Lanes filled from the top; empty lanes below are not drawn. */
    val usedLanes: Int = seating.maxOfOrNull { day -> day.seated.maxOfOrNull { it.lane + 1 } ?: 0 } ?: 0

    /** Each drawn lane as bars and merged gaps. */
    val lanes: List<List<GridCell<EventInstance>>> = List(usedLanes) { lane ->
        mergeGaps(week.days.size) { col ->
            val seat = seating[col].seated.firstOrNull { it.lane == lane } ?: return@mergeGaps null
            val endCol = if (seat.bar) week.spanAt(col, lane)?.endCol ?: col else col
            GridCell(seat.event, endCol - col + 1)
        }
    }

    /** The "+N" row as counts and merged gaps; empty when everything fits. */
    val overflow: List<GridCell<Int>> = seating.map { it.hidden.size }.let { hidden ->
        if (hidden.none { it > 0 }) emptyList()
        else mergeGaps(hidden.size) { col -> hidden[col].takeIf { it > 0 }?.let { GridCell(it, 1) } }
    }
}

/** How many lanes a week row holds, and whether they're the [compact] height. */
internal data class LaneFit(val lanes: Int, val compact: Boolean = false)

/**
 * Event lanes a week row [weekHeight] tall holds under its day number, held to
 * [MAX_LANES] so a tall widget's payload stays bounded (#214). A row too short
 * for one full lane still gets a compact one when its text fits at [fontScale] (#491).
 */
internal fun lanesFitting(weekHeight: Dp, fontScale: Float = 1f): LaneFit {
    val room = weekHeight.value - DAY_ROW_DP - LANES_TOP_DP
    val full = (room / LANE_ROW_DP).toInt().coerceIn(0, MAX_LANES)
    val compactFits = room >= COMPACT_LANE_ROW_DP && fontScale <= COMPACT_MAX_FONT_SCALE
    return if (full == 0 && compactFits) LaneFit(1, compact = true) else LaneFit(full)
}

/**
 * Seats each week in [fit]'s lanes. A week whose events need more gives its
 * last lane to the "+N" row instead, unless that would leave it no lane at all:
 * a single lane keeps its bars and drops the count (#491).
 */
internal fun fitWeeks(weeks: List<MonthWeek>, fit: LaneFit): List<WeekLayout> = weeks.map { week ->
    val lanes = fit.lanes
    if (lanes <= 1) return@map WeekLayout(week, laneCap = lanes, showsOverflow = false, compact = fit.compact)
    val needed = WeekLayout(week, UNCAPPED_LANES).usedLanes
    WeekLayout(week, if (needed <= lanes) lanes else lanes - 1)
}

/** Lanes a week shows at most, however tall the widget. */
internal const val MAX_LANES = 8

private const val DAY_ROW_DP = 18f
private const val LANES_TOP_DP = 2f
private const val LANE_ROW_DP = 15f
/** Matches `widget_month_lane_compact`'s height; its 9sp text clips past [COMPACT_MAX_FONT_SCALE]. */
private const val COMPACT_LANE_ROW_DP = 12f
private const val COMPACT_MAX_FONT_SCALE = 1.1f
private const val UNCAPPED_LANES = 64
