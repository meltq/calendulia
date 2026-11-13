package de.jeanlucmakiola.calendula.data.reminders

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the full-screen alerts (#171) share within the process: the reminder
 * [ReminderRingService] is ringing for, and the reminders just dismissed or
 * snoozed, so an alert screen showing one closes whichever way it was handled.
 */
@Singleton
class FullScreenAlerts @Inject constructor() {

    /** Written only by [ReminderRingService], on the main thread, for as long as it rings. */
    @Volatile
    var ringing: ReminderAlert? = null
        internal set

    /** Rings handed to [ReminderRingService] that have not reached it yet, by key. */
    internal val pending = ConcurrentHashMap<Long, ReminderAlert>()

    /** Whether a ring going or on its way [match]es, so a stop is worth sending. */
    fun mayRing(match: RingMatch): Boolean =
        ringing?.let(match::matches) == true || pending.values.any(match::matches)

    private val handledKeys = MutableSharedFlow<Long>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The [ReminderAlert.key]s of reminders as they are dismissed, snoozed or handed back. */
    val handled: SharedFlow<Long> = handledKeys.asSharedFlow()

    fun markHandled(alert: ReminderAlert) {
        handledKeys.tryEmit(alert.key)
    }
}

/** What becomes of a ring once its settings are re-read. */
enum class RingVerdict { Ring, HandBack, Drop }

/** Which ring a stop is for. */
sealed interface RingMatch {
    fun matches(alert: ReminderAlert): Boolean

    data class Key(val key: Long) : RingMatch {
        override fun matches(alert: ReminderAlert) = alert.key == key
    }

    data class Occurrence(val occurrenceKey: Long) : RingMatch {
        override fun matches(alert: ReminderAlert) = alert.occurrenceKey == occurrenceKey
    }

    data class Calendar(val calendarId: Long) : RingMatch {
        override fun matches(alert: ReminderAlert) = alert.calendarId == calendarId
    }

    data object Any : RingMatch {
        override fun matches(alert: ReminderAlert) = true
    }
}
