package de.jeanlucmakiola.calendula.widget.month

import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.ui.month.MonthWeek
import de.jeanlucmakiola.calendula.ui.month.seating
import de.jeanlucmakiola.calendula.ui.month.spanAt
import kotlinx.datetime.LocalDate

/** Event rows (lanes) a week draws at most before the rest collapse into "+N". */
internal const val MAX_LANES = 3

/**
 * Glance gives each rendering 500 view ids and fails the whole widget past them
 * (#372). The grid stays under this, leaving room for Glance's own wrappers.
 */
internal const val VIEW_BUDGET = 440

/** Views outside the week rows: the header row with its title and three icons, a spacer and the weekday row. */
internal const val FIXED_VIEWS = 18

/** A bar occupying [cols] columns, or — with a null event — that many blank ones. */
internal data class LaneCell(val event: EventInstance?, val cols: Int)

/** A "+[count]" label one column wide, or — with a zero count — a gap [cols] wide. */
internal data class OverflowCell(val count: Int, val cols: Int)

/**
 * Lane [lane] split into bars and the gaps between them, consecutive blank
 * columns merged into one gap: the day-open taps come from the strip underneath,
 * so a gap needs no per-column view and a mostly-empty lane collapses to a spacer.
 */
internal fun MonthWeek.laneCells(lane: Int, laneCap: Int): List<LaneCell> {
    val cells = mutableListOf<LaneCell>()
    var gap = 0
    var col = 0
    while (col < days.size) {
        val seat = seating(col, laneCap).seated.firstOrNull { it.lane == lane }
        if (seat == null) {
            gap += 1
            col += 1
            continue
        }
        if (gap > 0) cells += LaneCell(null, gap)
        gap = 0
        val cols = if (seat.bar) (spanAt(col, lane)?.endCol ?: col) - col + 1 else 1
        cells += LaneCell(seat.event, cols)
        col += cols
    }
    if (gap > 0) cells += LaneCell(null, gap)
    return cells
}

/** How many lanes this week fills from the top under [laneCap]; empty lanes below are not drawn. */
internal fun MonthWeek.usedLanes(laneCap: Int): Int =
    days.indices.maxOfOrNull { col -> seating(col, laneCap).seated.maxOfOrNull { it.lane + 1 } ?: 0 } ?: 0

/** Events per day that no lane had room for — the "+N" counts, 0 where all fit. */
internal fun MonthWeek.hiddenPerDay(laneCap: Int): List<Int> =
    days.indices.map { col -> seating(col, laneCap).hidden.size }

/** The "+N" row as labels and merged gaps; empty when nothing is hidden. */
internal fun overflowCells(hidden: List<Int>): List<OverflowCell> {
    if (hidden.none { it > 0 }) return emptyList()
    val cells = mutableListOf<OverflowCell>()
    var gap = 0
    hidden.forEach { count ->
        if (count == 0) {
            gap += 1
        } else {
            if (gap > 0) cells += OverflowCell(0, gap)
            gap = 0
            cells += OverflowCell(count, 1)
        }
    }
    if (gap > 0) cells += OverflowCell(0, gap)
    return cells
}

/** The views `WeekRow` emits for this week with [laneCap] lanes, counted the way it draws them. */
internal fun MonthWeek.viewCount(laneCap: Int, today: LocalDate): Int {
    val strips = 1 + days.size
    val numbers = 1 + days.size + if (today in days) 2 else 0
    val lanes = (0 until usedLanes(laneCap)).sumOf { lane ->
        2 + laneCells(lane, laneCap).sumOf { if (it.event == null) 1 else 3 }
    }
    val overflow = overflowCells(hiddenPerDay(laneCap)).let { if (it.isEmpty()) 0 else 1 + it.size }
    // The week's Box, its content Column and the spacer under the numbers.
    return 3 + strips + numbers + lanes + overflow
}

/**
 * The lanes each week may draw so the grid fits [budget] views: the most
 * expensive week gives up its lowest lane first, its events folding into "+N",
 * until the whole month fits (#372).
 */
internal fun laneCaps(
    weeks: List<MonthWeek>,
    today: LocalDate,
    budget: Int = VIEW_BUDGET - FIXED_VIEWS,
): List<Int> {
    val caps = MutableList(weeks.size) { MAX_LANES }
    val costs = MutableList(weeks.size) { weeks[it].viewCount(MAX_LANES, today) }
    while (costs.sum() > budget) {
        val busiest = weeks.indices.filter { caps[it] > 0 }.maxByOrNull { costs[it] } ?: break
        caps[busiest] -= 1
        costs[busiest] = weeks[busiest].viewCount(caps[busiest], today)
    }
    return caps
}
