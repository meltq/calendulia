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
val LEGACY_BACKUP_FILE: String = latestFileName(COMBINED_BACKUP_BASE)

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
 * gets its stable copy, which retention never touches. A stable name is only
 * written when the file is absent or this series owns it — or a series of this
 * run that is moving off it (a rename hand-off).
 */
fun planBackupFiles(
    targets: List<BackupTarget>,
    ledger: BackupLedger,
    existing: Set<String>,
    at: LocalDateTime,
    keep: Int,
): List<BackupFilePlan> {
    val retain = keep.coerceAtLeast(1)
    val runKeys = targets.map { it.key }.toSet()
    val owners = ledger.filterKeys { it.startsWith(LATEST_PREFIX) }
        .flatMap { (key, names) -> names.map { it to key.removePrefix(LATEST_PREFIX) } }
        .toMap()
    val currentLatest = targets.map { latestFileName(it.baseName) }.toSet()
    return targets.map { target ->
        val fileName = backupFileName(target.baseName, at)
        val latest = latestFileName(target.baseName)
        val earlier = ledger[target.key].orEmpty()
            .filter { it in existing && it != fileName && it != latest }
            .distinct()
        val kept = earlier.takeLast(retain - 1)
        val owner = owners[latest]
        val writable = latest !in existing || (owner != null && owner in runKeys)
        BackupFilePlan(
            target = target,
            fileName = fileName,
            keep = kept,
            prune = earlier.dropLast(kept.size),
            latestName = latest.takeIf { writable },
            staleLatest = ledger[latestLedgerKey(target.key)].orEmpty()
                .filter { it in existing && it != latest && it !in currentLatest },
        )
    }
}

/**
 * Stable copies the current layout replaced, per ledger key: a calendar's own
 * copy once the combined file holds it, or the combined copy once each calendar
 * has its own. Left in place they would keep serving old data under a path
 * something may follow. A calendar taken out of the backup keeps its copy.
 */
fun replacedLatestCopies(
    targets: List<BackupTarget>,
    ledger: BackupLedger,
    existing: Set<String>,
): Map<String, List<String>> {
    val runKeys = targets.map { it.key }.toSet()
    val covered = targets.flatMap { it.calendarIds }.map { it.toString() }.toSet()
    val currentLatest = targets.map { latestFileName(it.baseName) }.toSet()
    val combinedRun = COMBINED_SERIES in runKeys
    return ledger.filterKeys { it.startsWith(LATEST_PREFIX) }
        .mapKeys { it.key.removePrefix(LATEST_PREFIX) }
        .filterKeys { key ->
            key !in runKeys && (if (key == COMBINED_SERIES) !combinedRun else combinedRun && key in covered)
        }
        .mapValues { (_, names) -> names.filter { it in existing && it !in currentLatest } }
        .filterValues { it.isNotEmpty() }
}

/**
 * The ledger with [key]'s files set to [files] (empty drops the entry). Entries
 * of deleted calendars go ([liveKeys]); their files stay, unrecorded. Others
 * stay, so a calendar taken out of the backup still has its files pruned if it
 * is put back.
 */
fun withLedgerEntry(
    previous: BackupLedger,
    key: String,
    files: List<String>,
    liveKeys: Set<String>,
): BackupLedger {
    val next = previous.filterKeys { entry ->
        val series = entry.removePrefix(LATEST_PREFIX)
        series in liveKeys || series == COMBINED_SERIES
    }.toMutableMap()
    next[key] = files.distinct()
    // A stable copy has one owner: claiming it takes it off any other series.
    if (key.startsWith(LATEST_PREFIX)) {
        next.keys.filter { it != key && it.startsWith(LATEST_PREFIX) }.forEach { other ->
            next[other] = next.getValue(other) - files.toSet()
        }
    }
    return next.filterValues { it.isNotEmpty() }
}

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
