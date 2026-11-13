package de.jeanlucmakiola.calendula.ui.filter

import androidx.compose.ui.state.ToggleableState

/** The drawer group header's checkbox state: on, off, or some of [calendarIds] shown. */
fun groupVisibility(calendarIds: Collection<Long>, hidden: Set<Long>): ToggleableState {
    val shown = calendarIds.count { it !in hidden }
    return when (shown) {
        calendarIds.size -> ToggleableState.On
        0 -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
}

/** What tapping the header does: a fully shown group hides, anything else shows all. */
fun ToggleableState.nextGroupVisible(): Boolean = this != ToggleableState.On
