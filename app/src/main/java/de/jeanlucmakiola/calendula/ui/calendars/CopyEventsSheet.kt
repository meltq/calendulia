package de.jeanlucmakiola.calendula.ui.calendars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.ics.IcsImportSummary
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.CalendarPickerGroups
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position

/**
 * Copy every event of one calendar into another (#325): pick the source and the
 * target, copy, watch it run. The run lives in [CopyEventsViewModel], so this
 * sheet can be closed and reopened mid-copy.
 */
@Composable
fun CopyEventsSheet(
    calendars: List<CalendarSource>?,
    selection: CopySelection,
    run: CopyRunState,
    onSelectSource: (Long) -> Unit,
    onSelectTarget: (Long) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = calendars?.let(::copySources).orEmpty()
    val source = sources.firstOrNull { it.id == selection.sourceId }
    val targets = calendars?.let { copyTargets(it, source?.id) }.orEmpty()
    val target = targets.firstOrNull { it.id == selection.targetId }
    val running = run is CopyRunState.Running
    var picking by rememberSaveable { mutableStateOf<CopyEnd?>(null) }

    FullScreenPicker(
        title = stringResource(R.string.calendars_copy_action),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        GroupedHint(stringResource(R.string.calendars_copy_hint), style = MaterialTheme.typography.bodySmall)
        CopyEndRow(
            label = stringResource(R.string.calendars_copy_from),
            calendar = source,
            position = Position.Top,
            onClick = if (running) null else ({ picking = CopyEnd.Source }),
        )
        CopyEndRow(
            label = stringResource(R.string.calendars_copy_to),
            calendar = target,
            position = Position.Bottom,
            onClick = if (running || source == null) null else ({ picking = CopyEnd.Target }),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (run) {
                is CopyRunState.Running -> CopyProgress(run, onCancel)
                is CopyRunState.Done -> CopySummary(run.summary)
                CopyRunState.Cancelled -> CopyNote(stringResource(R.string.calendars_copy_cancelled))
                CopyRunState.Failed -> CopyNote(
                    stringResource(R.string.calendars_copy_failed),
                    error = true,
                )
                CopyRunState.Idle -> Unit
            }
            if (!running) {
                Button(
                    onClick = onStart,
                    enabled = source != null && target != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calendars_copy_start))
                }
            }
        }
    }

    when (picking) {
        CopyEnd.Source -> FullScreenPicker(
            title = stringResource(R.string.calendars_copy_from_title),
            onDismiss = { picking = null },
            predictiveBack = true,
        ) {
            CalendarPickerGroups(
                calendars = sources,
                selectedId = source?.id,
                onSelect = {
                    onSelectSource(it)
                    picking = null
                },
            )
        }
        CopyEnd.Target -> FullScreenPicker(
            title = stringResource(R.string.calendars_copy_to_title),
            onDismiss = { picking = null },
            predictiveBack = true,
        ) {
            CalendarPickerGroups(
                calendars = targets,
                selectedId = target?.id,
                onSelect = {
                    onSelectTarget(it)
                    picking = null
                },
            )
        }
        null -> Unit
    }
}

private enum class CopyEnd { Source, Target }

@Composable
private fun CopyEndRow(
    label: String,
    calendar: CalendarSource?,
    position: Position,
    onClick: (() -> Unit)?,
) {
    GroupedRow(
        title = label,
        summary = calendar?.displayName?.orUnnamed() ?: stringResource(R.string.calendars_copy_choose),
        position = position,
        dimmed = onClick == null,
        leading = calendar?.let { { CalendarColorChip(it.color) } },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyProgress(run: CopyRunState.Running, onCancel: () -> Unit) {
    val total = run.total
    if (total == null || total == 0) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearWavyProgressIndicator(
            progress = { run.done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        text = copyProgressText(run),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dialog_cancel))
    }
}

/** "Reading events…" until the source is read, then "120 of 2,400 events". */
@Composable
fun copyProgressText(run: CopyRunState.Running): String {
    val total = run.total ?: return stringResource(R.string.calendars_copy_reading)
    return pluralStringResource(R.plurals.calendars_copy_progress, total, run.done, total)
}

@Composable
private fun CopySummary(summary: IcsImportSummary) {
    Text(
        stringResource(R.string.calendars_copy_done),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CopyCount(stringResource(R.string.import_done_added_label), summary.imported)
        CopyCount(stringResource(R.string.import_done_skipped_label), summary.skippedDuplicate)
        if (summary.failed > 0) CopyCount(stringResource(R.string.import_done_failed_label), summary.failed)
    }
    if (summary.skippedDuplicate > 0) CopyNote(stringResource(R.string.import_done_dedup_note))
    if (summary.failed > 0) CopyNote(stringResource(R.string.import_done_failed_note))
    if (summary.notAttempted > 0) {
        CopyNote(
            pluralStringResource(R.plurals.calendars_copy_stopped, summary.notAttempted, summary.notAttempted),
            error = true,
        )
    }
}

@Composable
private fun CopyCount(label: String, count: Int) {
    Column {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
