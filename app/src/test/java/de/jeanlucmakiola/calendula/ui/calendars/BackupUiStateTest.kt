package de.jeanlucmakiola.calendula.ui.calendars

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.FailureReason
import org.junit.jupiter.api.Test

/** What the Backup & restore screen offers for a given calendar list (#304). */
class BackupUiStateTest {

    private fun cal(
        id: Long,
        local: Boolean = false,
        writable: Boolean = true,
        visible: Boolean = true,
        managed: Boolean = false,
        syncs: Boolean = true,
    ) = CalendarSource(
        id = id,
        displayName = "Calendar $id",
        accountName = "acc@example.com",
        accountType = if (local) "LOCAL" else "com.google",
        color = 0,
        isVisibleInSystem = visible,
        canModifyContents = writable,
        isLocal = local,
        isManaged = managed,
        syncsEvents = syncs,
    )

    private fun failure(state: BackupUiState) = (state as BackupUiState.Failure).reason

    private fun ready(state: BackupUiState) = state as BackupUiState.Ready

    @Test
    fun `no calendars at all reports the empty device`() {
        // This is #304's device. The screenshots on the issue show Calendula's
        // own calendar list with no local calendar and an empty "synced
        // calendars" section — the app saw nothing at all, so both halves of
        // Backup & restore had nothing to draw and the screen came up blank.
        // The reporter's "it's synced, I can see it in settings" meant Android's
        // settings, not this app's.
        assertThat(failure(backupUiState(emptyList())))
            .isEqualTo(FailureReason.NoCalendarsConfigured)
    }

    @Test
    fun `a synced writable calendar can receive a restore and be backed up`() {
        val state = ready(backupUiState(listOf(cal(1L))))
        assertThat(state.canImport).isTrue()
        assertThat(state.exportable.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `an account that has stopped syncing is no import target`() {
        // A calendar the provider no longer keeps events for: nothing to export
        // and nowhere to import to, which used to render a blank screen. Not
        // #304's device — that one had no calendars whatsoever — but the same
        // dead end, and reachable on its own.
        assertThat(failure(backupUiState(listOf(cal(1L, syncs = false)))))
            .isEqualTo(FailureReason.NoImportTarget)
    }

    @Test
    fun `a read-only subscription is no import target but can be backed up`() {
        // #147: export only reads, so a subscription is fair game.
        val state = ready(backupUiState(listOf(cal(1L, writable = false))))
        assertThat(state.canImport).isFalse()
        assertThat(state.exportable).hasSize(1)
    }

    @Test
    fun `a switched-off synced calendar still backs up`() {
        val state = ready(backupUiState(listOf(cal(1L, visible = false))))
        assertThat(state.canImport).isFalse()
        assertThat(state.exportable).hasSize(1)
    }

    @Test
    fun `a hidden local calendar still exports`() {
        // Export reads the provider directly, so system visibility is irrelevant
        // to it — calling this a failure would hide a working action.
        val state = ready(backupUiState(listOf(cal(1L, local = true, visible = false))))
        assertThat(state.exportable).hasSize(1)
        assertThat(state.canImport).isFalse()
    }

    @Test
    fun `a managed special-dates mirror is neither exportable nor a target`() {
        assertThat(failure(backupUiState(listOf(cal(1L, local = true, managed = true)))))
            .isEqualTo(FailureReason.NoImportTarget)
    }

    @Test
    fun `a local calendar alongside a synced one offers both halves`() {
        val state = ready(backupUiState(listOf(cal(1L, local = true), cal(2L))))
        assertThat(state.exportable.map { it.id }).containsExactly(1L, 2L)
        assertThat(state.canImport).isTrue()
    }
}
