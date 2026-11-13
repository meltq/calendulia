package de.jeanlucmakiola.calendula.data.reminders

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the full-screen alerts (#171) share within the process: the reminder
 * [ReminderRingService] is ringing for, and the reminders just dismissed or
 * snoozed, so an alert screen showing one closes whichever way it was handled.
 */
@Singleton
class FullScreenAlerts @Inject constructor() {

    /** Set by [ReminderRingService] for as long as it rings. */
    @Volatile
    var ringing: ReminderAlert? = null

    /** The key of a ring handed to [ReminderRingService] that has not reached it yet. */
    @Volatile
    var starting: Long? = null

    private val handledKeys = MutableSharedFlow<Long>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The [ReminderAlert.key]s of reminders as they are dismissed or snoozed. */
    val handled: SharedFlow<Long> = handledKeys.asSharedFlow()

    fun markHandled(alert: ReminderAlert) {
        handledKeys.tryEmit(alert.key)
    }
}
