package de.jeanlucmakiola.calendula.widget.agenda

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import de.jeanlucmakiola.calendula.widget.syncWidgetRollover
import de.jeanlucmakiola.floret.glance.reloadOnUpdate
import de.jeanlucmakiola.floret.glance.restoreState

/**
 * Host-facing receiver for the agenda widget. Declared in the manifest with the
 * `appwidget_info_agenda` provider metadata; delegates all rendering to
 * [AgendaWidget].
 */
class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()

    /** First agenda widget placed — start rolling "today" over at midnight (#228). */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        syncWidgetRollover(context)
    }

    /**
     * Last agenda widget removed. [syncWidgetRollover] cancels only if
     * no month widget is left either.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        syncWidgetRollover(context)
    }

    /** A backup restore or device transfer renumbered the widgets: keep each one's settings (#292). */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        restoreState(context, oldWidgetIds, newWidgetIds)
    }

    /**
     * Self-heal on the system's own `updatePeriodMillis` wake-up — see
     * [de.jeanlucmakiola.calendula.widget.month.MonthWidgetReceiver.onUpdate].
     */
    @SuppressLint("MissingSuperCall")
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // In place of super, which only recomposes and would take the goAsync handle.
        reloadOnUpdate(context, appWidgetIds)
        syncWidgetRollover(context)
    }
}
