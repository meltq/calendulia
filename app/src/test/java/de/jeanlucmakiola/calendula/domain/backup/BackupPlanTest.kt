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
        assertThat(target.key).isEqualTo(COMBINED_SERIES)
        assertThat(target.calendarIds).containsExactly(1L, 2L)
        assertThat(target.baseName).isEqualTo(COMBINED_BACKUP_BASE)
        assertThat(target.calendarName).isNull()
        assertThat(backupTargets(emptyList(), combined = true)).isEmpty()
    }

    @Test
    fun `the new file carries the date and time of the run`() {
        assertThat(backupFileName("Work", at)).isEqualTo("Work_2026-11-13_14-30-05.ics")
    }

    private val work = BackupTarget("1", setOf(1L), "Work", "Work")

    @Test
    fun `pruning keeps the newest recorded files and nothing else`() {
        val ledger = mapOf("1" to listOf("Work_a.ics", "Work_b.ics", "Work_c.ics"))
        val existing = setOf("Work_a.ics", "Work_b.ics", "Work_c.ics", "Work_2026-11-01_09-00-00.ics", "Work notes.ics")
        val plan = planBackupFiles(listOf(work), ledger, existing, at, keep = 3).single()
        assertThat(plan.fileName).isEqualTo("Work_2026-11-13_14-30-05.ics")
        assertThat(plan.keep).containsExactly("Work_b.ics", "Work_c.ics").inOrder()
        assertThat(plan.prune).containsExactly("Work_a.ics")
    }

    @Test
    fun `keeping one replaces every earlier file`() {
        val ledger = mapOf("1" to listOf("Work_a.ics", "Work_b.ics"))
        val plan = planBackupFiles(listOf(work), ledger, setOf("Work_a.ics", "Work_b.ics"), at, keep = 1).single()
        assertThat(plan.keep).isEmpty()
        assertThat(plan.prune).containsExactly("Work_a.ics", "Work_b.ics").inOrder()
    }

    @Test
    fun `files gone from the folder are forgotten, not counted`() {
        val ledger = mapOf("1" to listOf("Work_a.ics", "Work_b.ics"))
        val plan = planBackupFiles(listOf(work), ledger, setOf("Work_b.ics"), at, keep = 2).single()
        assertThat(plan.keep).containsExactly("Work_b.ics")
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `a same-named calendar never prunes another calendar's files`() {
        // Local "Work" (id 1) was backed up; the user now backs up a synced "Work" (id 9) instead.
        val ledger = mapOf("1" to listOf("Work_a.ics"))
        val synced = backupTargets(listOf(cal(9L, "Work")), combined = false).single()
        val plan = planBackupFiles(listOf(synced), ledger, setOf("Work_a.ics"), at, keep = 1).single()
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `a rerun in the same second never prunes the file it writes`() {
        val name = "Work_2026-11-13_14-30-05.ics"
        val plan = planBackupFiles(listOf(work), mapOf("1" to listOf(name)), setOf(name), at, keep = 1).single()
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `the pre-411 backup file is the combined series' oldest member`() {
        val target = BackupTarget(COMBINED_SERIES, setOf(1L, 2L), COMBINED_BACKUP_BASE, null)
        val newer = "calendula-backup_2026-11-12_09-00-00.ics"
        val ledger = mapOf(COMBINED_SERIES to listOf(newer))
        val existing = setOf(LEGACY_BACKUP_FILE, newer)
        assertThat(planBackupFiles(listOf(target), ledger, existing, at, keep = 3).single().prune).isEmpty()
        assertThat(planBackupFiles(listOf(target), ledger, existing, at, keep = 2).single().prune)
            .containsExactly(LEGACY_BACKUP_FILE)
    }

    @Test
    fun `per-calendar series leave the legacy combined file alone`() {
        val plan = planBackupFiles(listOf(work), emptyMap(), setOf(LEGACY_BACKUP_FILE), at, keep = 1).single()
        assertThat(plan.prune).isEmpty()
    }

    @Test
    fun `the ledger records what the run kept, failed to delete and wrote`() {
        val plan = planBackupFiles(
            listOf(work),
            mapOf("1" to listOf("Work_a.ics", "Work_b.ics")),
            setOf("Work_a.ics", "Work_b.ics"),
            at,
            keep = 2,
        ).single()
        val next = updatedBackupLedger(
            previous = mapOf("1" to listOf("Work_a.ics", "Work_b.ics"), "2" to listOf("Home_a.ics"), "7" to listOf("Gone.ics")),
            written = listOf(plan),
            writtenNames = mapOf("1" to "Work_new (1).ics"),
            undeleted = setOf("Work_a.ics"),
            liveKeys = setOf("1", "2"),
        )
        assertThat(next).containsExactly(
            "1", listOf("Work_a.ics", "Work_b.ics", "Work_new (1).ics"),
            "2", listOf("Home_a.ics"),
        )
    }

    @Test
    fun `a series whose write failed keeps its old entry`() {
        val plan = planBackupFiles(listOf(work), mapOf("1" to listOf("Work_a.ics")), setOf("Work_a.ics"), at, keep = 1)
        val next = updatedBackupLedger(
            previous = mapOf("1" to listOf("Work_a.ics")),
            written = plan,
            writtenNames = emptyMap(),
            undeleted = emptySet(),
            liveKeys = setOf("1"),
        )
        assertThat(next).containsExactly("1", listOf("Work_a.ics"))
    }

    @Test
    fun `the ledger round-trips through storage`() {
        val ledger = mapOf("3" to listOf("Work_b.ics", "Work_a (1).ics"), COMBINED_SERIES to listOf("calendula-backup_x.ics"))
        assertThat(parseBackupLedger(persistBackupLedger(ledger))).isEqualTo(ledger)
        assertThat(persistBackupLedger(emptyMap())).isNull()
        assertThat(parseBackupLedger("garbage|/x|4/")).isEmpty()
    }

    @Test
    fun `retention snaps to the offered choices`() {
        assertThat(normalizeBackupKeep(0)).isEqualTo(1)
        assertThat(normalizeBackupKeep(3)).isEqualTo(3)
        assertThat(normalizeBackupKeep(50)).isEqualTo(10)
    }

    @Test
    fun `a suffixed name that meets a real one is told apart again`() {
        val targets = backupTargets(
            listOf(cal(5L, "Work"), cal(6L, "Work"), cal(9L, "Work-5")),
            combined = false,
        )
        assertThat(targets.map { it.baseName }).containsExactly("Work-5-5", "Work-6", "Work-5-9").inOrder()
    }

    @Test
    fun `an emoji at the length limit is not split`() {
        val name = "a".repeat(59) + "\uD83D\uDCC5" + "tail"
        assertThat(sanitizeBackupName(name)).isEqualTo("a".repeat(59) + "\uD83D\uDCC5")
    }
}
