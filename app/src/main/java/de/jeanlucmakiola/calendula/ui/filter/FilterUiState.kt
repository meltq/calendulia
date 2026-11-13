package de.jeanlucmakiola.calendula.ui.filter

import de.jeanlucmakiola.calendula.domain.FailureReason
import de.jeanlucmakiola.calendula.ui.common.CalendarAccountGroup
import de.jeanlucmakiola.calendula.ui.common.CalendarView

/**
 * State for the calendar-filter sheet (M3). The user toggles per-calendar
 * visibility; the choice is persisted app-side (separate from the system's
 * VISIBLE flag) and applied to every calendar view.
 */
sealed interface FilterUiState {
    data object Loading : FilterUiState
    data class Failure(val reason: FailureReason) : FilterUiState
    /**
     * A calendar shows when its id is *not* in [hidden]; [hiddenViews] are the
     * views each calendar is hidden in regardless (#270).
     */
    data class Success(
        val groups: List<CalendarAccountGroup>,
        val hidden: Set<Long>,
        val hiddenViews: Map<Long, Set<CalendarView>> = emptyMap(),
    ) : FilterUiState
}
