package de.jeanlucmakiola.calendula.widget.countdown

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import de.jeanlucmakiola.calendula.data.prefs.is24Hour
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.widget.Themed
import de.jeanlucmakiola.calendula.widget.WidgetLoadContext
import de.jeanlucmakiola.calendula.widget.hasCalendarPermission
import de.jeanlucmakiola.calendula.widget.systemZone
import de.jeanlucmakiola.calendula.widget.today
import de.jeanlucmakiola.calendula.widget.widgetEntryPoint
import de.jeanlucmakiola.calendula.widget.widgetLoadContext
import de.jeanlucmakiola.calendula.widget.widgetState
import de.jeanlucmakiola.calendula.widget.withoutCalendars
import de.jeanlucmakiola.floret.android.goAsync
import de.jeanlucmakiola.floret.glance.updateWithRefresh
import de.jeanlucmakiola.floret.prefs.toEnum
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private val ModeKey = stringPreferencesKey("cfg_countdown_mode")
private val EventKey = longPreferencesKey("cfg_countdown_event")
private val TitleKey = stringPreferencesKey("cfg_countdown_title")

internal val Preferences.countdownOptions: CountdownOptions
    get() = CountdownOptions(this[ModeKey].toEnum(CountdownMode.NEXT), this[EventKey], this[TitleKey])

internal fun MutablePreferences.putCountdownOptions(options: CountdownOptions) {
    this[ModeKey] = options.mode.name
    if (options.eventId != null) this[EventKey] = options.eventId else remove(EventKey)
    if (options.title != null) this[TitleKey] = options.title else remove(TitleKey)
}

/** How far ahead the next-event mode looks; past this it shows no event. */
private val NextEventHorizon = 30.days

/** How far ahead a pinned event's next occurrence is looked for. */
private val PinnedHorizon = 730.days

sealed interface CountdownData {
    data object NeedsPermission : CountdownData
    data class Ready(
        val options: CountdownOptions,
        /** The occurrence counted to; null when there is none to show. */
        val event: EventInstance?,
        val now: Instant,
        val today: LocalDate,
        val is24Hour: Boolean,
        val soften: Boolean,
        /** The placed widget, for opening its settings; null in a preview. */
        val appWidgetId: Int?,
    ) : CountdownData
}

internal suspend fun Context.loadCountdown(
    load: WidgetLoadContext,
    options: CountdownOptions,
    appWidgetId: Int?,
): Themed<CountdownData> {
    val settings = load.settings
    if (!hasCalendarPermission()) return Themed(CountdownData.NeedsPermission, settings.themeMode)
    val now = Clock.System.now()
    val repository = widgetEntryPoint().calendarRepository()
    val event = when (options.mode) {
        CountdownMode.NEXT ->
            pickNextEvent(repository.instances(now..now + NextEventHorizon).first().withoutCalendars(load.hiddenCalendars), now)
        CountdownMode.PINNED -> options.eventId?.let { id ->
            pickPinnedOccurrence(repository.eventOccurrences(id, now - 1.days..now + PinnedHorizon), now, systemZone())
        }
    }
    return Themed(
        CountdownData.Ready(
            options = options,
            event = event,
            now = now,
            today = today(systemZone()),
            is24Hour = settings.timeFormat.is24Hour(android.text.format.DateFormat.is24HourFormat(this)),
            soften = settings.soften,
            appWidgetId = appWidgetId,
        ),
        settings.themeMode,
    )
}

/** [loadCountdown] for placed widget [id], arming its redraw for when the label next changes. */
internal suspend fun Context.loadPlacedCountdown(id: GlanceId): Themed<CountdownData> {
    val appWidgetId = GlanceAppWidgetManager(this).getAppWidgetId(id)
    val loaded = loadCountdown(widgetLoadContext(id), widgetState(id).countdownOptions, appWidgetId)
    val event = (loaded.content as? CountdownData.Ready)?.event
    if (event == null) {
        CountdownTicks.cancel(this, appWidgetId)
    } else {
        val zone = systemZone()
        CountdownTicks.schedule(this, appWidgetId, nextLabelChange(event.counted(zone), (loaded.content as CountdownData.Ready).now, zone))
    }
    return loaded
}

/**
 * One alarm per countdown widget, for the moment its label changes. Not a
 * wake-up alarm: with the screen off nobody sees the widget, so the redraw
 * waits for the device to wake. Exact where allowed (the app holds the exact
 * alarm permission for reminders), else within a minute.
 */
internal object CountdownTicks {
    private const val ACTION_TICK = "de.jeanlucmakiola.calendula.widget.COUNTDOWN_TICK"

    fun schedule(context: Context, appWidgetId: Int, at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context, appWidgetId)
        val millis = at.toEpochMilliseconds()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExact(AlarmManager.RTC, millis, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC, millis, 60_000L, intent)
        }
    }

    fun cancel(context: Context, appWidgetId: Int) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, appWidgetId))
    }

    private fun pendingIntent(context: Context, appWidgetId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, CountdownTickReceiver::class.java)
            .setAction(ACTION_TICK)
            // Keys the PendingIntent per widget.
            .setData("calendula://countdown/$appWidgetId".toUri())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Reloads the countdown widget whose label just changed; the load arms the next tick. */
class CountdownTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val appContext = context.applicationContext
        goAsync {
            try {
                val glanceId = GlanceAppWidgetManager(appContext).getGlanceIdBy(appWidgetId)
                CountdownWidget().updateWithRefresh(appContext, glanceId)
            } catch (_: IllegalArgumentException) {
                // The widget is gone.
                CountdownTicks.cancel(appContext, appWidgetId)
            }
        }
    }
}
