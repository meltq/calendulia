package de.jeanlucmakiola.calendula.widget.month

import android.os.Build
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.month.DaySeating
import de.jeanlucmakiola.calendula.ui.month.MonthWeek
import de.jeanlucmakiola.calendula.ui.month.seating
import de.jeanlucmakiola.calendula.ui.month.spanAt
import kotlinx.datetime.LocalDate

/** Event rows (lanes) a week draws at most before the rest collapse into "+N". */
internal const val MAX_LANES = 3

/**
 * View ids one rendering may spend. Glance has 499 and fails the whole widget
 * past them (#372); staying well under also keeps a busy month's payload, sent
 * once per host size, clear of the binder limit #214 ran into.
 */
internal const val VIEW_BUDGET = 400

/**
 * What views cost in Glance 1.2's view ids. A clickable view that isn't a
 * Button is wrapped in a Box sized like it, with a ripple Image and the view
 * itself filling it. Below Android 12 a view with a fixed width or height is
 * inflated into a sizing frame first, which takes a second id.
 */
internal class ViewIds(sdkInt: Int = Build.VERSION.SDK_INT) {
    val plain = 1
    val sized = if (sdkInt < Build.VERSION_CODES.S) 2 else 1
    val clickablePlain = plain + 2
    val clickableSized = sized + 2

    /**
     * `MonthWidgetBody`'s views outside the week rows: the surface Box, its
     * Column, the header row with its clickable title Box, title Text and (on a
     * transparent widget) the spacer after the title pill, two
     * sized clickable icon pills with their sized icons, the sized clickable
     * today pill with its Text and the two sized action gaps, the spacer, the
     * grid's Column and the weekday row of sized labels.
     */
    val fixed = plain + plain + plain + clickablePlain + plain + plain + 2 * (clickableSized + sized) +
        (clickableSized + plain) + 2 * sized + sized + plain + plain + 7 * sized
}

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

/** One week of the grid seated with [laneCap] lanes, computed once for both drawing and counting. */
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

    /**
     * The view ids `WeekRow` spends on this week, counted the way it draws it:
     * the week Box, the strip Row and its seven sized clickable strips, the
     * content Column, the sized day-number row and its sized numbers (today's
     * pill adds a sized Box around a plain Text), the sized spacer, each lane's
     * Row and sized spacer with sized gaps and bars (a sized Box around a
     * filling Box and Text), and the "+N" Row of sized cells.
     */
    fun viewIds(today: LocalDate, ids: ViewIds): Int {
        val days = week.days.size
        val strips = ids.plain + days * ids.clickableSized
        val numbers = ids.sized + days * ids.sized + if (today in week.days) ids.sized + ids.plain else 0
        val lanes = lanes.sumOf { cells ->
            ids.plain + ids.sized + cells.sumOf { if (it.item == null) ids.sized else ids.sized + 2 * ids.plain }
        }
        val overflow = if (overflow.isEmpty()) 0 else ids.plain + overflow.size * ids.sized
        return ids.plain + strips + ids.plain + numbers + ids.sized + lanes + overflow
    }
}

/**
 * Seats each week so the whole grid fits [budget] view ids (#372). Each round
 * folds the lowest lanes of whichever week saves the most ids per lane given
 * up, their events joining "+N", until the month fits.
 */
internal fun layoutWeeks(
    weeks: List<MonthWeek>,
    today: LocalDate,
    ids: ViewIds = ViewIds(),
    budget: Int = VIEW_BUDGET - ids.fixed,
): List<WeekLayout> {
    val layouts = weeks.map { week -> WeekLayout(week, WeekLayout(week, MAX_LANES).usedLanes) }.toMutableList()
    val costs = layouts.map { it.viewIds(today, ids) }.toMutableList()
    while (costs.sum() > budget) {
        var best: Triple<Int, WeekLayout, Int>? = null
        var bestRate = 0.0
        layouts.forEachIndexed { i, layout ->
            for (cap in layout.laneCap - 1 downTo 0) {
                val folded = WeekLayout(layout.week, cap)
                val cost = folded.viewIds(today, ids)
                val rate = (costs[i] - cost).toDouble() / (layout.laneCap - cap)
                if (rate > bestRate) {
                    bestRate = rate
                    best = Triple(i, folded, cost)
                }
            }
        }
        val (i, folded, cost) = best ?: break
        layouts[i] = folded
        costs[i] = cost
    }
    return layouts
}
