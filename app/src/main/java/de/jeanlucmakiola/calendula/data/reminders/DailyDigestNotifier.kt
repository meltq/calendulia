package de.jeanlucmakiola.calendula.data.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.MainActivity
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.is24Hour
import de.jeanlucmakiola.calendula.domain.reminders.ReminderEventInstance
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toKotlinLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the daily summary (#459): one notification listing a day's events, on
 * its own channel, replacing the one before it. Tapping opens that day.
 */
@Singleton
class DailyDigestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsPrefs: SettingsPrefs,
) {

    /**
     * Post the summary of [day] listing [listed], or withdraw the last one when
     * there is nothing to list — yesterday's would otherwise stand in for it.
     */
    suspend fun post(day: LocalDate, listed: List<ReminderEventInstance>) {
        val manager = NotificationManagerCompat.from(context)
        if (listed.isEmpty()) {
            manager.cancel(TAG_DIGEST, NOTIFICATION_ID)
            return
        }
        ensureChannel()
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val is24Hour = settingsPrefs.timeFormat.first()
            .is24Hour(android.text.format.DateFormat.is24HourFormat(context))
        val today = LocalDate.now(zone)
        val dayLabel = when (day) {
            today -> context.getString(R.string.reminder_day_today)
            today.plusDays(1) -> context.getString(R.string.reminder_day_tomorrow)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
        }
        val allDay = context.getString(R.string.event_detail_all_day)
        val lines = listed.map { entry ->
            // Read against the listed day, so a timed event on it shows its bare
            // range and one crossing midnight its dates; the week start never shows.
            val time = if (entry.isAllDay) allDay else reminderTimeText(
                beginMillis = entry.beginMillis,
                endMillis = entry.endMillis,
                isAllDay = entry.isAllDay,
                zone = zone,
                locale = locale,
                is24Hour = is24Hour,
                today = day,
                firstDayOfWeek = DayOfWeek.MONDAY,
                todayLabel = allDay,
                tomorrowLabel = context.getString(R.string.reminder_day_tomorrow),
                yesterdayLabel = context.getString(R.string.reminder_day_yesterday),
            )
            context.getString(R.string.digest_line, time, entry.title.ifBlank { context.getString(R.string.event_untitled) })
        }
        val title = context.resources.getQuantityString(R.plurals.digest_title, listed.size, dayLabel, listed.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach(style::addLine)
        if (lines.size > MAX_LINES) {
            val more = lines.size - MAX_LINES
            style.setSummaryText(context.resources.getQuantityString(R.plurals.digest_more, more, more))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(listed.joinToString(", ") { it.title.ifBlank { context.getString(R.string.event_untitled) } })
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openDay(day))
            .build()
        try {
            manager.notify(TAG_DIGEST, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post the daily summary", e)
        }
    }

    /** The summary's channel id, created if missing — for the settings deep-link. */
    fun channelId(): String {
        ensureChannel()
        return CHANNEL_ID
    }

    /** Creates the channel, or renames it after a language change; the user's settings on it stay. */
    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.digest_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.digest_channel_description) },
        )
    }

    private fun openDay(day: LocalDate): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        MainActivity.openDayIntent(context, day.toKotlinLocalDate()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "DailyDigestNotifier"
        const val CHANNEL_ID = "daily_digest"
        const val TAG_DIGEST = "daily_digest"
        const val NOTIFICATION_ID = 2
        const val REQUEST_CODE = 0xD16E
        const val MAX_LINES = 6
    }
}
