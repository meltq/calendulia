package de.jeanlucmakiola.calendula.ui.filter

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefs
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.FailureReason
import de.jeanlucmakiola.calendula.domain.calendarListFailure
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.groupByAccount
import de.jeanlucmakiola.floret.android.stateWhileSubscribed
import de.jeanlucmakiola.floret.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilterViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: CalendarPrefs,
    private val settingsPrefs: SettingsPrefs,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val state: StateFlow<FilterUiState> =
        combine(
            repository.calendars(),
            prefs.hiddenCalendarIds,
            // Only feeds a note on the rows; a failed read leaves it off rather than failing the list.
            settingsPrefs.perCalendarHiddenViews.catch { emit(emptyMap()) },
        ) { calendars, hidden, hiddenViews ->
            // Calendars switched off device-wide don't belong in the drawer's
            // hide/show list; they live only in Settings → Calendars.
            val enabled = calendars.filter { it.isVisibleInSystem }
            val failure = calendarListFailure(calendars)
            if (failure != null) {
                FilterUiState.Failure(failure)
            } else {
                FilterUiState.Success(enabled.groupByAccount(), hidden, hiddenViews)
            }
        }
            .catch { emit(FilterUiState.Failure(FailureReason.ProviderUnavailable)) }
            .flowOn(io)
            .stateWhileSubscribed(
                scope = viewModelScope,
                initialValue = FilterUiState.Loading,
            )

    /** Hide or show one calendar in [view] only (#517), the same setting as Settings → Calendars. */
    fun setHiddenIn(calendarId: Long, view: CalendarView, hidden: Boolean) {
        viewModelScope.launch {
            try {
                settingsPrefs.setCalendarHiddenIn(calendarId, view, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FilterViewModel", "Could not change hidden views of $calendarId", e)
            }
        }
    }

    /** Show or hide a single calendar; persists the new hidden set. */
    fun setVisible(calendarId: Long, visible: Boolean) {
        viewModelScope.launch {
            val current = prefs.hiddenCalendarIds.first()
            val next = if (visible) current - calendarId else current + calendarId
            if (next != current) prefs.setHiddenCalendarIds(next)
        }
    }
}

