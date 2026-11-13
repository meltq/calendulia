package de.jeanlucmakiola.calendula.domain.backup

import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.isNotSynced
import de.jeanlucmakiola.floret.android.safeFileName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Whether this calendar can be backed up at all. Any calendar the provider
 * holds can be *read* (#147), whatever its visibility or access level. Left out:
 * the managed special-dates mirrors, rebuilt from contacts so a restore would
 * only duplicate them, and calendars whose events aren't synced to the device.
 */
val CalendarSource.isBackupCandidate: Boolean
    get() = !isManaged && !isNotSynced

/**
 * Whether a backup includes this calendar until the user says otherwise: the
 * app's own writable local calendars, which have no other copy anywhere. Synced
 * calendars already live on their server, so they are opt-in.
 */
val CalendarSource.isBackedUpByDefault: Boolean
    get() = isLocal && canModifyContents && !isManaged

/**
 * Which calendars the automatic backup covers, stored as deviations from
 * [isBackedUpByDefault] so a calendar created later still follows the default:
 * [included] opts a calendar in, [excluded] opts one out.
 */
data class BackupSelection(
    val included: Set<Long> = emptySet(),
    val excluded: Set<Long> = emptySet(),
) {
    fun selects(calendar: CalendarSource): Boolean = when {
        !calendar.isBackupCandidate -> false
        calendar.id in included -> true
        calendar.id in excluded -> false
        else -> calendar.isBackedUpByDefault
    }

    /** The selection with [calendar] switched [on]; a choice matching the default leaves no trace. */
    fun with(calendar: CalendarSource, on: Boolean): BackupSelection {
        val id = calendar.id
        val default = calendar.isBackedUpByDefault
        return BackupSelection(
            included = if (on && !default) included + id else included - id,
            excluded = if (!on && default) excluded + id else excluded - id,
        )
    }
}

/** One backup file series: the calendars it holds and the name its files start with. */
data class BackupTarget(
    /** Stable identity of the series in the [BackupLedger]: the calendar id, or [COMBINED_SERIES]. */
    val key: String,
    val calendarIds: Set<Long>,
    val baseName: String,
    /** The calendar name written into the file, or null for a combined file. */
    val calendarName: String?,
)

/** What one run writes and which earlier files it removes afterwards. */
data class BackupFilePlan(
    val target: BackupTarget,
    val fileName: String,
    /** Earlier files of this series that stay, oldest first. */
    val keep: List<String>,
    /** Earlier files of this series beyond the retention count, oldest first. */
    val prune: List<String>,
    /**
     * The stable, undated copy overwritten every run (`<base>.ics`), for tools
     * that follow one path. Null when a file of that name is in the folder but
     * isn't one this backup wrote: the user's own file is never overwritten.
     */
    val latestName: String?,
    /** This series' earlier stable copies under a name it no longer uses (a rename), to delete. */
    val staleLatest: List<String> = emptyList(),
)

/**
 * The files the automatic backup has written, per series key, oldest first.
 * Pruning only ever deletes names recorded here, so a series is its files, not
 * whatever currently matches a calendar's name: renames, same-named calendars
 * and the user's own files in the folder can't pull in the wrong ones. A
 * series' stable copy is recorded apart, under [latestLedgerKey].
 */
typealias BackupLedger = Map<String, List<String>>

const val COMBINED_SERIES = "combined"

/** The combined file's series name; also the fixed name used before #411. */
const val COMBINED_BACKUP_BASE = "calendula-backup"

/**
 * The single file every automatic backup overwrote before #411: the combined
 * series' stable copy now, so an old setup keeps its path.
 */
const val LEGACY_BACKUP_FILE = "$COMBINED_BACKUP_BASE.ics"

private const val LATEST_PREFIX = "latest-"

/** Where the ledger records the stable copy of the series [key]. */
fun latestLedgerKey(key: String): String = "$LATEST_PREFIX$key"

/** The retention choices the settings offer, newest kept first. */
val BACKUP_KEEP_OPTIONS: List<Int> = listOf(1, 2, 3, 5, 7, 10)

const val DEFAULT_BACKUP_KEEP = 3

/** Room for the id suffix and the stamp within a file system's 255-byte name limit. */
private const val MAX_BASE_BYTES = 120
private const val ICS_EXTENSION = ".ics"
private const val STAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(STAMP_PATTERN)

/**
 * The file series the automatic backup writes: one combined file, or one per
 * calendar named after it. Calendars whose names clash case-insensitively (a
 * local and a synced "Personal") take their id as a suffix until every name in
 * the run is distinct, so no two series write the same file.
 */
fun backupTargets(calendars: List<CalendarSource>, combined: Boolean): List<BackupTarget> {
    if (calendars.isEmpty()) return emptyList()
    if (combined) {
        return listOf(
            BackupTarget(COMBINED_SERIES, calendars.map { it.id }.toSet(), COMBINED_BACKUP_BASE, calendarName = null),
        )
    }
    val bases = calendars.associateTo(LinkedHashMap()) {
        it.id to safeFileName(it.displayName, MAX_BASE_BYTES, fallback = "calendar")
    }
    while (true) {
        val counts = bases.values.groupingBy { it.lowercase() }.eachCount()
        val clashing = bases.filterValues {
            counts.getValue(it.lowercase()) > 1 || it.equals(COMBINED_BACKUP_BASE, ignoreCase = true)
        }
        if (clashing.isEmpty()) break
        clashing.forEach { (id, base) -> bases[id] = "$base-$id" }
    }
    return calendars.map { calendar ->
        BackupTarget(
            key = calendar.id.toString(),
            calendarIds = setOf(calendar.id),
            baseName = bases.getValue(calendar.id),
            calendarName = calendar.displayName,
        )
    }
}

/** `Work_2026-11-13_14-30-05.ics`: the series name and the local time of the run. */
fun backupFileName(baseName: String, at: LocalDateTime): String =
    "${baseName}_${at.format(STAMP_FORMAT)}$ICS_EXTENSION"

/**
 * Plan one run: a new dated file per target, and which of the series' recorded
 * files ([ledger]) fall out of the newest [keep], the new one included. Recorded
 * files no longer in the folder ([existing]) are forgotten. Each target also
 * gets its stable copy, which retention never touches; a renamed series drops
 * its old one. The pre-#411 [LEGACY_BACKUP_FILE] is the combined series'
 * stable copy, so it is overwritten rather than left to go stale.
 */
fun planBackupFiles(
    targets: List<BackupTarget>,
    ledger: BackupLedger,
    existing: Set<String>,
    at: LocalDateTime,
    keep: Int,
): List<BackupFilePlan> {
    val retain = keep.coerceAtLeast(1)
    val ownLatest = ledger.filterKeys { it.startsWith(LATEST_PREFIX) }.values.flatten().toSet() + LEGACY_BACKUP_FILE
    val currentLatest = targets.map { latestFileName(it.baseName) }.toSet()
    return targets.map { target ->
        val fileName = backupFileName(target.baseName, at)
        val earlier = ledger[target.key].orEmpty().filter { it in existing && it != fileName }.distinct()
        val kept = earlier.takeLast(retain - 1)
        val latest = latestFileName(target.baseName)
        BackupFilePlan(
            target = target,
            fileName = fileName,
            keep = kept,
            prune = earlier.dropLast(kept.size),
            latestName = latest.takeIf { it !in existing || it in ownLatest },
            staleLatest = ledger[latestLedgerKey(target.key)].orEmpty()
                .filter { it in existing && it != latest && it !in currentLatest },
        )
    }
}

/**
 * The ledger once [plan]'s new file landed as [writtenName]: the series records
 * what it kept, what it failed to delete ([undeleted]) and the new file. Other
 * series keep their entry while their calendar still exists ([liveKeys]), so a
 * calendar taken out of the backup still has its files pruned if it's put back.
 */
fun updatedBackupLedger(
    previous: BackupLedger,
    plan: BackupFilePlan,
    writtenName: String,
    undeleted: Collection<String>,
    liveKeys: Set<String>,
): BackupLedger {
    val next = previous.livePart(liveKeys)
    next[plan.target.key] = (plan.prune.filter { it in undeleted } + plan.keep + writtenName).distinct()
    return next.filterValues { it.isNotEmpty() }
}

/**
 * The ledger once [plan]'s stable copy landed as [writtenName]: recorded with
 * any stale copy that wouldn't delete ([undeleted]), so a later run retries it.
 */
fun updatedLatestLedger(
    previous: BackupLedger,
    plan: BackupFilePlan,
    writtenName: String,
    undeleted: Collection<String>,
    liveKeys: Set<String>,
): BackupLedger {
    val next = previous.livePart(liveKeys)
    next[latestLedgerKey(plan.target.key)] = (plan.staleLatest.filter { it in undeleted } + writtenName).distinct()
    return next.filterValues { it.isNotEmpty() }
}

/** Entries of calendars that still exist; a deleted calendar's files stay, unrecorded. */
private fun BackupLedger.livePart(liveKeys: Set<String>): MutableMap<String, List<String>> =
    filterKeys { key ->
        val series = key.removePrefix(LATEST_PREFIX)
        series in liveKeys || series == COMBINED_SERIES
    }.toMutableMap()

/** `Work.ics`: the series' stable copy. */
fun latestFileName(baseName: String): String = "$baseName$ICS_EXTENSION"

private const val LEDGER_ENTRY_SEP = "|"
private const val LEDGER_KEY_SEP = "/"

/** Stores the ledger; both separators are characters no name this app writes can hold. */
fun persistBackupLedger(ledger: BackupLedger): String? =
    ledger.filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.entries?.sortedBy { it.key }
        ?.joinToString(LEDGER_ENTRY_SEP) { (key, names) -> (listOf(key) + names).joinToString(LEDGER_KEY_SEP) }

fun parseBackupLedger(stored: String?): BackupLedger =
    stored?.split(LEDGER_ENTRY_SEP).orEmpty().mapNotNull { entry ->
        val parts = entry.split(LEDGER_KEY_SEP)
        val names = parts.drop(1).filter { it.isNotEmpty() }
        parts.first().takeIf { it.isNotEmpty() && names.isNotEmpty() }?.let { it to names }
    }.toMap()

/** Closest offered retention count to [stored], so a stray value still shows a row. */
fun normalizeBackupKeep(stored: Int): Int =
    BACKUP_KEEP_OPTIONS.minBy { kotlin.math.abs(it - stored) }
