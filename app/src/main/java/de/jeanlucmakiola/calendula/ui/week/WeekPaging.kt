package de.jeanlucmakiola.calendula.ui.week

import de.jeanlucmakiola.calendula.ui.common.startOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** Pages the week pager spans — a little under four centuries. */
internal const val WEEK_PAGE_COUNT: Int = 20_000

/** Pages the multi-day pager spans — over three centuries at the widest page. */
internal const val DAYS_PAGE_COUNT: Int = 40_000

/** Pages the day pager spans — a little under four centuries. */
internal const val DAY_PAGE_COUNT: Int = 140_000

/** A fixed day every grid's pages are counted from, at its middle page. */
private val DAYS_EPOCH = LocalDate(2000, 1, 1)

/** The week the week pager's middle page shows; any date works, it only has to stay fixed. */
private val WEEK_EPOCH = LocalDate(2000, 1, 3)

/**
 * How a timeline pager cuts the calendar: runs of [dayCount] days, offset
 * [phase] days from a fixed epoch, over [pageCount] pages centred on it. The
 * multi-day view's phase (#193) stays put while paging and only changes when a
 * jump moves another date to the first column.
 */
data class DaysGrid(val dayCount: Int, val phase: Int, val pageCount: Int = DAYS_PAGE_COUNT) {

    private val epochPage: Int get() = pageCount / 2

    /** First day shown on pager [page]. */
    fun startForPage(page: Int): LocalDate =
        DAYS_EPOCH.plus(phase + (page - epochPage) * dayCount, DateTimeUnit.DAY)

    /** The pager page showing [date]. */
    fun pageFor(date: LocalDate): Int =
        (epochPage + Math.floorDiv(DAYS_EPOCH.daysUntil(date) - phase, dayCount))
            .coerceIn(0, pageCount - 1)

    companion object {
        /** Single days: every date starts a page of its own. */
        val days: DaysGrid = DaysGrid(dayCount = 1, phase = 0, pageCount = DAY_PAGE_COUNT)

        /** Whole weeks, starting on [firstDay]. */
        fun weeks(firstDay: DayOfWeek): DaysGrid = DaysGrid(
            dayCount = 7,
            phase = DAYS_EPOCH.daysUntil(WEEK_EPOCH.startOfWeek(firstDay)),
            pageCount = WEEK_PAGE_COUNT,
        )

        /** The grid of [dayCount]-day pages on which [date] is a page's first day. */
        fun startingAt(date: LocalDate, dayCount: Int): DaysGrid =
            DaysGrid(dayCount, Math.floorMod(DAYS_EPOCH.daysUntil(date), dayCount))
    }
}

/**
 * Where the focused date goes when the pager settles on [page] (#184): kept if
 * the page still shows it, otherwise moved by the pages travelled, so it keeps
 * its place in the page — the same weekday a week on.
 */
internal fun DaysGrid.focusOnPage(focus: LocalDate, page: Int): LocalDate {
    val start = startForPage(page)
    val end = start.plus(dayCount - 1, DateTimeUnit.DAY)
    if (focus in start..end) return focus
    return focus.plus((page - pageFor(focus)) * dayCount, DateTimeUnit.DAY).coerceIn(start, end)
}
