package de.jeanlucmakiola.calendula.ui.filter

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.groupByAccount
import org.junit.jupiter.api.Test

class FilterUiStateTest {

    private fun calendar(id: Long) = CalendarSource(id, "Cal $id", "x@y", "LOCAL", 0, true)

    @Test
    fun `hiddenOnlyIn counts listed calendars the view hides on its own`() {
        val state = FilterUiState.Success(
            groups = listOf(calendar(1L), calendar(2L), calendar(3L)).groupByAccount(),
            // 3 is hidden everywhere from the drawer already.
            hidden = setOf(3L),
            hiddenViews = mapOf(
                1L to setOf(CalendarView.Month),
                2L to setOf(CalendarView.Month, CalendarView.Week),
                3L to setOf(CalendarView.Month),
                // Not listed (switched off device-wide).
                9L to setOf(CalendarView.Month),
            ),
        )
        assertThat(state.hiddenOnlyIn(CalendarView.Month)).isEqualTo(2)
        assertThat(state.hiddenOnlyIn(CalendarView.Week)).isEqualTo(1)
        assertThat(state.hiddenOnlyIn(CalendarView.Agenda)).isEqualTo(0)
    }
}
