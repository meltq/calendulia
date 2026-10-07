package de.jeanlucmakiola.calendula.data.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Clock

/** The current local date, re-emitted whenever it changes (#373). */
interface TodaySource {
    val today: Flow<LocalDate>
}

/**
 * Re-reads the date on every minute boundary while collected, and at once on the
 * system's date, time and zone broadcasts, which can arrive late or not at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTodaySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : TodaySource {

    override val today: Flow<LocalDate> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
        .onStart { emit(Unit) }
        .transformLatest {
            while (true) {
                emit(currentDate())
                delay(MINUTE_MILLIS - Clock.System.now().toEpochMilliseconds() % MINUTE_MILLIS)
            }
        }
        .distinctUntilChanged()
}

private const val MINUTE_MILLIS = 60_000L

fun currentDate(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
