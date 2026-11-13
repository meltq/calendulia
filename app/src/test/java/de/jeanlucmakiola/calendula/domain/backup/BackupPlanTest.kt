package de.jeanlucmakiola.calendula.domain.backup

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.domain.CalendarSource
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class BackupPlanTest {

    private fun cal(
        id: Long,
        name: String = "Calendar $id",
        local: Boolean = false,
        writable: Boolean = true,
        managed: Boolean = false,
        syncs: Boolean = true,
    ) = CalendarSource(
        id = id,
        displayName = name,
        accountName = "acc",
        accountType = if (local) "LOCAL" else "bitfire.at.davdroid",
        color = 0,
        isVisibleInSystem = true,
        canModifyContents = writable,
        isLocal = local,
        isManaged = managed,
        syncsEvents = syncs,
    )

    private val at = LocalDateTime.of(2026, 11, 13, 14, 30, 5)

    @Test
    fun `local calendars are selected by default, synced ones are opt-in`() {
        val selection = BackupSelection()
        assertThat(selection.selects(cal(1L, local = true))).isTrue()
        assertThat(selection.selects(cal(2L))).isFalse()
        assertThat(selection.selects(cal(3L, writable = false))).isFalse()
    }

    @Test
    fun `managed and unsynced calendars are never backed up`() {
        val selection = BackupSelection(included = setOf(1L, 2L))
        assertThat(selection.selects(cal(1L, local = true, managed = true))).isFalse()
        assertThat(selection.selects(cal(2L, syncs = false))).isFalse()
    }

    @Test
    fun `toggling back to the default leaves no trace`() {
        val synced = cal(2L)
        val local = cal(1L, local = true)
        val chosen = BackupSelection().with(synced, true).with(local, false)
        assertThat(chosen).isEqualTo(BackupSelection(included = setOf(2L), excluded = setOf(1L)))
        assertThat(chosen.with(synced, false).with(local, true)).isEqualTo(BackupSelection())
    }

    @Test
    fun `file names are sanitised calendar names`() {
        assertThat(sanitizeBackupName("Work/Home: \"plans\"")).isEqualTo("Work_Home_ _plans_")
        assertThat(sanitizeBackupName("  ..hidden  ")).isEqualTo("hidden")
        assertThat(sanitizeBackupName("   ")).isEqualTo("calendar")
        assertThat(sanitizeBackupName("a\tb\nc")).isEqualTo("a_b_c")
    }

    @Test
    fun `one target per calendar, clashing names told apart by id`() {
        val targets = backupTargets(
            listOf(cal(1L, "Personal", local = true), cal(2L, "personal"), cal(3L, "Work")),
            combined = false,
        )
        assertThat(targets.map { it.baseName }).containsExactly("Personal-1", "personal-2", "Work").inOrder()
        assertThat(targets.map { it.calendarName }).containsExactly("Personal", "personal", "Work").inOrder()
    }

    @Test
    fun `a calendar named like the combined file doesn't share its series`() {
        val target = backupTargets(listOf(cal(4L, COMBINED_BACKUP_BASE)), combined = false).single()
        assertThat(target.baseName).isEqualTo("$COMBINED_BACKUP_BASE-4")
    }

    @Test
    fun `combined mode is one target holding every calendar`() {
        val target = backupTargets(listOf(cal(1L), cal(2L)), combined = true).single()
        assertThat(target.calendarIds).containsExactly(1L, 2L)
        assertThat(target.baseName).isEqualTo(COMBINED_BACKUP_BASE)
        assertThat(target.calendarName).isNull()
        assertThat(backupTargets(emptyList(), combined = true)).isEmpty()
    }

    @Test
    fun `the new file carries the date and time of the run`() {
        assertThat(backupFileName("Work", at)).isEqualTo("Work_2026-11-13_14-30-05.ics")
    }

    @Test
    fun `pruning keeps the newest files of the series and nothing else`() {
        val target = BackupTarget(setOf(1L), "Work", "Work")
        val existing = listOf(
            "Work_2026-11-10_09-00-00.ics",
            "Work_2026-11-12_09-00-00.ics",
            "Work_2026-11-11_09-00-00.ics",
            "Work notes.ics",
            "Work_backup.ics",
            "Workshop_2026-11-01_09-00-00.ics",
            "Work-7_2026-11-01_09-00-00.ics",
        )
        val plan = planBackupFiles(listOf(target), existing, at, keep = 3).single()
        assertThat(plan.fileName).isEqualTo("Work_2026-11-13_14-30-05.ics")
        assertThat(plan.prune).containsExactly("Work_2026-11-10_09-00-00.ics")
    }

    @Test
    fun `keeping one replaces every earlier file`() {
        val target = BackupTarget(setOf(1L), "Work", "Work")
        val existing = listOf("Work_2026-11-11_09-00-00.ics", "Work_2026-11-12_09-00-00.ics")
        val plan = planBackupFiles(listOf(target), existing, at, keep = 1).single()
        assertThat(plan.prune).containsExactly("Work_2026-11-11_09-00-00.ics", "Work_2026-11-12_09-00-00.ics").inOrder()
    }

    @Test
    fun `a rerun in the same second never prunes the file it writes`() {
        val target = BackupTarget(setOf(1L), "Work", "Work")
        val plan = planBackupFiles(listOf(target), listOf("Work_2026-11-13_14-30-05.ics"), at, keep = 1).single()
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `the pre-411 backup file is the combined series' oldest member`() {
        val target = BackupTarget(setOf(1L, 2L), COMBINED_BACKUP_BASE, null)
        val existing = listOf(LEGACY_BACKUP_FILE, "calendula-backup_2026-11-12_09-00-00.ics")
        assertThat(planBackupFiles(listOf(target), existing, at, keep = 3).single().prune).isEmpty()
        assertThat(planBackupFiles(listOf(target), existing, at, keep = 2).single().prune)
            .containsExactly(LEGACY_BACKUP_FILE)
    }

    @Test
    fun `per-calendar series leave the legacy combined file alone`() {
        val target = BackupTarget(setOf(1L), "Work", "Work")
        val plan = planBackupFiles(listOf(target), listOf(LEGACY_BACKUP_FILE), at, keep = 1).single()
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `retention snaps to the offered choices`() {
        assertThat(normalizeBackupKeep(0)).isEqualTo(1)
        assertThat(normalizeBackupKeep(3)).isEqualTo(3)
        assertThat(normalizeBackupKeep(50)).isEqualTo(10)
    }
}
