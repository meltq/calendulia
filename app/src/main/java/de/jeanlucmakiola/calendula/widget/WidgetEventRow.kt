package de.jeanlucmakiola.calendula.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.jeanlucmakiola.calendula.MainActivity
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.EventInstance
import de.jeanlucmakiola.calendula.domain.isDeclined
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.common.EventDimAlpha
import de.jeanlucmakiola.calendula.ui.common.SECONDARY_INK_ALPHA
import de.jeanlucmakiola.calendula.ui.common.eventFill
import de.jeanlucmakiola.calendula.ui.common.eventInk
import de.jeanlucmakiola.calendula.widget.agenda.metricsFor
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.glance.WidgetGroupedRow
import de.jeanlucmakiola.floret.glance.themedColor

/** Text and row sizes of a compact event list, from a widget's [WidgetSize]. */
internal data class EventRowMetrics(val title: TextUnit, val time: TextUnit, val rowHeight: Dp, val message: TextUnit)

/** The agenda widget's sizes with tighter rows, resolved against the system font scale. */
internal fun compactRowMetrics(size: WidgetSize, fontScale: Float): EventRowMetrics {
    val agenda = metricsFor(size)
    return EventRowMetrics(
        title = agenda.eventTitle,
        time = agenda.eventTime,
        rowHeight = agenda.contentH * fontScale + agenda.rowVPad,
        message = agenda.message,
    )
}

/**
 * One event as a two-line row in its calendar's fill, part of a grouped card
 * at [position]; opens the occurrence. [quiet] draws it in the card colour
 * instead, for events shown only as a look ahead.
 */
@Composable
internal fun WidgetEventRow(
    event: EventInstance,
    secondary: String,
    soften: Boolean,
    metrics: EventRowMetrics,
    position: Position,
    source: CalendarView,
    dimmed: Boolean = false,
    quiet: Boolean = false,
) {
    val context = LocalContext.current
    fun tone(dark: Boolean): Color = eventFill(event.color, dark, soften).let { if (dimmed) it.copy(alpha = EventDimAlpha) else it }
    val day = tone(dark = false)
    val night = tone(dark = true)
    // Ink is picked against the opaque fill, then carries the dim along with it.
    fun ink(fill: Color, alpha: Float) = eventInk(fill.copy(alpha = 1f), alpha * fill.alpha)
    val card = widgetCardColor()
    WidgetGroupedRow(position = position, color = if (quiet) card else themedColor(day = day, night = night)) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(metrics.rowHeight)
                .padding(horizontal = 12.dp)
                .clickable(
                    actionStartActivity(
                        MainActivity.openEventIntent(
                            context = context,
                            eventId = event.eventId,
                            beginMillis = event.start.toEpochMilliseconds(),
                            endMillis = event.end.toEpochMilliseconds(),
                            source = source,
                        ),
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val titleInk = if (quiet) {
                GlanceTheme.colors.onSurface
            } else {
                themedColor(day = ink(day, 1f), night = ink(night, 1f))
            }
            val timeInk = if (quiet) {
                GlanceTheme.colors.onSurfaceVariant
            } else {
                themedColor(day = ink(day, SECONDARY_INK_ALPHA), night = ink(night, SECONDARY_INK_ALPHA))
            }
            Text(
                text = event.title.ifBlank { context.getString(R.string.event_untitled) },
                maxLines = 1,
                style = TextStyle(
                    color = titleInk,
                    fontSize = metrics.title,
                    fontWeight = FontWeight.Medium,
                    textDecoration = glanceDeclinedDecoration(event.isDeclined),
                ),
            )
            Text(text = secondary, maxLines = 1, style = TextStyle(color = timeInk, fontSize = metrics.time))
        }
    }
}
