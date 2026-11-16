package de.jeanlucmakiola.calendula.ui.imports

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.EventForm
import de.jeanlucmakiola.calendula.domain.ics.IcsParseWarning
import de.jeanlucmakiola.calendula.ui.common.CalendarPickerGroups
import de.jeanlucmakiola.calendula.ui.common.ImportSummaryStats
import de.jeanlucmakiola.calendula.ui.common.ResultBadge
import de.jeanlucmakiola.calendula.ui.common.ResultMessage
import de.jeanlucmakiola.calendula.ui.common.importSummaryNotes
import de.jeanlucmakiola.floret.identity.predictiveBack

/**
 * Handles an opened/received `.ics` file. A single event is handed straight to
 * the prefilled create form via [onOpenSingle]; several events show a target-
 * calendar picker and import in bulk (dedup by UID), then a result summary.
 * Empty/failed files show a short message and close. [forceMany] keeps a
 * single-event file on the bulk path — used by the in-app restore, whose intent
 * is "restore a backup" rather than "add this one event".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    uri: Uri,
    session: Int,
    onClose: () -> Unit,
    onOpenSingle: (EventForm) -> Unit,
    forceMany: Boolean = false,
    onManageCalendars: (() -> Unit)? = null,
    visible: Boolean = true,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    // hiltViewModel() resolves to the Activity's store and is retained across
    // imports, so the reload is driven by [session] rather than by a fresh VM:
    // keying per uri looked right but handed a *re-import of the same file* the
    // previous run's finished state, importing nothing (#304). A rotation keeps
    // the session and so keeps the result.
    LaunchedEffect(session) { viewModel.load(uri, forceMany, session) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A single event isn't shown here — it opens the create form for review.
    // Read live: the state composed with may be the previous import's result,
    // which the load above has already replaced.
    LaunchedEffect(state) {
        (viewModel.state.value as? ImportUiState.Single)?.let { onOpenSingle(it.form); onClose() }
    }

    // Hoisted target calendar so the always-visible top-bar Import action can
    // read it without the user scrolling to a bottom button (see
    // [defaultImportTarget] for the choice). Re-defaults when the "many" list
    // first arrives (keyed on it), then holds the pick.
    val many = state as? ImportUiState.Many
    val defaultTarget = many?.let { defaultImportTarget(it.calendars, it.fileCalendarName) }
    var selected by rememberSaveable(defaultTarget) { mutableStateOf(defaultTarget) }

    Scaffold(
        modifier = Modifier
            .predictiveBack(onBack = onClose, visible = visible)
            .fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (many != null) {
                            pluralStringResource(
                                R.plurals.import_title_count,
                                many.events.size,
                                many.events.size,
                            )
                        } else {
                            stringResource(R.string.import_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.event_edit_close))
                    }
                },
                actions = {
                    // Only meaningful in the multi-event picker with a writable
                    // target; every other state has nothing to confirm here.
                    if (many != null && many.calendars.isNotEmpty()) {
                        Button(
                            onClick = { selected?.let(viewModel::import) },
                            enabled = selected != null,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Text(stringResource(R.string.import_button))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ImportUiState.Loading,
                ImportUiState.Importing,
                is ImportUiState.Single,
                -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                ImportUiState.Empty -> CenteredMessage(stringResource(R.string.import_empty), onClose)
                ImportUiState.Failed -> CenteredMessage(stringResource(R.string.import_failed), onClose)
                is ImportUiState.Many -> ManyContent(
                    state = s,
                    selected = selected,
                    onSelect = { selected = it },
                    onManageCalendars = onManageCalendars,
                )
                is ImportUiState.Done -> DoneContent(s, onClose)
            }
        }
    }
}

@Composable
private fun ManyContent(
    state: ImportUiState.Many,
    selected: Long?,
    onSelect: (Long) -> Unit,
    onManageCalendars: (() -> Unit)? = null,
) {
    // No calendar to import into; carries the same way out the picker's footer
    // offers below (#76).
    if (state.calendars.isEmpty()) {
        CenteredMessage(
            message = stringResource(R.string.import_no_calendar),
            onClose = null,
            actionLabel = stringResource(R.string.settings_manage_calendars)
                .takeIf { onManageCalendars != null },
            onAction = onManageCalendars,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        CalendarPickerGroups(
            calendars = state.calendars,
            selectedId = selected,
            onSelect = onSelect,
            onManageCalendars = onManageCalendars,
        )
        if (state.warnings.isNotEmpty()) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.warnings.forEach { WarningText(it) }
            }
        }
    }
}

@Composable
private fun DoneContent(state: ImportUiState.Done, onClose: () -> Unit) {
    // Part of the file never reached the calendar, so the screen doesn't get to
    // read as a plain success.
    val stopped = state.summary.notAttempted > 0

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        ResultBadge(error = stopped)
        ResultMessage(
            title = stringResource(
                if (stopped) R.string.import_done_stopped_title else R.string.import_done_title,
            ),
            // The counts cover the part of the file that was tried, so say how much never was.
            notes = importSummaryNotes(
                state.summary,
                stoppedNote = if (stopped) {
                    pluralStringResource(R.plurals.import_done_stopped, state.summary.notAttempted, state.summary.notAttempted)
                } else {
                    null
                },
            ),
        )
        Spacer(Modifier.height(24.dp))
        ImportSummaryStats(state.summary)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_close))
        }
    }
}

@Composable
private fun WarningText(warning: IcsParseWarning) {
    val text = when (warning) {
        IcsParseWarning.ModifiedOccurrenceSkipped -> stringResource(R.string.import_warning_recurrence)
        IcsParseWarning.EventWithoutStartSkipped -> stringResource(R.string.import_warning_no_start)
        IcsParseWarning.AttendeesIgnored -> stringResource(R.string.import_warning_attendees)
        IcsParseWarning.UnknownTimezone -> stringResource(R.string.import_warning_timezone)
        IcsParseWarning.TasksImportedAsEvents -> stringResource(R.string.import_warning_tasks)
        IcsParseWarning.RecurrenceRuleRepaired ->
            stringResource(R.string.import_warning_recurrence_repaired)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CenteredMessage(
    message: String,
    onClose: (() -> Unit)?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (onClose != null) {
                Button(onClick = onClose) { Text(stringResource(R.string.import_close)) }
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
