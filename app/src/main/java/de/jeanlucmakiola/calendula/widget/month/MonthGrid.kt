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

/** One week of the grid seated with [laneCap] lanes. */
internal class WeekLayout(val week: MonthWeek, val laneCap: Int) {
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

/** Event lanes a week row [weekHeight] tall holds under its day number. */
internal fun lanesFitting(weekHeight: Dp): Int =
    ((weekHeight.value - DAY_ROW_DP - LANES_TOP_DP) / LANE_ROW_DP).toInt().coerceAtLeast(0)

/**
 * Seats each week in [lanes] lanes. A week whose events need more gives its
 * last lane to the "+N" row instead.
 */
internal fun fitWeeks(weeks: List<MonthWeek>, lanes: Int): List<WeekLayout> = weeks.map { week ->
    val needed = WeekLayout(week, UNCAPPED_LANES).usedLanes
    WeekLayout(week, if (needed <= lanes) lanes else (lanes - 1).coerceAtLeast(0))
}

private const val DAY_ROW_DP = 18f
private const val LANES_TOP_DP = 2f
private const val LANE_ROW_DP = 15f
private const val UNCAPPED_LANES = 64
