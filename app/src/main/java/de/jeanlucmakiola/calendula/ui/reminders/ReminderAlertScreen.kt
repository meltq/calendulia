package de.jeanlucmakiola.calendula.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.ui.common.LocalSoftenColors
import de.jeanlucmakiola.calendula.ui.common.eventFill
import de.jeanlucmakiola.calendula.ui.common.eventInk
import de.jeanlucmakiola.floret.identity.LocalFloretDarkTheme
import de.jeanlucmakiola.floret.ring.RingBadge

/**
 * The alert itself, after the incoming-call screen: the calendar's pulsing
 * badge, what and when in the middle, and the two ways out at the bottom,
 * large enough to hit half awake.
 */
@Composable
internal fun ReminderAlertScreen(
    state: ReminderAlertUiState.Showing,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.calendarName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            CalendarBadge(state.calendarColor)
            Spacer(Modifier.size(32.dp))
            Text(
                text = state.title.ifBlank { stringResource(R.string.event_untitled) },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = state.time,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            state.location?.let { location ->
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1.5f))
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlertButton(
                    label = stringResource(R.string.reminder_action_snooze),
                    icon = Icons.Outlined.Snooze,
                    tonal = true,
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f),
                )
                AlertButton(
                    label = stringResource(R.string.reminder_action_dismiss),
                    icon = Icons.Rounded.Close,
                    tonal = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The calendar's colour as the badge fill, or the theme's primary container without one. */
@Composable
private fun CalendarBadge(color: Int?) {
    if (color == null) {
        RingBadge(icon = Icons.Outlined.Event, size = 120.dp)
        return
    }
    val fill = eventFill(color, LocalFloretDarkTheme.current, LocalSoftenColors.current)
    RingBadge(
        icon = Icons.Outlined.Event,
        containerColor = fill,
        contentColor = eventInk(fill, alpha = 1f),
        size = 120.dp,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlertButton(
    label: String,
    icon: ImageVector,
    tonal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = ButtonDefaults.LargeContainerHeight
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
        Text(label, style = ButtonDefaults.textStyleFor(height), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val buttonModifier = modifier.heightIn(min = height)
    val padding = ButtonDefaults.contentPaddingFor(height)
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            modifier = buttonModifier,
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = padding,
        ) { content() }
    } else {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = padding,
        ) { content() }
    }
}
