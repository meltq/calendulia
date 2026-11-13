package de.jeanlucmakiola.calendula.data.reminders

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.calendula.data.di.ApplicationScope
import de.jeanlucmakiola.calendula.data.reminders.ReminderActionReceiver.Companion.putAlert
import de.jeanlucmakiola.floret.ring.RingVibrator
import de.jeanlucmakiola.floret.ring.RingWakeLock
import de.jeanlucmakiola.floret.ring.TonePlayer
import de.jeanlucmakiola.floret.ring.isInCall
import de.jeanlucmakiola.floret.ring.startRingForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rings a reminder whose calendar asks for "Ring until dismissed" (#171): the
 * alarm sound looped with vibration, for as long as the reminder is neither
 * dismissed nor snoozed, up to [RING_TIMEOUT_MILLIS]. Then it goes quiet and
 * hands the reminder back to the shade as an ordinary notification.
 *
 * One reminder rings at a time. A new one takes over, and the one it replaces
 * goes back to the shade, silently, unless it was for the same occurrence.
 * Starts and stops both arrive as commands, so they are handled on the main
 * thread in the order they were sent.
 */
@AndroidEntryPoint
class ReminderRingService : Service() {

    @Inject lateinit var alerts: FullScreenAlerts
    @Inject lateinit var notifier: ReminderNotifier

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tones by lazy { TonePlayer(this) }
    private val vibrator by lazy { RingVibrator(this) }
    private val wakeLock by lazy { RingWakeLock(this, WAKE_LOCK_TAG, RING_TIMEOUT_MILLIS) }

    private var ringing: Job? = null
    private var timeout: Job? = null
    private var notificationId = RING_NOTIFICATION_ID
    private var lastStartId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(intent, startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RING) {
            alerts.pendingStarts.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        val alert = intent?.let(ReminderActionReceiver::alertFrom)
        val notification = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java) }
        if (alert == null || notification == null) {
            if (alerts.ringing == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val previous = alerts.ringing
        // A different reminder posts under the other id, so it is a new
        // notification whose full-screen intent opens; an update would not.
        val replacing = previous != null && previous.key != alert.key
        val shown = notificationId
        if (replacing) notificationId = if (shown == RING_NOTIFICATION_ID) OTHER_RING_NOTIFICATION_ID else RING_NOTIFICATION_ID
        val foreground = runCatching { startRingForeground(notificationId, notification) }
        if (foreground.isFailure) {
            // A restart after a process death may be refused a foreground start:
            // the reminder still shows full-screen, sounding once.
            Log.w(TAG, "Could not ring for event ${alert.eventId}", foreground.exceptionOrNull())
            notificationId = shown
            handBack(alert, quiet = false)
            if (alerts.ringing == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (replacing) NotificationManagerCompat.from(this).cancel(shown)
        // Only the newest start is redelivered after a process death; the older
        // ones are done, so a handled reminder never rings again.
        lastStartId?.let { stopSelfResult(it) }
        lastStartId = startId
        if (previous?.key == alert.key && timeout?.isActive == true) return START_REDELIVER_INTENT
        alerts.ringing = alert
        if (previous != null && previous.occurrenceKey != alert.occurrenceKey) handBack(previous)
        ring()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        releaseRing()
        alerts.ringing = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ring() {
        releaseRing()
        wakeLock.acquire()
        // Vibration first: the sound may take a moment to open.
        vibrator.start()
        // No alarm tone over a phone call; the vibration says enough.
        if (!isInCall()) ringing = scope.launch { tones.play(scope, ringSources()) }
        timeout = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            giveUp()
        }
    }

    private fun stop(intent: Intent, startId: Int) {
        val current = alerts.ringing
        if (current != null && matchFrom(intent).matches(current)) {
            if (intent.getBooleanExtra(EXTRA_HAND_BACK, false)) handBack(current)
            end()
        }
        // Declines while a newer start is still on its way.
        if (alerts.ringing == null) stopSelfResult(startId)
    }

    /** Rung long enough: back to the shade quietly. */
    private fun giveUp() {
        timeout = null
        alerts.ringing?.let(::handBack)
        end()
        lastStartId?.let { stopSelfResult(it) }
    }

    private fun end() {
        releaseRing()
        alerts.ringing = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * [alert] back in the shade, in the app's scope so a service stopped
     * meanwhile cannot cancel it, and its alert screen closed.
     */
    private fun handBack(alert: ReminderAlert, quiet: Boolean = true) {
        alerts.markHandled(alert)
        appScope.launch {
            try {
                notifier.post(alert, quiet = quiet, ring = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not hand the reminder of event ${alert.eventId} back", e)
            }
        }
    }

    private fun releaseRing() {
        ringing?.cancel()
        ringing = null
        timeout?.cancel()
        timeout = null
        tones.stop()
        vibrator.stop()
        wakeLock.release()
    }

    /** The device's alarm sound, then the built-in ones; none opening leaves the vibration. */
    private fun ringSources() = listOfNotNull(
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    )

    companion object {
        private const val TAG = "ReminderRingService"
        private const val ACTION_RING = "de.jeanlucmakiola.calendula.reminders.RING"
        private const val ACTION_STOP = "de.jeanlucmakiola.calendula.reminders.RING_STOP"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_MATCH_KIND = "match_kind"
        private const val EXTRA_MATCH_VALUE = "match_value"
        private const val EXTRA_HAND_BACK = "hand_back"
        private const val WAKE_LOCK_TAG = "calendula:reminder-ring"

        /** Clear of the reminders' tagged id 1 and the backup notice's 2. */
        private const val RING_NOTIFICATION_ID = 171
        private const val OTHER_RING_NOTIFICATION_ID = 172

        /** How long a reminder rings unanswered before it goes quiet. */
        const val RING_TIMEOUT_MILLIS = 10L * 60 * 1000

        /**
         * Rings [alert] under [notification]. False when Android refused to
         * start the service from where the reminder fired.
         */
        fun start(context: Context, alerts: FullScreenAlerts, alert: ReminderAlert, notification: Notification): Boolean {
            val intent = Intent(context, ReminderRingService::class.java)
                .setAction(ACTION_RING)
                .putAlert(alert)
                .putExtra(EXTRA_NOTIFICATION, notification)
            alerts.pendingStarts.incrementAndGet()
            return try {
                RingWakeLock.handOff(context, WAKE_LOCK_TAG)
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the ring for event ${alert.eventId}", e)
                alerts.pendingStarts.updateAndGet { (it - 1).coerceAtLeast(0) }
                false
            }
        }

        /**
         * Stops the ring if it is for a reminder [match]es, in order with the
         * starts sent before it. [handBack] puts it back in the shade quietly.
         * A command rather than `stopService`, which would also drop a start
         * still on its way; sent while the ring's foreground service keeps the
         * app allowed to.
         */
        fun stop(context: Context, match: RingMatch, handBack: Boolean = false) {
            val (kind, value) = when (match) {
                is RingMatch.Key -> 0 to match.key
                is RingMatch.Occurrence -> 1 to match.occurrenceKey
                is RingMatch.Calendar -> 2 to match.calendarId
                RingMatch.Any -> 3 to 0L
            }
            val intent = Intent(context, ReminderRingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_MATCH_KIND, kind)
                .putExtra(EXTRA_MATCH_VALUE, value)
                .putExtra(EXTRA_HAND_BACK, handBack)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the ring", e)
                // Refused from the background: nothing must ring on regardless.
                context.stopService(Intent(context, ReminderRingService::class.java))
            }
        }

        private fun matchFrom(intent: Intent): RingMatch {
            val value = intent.getLongExtra(EXTRA_MATCH_VALUE, 0L)
            return when (intent.getIntExtra(EXTRA_MATCH_KIND, 3)) {
                0 -> RingMatch.Key(value)
                1 -> RingMatch.Occurrence(value)
                2 -> RingMatch.Calendar(value)
                else -> RingMatch.Any
            }
        }
    }
}
