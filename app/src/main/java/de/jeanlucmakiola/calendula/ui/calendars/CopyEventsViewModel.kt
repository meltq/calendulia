package de.jeanlucmakiola.calendula.ui.calendars

import android.util.Log
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/**
 * Copy events from one calendar into another (#325). Scoped to the activity, so
 * a copy keeps running across rotation and while the Backup screen is closed;
 * finishing the activity or losing the process stops it like [cancel] does,
 * and a re-run picks up where it ended.
 */
@HiltViewModel
class CopyEventsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val savedState: SavedStateHandle,
    @IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {

    /** Null until the first read. */
    val calendars: StateFlow<List<CalendarSource>?> =
        repository.calendars()
            .catch { emit(emptyList()) }
            .flowOn(io)
            .stateWhileSubscribed(scope = viewModelScope, initialValue = null)

    val selection: StateFlow<CopySelection> = combine(
        savedState.getStateFlow<Long?>(KEY_SOURCE, null),
        savedState.getStateFlow<Long?>(KEY_TARGET, null),
    ) { source, target -> CopySelection(source, target) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CopySelection())

    private val _run = MutableStateFlow<CopyRunState>(CopyRunState.Idle)
    val run: StateFlow<CopyRunState> = _run.asStateFlow()

    private var job: Job? = null

    private val busy: Boolean get() = job?.isCompleted == false

    private fun current() = CopySelection(savedState[KEY_SOURCE], savedState[KEY_TARGET])

    private fun select(selection: CopySelection) {
        savedState[KEY_SOURCE] = selection.sourceId
        savedState[KEY_TARGET] = selection.targetId
    }

    fun setSource(id: Long) {
        if (busy) return
        select(current().withSource(id))
        clearFinished()
    }

    fun setTarget(id: Long) {
        if (busy) return
        select(current().copy(targetId = id))
        clearFinished()
    }

    fun start() {
        val source = current().sourceId ?: return
        val target = current().targetId ?: return
        if (busy) return
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
                Log.w(TAG, "Copy events failed", e)
                _run.value = CopyRunState.Failed
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to idle once a finished run's outcome has been seen. */
    fun clearFinished() {
        if (!busy) _run.value = CopyRunState.Idle
    }

    private companion object {
        const val KEY_SOURCE = "copy_source"
        const val KEY_TARGET = "copy_target"
        const val TAG = "CopyEvents"
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

    data class Done(val summary: IcsImportSummary) : CopyRunState {
        /** The target stopped taking events part way, so some were never tried. */
        val stopped: Boolean get() = summary.notAttempted > 0
    }
    data object Cancelled : CopyRunState
    data object Failed : CopyRunState
}
