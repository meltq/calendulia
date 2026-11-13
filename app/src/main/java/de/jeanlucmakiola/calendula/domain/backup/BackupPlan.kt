package de.jeanlucmakiola.calendula.domain.backup

import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.isNotSynced
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

    /** Ids of the selected calendars among [calendars]. */
    fun selectedIds(calendars: List<CalendarSource>): Set<Long> =
        calendars.filter(::selects).map { it.id }.toSet()
}

/** One backup file series: the calendars it holds and the name its files start with. */
data class BackupTarget(
    val calendarIds: Set<Long>,
    val baseName: String,
    /** The calendar name written into the file, or null for a combined file. */
    val calendarName: String?,
)

/** What one run writes and which earlier files it removes afterwards. */
data class BackupFilePlan(
    val target: BackupTarget,
    val fileName: String,
    /** Earlier files of this series beyond the retention count, oldest first. */
    val prune: List<String>,
)

/** The combined file's series name; also the fixed name used before #411. */
const val COMBINED_BACKUP_BASE = "calendula-backup"

/** The single file every automatic backup overwrote before #411. */
const val LEGACY_BACKUP_FILE = "$COMBINED_BACKUP_BASE.ics"

/** The retention choices the settings offer, newest kept first. */
val BACKUP_KEEP_OPTIONS: List<Int> = listOf(1, 2, 3, 5, 7, 10)

const val DEFAULT_BACKUP_KEEP = 3

private const val ICS_EXTENSION = ".ics"
private const val MAX_BASE_LENGTH = 60
private const val STAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(STAMP_PATTERN)
private const val STAMP_REGEX = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}"
private val FORBIDDEN_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val WHITESPACE = Regex("\\s+")

/**
 * [name] made safe as a file name on every storage provider: path separators,
 * reserved and control characters become `_`, whitespace collapses, and leading
 * dots go so the file can't turn hidden. Falls back to [fallback] when nothing
 * is left.
 */
fun sanitizeBackupName(name: String, fallback: String = "calendar"): String {
    val cleaned = name
        .replace(FORBIDDEN_CHARS, "_")
        .replace(WHITESPACE, " ")
        .trim()
        .trimStart('.')
        .take(MAX_BASE_LENGTH)
        .trim()
    return cleaned.ifEmpty { fallback }
}

/**
 * The file series the automatic backup writes: one combined file, or one per
 * calendar named after it. Two calendars that sanitise to the same name (a local
 * and a synced "Personal") keep apart by their id, so neither prunes the other's
 * files.
 */
fun backupTargets(calendars: List<CalendarSource>, combined: Boolean): List<BackupTarget> {
    if (calendars.isEmpty()) return emptyList()
    if (combined) {
        return listOf(BackupTarget(calendars.map { it.id }.toSet(), COMBINED_BACKUP_BASE, calendarName = null))
    }
    val bases = calendars.associate { it.id to sanitizeBackupName(it.displayName) }
    val taken = bases.values.groupingBy { it.lowercase() }.eachCount()
    return calendars.map { calendar ->
        val base = bases.getValue(calendar.id)
        val clash = taken.getValue(base.lowercase()) > 1 || base.equals(COMBINED_BACKUP_BASE, ignoreCase = true)
        BackupTarget(
            calendarIds = setOf(calendar.id),
            baseName = if (clash) "$base-${calendar.id}" else base,
            calendarName = calendar.displayName,
        )
    }
}

/** `Work_2026-11-13_14-30-05.ics`: the series name and the local time of the run. */
fun backupFileName(baseName: String, at: LocalDateTime): String =
    "${baseName}_${at.format(STAMP_FORMAT)}$ICS_EXTENSION"

/**
 * Plan one run: a new file per target, and which of that target's earlier files
 * fall out of the newest [keep] (the new one included). Only names this app
 * writes for the target count — `<base>_<stamp>.ics`, plus the pre-#411
 * [LEGACY_BACKUP_FILE] for the combined series, which ranks oldest — so a user's
 * own files in the folder are never touched.
 */
fun planBackupFiles(
    targets: List<BackupTarget>,
    existing: Collection<String>,
    at: LocalDateTime,
    keep: Int,
): List<BackupFilePlan> {
    val retain = keep.coerceAtLeast(1)
    return targets.map { target ->
        val fileName = backupFileName(target.baseName, at)
        val series = Regex("^${Regex.escape(target.baseName)}_($STAMP_REGEX)${Regex.escape(ICS_EXTENSION)}$")
        val earlier = existing
            .filter { it != fileName }
            .mapNotNull { name ->
                val stamp = series.find(name)?.groupValues?.get(1)
                    ?: if (target.baseName == COMBINED_BACKUP_BASE && name == LEGACY_BACKUP_FILE) "" else null
                stamp?.let { name to it }
            }
            .sortedByDescending { it.second }
            .map { it.first }
        BackupFilePlan(
            target = target,
            fileName = fileName,
            prune = earlier.drop(retain - 1).reversed(),
        )
    }
}

/** Closest offered retention count to [stored], so a stray value still shows a row. */
fun normalizeBackupKeep(stored: Int): Int =
    BACKUP_KEEP_OPTIONS.minBy { kotlin.math.abs(it - stored) }
