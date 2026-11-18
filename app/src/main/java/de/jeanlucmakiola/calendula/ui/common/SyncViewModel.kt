package de.jeanlucmakiola.calendula.ui.common

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.calendar.SyncAccount
import de.jeanlucmakiola.calendula.data.calendar.SyncRequestResult
import de.jeanlucmakiola.calendula.data.calendar.SyncTargets
import de.jeanlucmakiola.calendula.data.calendar.SyncWait
import de.jeanlucmakiola.floret.android.stateWhileSubscribed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The drawer's "Sync now" (#240): asks the sync adapters of every visible synced
 * calendar to run now, then follows them through the system's sync state.
 * [inFlight] holds until every reached account has synced or the wait gives up;
 * [result] then says [SyncRequestResult.Completed] only when that was seen.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    /** Who "Sync now" can reach, and who first needs the user's grant; null until first read. */
    val targets: StateFlow<SyncTargets?> = repository.syncTargets
        // A failed read hides the rows for now, then reads again rather than for good.
        .retryWhen { e, attempt ->
            if (e is CancellationException) return@retryWhen false
            emit(SyncTargets.None)
            delay((RETRY_BASE_MILLIS shl attempt.coerceAtMost(5).toInt()).coerceAtMost(RETRY_MAX_MILLIS))
            true
        }
        .stateWhileSubscribed(scope = viewModelScope, initialValue = null)

    /** Re-read account access: on resume (which includes the picker returning) and drawer open. */
    fun refreshAccess() = repository.refreshSyncAccess()

    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    private val _result = MutableStateFlow<SyncRequestResult?>(null)

    /** The last request's outcome, until [consumeResult]. */
    val result: StateFlow<SyncRequestResult?> = _result.asStateFlow()

    fun requestSync() {
        if (_inFlight.value) return
        _inFlight.value = true
        _result.value = null
        viewModelScope.launch {
            try {
                val request = repository.requestSync()
                val waited = if (request.accounts.isEmpty()) null else repository.awaitSyncs(request.accounts)
                _result.value = if (waited == SyncWait.Completed && request.result == SyncRequestResult.Requested) {
                    SyncRequestResult.Completed
                } else {
                    request.result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync now failed", e)
                _result.value = SyncRequestResult.Failed
            } finally {
                _inFlight.value = false
            }
        }
    }

    /** Stop asking for [account]'s access (#240); undone from the calendar manager. */
    fun dismissAccess(account: SyncAccount) {
        viewModelScope.launch { repository.setSyncAccessDismissed(account, dismissed = true) }
    }

    fun consumeResult() {
        _result.value = null
    }

    private companion object {
        const val RETRY_BASE_MILLIS = 1_000L
        const val RETRY_MAX_MILLIS = 30_000L
        const val TAG = "SyncViewModel"
    }
}

/** The text for a sync request's outcome, in the drawer row or the chip. */
@StringRes
fun SyncRequestResult.messageRes(): Int = when (this) {
    SyncRequestResult.Requested -> R.string.sync_requested
    SyncRequestResult.Completed -> R.string.sync_completed
    SyncRequestResult.PartlyFailed -> R.string.sync_request_partly_failed
    SyncRequestResult.NeedsAccess -> R.string.sync_request_needs_access
    SyncRequestResult.SomeNeedAccess -> R.string.sync_request_some_need_access
    SyncRequestResult.NothingToSync -> R.string.sync_nothing_to_sync
    SyncRequestResult.Failed -> R.string.sync_request_failed
}
