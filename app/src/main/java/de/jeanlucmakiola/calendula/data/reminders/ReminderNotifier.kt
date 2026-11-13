package de.jeanlucmakiola.calendula.data.reminders

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.MainActivity
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefs
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.is24Hour
import de.jeanlucmakiola.calendula.data.prefs.resolveFirstDay
import de.jeanlucmakiola.calendula.domain.reminders.ReminderAlertStyle
import de.jeanlucmakiola.calendula.domain.reminders.alertStyleFor
import de.jeanlucmakiola.calendula.ui.reminders.ReminderAlertActivity
import de.jeanlucmakiola.floret.android.hasPermission
import de.jeanlucmakiola.floret.ring.canUseFullScreenIntent
import kotlinx.coroutines.flow.first
import kotlinx.datetime.isoDayNumber
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts one notification per occurrence on its calendar's channel
 * ([ReminderChannels], #145), or as a full-screen alert where the calendar
 * asks for one (#171). Tapping opens the event's detail screen.
 *
 * The tag is the [occurrenceKey], so each reminder for an occurrence replaces
 * the one before it in place (#463). A different reminder alerts afresh; the
 * same one posted again — a catch-up pass overlapping the alarm that already
 * fired — replaces its notification silently.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsPrefs: SettingsPrefs,
    private val calendarPrefs: CalendarPrefs,
    private val calendarDataSource: CalendarDataSource,
    private val channels: ReminderChannels,
    private val alerts: FullScreenAlerts,
) {

    /** False when the user declined `POST_NOTIFICATIONS` or muted the app. */
    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * The single choke point for "this calendar posts no reminders": switched
     * off (also covering a snooze armed before the switch-off, and a read-only
     * install whose switch lives in [CalendarPrefs]), or its notifications
     * turned off in Calendula's own settings (#272).
     */
    private suspend fun isSilenced(calendarId: Long): Boolean =
        calendarId in settingsPrefs.mutedReminderCalendarIds.first() ||
            calendarId in calendarPrefs.pendingDisabledCalendarIds.first() ||
            calendarDataSource.isCalendarVisible(calendarId) == false

    /**
     * Post [alert] in its calendar's alert style (#171), unless the calendar is
     * silenced or gone. [ring] false posts a ringing style as a full-screen
     * alert that sounds once instead; [quiet] posts a plain notification that
     * makes no sound. Both are how a ring hands a reminder back to the shade.
     */
    suspend fun post(alert: ReminderAlert, quiet: Boolean = false, ring: Boolean = true) {
        if (isSilenced(alert.calendarId)) return
        val style = when {
            quiet -> ReminderAlertStyle.Notification
            else -> effectiveStyle(alert).let {
                if (it == ReminderAlertStyle.FullScreenRing && !ring) ReminderAlertStyle.FullScreen else it
            }
        }
        // Rung already and handed back to the shade: a catch-up post leaves it there.
        if (style == ReminderAlertStyle.FullScreenRing && isShowing(alert)) return
        val channelId = channelFor(alert, style) ?: return
        val title = displayTitle(alert)
        val text = contentText(alert)
        if (style == ReminderAlertStyle.FullScreenRing) {
            if (ReminderRingService.start(context, alerts, alert, build(alert, channelId, title, text, style, quiet = false))) {
                // An earlier reminder for this occurrence gives way to the ring.
                NotificationManagerCompat.from(context).cancel(alert.occurrenceKey.toString(), NOTIFICATION_ID)
                cancelLegacy(alert)
                return
            }
            // Android refused the ring: still full-screen, sounding once on the calendar's channel.
            stopRing(RingMatch.Occurrence(alert.occurrenceKey))
            val fallback = channelFor(alert, ReminderAlertStyle.FullScreen) ?: return
            notify(alert, build(alert, fallback, title, text, ReminderAlertStyle.FullScreen, quiet = false))
            return
        }
        // A fresh reminder replaces a ring for its occurrence from before the style changed.
        if (ring) stopRing(RingMatch.Occurrence(alert.occurrenceKey))
        notify(alert, build(alert, channelId, title, text, style, quiet))
    }

    /** Whether a ring for [alert] should go on, now that settings may have changed since it started. */
    suspend fun ringVerdict(alert: ReminderAlert): RingVerdict = when {
        !settingsPrefs.remindersEnabled.first() || !canPost() || isSilenced(alert.calendarId) -> RingVerdict.Drop
        effectiveStyle(alert) != ReminderAlertStyle.FullScreenRing -> RingVerdict.HandBack
        else -> RingVerdict.Ring
    }

    /** Whether a live ring for [alert] may go on: its notification can still be seen. */
    fun canStillRing(alert: ReminderAlert): Boolean = canPost() && channels.canRing(alert.calendarId)

    /** The calendar's style, short of ringing where the user blocked its calendar, group or the ring channel. */
    private suspend fun effectiveStyle(alert: ReminderAlert): ReminderAlertStyle {
        val chosen = settingsPrefs.reminderAlertStyles.first().alertStyleFor(alert.calendarId)
        return if (chosen == ReminderAlertStyle.FullScreenRing && !channels.canRing(alert.calendarId)) {
            ReminderAlertStyle.FullScreen
        } else {
            chosen
        }
    }

    private fun channelFor(alert: ReminderAlert, style: ReminderAlertStyle): String? = try {
        channels.channelFor(alert.calendarId, style)
    } catch (e: Exception) {
        Log.w(TAG, "No channel for the reminder of event ${alert.eventId}", e)
        null
    }

    private suspend fun contentText(alert: ReminderAlert): String =
        listOfNotNull(timeText(alert), alert.location).joinToString(" · ")

    /** When [alert]'s occurrence happens, as its notification words it. */
    suspend fun timeText(alert: ReminderAlert): String {
        val is24Hour = settingsPrefs.timeFormat.first()
            .is24Hour(android.text.format.DateFormat.is24HourFormat(context))
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        // resolveFirstDay yields a kotlinx.datetime day; bridge it to java.time by
        // its shared ISO number (1..7) for the date math in reminderTimeText.
        val firstDayOfWeek = DayOfWeek.of(settingsPrefs.weekStart.first().resolveFirstDay(locale).isoDayNumber)
        return reminderTimeText(
            beginMillis = alert.beginMillis,
            endMillis = alert.endMillis,
            isAllDay = alert.isAllDay,
            zone = zone,
            locale = locale,
            is24Hour = is24Hour,
            today = Instant.now().atZone(zone).toLocalDate(),
            firstDayOfWeek = firstDayOfWeek,
            todayLabel = context.getString(R.string.reminder_day_today),
            tomorrowLabel = context.getString(R.string.reminder_day_tomorrow),
            yesterdayLabel = context.getString(R.string.reminder_day_yesterday),
        )
    }

    /**
     * The reminder's notification in [style]. A ringing one belongs to
     * [ReminderRingService]: ongoing and opening the alert screen. A full-screen
     * one adds the alert screen as a full-screen intent where Android allows
     * it, degrading to a heads-up where it does not, and is dismissed by a
     * swipe, so its screen closes too.
     */
    private fun build(
        alert: ReminderAlert,
        channelId: String,
        title: String,
        text: String,
        style: ReminderAlertStyle,
        quiet: Boolean,
    ): Notification {
        val ringing = style == ReminderAlertStyle.FullScreenRing
        val fullScreen = style.isFullScreen
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(alert.beginMillis)
            .setShowWhen(false)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (ringing) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .addExtras(Bundle().apply { putLong(EXTRA_REMINDER_KEY, alert.key) })
            .setContentIntent(if (ringing) alertScreenIntent(alert) else detailIntent(alert))
            .addAction(
                R.drawable.ic_notification_snooze,
                context.getString(R.string.reminder_action_snooze),
                actionIntent(alert, ReminderActionReceiver.ACTION_SNOOZE),
            )
            .addAction(
                R.drawable.ic_notification_dismiss,
                context.getString(R.string.reminder_action_dismiss),
                actionIntent(alert, ReminderActionReceiver.ACTION_DISMISS),
            )
        if (fullScreen) builder.setDeleteIntent(actionIntent(alert, ReminderActionReceiver.ACTION_DISMISS))
        if (ringing) {
            builder.setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setAutoCancel(true).setOnlyAlertOnce(shownKey(alert) == alert.key)
        }
        if (quiet) builder.setSilent(true)
        if (fullScreen && context.canUseFullScreenIntent()) builder.setFullScreenIntent(alertScreenIntent(alert), true)
        return builder.build()
    }

    private fun notify(alert: ReminderAlert, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        cancelLegacy(alert)
        try {
            manager.notify(alert.occurrenceKey.toString(), NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between canPost() and here.
            Log.w(TAG, "Could not post reminder for event ${alert.eventId}", e)
        }
    }

    /**
     * Remove [alert]'s notification (snooze re-shows it later; dismiss is final),
     * unless a later reminder has replaced it — an action tapped on a stale view
     * mustn't take the newer one with it.
     */
    fun cancel(alert: ReminderAlert) {
        val manager = NotificationManagerCompat.from(context)
        if (shownKey(alert) == alert.key) manager.cancel(alert.occurrenceKey.toString(), NOTIFICATION_ID)
        // Posted before reminders shared a notification, under the reminder's own key.
        manager.cancel(alert.key.toString(), NOTIFICATION_ID)
        stopRing(RingMatch.Key(alert.key))
        alerts.markHandled(alert)
    }

    /** Stop the ring if it is for a reminder [match]es; [handBack] keeps it in the shade, quietly. */
    fun stopRing(match: RingMatch, handBack: Boolean = false) {
        if (alerts.mayRing(match)) ReminderRingService.stop(context, alerts, match, handBack)
    }

    /** Whether a different reminder for [alert]'s occurrence is on screen now. */
    fun isSuperseded(alert: ReminderAlert): Boolean {
        val ringing = alerts.ringing?.takeIf { it.occurrenceKey == alert.occurrenceKey }
        if (ringing != null) return ringing.key != alert.key
        return shownKey(alert).let { it != null && it != alert.key }
    }

    /** Whether [alert] itself is still on screen, as a notification or ringing. */
    fun isShowing(alert: ReminderAlert): Boolean =
        alerts.ringing?.key == alert.key || shownKey(alert) == alert.key

    /** The key of the reminder showing for [alert]'s occurrence, if any. */
    private fun shownKey(alert: ReminderAlert): Long? {
        val tag = alert.occurrenceKey.toString()
        return NotificationManagerCompat.from(context).activeNotifications
            .firstOrNull { it.tag == tag && it.id == NOTIFICATION_ID }
            ?.notification?.extras
            ?.takeIf { it.containsKey(EXTRA_REMINDER_KEY) }
            ?.getLong(EXTRA_REMINDER_KEY)
    }

    /**
     * Remove a reminder for the same occurrence posted before reminders shared a
     * notification. It carries no reminder key; its time and title identify it.
     */
    private fun cancelLegacy(alert: ReminderAlert) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.notification.channelId == LEGACY_REMINDER_CHANNEL_ID }
            .filter { !it.notification.extras.containsKey(EXTRA_REMINDER_KEY) }
            .filter { it.notification.`when` == alert.beginMillis }
            .filter { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() == displayTitle(alert) }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun displayTitle(alert: ReminderAlert): String =
        alert.title.ifBlank { context.getString(R.string.event_untitled) }

    private fun actionIntent(alert: ReminderAlert, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ReminderActionReceiver.requestCode(alert, action),
            ReminderActionReceiver.intent(context, action, alert),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alertScreenIntent(alert: ReminderAlert): PendingIntent = PendingIntent.getActivity(
        context,
        ReminderActionReceiver.requestCode(alert, ReminderActionReceiver.ACTION_ALERT_SCREEN),
        ReminderAlertActivity.intent(context, alert),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun detailIntent(alert: ReminderAlert): PendingIntent = PendingIntent.getActivity(
        context,
        // Shares the per-(alert, action) request-code scheme with the buttons.
        /* requestCode = */ ReminderActionReceiver.requestCode(
            alert, ReminderActionReceiver.ACTION_OPEN,
        ),
        MainActivity.eventDetailIntent(context, alert.eventId, alert.beginMillis, alert.endMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "ReminderNotifier"

        // One id, distinct tags: the tag (occurrence) already keys the
        // notification, so a fixed id keeps cancellation/replacement simple.
        const val NOTIFICATION_ID = 1

        const val EXTRA_REMINDER_KEY = "de.jeanlucmakiola.calendula.reminder_key"
    }
}
