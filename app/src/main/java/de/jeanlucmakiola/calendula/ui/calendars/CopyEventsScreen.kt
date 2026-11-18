package de.jeanlucmakiola.calendula.ui.calendars

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.FailureReason
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.CalendarFailure
import de.jeanlucmakiola.calendula.ui.common.CalendarPickerGroups
import de.jeanlucmakiola.calendula.ui.common.ImportSummaryStats
import de.jeanlucmakiola.calendula.ui.common.ResultBadge
import de.jeanlucmakiola.calendula.ui.common.ResultMessage
import de.jeanlucmakiola.calendula.ui.common.importSummaryNotes
import de.jeanlucmakiola.floret.components.OnboardingPrimaryButton
import de.jeanlucmakiola.floret.components.ResultBadge as FloretResultBadge
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.IconInfoCard

/**
 * Copy every event of one calendar into another (#325), in the import flow's
 * steps: pick the source, pick the target and copy, watch it run, see the result.
 * The run lives in [CopyEventsViewModel], so the screen can be closed and
 * reopened mid-copy.
 */
@Composable
fun CopyEventsScreen(
    calendars: CopyCalendars,
    selection: CopySelection,
    run: CopyRunState,
    onSelectSource: (Long) -> Unit,
    onSelectTarget: (Long) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val list = (calendars as? CopyCalendars.Ready)?.calendars.orEmpty()
    val sources = remember(list) { copySources(list) }
    val source = sources.firstOrNull { it.id == selection.sourceId }
    val targets = remember(list, source?.id) { copyTargets(list, source?.id) }
    val target = targets.firstOrNull { it.id == selection.targetId }
    var step by rememberSaveable { mutableStateOf(CopyStep.Source) }
    val idle = run == CopyRunState.Idle
    // The run's ends, held once seen so a calendar gone or a list reloading mid-run doesn't blank them.
    val runSource = rememberLastSeen(selection.sourceId, list)
    val runTarget = rememberLastSeen(selection.targetId, list)
    // A source that stopped qualifying sends the target step back to the start.
    // Keyed on the chosen id too: right after a pick the selection may not have arrived yet.
    LaunchedEffect(step, selection.sourceId, source, calendars) {
        val gone = selection.sourceId != null && source == null && calendars is CopyCalendars.Ready
        if (step == CopyStep.Target && gone) step = CopyStep.Source
    }

    FullScreenPicker(
        title = stringResource(
            when {
                !idle -> R.string.calendars_copy_action
                step == CopyStep.Source -> R.string.calendars_copy_from_title
                else -> R.string.calendars_copy_to_title
            },
        ),
        onDismiss = { if (idle && step == CopyStep.Target) step = CopyStep.Source else onDismiss() },
        // The target step's back only steps back, so it mustn't preview closing the screen.
        predictiveBack = !(idle && step == CopyStep.Target),
        scrollable = idle,
        actions = {
            if (idle && step == CopyStep.Target) {
                Button(
                    onClick = onStart,
                    enabled = source != null && target != null,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Text(stringResource(R.string.calendars_copy_start))
                }
            }
        },
    ) {
        if (!idle) {
            val copyMore = {
                onClearFinished()
                step = CopyStep.Source
            }
            CopyRun(
                run = run,
                source = runSource,
                target = runTarget,
                onCancel = onCancel,
                // A retry only where the picker would still offer both ends; otherwise pick again.
                onRetry = if (source != null && target != null) onStart else copyMore,
                onCopyMore = copyMore,
                onClose = onDismiss,
            )
            return@FullScreenPicker
        }
        when (calendars) {
            CopyCalendars.Loading -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@FullScreenPicker
            }
            CopyCalendars.Failure -> {
                CalendarFailure(reason = FailureReason.ProviderUnavailable, onRetry = onDismiss)
                return@FullScreenPicker
            }
            is CopyCalendars.Ready -> Unit
        }
        if (sources.isEmpty()) {
            GroupedHint(stringResource(R.string.calendars_copy_none))
            return@FullScreenPicker
        }
        when (step) {
            CopyStep.Source -> {
                IconInfoCard(
                    icon = Icons.Outlined.Info,
                    iconContentDescription = null,
                    modifier = Modifier.padding(horizontal = GroupedListInset),
                ) {
                    Text(stringResource(R.string.calendars_copy_hint), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                CalendarPickerGroups(
                    calendars = sources,
                    selectedId = source?.id,
                    onSelect = {
                        onSelectSource(it)
                        step = CopyStep.Target
                    },
                )
            }
            CopyStep.Target -> {
                CopyRouteCard(source, target, Modifier.padding(horizontal = GroupedListInset))
                Spacer(Modifier.height(16.dp))
                CalendarPickerGroups(
                    calendars = targets,
                    selectedId = target?.id,
                    onSelect = onSelectTarget,
                )
            }
        }
    }
}

private enum class CopyStep { Source, Target }

/** Progress while the copy runs, then its outcome; both under the route it took. */
@Composable
private fun ColumnScope.CopyRun(
    run: CopyRunState,
    source: CalendarSource?,
    target: CalendarSource?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCopyMore: () -> Unit,
    onClose: () -> Unit,
) {
    // Centred where it fits, scrolling where it doesn't (landscape, large fonts).
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = GroupedListInset)
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CopyRouteCard(source, target, pickable = false)
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (run) {
            is CopyRunState.Running -> CopyProgress(run)
            is CopyRunState.Done -> CopyDone(run)
            CopyRunState.Cancelled -> {
                FloretResultBadge(
                    Icons.Default.Close,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
                ResultMessage(stringResource(R.string.calendars_copy_cancelled))
            }
            CopyRunState.Failed -> {
                ResultBadge(error = true)
                ResultMessage(stringResource(R.string.calendars_copy_failed))
            }
            CopyRunState.Idle -> Unit
        }
        }
        when (run) {
            is CopyRunState.Running -> TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dialog_cancel))
            }
            is CopyRunState.Done -> CopyEndButtons(
                primary = stringResource(R.string.import_close),
                onPrimary = onClose,
                secondary = stringResource(R.string.calendars_copy_more),
                onSecondary = onCopyMore,
            )
            CopyRunState.Cancelled, CopyRunState.Failed -> CopyEndButtons(
                primary = stringResource(R.string.state_retry),
                onPrimary = onRetry,
                secondary = stringResource(R.string.import_close),
                onSecondary = onClose,
            )
            CopyRunState.Idle -> Unit
        }
    }
    }
}

