package de.jeanlucmakiola.calendula.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.jeanlucmakiola.floret.glance.FloretWidgetUpdateReceiver
import kotlin.time.Duration.Companion.minutes

/**
 * Redraws both widgets when their data, day or language goes stale, and keeps
 * the midnight rollover armed. `PROVIDER_CHANGED` covers changes made outside
 * the app; the app's own writes go through [WidgetRefreshCoordinator]. A data
 * change also re-arms, at most every 15 minutes: the inexact alarm can be
 * dropped in a restricted standby bucket, and a calendar change is the one
 * signal that keeps arriving.
 */
class WidgetUpdateReceiver : FloretWidgetUpdateReceiver() {
    override val rolloverAction = ACTION_WIDGET_ROLLOVER
    override val widgetReceiverClasses = WIDGET_RECEIVER_CLASSES
    override val extraHandledActions = setOf(Intent.ACTION_PROVIDER_CHANGED)
    override val extraRearmInterval = 15.minutes

    override fun rolloverPendingIntent(context: Context): PendingIntent = widgetRolloverPendingIntent(context)

    override suspend fun redraw(context: Context) = redrawWidgets(context)
}
