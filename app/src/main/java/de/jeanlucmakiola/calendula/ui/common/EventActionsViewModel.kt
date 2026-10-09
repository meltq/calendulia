package de.jeanlucmakiola.calendula.ui.common

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.calendar.NoSuchEventException
import de.jeanlucmakiola.calendula.data.di.IoDispatcher
import de.jeanlucmakiola.calendula.data.ics.IcsExporter
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.EventDetail
import de.jeanlucmakiola.calendula.domain.EventForm
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.domain.RecurringWriteScope
import de.jeanlucmakiola.calendula.domain.toDuplicateForm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/** A delete waiting on its confirmation dialog. */
data class DeletePrompt(val event: EventInstance, val isRecurring: Boolean)

/** How an action on a selected event ended, when it has something to say. */
enum class EventActionFailure { LoadFailed, ShareFailed, DeleteFailed, WriteDenied, Gone }

/** Duplicate, share and delete for a selected event (#141), loading it on demand. */
@HiltViewModel
class EventActionsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val icsExporter: IcsExporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** Every calendar by id, for what a selected event's bar may offer. */
    val calendars: StateFlow<Map<Long, CalendarSource>> = repository.calendars()
        .map { list -> list.associateBy { it.id } }
        .catch { emit(emptyMap()) }
        .flowOn(io)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyMap(),
        )

    private val _deletePrompt = MutableStateFlow<DeletePrompt?>(null)
    val deletePrompt: StateFlow<DeletePrompt?> = _deletePrompt.asStateFlow()

    private val _failure = MutableStateFlow<EventActionFailure?>(null)
    val failure: StateFlow<EventActionFailure?> = _failure.asStateFlow()

    /** A create form copying [event], or null when it can't be read. */
    suspend fun duplicateForm(event: EventInstance): EventForm? {
        val detail = load(event) ?: return null
        val canModify = calendars.value[event.calendarId]?.canModifyContents == true
        return detail.toDuplicateForm(canModify, TimeZone.currentSystemDefault())
    }

    /** A staged `.ics` for [event], or null — reported — when it can't be built. */
    suspend fun shareUri(event: EventInstance): Uri? {
        val uri = load(event)?.let { withContext(io) { icsExporter.stageEventShare(it) } }
        if (uri == null && _failure.value == null) _failure.value = EventActionFailure.ShareFailed
        return uri
    }

    /** Ask to delete [event]; the dialog it needs depends on whether it recurs. */
    fun requestDelete(event: EventInstance) {
        viewModelScope.launch {
            val detail = load(event) ?: return@launch
            _deletePrompt.value = DeletePrompt(event, isRecurring = !detail.rrule.isNullOrBlank())
        }
    }

    fun confirmDelete(scope: RecurringWriteScope) {
        val prompt = _deletePrompt.value ?: return
        _deletePrompt.value = null
        viewModelScope.launch {
            val event = prompt.event
            _failure.value = try {
                repository.deleteEvent(event.eventId, event.start.toEpochMilliseconds(), scope)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                EventActionFailure.WriteDenied
            } catch (e: NoSuchEventException) {
                EventActionFailure.Gone
            } catch (e: Exception) {
                EventActionFailure.DeleteFailed
            }
        }
    }

    fun cancelDelete() {
        _deletePrompt.value = null
    }

    /** The chooser itself couldn't open — nothing on the device takes an `.ics`. */
    fun reportShareFailed() {
        _failure.value = EventActionFailure.ShareFailed
    }

    fun consumeFailure() {
        _failure.value = null
    }

    private suspend fun load(event: EventInstance): EventDetail? = try {
        val detail = repository.eventDetail(event.eventId)
        // The row holds the series start; the actions work on this occurrence.
        detail.copy(instance = detail.instance.copy(start = event.start, end = event.end))
    } catch (e: CancellationException) {
        throw e
    } catch (e: NoSuchEventException) {
        _failure.value = EventActionFailure.Gone
        null
    } catch (e: Exception) {
        _failure.value = EventActionFailure.LoadFailed
        null
    }
}
