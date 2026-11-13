package de.jeanlucmakiola.calendula.ui.calendars

import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.FailureReason
import de.jeanlucmakiola.calendula.domain.backup.isBackupCandidate
import de.jeanlucmakiola.calendula.domain.calendarListFailure
import de.jeanlucmakiola.calendula.domain.isEventTarget

/**
 * State of the Backup & restore screen (#69). Three states, because the calendar
 * list it is derived from has all three: it arrives empty, it can throw, and
 * "loaded but nothing to offer" is a real outcome the screen used to render as a
 * blank page (#304).
 */
sealed interface BackupUiState {
    data object Loading : BackupUiState
    data class Failure(val reason: FailureReason) : BackupUiState

    /**
     * At least one half of the screen works. [exportable] may be empty (restore
     * only) and [canImport] may be false (export only), but never both — that is
     * a [Failure].
     */
    data class Ready(
        val exportable: List<CalendarSource>,
        val canImport: Boolean,
        /** Some calendar can be copied into a different one (#325). */
        val canCopy: Boolean = false,
    ) : BackupUiState
}

/**
 * What the screen can offer for this calendar list.
 *
 * Export can read any calendar (#147) — the local ones are only its default;
 * managed special-dates mirrors are rebuilt from contacts, so they are excluded
 * ([isBackupCandidate]). Restore can target anything the import picker offers
 * ([isEventTarget]).
 *
 * A failure is raised only when *neither* is possible — deliberately not
 * [calendarListFailure] on its own, which would call an all-hidden device a
 * failure while its calendars are still perfectly exportable. Since export
 * reads hidden and read-only calendars too, both halves are dead only when there
 * is no calendar at all, or every one is managed or not synced to the device
 * ([FailureReason.NoImportTarget] unless the list itself says why).
 */
fun backupUiState(calendars: List<CalendarSource>): BackupUiState {
    val exportable = calendars.filter { it.isBackupCandidate }
    val canImport = calendars.any { it.isEventTarget }
    if (exportable.isEmpty() && !canImport) {
        return BackupUiState.Failure(
            calendarListFailure(calendars) ?: FailureReason.NoImportTarget,
        )
    }
    return BackupUiState.Ready(
        exportable = exportable,
        canImport = canImport,
        canCopy = exportable.any { copyTargets(calendars, it.id).isNotEmpty() },
    )
}

/** Calendars a copy can read from: any a backup can (#325), synced and read-only ones included. */
fun copySources(calendars: List<CalendarSource>): List<CalendarSource> =
    calendars.filter { it.isBackupCandidate }

/** Calendars a copy from [sourceId] can write into: the import targets, minus the source itself. */
fun copyTargets(calendars: List<CalendarSource>, sourceId: Long?): List<CalendarSource> =
    calendars.filter { it.isEventTarget && it.id != sourceId }
