package de.jeanlucmakiola.calendula.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.calendar.CalendarRepository
import de.jeanlucmakiola.calendula.data.ics.IcsExporter
import de.jeanlucmakiola.calendula.domain.EventDetail
import de.jeanlucmakiola.calendula.domain.RecurringWriteScope
import de.jeanlucmakiola.calendula.domain.ics.IcsWriter
import de.jeanlucmakiola.calendula.domain.ics.toShareIcsEvent
import de.jeanlucmakiola.floret.components.OptionCard
import kotlin.time.Clock

/**
 * Serialise [detail] to a cache `.ics` and return a shareable content Uri for an
 * `ACTION_SEND`, or null when the write fails. Exported as a one-off (see
 * [toShareIcsEvent]). Blocking — call it off the main thread.
 */
fun IcsExporter.stageEventShare(detail: EventDetail): Uri? = runCatching {
    val ics = IcsWriter().writeCalendar(
        events = listOf(detail.toShareIcsEvent()),
        dtStamp = Clock.System.now(),
    )
    stageShareFile(shareFileName(detail.instance.title), ics)
}.getOrNull()

/** Open the system chooser for a staged `.ics`; false when nothing could take it. */
fun Context.sendEventShare(uri: Uri, chooserTitle: String): Boolean = runCatching {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/calendar"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, chooserTitle))
}.isSuccess

/** Delete an event, or as much of its series as [scope] reaches from the occurrence at [beginMillis]. */
suspend fun CalendarRepository.deleteEvent(
    eventId: Long,
    beginMillis: Long,
    scope: RecurringWriteScope,
) = when (scope) {
    RecurringWriteScope.AllEvents -> deleteEvent(eventId)
    RecurringWriteScope.ThisEvent -> deleteOccurrence(eventId, beginMillis)
    RecurringWriteScope.ThisAndFollowing -> deleteEventFromOccurrence(eventId, beginMillis)
}

/**
 * Runs an action that writes to the calendar, asking for `WRITE_CALENDAR` first
 * where the install only holds read access (v1.0 installs). A grant continues
 * straight into the action that asked for it.
 */
@Composable
fun rememberWriteGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    return { action ->
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.WRITE_CALENDAR)
        }
    }
}

/**
 * Delete confirmation. Recurring events choose between cancelling just the
 * tapped occurrence (default), truncating the series from it onwards, and
 * removing the whole series.
 */
@Composable
fun DeleteEventDialog(
    isRecurring: Boolean,
    onConfirm: (RecurringWriteScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf(RecurringWriteScope.ThisEvent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isRecurring) R.string.event_delete_recurring_title
                    else R.string.event_delete_title,
                ),
            )
        },
        text = {
            if (isRecurring) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionCard(
                        label = stringResource(R.string.event_delete_option_occurrence),
                        onClick = { scope = RecurringWriteScope.ThisEvent },
                        selected = scope == RecurringWriteScope.ThisEvent,
                    )
                    OptionCard(
                        label = stringResource(R.string.event_delete_option_following),
                        onClick = { scope = RecurringWriteScope.ThisAndFollowing },
                        selected = scope == RecurringWriteScope.ThisAndFollowing,
                    )
                    OptionCard(
                        label = stringResource(R.string.event_delete_option_series),
                        onClick = { scope = RecurringWriteScope.AllEvents },
                        selected = scope == RecurringWriteScope.AllEvents,
                    )
                }
            } else {
                Text(stringResource(R.string.event_delete_body))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (isRecurring) scope else RecurringWriteScope.AllEvents) },
            ) {
                Text(
                    text = stringResource(R.string.event_detail_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** A filesystem-safe `.ics` file name from an event title (or a fallback). */
private fun shareFileName(title: String): String {
    val base = title.trim()
        .replace(Regex("""[^\p{L}\p{Nd} _-]"""), "")
        .replace(' ', '_')
        .take(40)
        .ifBlank { "event" }
    return "$base.ics"
}
