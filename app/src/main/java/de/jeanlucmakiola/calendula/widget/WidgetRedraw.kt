package de.jeanlucmakiola.calendula.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.di.ApplicationScope
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.widget.agenda.AgendaWidget
import de.jeanlucmakiola.calendula.widget.agenda.AgendaWidgetReceiver
import de.jeanlucmakiola.calendula.widget.month.MonthWidget
import de.jeanlucmakiola.calendula.widget.month.MonthWidgetReceiver
import de.jeanlucmakiola.floret.glance.WidgetRefresher
import de.jeanlucmakiola.floret.glance.WidgetRollover
import de.jeanlucmakiola.floret.glance.updatePlaced
import de.jeanlucmakiola.floret.glance.updateWithRefresh
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Every widget receiver; the set [WidgetRollover] checks for placed instances. */
internal val WIDGET_RECEIVER_CLASSES: List<Class<*>> = listOf(
    MonthWidgetReceiver::class.java,
    AgendaWidgetReceiver::class.java,
)

/** The app's own midnight wake-up (#228). */
internal const val ACTION_WIDGET_ROLLOVER = "de.jeanlucmakiola.calendula.widget.ROLLOVER"

/** Fixed: there is only ever one rollover alarm, and re-arming must replace it. */
private const val ROLLOVER_REQUEST_CODE = 0x0DA1

internal fun widgetRolloverPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    ROLLOVER_REQUEST_CODE,
    Intent(context, WidgetUpdateReceiver::class.java).setAction(ACTION_WIDGET_ROLLOVER),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Arm the next midnight rollover, or cancel it when no widget is placed. Idempotent. */
internal fun syncWidgetRollover(context: Context) =
    WidgetRollover.sync(context, WIDGET_RECEIVER_CLASSES, ::widgetRolloverPendingIntent)

/**
 * Reloads and redraws every placed widget, a running session included: the
 * refresh stamp moves, and both widgets key their data load on it (#420).
 */
internal suspend fun redrawWidgets(context: Context) {
    redrawAgendaWidgets(context)
    redrawMonthWidgets(context)
}

private suspend fun redrawAgendaWidgets(context: Context) =
    AgendaWidget().updatePlaced(context, AgendaWidgetReceiver::class.java, bumpRefreshStamp = true)

/** Redraws only the agenda widgets with no options of their own, which still follow the app's. */
private suspend fun redrawUnconfiguredAgendaWidgets(context: Context) {
    val appContext = context.applicationContext
    val ids = AppWidgetManager.getInstance(appContext)
        ?.getAppWidgetIds(ComponentName(appContext, AgendaWidgetReceiver::class.java)) ?: return
    val glanceManager = GlanceAppWidgetManager(appContext)
    ids.forEach { appWidgetId ->
        val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
        if (!appContext.widgetState(glanceId).hasAgendaOptions) AgendaWidget().updateWithRefresh(appContext, glanceId)
    }
}

private suspend fun redrawMonthWidgets(context: Context) =
    MonthWidget().updatePlaced(context, MonthWidgetReceiver::class.java, bumpRefreshStamp = true)

/** When the app last saw a calendar change of its own, for skipping the `PROVIDER_CHANGED` that echoes it. */
private val lastLocalChange = AtomicLong(0L)

private const val PROVIDER_ECHO_WINDOW_MILLIS = 5_000L

internal fun isProviderEcho(): Boolean {
    val last = lastLocalChange.get()
    return last != 0L && SystemClock.elapsedRealtime() - last < PROVIDER_ECHO_WINDOW_MILLIS
}

/**
 * Redraws the widgets on the app's own calendar writes, visibility changes and
 * the settings they draw with, so those don't wait for `PROVIDER_CHANGED`,
 * which arrives seconds late or not at all (#420, #229). Changes from outside
 * the app are [WidgetUpdateReceiver]'s. The app's agenda options only redraw
 * agenda widgets that have none of their own (#292). Started once from
 * `CalendulaApp`.
 */
@Singleton
class WidgetRefreshCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CalendarRepository,
    private val prefs: SettingsPrefs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Subscribes before returning, so a change made right after is never taken for the starting value. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val agenda = WidgetRefresher(scope) { redrawAgendaWidgets(context) }
        val unconfiguredAgenda = WidgetRefresher(scope) { redrawUnconfiguredAgendaWidgets(context) }
        val month = WidgetRefresher(scope) { redrawMonthWidgets(context) }
        watch(repository.localChanges) {
            lastLocalChange.set(SystemClock.elapsedRealtime())
            agenda.request()
            month.request()
        }
        watch(prefs.widgetSettings.map { it.shared }.distinctUntilChanged().drop(1)) {
            agenda.request()
            month.request()
        }
        watch(prefs.widgetSettings.map { it.agendaDefaults }.distinctUntilChanged().drop(1)) {
            unconfiguredAgenda.request()
        }
    }

    private fun watch(changes: Flow<*>, onChange: () -> Unit) {
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            changes.collect { onChange() }
        }
    }
}
