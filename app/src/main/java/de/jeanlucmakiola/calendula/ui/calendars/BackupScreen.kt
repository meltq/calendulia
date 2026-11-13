package de.jeanlucmakiola.calendula.ui.calendars

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.prefs.BackupStatus
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.backup.BACKUP_KEEP_OPTIONS
import de.jeanlucmakiola.calendula.domain.backup.BackupSelection
import de.jeanlucmakiola.calendula.domain.backup.isBackedUpByDefault
import de.jeanlucmakiola.calendula.ui.common.CalendarCheckGroups
import de.jeanlucmakiola.calendula.ui.common.CalendarFailure
import de.jeanlucmakiola.calendula.ui.common.LeadingAvatar
import de.jeanlucmakiola.calendula.ui.common.LocalManageCalendars
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.DialogAmountField
import de.jeanlucmakiola.floret.components.DialogUnitDropdown
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.locale.relativeTimeAgo
import java.time.LocalDate

// SAF mime filter for the restore picker. Source apps hand `.ics` files out
// under several mimes, so accept the common set.
private val RESTORE_MIME_TYPES = arrayOf(
    "text/calendar",
    "application/octet-stream",
    "text/plain",
)

/**
 * Backup & restore (#69): `.ics` export and restore, with
 * optional automatic backup. Shares [CalendarsViewModel] with the manager.
 *
 * A full-screen destination hoisted in `CalendarHost`; [onBack] pops it,
 * [onImport] hands a picked file to the app's normal .ics import flow.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onImport: (Uri) -> Unit,
    visible: Boolean = true,
    viewModel: CalendarsViewModel = hiltViewModel(),
    copyViewModel: CopyEventsViewModel = hiltViewModel(),
) {
    val state by viewModel.backupState.collectAsStateWithLifecycle()
    val copyRun by copyViewModel.run.collectAsStateWithLifecycle()
    var showCopy by rememberSaveable { mutableStateOf(false) }
    val backupResult by viewModel.backupResult.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The one-calendar shortcut; the per-calendar selector owns its own launcher.
    var shortcutExportId by rememberSaveable { mutableStateOf<Long?>(null) }
    val createBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val id = shortcutExportId
        if (uri != null && id != null) viewModel.exportBackup(uri, setOf(id))
        shortcutExportId = null
    }
    var showExportPicker by rememberSaveable { mutableStateOf(false) }

    // Restore runs the picked file through the normal .ics import flow.
    val openBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    // The VM persists the write grant so background runs can keep writing.
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::setAutoBackupFolder) }
    var showInterval by remember { mutableStateOf(false) }
    var showBackupCalendars by rememberSaveable { mutableStateOf(false) }
    var selectionBefore by remember { mutableStateOf<BackupSelection?>(null) }
    var showKeep by rememberSaveable { mutableStateOf(false) }

    val backupFailedText = stringResource(R.string.calendars_backup_failed)
    LaunchedEffect(backupResult) {
        when (val r = backupResult) {
            is BackupResult.Success -> {
                snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(
                        R.plurals.calendars_backup_done, r.eventCount, r.eventCount,
                    ),
                )
                viewModel.consumeBackupResult()
            }
            BackupResult.Failure -> {
                snackbarHostState.showSnackbar(backupFailedText)
                viewModel.consumeBackupResult()
            }
            null -> Unit
        }
    }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_backup),
        onBack = onBack,
        // Loading and failure fill the screen and centre themselves, which they
        // can only do in an unscrolled column — the scrolling one measures them
        // against an unbounded height and leaves them hanging under the header.
        scrollable = state is BackupUiState.Ready,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        predictiveBack = true,
        visible = visible,
    ) {
        when (val s = state) {
            BackupUiState.Loading -> BackupLoading()
            is BackupUiState.Failure -> CalendarFailure(reason = s.reason, onRetry = onBack)
            is BackupUiState.Ready -> BackupContent(
                exportable = s.exportable,
                canImport = s.canImport,
                canCopy = s.canCopy,
                copyRun = copyRun,
                onCopy = { showCopy = true },
                autoBackup = autoBackup,
                viewModel = viewModel,
                onRestore = { runCatching { openBackup.launch(RESTORE_MIME_TYPES) } },
                onPickFolder = { runCatching { pickFolder.launch(null) } },
                onExportOnly = {
                    shortcutExportId = s.exportable.singleOrNull()?.id
                    runCatching { createBackup.launch(defaultBackupName()) }
                },
                onExportPick = { showExportPicker = true },
                onEditInterval = { showInterval = true },
                onEditCalendars = {
                    selectionBefore = autoBackup.selection
                    showBackupCalendars = true
                },
                onEditKeep = { showKeep = true },
            )
        }
    }

    // Gated on Ready rather than resetting the flag when the state leaves it:
    // flipping it here would be a write during composition.
    (state as? BackupUiState.Ready)?.let { ready ->
        if (showExportPicker) {
            ExportCalendarPicker(
                calendars = ready.exportable,
                onExport = viewModel::exportBackup,
                onDismiss = { showExportPicker = false },
            )
        }
        if (showCopy) {
            val calendars by copyViewModel.calendars.collectAsStateWithLifecycle()
            val selection by copyViewModel.selection.collectAsStateWithLifecycle()
            CopyEventsSheet(
                calendars = calendars,
                selection = selection,
                run = copyRun,
                onSelectSource = copyViewModel::setSource,
                onSelectTarget = copyViewModel::setTarget,
                onStart = copyViewModel::start,
                onCancel = copyViewModel::cancel,
                onDismiss = {
                    showCopy = false
                    copyViewModel.clearFinished()
                },
            )
        }
        if (showBackupCalendars) {
            AutoBackupCalendarPicker(
                calendars = ready.exportable,
                selection = autoBackup.selection,
                onCheckedChange = viewModel::setAutoBackupCalendar,
                onDismiss = {
                    showBackupCalendars = false
                    viewModel.autoBackupCalendarsChosen(selectionBefore ?: autoBackup.selection)
                },
            )
        }
    }
    if (showKeep) {
        OptionPicker(
            title = stringResource(R.string.calendars_auto_backup_keep),
            header = {
                GroupedHint(
                    stringResource(R.string.calendars_auto_backup_keep_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            predictiveBack = true,
            options = BACKUP_KEEP_OPTIONS,
            selected = autoBackup.keep,
            label = { backupKeepLabel(it) },
            onSelect = viewModel::setAutoBackupKeep,
            onDismiss = { showKeep = false },
        )
    }
    if (showInterval) {
        BackupIntervalDialog(
            currentMinutes = autoBackup.intervalMinutes,
            onConfirm = viewModel::setAutoBackupIntervalMinutes,
            onDismiss = { showInterval = false },
        )
    }
}

/** The screen's loading state, centred in the scaffold's content column. */
@Composable
private fun BackupLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** Default file name for a one-shot export. */
private fun defaultBackupName(): String = "calendula-backup-${LocalDate.now()}.ics"

