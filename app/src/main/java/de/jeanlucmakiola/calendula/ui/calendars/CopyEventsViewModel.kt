package de.jeanlucmakiola.calendula.ui.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.ics.IcsImportSummary
import de.jeanlucmakiola.floret.android.stateWhileSubscribed
import de.jeanlucmakiola.floret.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/**
 * Copy events from one calendar into another (#325). Lives as long as the
 * activity, so a long copy keeps running across rotation and when the Backup
 * screen is closed; [cancel] stops it between events.
 */
@HiltViewModel
class CopyEventsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    @IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {

    val calendars: StateFlow<List<CalendarSource>> =
        repository.calendars()
            .catch { emit(emptyList()) }
            .flowOn(io)
            .stateWhileSubscribed(scope = viewModelScope, initialValue = emptyList())

    private val _selection = MutableStateFlow(CopySelection())
    val selection: StateFlow<CopySelection> = _selection.asStateFlow()

    private val _run = MutableStateFlow<CopyRunState>(CopyRunState.Idle)
    val run: StateFlow<CopyRunState> = _run.asStateFlow()

    private var job: Job? = null

    fun setSource(id: Long) {
        if (job?.isActive == true) return
        _selection.value = _selection.value.withSource(id)
        clearFinished()
    }

    fun setTarget(id: Long) {
        if (job?.isActive == true) return
        _selection.value = _selection.value.copy(targetId = id)
        clearFinished()
    }

    fun start() {
        val source = _selection.value.sourceId ?: return
        val target = _selection.value.targetId ?: return
        if (job?.isActive == true) return
        _run.value = CopyRunState.Running(done = 0, total = null)
        job = viewModelScope.launch {
            try {
                val summary = repository.copyEvents(source, target) { done, total ->
                    _run.value = CopyRunState.Running(done, total)
                }
                _run.value = CopyRunState.Done(summary)
            } catch (e: CancellationException) {
                _run.value = CopyRunState.Cancelled
                throw e
            } catch (e: Exception) {
                _run.value = CopyRunState.Failed
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to idle once a finished run's outcome has been seen. */
    fun clearFinished() {
        if (job?.isActive != true) _run.value = CopyRunState.Idle
    }
}

/** The calendars a copy runs between. */
data class CopySelection(val sourceId: Long? = null, val targetId: Long? = null) {
    /** A target equal to the new source is dropped: a calendar is never copied into itself. */
    fun withSource(id: Long): CopySelection =
        CopySelection(sourceId = id, targetId = targetId?.takeIf { it != id })
}

sealed interface CopyRunState {
    data object Idle : CopyRunState

    /** [total] is null while the source calendar is still being read. */
    data class Running(val done: Int, val total: Int?) : CopyRunState

    data class Done(val summary: IcsImportSummary) : CopyRunState
    data object Cancelled : CopyRunState
    data object Failed : CopyRunState
}
