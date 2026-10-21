package de.jeanlucmakiola.calendula.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
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
    AgendaWidget().updatePlaced(context, AgendaWidgetReceiver::class.java, bumpRefreshStamp = true)
    MonthWidget().updatePlaced(context, MonthWidgetReceiver::class.java, bumpRefreshStamp = true)
}

/**
 * Redraws the widgets on the app's own calendar writes, visibility changes and
 * the settings they draw with, so those don't wait for `PROVIDER_CHANGED`,
 * which arrives seconds late or not at all (#420, #229). Changes from outside
 * the app are [WidgetUpdateReceiver]'s. Started once from `CalendulaApp`.
 */
@Singleton
class WidgetRefreshCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CalendarRepository,
    private val prefs: SettingsPrefs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        val refresher = WidgetRefresher(scope) { redrawWidgets(context) }
        val settings = listOf(
            prefs.softenCalendarColors,
            prefs.weekStart,
            prefs.timeFormat,
            prefs.pastEventDisplay,
            prefs.agendaWidgetRange,
            prefs.widgetSize,
            prefs.agendaShowToday,
        ).map { flow -> flow.distinctUntilChanged().drop(1).map {} }
        scope.launch(Dispatchers.IO) {
            merge(repository.localChanges, *settings.toTypedArray()).collect { refresher.request() }
        }
    }
}
