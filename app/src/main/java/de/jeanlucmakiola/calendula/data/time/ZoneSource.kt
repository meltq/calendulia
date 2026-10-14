package de.jeanlucmakiola.calendula.data.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.TimeZone
import javax.inject.Inject

/**
 * The zone the device is in. Every screen that turns wall clock into instants
 * reads it from here, so the timeline, the event form and a saved move use the
 * same zone (#387).
 */
interface ZoneSource {
    /** The device's zone, re-emitted whenever it changes. */
    val zone: Flow<TimeZone>

    /** The device's zone right now. */
    fun current(): TimeZone
}

/** Follows the system's zone broadcast. */
class AndroidZoneSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ZoneSource {

    override val zone: Flow<TimeZone> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
        .onStart { emit(Unit) }
        .map { current() }
        .distinctUntilChanged()

    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}
