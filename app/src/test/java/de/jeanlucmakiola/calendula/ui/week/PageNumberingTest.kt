package de.jeanlucmakiola.calendula.ui.week

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Test

/**
 * Pins each grid's page numbering to the formulas it shipped with: a pager
 * restores its page by number, so a change here moves the user to another date.
 */
class PageNumberingTest {

    private val dates: List<LocalDate> = buildList {
        var d = LocalDate(1500, 1, 1)
        while (d < LocalDate(2500, 1, 1)) {
            add(d)
            d = d.plus(37, DateTimeUnit.DAY)
        }
        var e = LocalDate(1999, 12, 1)
        while (e < LocalDate(2000, 2, 1)) {
            add(e)
            e = e.plus(1, DateTimeUnit.DAY)
        }
    }

    private fun pages(count: Int): List<Int> =
        listOf(-7, -1, 0, 1, count / 2 - 3, count / 2 - 1, count / 2, count / 2 + 1, count - 2, count - 1, count, count + 5) +
            (0 until count step 997)

    @Test
    fun `week pages keep their numbering for every first day`() {
        DayOfWeek.entries.forEach { firstDay ->
            val grid = DaysGrid.weeks(firstDay)
            assertThat(grid.dayCount).isEqualTo(7)
            assertThat(grid.pageCount).isEqualTo(LEGACY_WEEK_COUNT)
            pages(LEGACY_WEEK_COUNT).forEach { page ->
                assertThat(grid.startForPage(page)).isEqualTo(legacyWeekStart(page, firstDay))
            }
            dates.forEach { date ->
                assertThat(grid.pageFor(date)).isEqualTo(legacyWeekPage(date, firstDay))
            }
        }
    }

    @Test
    fun `multi-day pages keep their numbering for every count and phase`() {
        (1..7).forEach { count ->
            (-count..count * 2).forEach { phase ->
                val grid = DaysGrid(count, phase)
                assertThat(grid.pageCount).isEqualTo(LEGACY_DAYS_COUNT)
                pages(LEGACY_DAYS_COUNT).forEach { page ->
                    assertThat(grid.startForPage(page)).isEqualTo(legacyDaysStart(page, count, phase))
                }
                dates.forEach { date ->
                    assertThat(grid.pageFor(date)).isEqualTo(legacyDaysPage(date, count, phase))
                }
            }
            dates.filter { it.year in 1950..2050 }.forEach { date ->
                val grid = DaysGrid.startingAt(date, count)
                assertThat(grid.startForPage(grid.pageFor(date))).isEqualTo(date)
            }
        }
    }

    @Test
    fun `day pages keep their numbering`() {
        val grid = DaysGrid.days
        assertThat(grid.dayCount).isEqualTo(1)
        assertThat(grid.pageCount).isEqualTo(LEGACY_DAY_COUNT)
        pages(LEGACY_DAY_COUNT).forEach { page ->
            assertThat(grid.startForPage(page)).isEqualTo(legacyDayStart(page))
        }
        dates.forEach { date ->
            assertThat(grid.pageFor(date)).isEqualTo(legacyDayPage(date))
        }
    }

    @Test
    fun `a multi-day jump re-frames exactly when the date would open mid-page`() {
        val inRange = dates.filter { it.year in 1950..2050 }
        (3..6).forEach { count ->
            val grid = DaysGrid.startingAt(LocalDate(2026, 10, 16), count)
            inRange.forEach { target ->
                val midPage = grid.startForPage(grid.pageFor(target)) != target
                assertThat(DaysGrid.startingAt(target, count) != grid).isEqualTo(midPage)
            }
        }
    }

    @Test
    fun `grids are equal exactly when they page the same way`() {
        assertThat(DaysGrid.weeks(DayOfWeek.MONDAY)).isEqualTo(DaysGrid.weeks(DayOfWeek.MONDAY))
        assertThat(DaysGrid.weeks(DayOfWeek.MONDAY)).isNotEqualTo(DaysGrid.weeks(DayOfWeek.SUNDAY))
        assertThat(DaysGrid(3, 1)).isEqualTo(DaysGrid(3, 1))
        assertThat(DaysGrid(3, 1)).isNotEqualTo(DaysGrid(3, 2))
        assertThat(DaysGrid(3, 1)).isNotEqualTo(DaysGrid(4, 1))
        assertThat(DaysGrid.days).isEqualTo(DaysGrid.days)
        assertThat(DaysGrid.days.hashCode()).isEqualTo(DaysGrid.days.hashCode())
    }

    private companion object {
        const val LEGACY_WEEK_COUNT = 20_000
        const val LEGACY_DAYS_COUNT = 40_000
        const val LEGACY_DAY_COUNT = 140_000
        val WEEK_EPOCH = LocalDate(2000, 1, 3)
        val DAYS_EPOCH = LocalDate(2000, 1, 1)

        fun LocalDate.weekStart(firstDay: DayOfWeek): LocalDate =
            minus(((dayOfWeek.ordinal - firstDay.ordinal) + 7) % 7, DateTimeUnit.DAY)

        fun legacyWeekStart(page: Int, firstDay: DayOfWeek): LocalDate =
            WEEK_EPOCH.weekStart(firstDay).plus((page - LEGACY_WEEK_COUNT / 2) * 7, DateTimeUnit.DAY)

        fun legacyWeekPage(date: LocalDate, firstDay: DayOfWeek): Int {
            val days = WEEK_EPOCH.weekStart(firstDay).daysUntil(date.weekStart(firstDay))
            return (LEGACY_WEEK_COUNT / 2 + days / 7).coerceIn(0, LEGACY_WEEK_COUNT - 1)
        }

        fun legacyDaysStart(page: Int, count: Int, phase: Int): LocalDate =
            DAYS_EPOCH.plus(phase + (page - LEGACY_DAYS_COUNT / 2) * count, DateTimeUnit.DAY)

        fun legacyDaysPage(date: LocalDate, count: Int, phase: Int): Int =
            (LEGACY_DAYS_COUNT / 2 + Math.floorDiv(DAYS_EPOCH.daysUntil(date) - phase, count))
                .coerceIn(0, LEGACY_DAYS_COUNT - 1)

        fun legacyDayStart(page: Int): LocalDate =
            DAYS_EPOCH.plus(page - LEGACY_DAY_COUNT / 2, DateTimeUnit.DAY)

        fun legacyDayPage(date: LocalDate): Int =
            (LEGACY_DAY_COUNT / 2 + DAYS_EPOCH.daysUntil(date)).coerceIn(0, LEGACY_DAY_COUNT - 1)
    }
}
