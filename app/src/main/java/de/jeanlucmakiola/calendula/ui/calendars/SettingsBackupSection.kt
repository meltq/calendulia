package de.jeanlucmakiola.calendula.ui.calendars

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.backup.SettingsImportSummary
import de.jeanlucmakiola.calendula.data.backup.SkippedSetting
import de.jeanlucmakiola.calendula.domain.FontRole
import de.jeanlucmakiola.calendula.ui.common.LeadingAvatar
import de.jeanlucmakiola.floret.backup.BackupProblem
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.locale.AppLanguage
import java.time.LocalDate

// What the import picker accepts: providers label .json inconsistently.
private val SETTINGS_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** The Settings block's pickers and the import waiting for its confirmation. */
class SettingsBackupActions internal constructor(
    val busy: Boolean,
    internal val pendingImport: MutableState<String?>,
    val export: () -> Unit,
    val import: () -> Unit,
)

@Composable
fun rememberSettingsBackupActions(viewModel: SettingsBackupViewModel): SettingsBackupActions {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pendingImport = rememberSaveable { mutableStateOf<String?>(null) }
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::export) }
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImport.value = uri?.toString() }
    return SettingsBackupActions(
        busy = busy,
        pendingImport = pendingImport,
        export = { runCatching { createFile.launch("calendula-settings-${LocalDate.now()}.json") } },
        import = { runCatching { openFile.launch(SETTINGS_MIME_TYPES) } },
    )
}

/** Settings export and import (#481), below the events' backup. */
@Composable
fun SettingsBackupSection(actions: SettingsBackupActions) {
    GroupedSectionHeader(stringResource(R.string.settings_backup_header))
    GroupedHint(stringResource(R.string.settings_backup_hint), style = MaterialTheme.typography.bodySmall)
    GroupedRow(
        title = stringResource(R.string.settings_backup_export),
        summary = stringResource(R.string.settings_backup_export_summary),
        position = Position.Top,
        leading = { LeadingAvatar(Icons.Default.FileDownload) },
        dimmed = actions.busy,
        onClick = { if (!actions.busy) actions.export() },
    )
    GroupedRow(
        title = stringResource(R.string.settings_backup_import),
        summary = stringResource(R.string.settings_backup_import_summary),
        position = Position.Bottom,
        leading = { LeadingAvatar(Icons.Default.FileUpload) },
        dimmed = actions.busy,
        onClick = { if (!actions.busy) actions.import() },
    )
}

/**
 * The import's confirmation and summary dialogs, and the snackbars for
 * everything else. Plain confirmations, so [AlertDialog]s.
 */
@Composable
fun SettingsBackupDialogs(
    actions: SettingsBackupActions,
    viewModel: SettingsBackupViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val exported = stringResource(R.string.settings_backup_export_done)
    val exportFailed = stringResource(R.string.settings_backup_export_failed)
    val importFailed = BackupProblem.entries.associateWith { stringResource(it.messageRes()) }

    LaunchedEffect(result) {
        val message = when (val r = result) {
            SettingsBackupResult.Exported -> exported
            SettingsBackupResult.ExportFailed -> exportFailed
            is SettingsBackupResult.ImportFailed -> importFailed.getValue(r.problem)
            is SettingsBackupResult.Imported, null -> return@LaunchedEffect
        }
        viewModel.consumeResult()
        snackbarHostState.showSnackbar(message)
    }

    (result as? SettingsBackupResult.Imported)?.let { imported ->
        ImportSummaryDialog(imported.summary, onDismiss = viewModel::consumeResult)
    }

    actions.pendingImport.value?.let { uri ->
        AlertDialog(
            onDismissRequest = { actions.pendingImport.value = null },
            title = { Text(stringResource(R.string.settings_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.pendingImport.value = null
                        viewModel.import(uri.toUri())
                    },
                ) { Text(stringResource(R.string.settings_backup_import_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { actions.pendingImport.value = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportSummaryDialog(summary: SettingsImportSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_imported_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.settings_backup_imported_body))
                if (summary.calendarsMatched > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.settings_backup_imported_calendars,
                            summary.calendarsMatched,
                            summary.calendarsMatched,
                        ),
                    )
                }
                if (summary.skipped.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_backup_imported_skipped),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    summary.skipped.forEach { skipped ->
                        Text(
                            "• " + skipped.label(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
        },
    )
}

@Composable
private fun SkippedSetting.label(): String = when (this) {
    is SkippedSetting.Calendar -> stringResource(R.string.settings_backup_skipped_calendar, displayName, accountName)
    is SkippedSetting.CustomFont -> stringResource(
        when (role) {
            FontRole.BRAND -> R.string.settings_backup_skipped_font_headings
            FontRole.PLAIN -> R.string.settings_backup_skipped_font_body
        },
    )
    SkippedSetting.SpecialDates -> stringResource(R.string.settings_backup_skipped_special_dates)
    SkippedSetting.AutoBackupFolder -> stringResource(R.string.settings_backup_skipped_auto_backup)
    is SkippedSetting.Language -> stringResource(R.string.settings_backup_skipped_language, AppLanguage.displayName(tag))
}

private fun BackupProblem.messageRes(): Int = when (this) {
    BackupProblem.NOT_A_BACKUP -> R.string.settings_backup_import_failed_not_backup
    BackupProblem.NEWER_VERSION -> R.string.settings_backup_import_failed_newer
    BackupProblem.INVALID_DATA -> R.string.settings_backup_import_failed_invalid
    BackupProblem.TOO_LARGE -> R.string.settings_backup_import_failed_too_large
    BackupProblem.UNREADABLE -> R.string.settings_backup_import_failed_unreadable
    BackupProblem.NOT_APPLIED -> R.string.settings_backup_import_failed
}