/** The calendar with [id] in [list], or the last one seen under that id while it is missing. */
@Composable
private fun rememberLastSeen(id: Long?, list: List<CalendarSource>): CalendarSource? {
    val held = remember(id) { mutableStateOf<CalendarSource?>(null) }
    list.firstOrNull { it.id == id }?.let { held.value = it }
    return held.value
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyProgress(run: CopyRunState.Running) {
    val total = run.total
    if (total == null || total == 0) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearWavyProgressIndicator(progress = { run.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = copyProgressText(run),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Reading events…" until the source is read, then "120 of 2,400 events". */
@Composable
fun copyProgressText(run: CopyRunState.Running): String {
    val total = run.total ?: return stringResource(R.string.calendars_copy_reading)
    return pluralStringResource(R.plurals.calendars_copy_progress, total, run.done, total)
}

@Composable
private fun CopyDone(run: CopyRunState.Done) {
    val summary = run.summary
    // As on the import: only a run the target cut short reads as an error.
    ResultBadge(error = run.stopped)
    ResultMessage(
        title = stringResource(run.titleRes),
        notes = importSummaryNotes(
            summary,
            stoppedNote = if (run.stopped) {
                pluralStringResource(R.plurals.calendars_copy_stopped, summary.notAttempted, summary.notAttempted)
            } else {
                null
            },
        ),
    )
    Spacer(Modifier.height(24.dp))
    ImportSummaryStats(summary)
}

/** The way on ([secondary], e.g. Copy more) above the way out (Close). */
@Composable
private fun CopyEndButtons(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OnboardingPrimaryButton(text = primary, onClick = onPrimary)
        TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
    }
}

/**
 * Where the events come from and go to, with an arrow between. A missing end
 * reads "Choose a calendar" while [pickable], or as unnamed on the run screens.
 */
@Composable
private fun CopyRouteCard(
    source: CalendarSource?,
    target: CalendarSource?,
    modifier: Modifier = Modifier,
    pickable: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp)) {
            CopyRouteEnd(stringResource(R.string.calendars_copy_from), source, pickable)
            Box(Modifier.width(40.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(6.dp).size(20.dp),
                    )
                }
            }
            CopyRouteEnd(stringResource(R.string.calendars_copy_to), target, pickable)
        }
    }
}

@Composable
private fun CopyRouteEnd(label: String, calendar: CalendarSource?, pickable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (calendar != null) {
            CalendarColorChip(calendar.color)
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {}
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                calendar?.displayName?.orUnnamed()
                    ?: if (pickable) stringResource(R.string.calendars_copy_choose) else "".orUnnamed(),
                style = MaterialTheme.typography.titleMedium,
                color = if (calendar != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (calendar != null) {
                Text(
                    if (calendar.isLocal) stringResource(R.string.calendars_local_header) else calendar.accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
