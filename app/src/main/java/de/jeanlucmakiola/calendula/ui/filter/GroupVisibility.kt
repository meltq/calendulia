package de.jeanlucmakiola.calendula.ui.filter

import androidx.compose.ui.state.ToggleableState

/** The drawer group header's checkbox state: on, off, or some of [calendarIds] shown. */
internal fun groupVisibility(calendarIds: Collection<Long>, hidden: Set<Long>): ToggleableState {
    val shown = calendarIds.count { it !in hidden }
    return when (shown) {
        0 -> ToggleableState.Off
        calendarIds.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** What tapping the header does: a fully shown group hides, anything else shows all. */
internal fun ToggleableState.nextGroupVisible(): Boolean = this != ToggleableState.On

/** [hidden] after tapping the header of the group holding [calendarIds]. */
internal fun toggledGroup(hidden: Set<Long>, calendarIds: Collection<Long>): Set<Long> =
    if (groupVisibility(calendarIds, hidden).nextGroupVisible()) hidden - calendarIds.toSet() else hidden + calendarIds
