package de.jeanlucmakiola.calendula.widget.month

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import de.jeanlucmakiola.calendula.widget.syncWidgetRollover
import de.jeanlucmakiola.floret.glance.reloadOnUpdate

/**
 * Host-facing receiver for the month widget. Declared in the manifest with the
 * `appwidget_info_month` provider metadata; delegates rendering to [MonthWidget].
 */
class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()

    /** First month widget placed — start rolling "today" over at midnight (#228). */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        syncWidgetRollover(context)
    }

    /**
     * Last month widget removed. [syncWidgetRollover] cancels only if
     * no agenda widget is left either.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        syncWidgetRollover(context)
    }

    /**
     * The `updatePeriodMillis` backstop is the one wake-up the *system* still
     * owns, so it doubles as the alarm's self-heal: anything that drops a
     * pending alarm without a broadcast (force-stop, battery restriction, an OEM
     * freeze) is repaired here rather than on the next app open. Re-arming
     * closer to midnight also narrows the inexact delivery window.
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
