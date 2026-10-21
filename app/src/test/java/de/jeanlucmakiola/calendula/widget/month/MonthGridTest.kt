package de.jeanlucmakiola.calendula.widget.month

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.allDayEvent
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.timedEvent
import de.jeanlucmakiola.calendula.ui.month.MonthWeek
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

    /** Monday Jul 6 to Sunday Jul 12. */
    private val monday = LocalDate(2026, 7, 6)

    private val android16 = ViewIds(sdkInt = 36)
    private val android10 = ViewIds(sdkInt = 29)

    private fun weeks(events: List<EventInstance>): List<MonthWeek> =
        layoutMonthWeeks(jul26, DayOfWeek.MONDAY, events, zone)

    /** July 2026 with [perDay] timed events on every day of the grid. */
    private fun busyWeeks(perDay: Int): List<MonthWeek> {
        val first = LocalDate(2026, 6, 29)
        var id = 0L
        return weeks(
            (0 until 42).flatMap { offset ->
                val day = first.plus(offset, DateTimeUnit.DAY)
                (1..perDay).map { hour -> timedEvent(day, hour, zone = zone, id = ++id, title = "E$id") }
            },
        )
    }

    private fun List<WeekLayout>.cost(ids: ViewIds) = sumOf { it.viewIds(today, ids) }

    @Test
    fun `a quiet month keeps every lane it uses`() {
        val layouts = layoutWeeks(busyWeeks(perDay = 1), today, android16)
        assertThat(layouts.map { it.laneCap }.distinct()).containsExactly(1)
        assertThat(layouts.all { it.overflow.isEmpty() }).isTrue()
    }

    @Test
    fun `a full month folds lanes until it fits, on every Android version`() {
        for (ids in listOf(android16, android10)) {
            val weeks = busyWeeks(perDay = 5)
            assertThat(weeks.map { WeekLayout(it, MAX_LANES) }.cost(ids)).isGreaterThan(VIEW_BUDGET - ids.fixed)

            val layouts = layoutWeeks(weeks, today, ids)
            assertThat(layouts.cost(ids)).isAtMost(VIEW_BUDGET - ids.fixed)
            assertThat(layouts.any { it.laneCap < MAX_LANES }).isTrue()
        }
    }

    @Test
    fun `older Android folds more, since a sized view costs it two ids`() {
        val weeks = busyWeeks(perDay = 3)
        val newer = layoutWeeks(weeks, today, android16).sumOf { it.laneCap }
        val older = layoutWeeks(weeks, today, android10).sumOf { it.laneCap }
        assertThat(older).isLessThan(newer)
    }

    @Test
    fun `folded events are counted in the overflow`() {
        val layout = WeekLayout(busyWeeks(perDay = 5)[1], laneCap = 1)
        assertThat(layout.usedLanes).isEqualTo(1)
        assertThat(layout.overflow).containsExactly(GridCell(4, 1), GridCell(4, 1), GridCell(4, 1),
            GridCell(4, 1), GridCell(4, 1), GridCell(4, 1), GridCell(4, 1)).inOrder()
    }

    @Test
    fun `a multi-day bar is one cell as wide as its days`() {
        val bar = allDayEvent(monday.plus(1, DateTimeUnit.DAY), monday.plus(3, DateTimeUnit.DAY), id = 1)
        val layout = WeekLayout(weeks(listOf(bar))[1], MAX_LANES)
        assertThat(layout.lanes).hasSize(1)
        assertThat(layout.lanes[0]).containsExactly(GridCell(null, 1), GridCell(bar, 3), GridCell(null, 3)).inOrder()
    }

    @Test
    fun `a bar on a folded lane moves into the overflow of each day it covers`() {
        val top = allDayEvent(monday, monday.plus(6, DateTimeUnit.DAY), id = 1)
        val lower = allDayEvent(monday.plus(2, DateTimeUnit.DAY), monday.plus(3, DateTimeUnit.DAY), id = 2)
        val layout = WeekLayout(weeks(listOf(top, lower))[1], laneCap = 1)
        assertThat(layout.lanes).hasSize(1)
        assertThat(layout.lanes[0]).containsExactly(GridCell(top, 7))
        assertThat(layout.overflow).containsExactly(GridCell(null, 2), GridCell(1, 1), GridCell(1, 1), GridCell(null, 3))
            .inOrder()
    }

    @Test
    fun `gaps are merged`() {
        val counts = listOf(0, 0, 2, 0, 0, 0, 1)
        val cells = mergeGaps(counts.size) { col -> counts[col].takeIf { it > 0 }?.let { GridCell(it, 1) } }
        assertThat(cells).containsExactly(GridCell(null, 2), GridCell(2, 1), GridCell(null, 3), GridCell(1, 1))
            .inOrder()
    }
}