/**
 * The working screen: export (when any calendar can be backed up), restore,
 * and the automatic-backup block. Restore is always on screen — when nothing can
 * receive the events it says so and routes to the calendar manager rather than
 * disappearing, which is what #304 reported as an invisible button.
 */
@Composable
private fun BackupContent(
    exportable: List<CalendarSource>,
    canImport: Boolean,
    canCopy: Boolean,
    copyRun: CopyRunState,
    onCopy: () -> Unit,
    autoBackup: AutoBackupUiState,
    viewModel: CalendarsViewModel,
    onRestore: () -> Unit,
    onPickFolder: () -> Unit,
    onExportOnly: () -> Unit,
    onExportPick: () -> Unit,
    onEditInterval: () -> Unit,
    onEditCalendars: () -> Unit,
    onEditKeep: () -> Unit,
) {
    val manageCalendars = LocalManageCalendars.current
    // Restore never depends on export eligibility, and never disappears: when no
    // calendar can receive events it explains that and offers the way to fix it.
    val restoreSummary = if (canImport) {
        stringResource(R.string.calendars_restore_hint)
    } else {
        stringResource(R.string.calendars_restore_unavailable)
    }
    val onRestoreClick = if (canImport) onRestore else (manageCalendars ?: onRestore)

    if (exportable.isEmpty()) {
        GroupedSectionHeader(stringResource(R.string.calendars_restore_header))
        GroupedHint(restoreSummary, style = MaterialTheme.typography.bodySmall)
        GroupedRow(
            title = stringResource(R.string.calendars_restore_action),
            position = Position.Alone,
            leading = { LeadingAvatar(Icons.Default.FileUpload) },
            onClick = onRestoreClick,
        )
        return
    }

    GroupedHint(stringResource(R.string.calendars_backup_hint), style = MaterialTheme.typography.bodySmall)
    GroupedRow(
        title = stringResource(R.string.calendars_backup_action),
        position = Position.Top,
        leading = { LeadingAvatar(Icons.Default.FileDownload) },
        // A single exportable calendar skips the selector.
        onClick = if (exportable.size == 1) onExportOnly else onExportPick,
    )
    GroupedRow(
        title = stringResource(R.string.calendars_restore_action),
        summary = restoreSummary,
        position = Position.Middle,
        leading = { LeadingAvatar(Icons.Default.FileUpload) },
        onClick = onRestoreClick,
    )
    if (canCopy) {
        GroupedRow(
            title = stringResource(R.string.calendars_copy_action),
            summary = (copyRun as? CopyRunState.Running)?.let { copyProgressText(it) }
                ?: stringResource(R.string.calendars_copy_summary),
            position = Position.Middle,
            leading = { LeadingAvatar(Icons.Default.ContentCopy) },
            onClick = onCopy,
        )
    }
    GroupedRow(
        title = stringResource(R.string.calendars_auto_backup),
        summary = stringResource(R.string.calendars_auto_backup_hint),
        position = if (autoBackup.enabled) Position.Middle else Position.Bottom,
        leading = { LeadingAvatar(Icons.Default.Schedule) },
        trailing = {
            Switch(checked = autoBackup.enabled, onCheckedChange = viewModel::setAutoBackupEnabled)
        },
        onClick = { viewModel.setAutoBackupEnabled(!autoBackup.enabled) },
    )
    if (autoBackup.enabled) {
        GroupedRow(
            title = stringResource(R.string.calendars_auto_backup_folder),
            summary = rememberFolderName(autoBackup.folderUri)
                ?: stringResource(R.string.calendars_auto_backup_folder_unset),
            position = Position.Middle,
            onClick = onPickFolder,
        )
        GroupedRow(
            title = stringResource(R.string.calendars_auto_backup_interval),
            summary = backupIntervalLabel(autoBackup.intervalMinutes),
            position = Position.Middle,
            onClick = onEditInterval,
        )
        val selectedCount = exportable.count(autoBackup.selection::selects)
        GroupedRow(
            title = stringResource(R.string.calendars_auto_backup_calendars),
            summary = if (selectedCount == 0) {
                stringResource(R.string.calendars_auto_backup_calendars_none)
            } else {
                pluralStringResource(R.plurals.calendars_auto_backup_calendars_count, selectedCount, selectedCount)
            },
            position = Position.Middle,
            onClick = onEditCalendars,
        )
        GroupedSwitchRow(
            title = stringResource(R.string.calendars_auto_backup_combined),
            summary = stringResource(
                if (autoBackup.combined) {
                    R.string.calendars_auto_backup_combined_on
                } else {
                    R.string.calendars_auto_backup_combined_off
                },
            ),
            checked = autoBackup.combined,
            onCheckedChange = viewModel::setAutoBackupCombined,
            position = Position.Middle,
        )
        GroupedRow(
            title = stringResource(R.string.calendars_auto_backup_keep),
            summary = backupKeepLabel(autoBackup.keep),
            position = Position.Bottom,
            onClick = onEditKeep,
        )
        GroupedHint(
            if (selectedCount == 0) {
                stringResource(R.string.calendars_auto_backup_nothing_selected)
            } else {
                backupStatusText(autoBackup.status)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Choose which calendars to include in a one-time `.ics` export. Starts on the
 * calendars a backup covers by default, or on all of them when none is; the Export action opens the SAF save dialog and hands back
 * the picked file with the chosen calendar ids.
 */
@Composable
private fun ExportCalendarPicker(
    calendars: List<CalendarSource>,
    onExport: (Uri, Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately not keyed on [calendars]: that list is observer-driven, so
    // keying it would reset the user's de-selections on every provider re-emit.
    var selected by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) {
        val defaults = calendars.filter { it.isBackedUpByDefault }.ifEmpty { calendars }
        mutableStateOf(defaults.map { it.id }.toSet())
    }
    val createBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        if (uri != null) {
            onExport(uri, selected)
            onDismiss()
        }
    }

    FullScreenPicker(
        title = stringResource(R.string.calendars_export_title),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        GroupedHint(stringResource(R.string.calendars_export_hint), style = MaterialTheme.typography.bodySmall)
        CalendarCheckGroups(
            calendars = calendars,
            isChecked = { it.id in selected },
            onCheckedChange = { calendar, checked ->
                selected = if (checked) selected + calendar.id else selected - calendar.id
            },
        )
        Button(
            onClick = {
                runCatching { createBackup.launch(defaultBackupName()) }
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(stringResource(R.string.calendars_export_action))
        }
    }
}

/**
 * Choose which calendars the automatic backup covers (#147). Each tick is saved
 * at once, as a deviation from the default, so there is nothing to confirm.
 */
@Composable
private fun AutoBackupCalendarPicker(
    calendars: List<CalendarSource>,
    selection: BackupSelection,
    onCheckedChange: (CalendarSource, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenPicker(
        title = stringResource(R.string.calendars_auto_backup_calendars),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        GroupedHint(
            stringResource(R.string.calendars_auto_backup_calendars_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        CalendarCheckGroups(
            calendars = calendars,
            isChecked = selection::selects,
            onCheckedChange = onCheckedChange,
        )
    }
}

/** "Only the latest" / "The latest 3". */
@Composable
private fun backupKeepLabel(keep: Int): String =
    pluralStringResource(R.plurals.calendars_auto_backup_keep_count, keep, keep)

/** Readable name of the persisted backup folder, resolved from its tree Uri. */
@Composable
private fun rememberFolderName(uriString: String?): String? {
    val context = LocalContext.current
    return remember(uriString) {
        uriString?.let {
            runCatching { DocumentFile.fromTreeUri(context, it.toUri())?.name }.getOrNull()
        }
    }
}

/** "Every 30 minutes" / "Every 2 hours" / "Every day" — the interval in its largest whole unit. */
@Composable
private fun backupIntervalLabel(minutes: Long): String {
    val duration = when {
        minutes % MINUTES_PER_WEEK == 0L ->
            pluralStringResource(R.plurals.duration_weeks, (minutes / MINUTES_PER_WEEK).toInt(), (minutes / MINUTES_PER_WEEK).toInt())
        minutes % MINUTES_PER_DAY == 0L ->
            pluralStringResource(R.plurals.duration_days, (minutes / MINUTES_PER_DAY).toInt(), (minutes / MINUTES_PER_DAY).toInt())
        minutes % 60L == 0L ->
            pluralStringResource(R.plurals.duration_hours, (minutes / 60L).toInt(), (minutes / 60L).toInt())
        else ->
            pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
    }
    return stringResource(R.string.calendars_auto_backup_every, duration)
}

/** "Last backup: 5 minutes ago" / "… failed" / "No automatic backup yet". */
@Composable
private fun backupStatusText(status: BackupStatus): String {
    if (status.lastRun == 0L) return stringResource(R.string.calendars_auto_backup_status_never)
    val relative = relativeTimeAgo(status.lastRun)
    return if (status.lastSuccess) {
        stringResource(R.string.calendars_auto_backup_status_ok, relative)
    } else {
        stringResource(R.string.calendars_auto_backup_status_failed, relative)
    }
}

/** Amount + unit picker for the backup interval (floored at 30 minutes). */
@Composable
private fun BackupIntervalDialog(
    currentMinutes: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Pick the largest unit the current value divides into.
    val unitMinutes = remember { listOf(1L, 60L, MINUTES_PER_DAY, MINUTES_PER_WEEK) }
    val units = stringArrayResource(R.array.backup_interval_units).toList()
    val initialUnit = unitMinutes.indexOfLast { currentMinutes % it == 0L }.coerceAtLeast(0)
    var amount by rememberSaveable { mutableStateOf((currentMinutes / unitMinutes[initialUnit]).toString()) }
    var unitIndex by rememberSaveable { mutableStateOf(initialUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendars_auto_backup_interval)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DialogAmountField(value = amount, onValueChange = { amount = it }, placeholder = "1")
                    Spacer(Modifier.width(12.dp))
                    DialogUnitDropdown(label = units[unitIndex], entries = units) { unitIndex = it }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.calendars_auto_backup_interval_min),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                onConfirm((value * unitMinutes[unitIndex]).coerceAtLeast(SettingsPrefs.MIN_BACKUP_INTERVAL))
                onDismiss()
            }) { Text(stringResource(R.string.reminder_custom_set)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private const val MINUTES_PER_DAY = 1_440L
private const val MINUTES_PER_WEEK = 10_080L
