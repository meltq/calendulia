package de.jeanlucmakiola.calendula.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.ics.IcsImportSummary
import de.jeanlucmakiola.floret.components.ResultBadge as FloretResultBadge

/** floret's [FloretResultBadge] as a check on success, or an exclamation mark in the error colours. */
@Composable
fun ResultBadge(error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (error) {
        FloretResultBadge(Icons.Default.PriorityHigh, scheme.errorContainer, scheme.onErrorContainer)
    } else {
        FloretResultBadge(Icons.Default.Check, scheme.primaryContainer, scheme.onPrimaryContainer)
    }
}

/** A result screen's headline under its badge, then its notes; an error note takes the error colour. */
@Composable
fun ResultMessage(title: String, notes: List<ResultNote> = emptyList()) {
    Spacer(Modifier.height(24.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    notes.forEach { note ->
        Spacer(Modifier.height(8.dp))
        Text(
            note.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (note.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

data class ResultNote(val text: String, val error: Boolean = false)

/** The notes an import summary carries: failures, skipped duplicates, then [stoppedNote] when part was never tried. */
@Composable
fun importSummaryNotes(summary: IcsImportSummary, stoppedNote: String?): List<ResultNote> = buildList {
    if (summary.failed > 0) add(ResultNote(stringResource(R.string.import_done_failed_note)))
    if (summary.skippedDuplicate > 0) add(ResultNote(stringResource(R.string.import_done_dedup_note)))
    if (stoppedNote != null) add(ResultNote(stoppedNote, error = true))
}

/** Added / skipped / failed as big-number tiles, the latter two only when non-zero. */
@Composable
fun ImportSummaryStats(summary: IcsImportSummary, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultStatCard(
            count = summary.imported,
            label = stringResource(R.string.import_done_added_label),
            contentDescription = pluralStringResource(R.plurals.import_done_imported, summary.imported, summary.imported),
            container = MaterialTheme.colorScheme.secondaryContainer,
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (summary.skippedDuplicate > 0) {
            ResultStatCard(
                count = summary.skippedDuplicate,
                label = stringResource(R.string.import_done_skipped_label),
                contentDescription = pluralStringResource(
                    R.plurals.import_done_skipped,
                    summary.skippedDuplicate,
                    summary.skippedDuplicate,
                ),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.failed > 0) {
            ResultStatCard(
                count = summary.failed,
                label = stringResource(R.string.import_done_failed_label),
                contentDescription = pluralStringResource(R.plurals.import_done_failed, summary.failed, summary.failed),
                container = MaterialTheme.colorScheme.errorContainer,
                onContainer = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** A big-number tonal tile summarising one outcome. */
@Composable
private fun RowScope.ResultStatCard(
    count: Int,
    label: String,
    contentDescription: String,
    container: Color,
    onContainer: Color,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(24.dp),
        color = container,
    ) {
        Column(
            Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(count.toString(), style = MaterialTheme.typography.displaySmall, color = onContainer)
            Text(label, style = MaterialTheme.typography.labelLarge, color = onContainer.copy(alpha = 0.85f))
        }
    }
}
