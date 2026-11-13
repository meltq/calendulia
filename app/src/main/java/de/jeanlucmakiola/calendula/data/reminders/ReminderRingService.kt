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
import de.jeanlucmakiola.calendula.data.reminders.ReminderActionReceiver.Companion.putAlert
import de.jeanlucmakiola.floret.ring.RingVibrator
import de.jeanlucmakiola.floret.ring.RingWakeLock
import de.jeanlucmakiola.floret.ring.TonePlayer
import de.jeanlucmakiola.floret.ring.startRingForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Rings a reminder whose calendar asks for "Ring until dismissed" (#171): the
 * alarm sound looped with vibration, for as long as the reminder is neither
 * dismissed nor snoozed, up to [RING_TIMEOUT_MILLIS]. Then it goes quiet and
 * hands the reminder back to the shade as an ordinary notification.
 *
 * One reminder rings at a time. A new one takes over, and the one it replaces
 * stays in the shade, silently, unless it was for the same occurrence.
 */
@AndroidEntryPoint
class ReminderRingService : Service() {

    @Inject lateinit var alerts: FullScreenAlerts
    @Inject lateinit var notifier: ReminderNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tones by lazy { TonePlayer(this) }
    private val vibrator by lazy { RingVibrator(this) }
    private val wakeLock by lazy { RingWakeLock(this, WAKE_LOCK_TAG, RING_TIMEOUT_MILLIS) }

    private var ringing: Job? = null
    private var timeout: Job? = null
    private var notificationId = RING_NOTIFICATION_ID
    private var lastStartId: Int? = null

    /** What this service rings, which [FullScreenAlerts.ringing] may already have let go of. */
    private var currentKey: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alert = intent?.let(ReminderActionReceiver::alertFrom)
        val notification = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java) }
        if (alerts.starting == alert?.key) alerts.starting = null
        if (alert == null || notification == null) {
            if (alerts.ringing == null) stopSelf()
            return START_NOT_STICKY
        }
        // Null when the user handled it while this ring was on its way.
        val previous = alerts.ringing
        // A different reminder posts under the other id, so it is a new
        // notification whose full-screen intent opens; an update would not.
        val replacing = currentKey != null && currentKey != alert.key
        val shown = notificationId
        if (replacing) notificationId = if (shown == RING_NOTIFICATION_ID) OTHER_RING_NOTIFICATION_ID else RING_NOTIFICATION_ID
        val foreground = runCatching { startRingForeground(notificationId, notification) }
        if (foreground.isFailure) {
            // A restart after a process death may be refused a foreground start:
            // the reminder goes back to the shade rather than being lost.
            Log.w(TAG, "Could not ring for event ${alert.eventId}", foreground.exceptionOrNull())
            notificationId = shown
            scope.launch {
                handBack(alert)
                if (alerts.ringing == null) stopSelf()
            }
            return START_NOT_STICKY
        }
        if (replacing) NotificationManagerCompat.from(this).cancel(shown)
        // Only the newest start is redelivered after a process death; the older
        // ones are done, so a handled reminder never rings again.
        lastStartId?.let { stopSelfResult(it) }
        lastStartId = startId
        if (currentKey == alert.key && timeout?.isActive == true) return START_REDELIVER_INTENT
        currentKey = alert.key
        alerts.ringing = alert
        if (previous != null && previous.occurrenceKey != alert.occurrenceKey) {
            scope.launch { handBack(previous) }
        }
        ring()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        releaseRing()
        alerts.ringing = null
        alerts.starting = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ring() {
        releaseRing()
        wakeLock.acquire()
        // Vibration first: the sound may take a moment to open.
        vibrator.start()
        ringing = scope.launch { tones.play(scope, ringSources()) }
        timeout = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            giveUp()
        }
    }

    /** Rung long enough: back to the shade quietly, and the alert screen closes. */
    private suspend fun giveUp() {
        timeout = null
        currentKey = null
        val alert = alerts.ringing
        releaseRing()
        alerts.ringing = null
        if (alert != null) {
            handBack(alert)
            alerts.markHandled(alert)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** [alert] as a quiet notification in the shade, so it is not lost. */
    private suspend fun handBack(alert: ReminderAlert) {
        try {
            withContext(Dispatchers.IO) { notifier.post(alert, quiet = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not hand the reminder of event ${alert.eventId} back", e)
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
        private const val EXTRA_NOTIFICATION = "notification"
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
                .putAlert(alert)
                .putExtra(EXTRA_NOTIFICATION, notification)
            return try {
                RingWakeLock.handOff(context, WAKE_LOCK_TAG)
                alerts.starting = alert.key
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the ring for event ${alert.eventId}", e)
                alerts.starting = null
                false
            }
        }

        /** `stopService`, not a command: a command is a start, which can throw from the background. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ReminderRingService::class.java))
        }
    }
}
