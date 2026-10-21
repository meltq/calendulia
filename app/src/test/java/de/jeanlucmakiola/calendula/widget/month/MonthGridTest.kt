package de.jeanlucmakiola.calendula.widget.month

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.timedEvent
import de.jeanlucmakiola.calendula.ui.month.layoutMonthWeeks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import org.junit.jupiter.api.Test

/** The month widget's view budget (#372). */
class MonthGridTest {

    private val zone = TimeZone.UTC
    private val jul26 = YearMonth(2026, Month.JULY)
    private val today = LocalDate(2026, 7, 15)

    /** July 2026 with [perDay] timed events on every day of the grid. */
    private fun weeks(perDay: Int): List<de.jeanlucmakiola.calendula.ui.month.MonthWeek> {
        val first = LocalDate(2026, 6, 29)
        var id = 0L
        val events = (0 until 42).flatMap { offset ->
            val day = first.plus(offset, DateTimeUnit.DAY)
            (1..perDay).map { hour ->
                timedEvent(day, hour, zone = zone, id = ++id, title = "E$id", color = 0xFFF44336.toInt())
            }
        }
        return layoutMonthWeeks(jul26, DayOfWeek.MONDAY, events, zone)
    }

    @Test
    fun `a quiet month keeps every lane`() {
        val weeks = weeks(perDay = 1)
        assertThat(laneCaps(weeks, today)).containsExactlyElementsIn(List(weeks.size) { MAX_LANES })
    }

    @Test
    fun `a full month folds lanes until it fits the budget`() {
        val weeks = weeks(perDay = 5)
        val fullCost = weeks.sumOf { it.viewCount(MAX_LANES, today) }
        assertThat(fullCost).isGreaterThan(VIEW_BUDGET - FIXED_VIEWS)

        val caps = laneCaps(weeks, today)
        val cost = weeks.indices.sumOf { weeks[it].viewCount(caps[it], today) }
        assertThat(cost).isAtMost(VIEW_BUDGET - FIXED_VIEWS)
        assertThat(caps.any { it < MAX_LANES }).isTrue()
    }

    @Test
    fun `folded events are counted in the overflow`() {
        val week = weeks(perDay = 5)[1]
        val hidden = week.hiddenPerDay(laneCap = 1)
        assertThat(hidden).containsExactlyElementsIn(List(7) { 4 })
        assertThat(week.usedLanes(laneCap = 1)).isEqualTo(1)
    }

    @Test
    fun `a lane of single-day events is one cell per day`() {
        val cells = weeks(perDay = 1)[1].laneCells(lane = 0, laneCap = MAX_LANES)
        assertThat(cells).hasSize(7)
        assertThat(cells.all { it.event != null && it.cols == 1 }).isTrue()
    }

    @Test
    fun `overflow gaps are merged`() {
        assertThat(overflowCells(listOf(0, 0, 2, 0, 0, 0, 1))).containsExactly(
            OverflowCell(0, 2),
            OverflowCell(2, 1),
            OverflowCell(0, 3),
            OverflowCell(1, 1),
        ).inOrder()
        assertThat(overflowCells(List(7) { 0 })).isEmpty()
    }
}
