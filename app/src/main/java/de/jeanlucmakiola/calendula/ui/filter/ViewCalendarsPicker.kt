package de.jeanlucmakiola.calendula.ui.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.accountGroupTitle
import de.jeanlucmakiola.calendula.ui.common.calendarsHiddenIn
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.MultiOptionRows

/**
 * The calendars hidden in [view] (#517), opened from that view's top bar: the
 * per-view setting from Settings → Calendars (#270), seen from the view's side.
 * Toggles apply at once.
 */
@Composable
fun ViewCalendarsPicker(
    view: CalendarView,
    state: FilterUiState,
    onSetHidden: (calendarId: Long, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenPicker(
        title = stringResource(R.string.view_calendars_title),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        when (state) {
            FilterUiState.Loading -> FilterLoading()
            is FilterUiState.Failure -> FilterMessage(state.reason)
            is FilterUiState.Success -> {
                // Seeded once, then ahead of the store: the picker is the only
                // writer while open, so a quick second tap still flips.
                var hidden by remember { mutableStateOf(state.hiddenViews.calendarsHiddenIn(view)) }
                GroupedHint(stringResource(R.string.view_calendars_hint), verticalPadding = 8.dp)
                state.groups.forEach { group ->
                    GroupedSectionHeader(accountGroupTitle(group))
                    MultiOptionRows(
                        options = group.calendars,
                        selected = group.calendars.filterTo(mutableSetOf()) { it.id in hidden },
                        label = { it.displayName.orUnnamed() },
                        onToggle = { calendar, hide ->
                            hidden = if (hide) hidden + calendar.id else hidden - calendar.id
                            onSetHidden(calendar.id, hide)
                        },
                        summary = { calendar ->
                            if (calendar.id in state.hidden) stringResource(R.string.view_calendars_hidden_everywhere) else null
                        },
                        leading = { CalendarColorChip(it.color) },
                    )
                }
            }
        }
    }
}
