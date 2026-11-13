package de.jeanlucmakiola.calendula.ui.common

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.calendar.SyncRequestResult
import de.jeanlucmakiola.floret.android.stateWhileSubscribed
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The drawer's "Sync now" (#240): asks the sync adapters of every visible synced
 * calendar to run now. [inFlight] holds until the provider next reports a change
 * or [SETTLE_MILLIS] pass — the request itself says nothing about when, or
 * whether, the sync completes.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    /** Whether any visible calendar has a sync adapter behind it. */
    val available: StateFlow<Boolean> = repository.canRequestSync
        .catch { emit(false) }
        .stateWhileSubscribed(scope = viewModelScope, initialValue = false)

    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    private val _result = MutableStateFlow<SyncRequestResult?>(null)

    /** The last request's outcome, until [consumeResult]. */
    val result: StateFlow<SyncRequestResult?> = _result.asStateFlow()

    fun requestSync() {
        if (_inFlight.value) return
        _inFlight.value = true
        // A repeat receipt must land as a new one, restarting the chip.
        _result.value = null
        viewModelScope.launch {
            try {
                // Listening before asking, so a sync that lands at once still counts.
                val settled = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(SETTLE_MILLIS) { repository.providerChanges.firstOrNull() }
                }
                val outcome = repository.requestSync()
                _result.value = outcome
                if (outcome == SyncRequestResult.Requested || outcome == SyncRequestResult.PartlyFailed) settled.await() else settled.cancel()
            } finally {
                _inFlight.value = false
            }
        }
    }

    fun consumeResult() {
        _result.value = null
    }

    private companion object {
        const val SETTLE_MILLIS = 15_000L
    }
}

/** The chip text for a sync request's outcome. */
@StringRes
fun SyncRequestResult.messageRes(): Int = when (this) {
    SyncRequestResult.Requested -> R.string.sync_requested
    SyncRequestResult.PartlyFailed -> R.string.sync_request_partly_failed
    SyncRequestResult.NothingToSync -> R.string.sync_nothing_to_sync
    SyncRequestResult.Failed -> R.string.sync_request_failed
}
