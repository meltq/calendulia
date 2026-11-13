package de.jeanlucmakiola.calendula.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import de.jeanlucmakiola.calendula.data.appname.LauncherName
import de.jeanlucmakiola.calendula.data.calendar.ManagedCalendarRow
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.FontRole
import de.jeanlucmakiola.calendula.ui.theme.FONT_CUSTOM_TOKEN
import de.jeanlucmakiola.floret.locale.AppLanguage
import de.jeanlucmakiola.floret.prefs.Pref
import de.jeanlucmakiola.floret.prefs.get
import de.jeanlucmakiola.floret.prefs.rawValue
import de.jeanlucmakiola.floret.prefs.set
import de.jeanlucmakiola.floret.prefs.setRawValue

/** What this device can take of a backup, read before planning. */
data class ImportEnvironment(
    /** Null when the calendar list can't be read: the per-calendar settings are then left alone. */
    val calendars: List<CalendarSource>?,
    val managed: List<ManagedCalendarRow>,
    val contactsPermission: Boolean,
    val customFonts: Set<FontRole>,
    val hasBackupFolder: Boolean,
    val supportedLanguages: List<String>,
)

/** Something in the file this device could not take, for the import summary. */
sealed interface SkippedSetting {
    /** No calendar on this device matches the file's, or [ambiguous]ly more than one does. */
    data class Calendar(
        val displayName: String,
        val accountName: String,
        val ambiguous: Boolean = false,
    ) : SkippedSetting

    /** The calendars couldn't be read, so the per-calendar settings were left as they are. */
    data object CalendarsUnavailable : SkippedSetting

    /** A custom font: the font file itself is not part of the backup. */
    data class CustomFont(val role: FontRole) : SkippedSetting

    /** Contact special dates were on, but this device hasn't granted the contacts permission. */
    data object SpecialDates : SkippedSetting

    /** Automatic backup was on, but its folder has to be chosen again here. */
    data object AutoBackupFolder : SkippedSetting

    /** The file's app language isn't one this build ships. */
    data class Language(val tag: String) : SkippedSetting
}

data class SettingsImportSummary(
    val calendarsMatched: Int,
    val skipped: List<SkippedSetting>,
)

/**
 * A validated backup laid onto this device: the stored values to write in one
 * edit, the side effects the engine runs after it, and the summary.
 * [calendars] null leaves the per-calendar keys untouched.
 * [pendingSpecialDates] are mirror calendars that don't exist yet; the engine
 * matches them once the mirror's sync has created them.
 */
data class SettingsImportPlan(
    val prefs: Map<Pref<*>, Any?>,
    val calendars: Map<Long, CalendarSettings>?,
    val pendingSpecialDates: List<CalendarSettings>,
    val language: LanguageChoice?,
    val launcherName: LauncherName?,
    val skipped: List<SkippedSetting>,
)

/** Plans an import that replaces every backed-up setting; see docs/SETTINGS_BACKUP.md. */
fun planSettingsImport(
    backup: SettingsBackup,
    current: Preferences,
    environment: ImportEnvironment,
): SettingsImportPlan {
    val skipped = mutableListOf<SkippedSetting>()
    val prefs = backup.prefs.toMutableMap<Pref<*>, Any?>()

    fun keepCurrent(pref: Pref<*>) {
        prefs[pref] = current.rawValue(pref)
    }

    FontRole.entries.forEach { role ->
        val pref = role.fontPref()
        if (prefs[pref] == FONT_CUSTOM_TOKEN && role !in environment.customFonts) {
            keepCurrent(pref)
            skipped += SkippedSetting.CustomFont(role)
        }
    }
    if (prefs[SettingsKeys.specialDatesEnabled] == true && !environment.contactsPermission) {
        keepCurrent(SettingsKeys.specialDatesEnabled)
        skipped += SkippedSetting.SpecialDates
    }
    if (prefs[SettingsKeys.autoBackupEnabled] == true && !environment.hasBackupFolder) {
        keepCurrent(SettingsKeys.autoBackupEnabled)
        skipped += SkippedSetting.AutoBackupFolder
    }

    val language = backup.language?.let { choice ->
        val tag = choice.tag ?: return@let choice
        val resolved = AppLanguage.resolveTag(tag, environment.supportedLanguages)
        if (resolved in environment.supportedLanguages) {
            LanguageChoice(resolved)
        } else {
            skipped += SkippedSetting.Language(tag)
            null
        }
    }

    // The mirror's calendars are torn down when the imported settings leave it off.
    val mirroring = prefs[SettingsKeys.specialDatesEnabled] == true
    val available = environment.calendars
    val calendars = linkedMapOf<Long, CalendarSettings>()
    val pending = mutableListOf<CalendarSettings>()
    if (available == null) {
        if (backup.calendars.isNotEmpty()) skipped += SkippedSetting.CalendarsUnavailable
    } else {
        backup.calendars.forEach { entry ->
            val type = entry.calendar.specialDates
            if (type != null) {
                // The mirror only keeps calendars for the types the imported settings switch on.
                val typeOn = prefs[SettingsKeys.specialDateTypeEnabled.getValue(type)] != false
                if (!mirroring || !typeOn) return@forEach
                val id = environment.managed.firstOrNull { it.type == type }?.id
                if (id != null) calendars[id] = entry else pending += entry
                return@forEach
            }
            val candidates = available.filter { !it.isManaged && it.matches(entry.calendar) }
            when (candidates.size) {
                1 -> calendars[candidates.single().id] = entry
                else -> skipped += entry.calendar.skipped(ambiguous = candidates.size > 1)
            }
        }
    }

    return SettingsImportPlan(
        prefs = prefs,
        calendars = calendars.takeIf { available != null },
        pendingSpecialDates = pending,
        language = language,
        launcherName = backup.launcherName,
        skipped = skipped,
    )
}

/** Writes the plan's stored values, replacing every backed-up setting and all per-calendar ones. */
fun SettingsImportPlan.applyTo(target: MutablePreferences) {
    plainSection.apply(target, prefs)
    calendars?.let(target::writeCalendarSettings)
}

/**
 * Replaces the per-calendar keys with [calendars], by this device's ids. A
 * last-used calendar only counts from the first entry claiming it.
 */
fun MutablePreferences.writeCalendarSettings(calendars: Map<Long, CalendarSettings>) {
    this[CalendarPrefKeys.hiddenCalendarIds] = calendars.filterValues { it.hidden }.keys
    this[CalendarPrefKeys.lastUsedCalendarId] = calendars.entries.firstOrNull { it.value.lastUsed }?.key
    setMapOrClear(
        SettingsKeys.perCalendarEventDuration,
        calendars.mapNotNull { (id, entry) -> entry.eventDurationMinutes?.let { id to it.clampDuration() } }.toMap(),
    )
    setMapOrClear(
        SettingsKeys.perCalendarReminderOverride,
        calendars.mapNotNull { (id, entry) -> entry.reminders?.let { id to it } }.toMap(),
    )
    setMapOrClear(
        SettingsKeys.perCalendarAllDayReminderOverride,
        calendars.mapNotNull { (id, entry) -> entry.allDayReminders?.let { id to it } }.toMap(),
    )
}

/** Adds one late-matched calendar's settings to what [writeCalendarSettings] stored. */
fun MutablePreferences.addCalendarSettings(id: Long, entry: CalendarSettings) {
    if (entry.hidden) this[CalendarPrefKeys.hiddenCalendarIds] = this[CalendarPrefKeys.hiddenCalendarIds] + id
    if (entry.lastUsed && this[CalendarPrefKeys.lastUsedCalendarId] == null) {
        this[CalendarPrefKeys.lastUsedCalendarId] = id
    }
    entry.eventDurationMinutes?.let {
        this[SettingsKeys.perCalendarEventDuration] =
            this[SettingsKeys.perCalendarEventDuration] + (id to it.clampDuration())
    }
    entry.reminders?.let {
        this[SettingsKeys.perCalendarReminderOverride] = this[SettingsKeys.perCalendarReminderOverride] + (id to it)
    }
    entry.allDayReminders?.let {
        this[SettingsKeys.perCalendarAllDayReminderOverride] =
            this[SettingsKeys.perCalendarAllDayReminderOverride] + (id to it)
    }
}

fun CalendarIdentity.skipped(ambiguous: Boolean = false): SkippedSetting =
    SkippedSetting.Calendar(displayName, accountName, ambiguous)

fun SettingsImportPlan.summary(calendarsMatched: Int, more: List<SkippedSetting> = emptyList()) =
    SettingsImportSummary(
        calendarsMatched = calendarsMatched,
        skipped = skipped + more,
    )

private fun CalendarSource.matches(identity: CalendarIdentity): Boolean =
    accountType == identity.accountType &&
        accountName == identity.accountName &&
        displayName == identity.displayName

/** An empty map removes the key; its codec would otherwise store an empty string. */
private fun <V> MutablePreferences.setMapOrClear(pref: Pref<Map<Long, V>>, value: Map<Long, V>) {
    if (value.isEmpty()) setRawValue(pref, null) else this[pref] = value
}

/** The same range the per-calendar duration setter clamps to. */
private fun Int.clampDuration(): Int = coerceIn(SettingsPrefs.MIN_EVENT_DURATION, SettingsPrefs.MAX_EVENT_DURATION)

private fun FontRole.fontPref(): Pref<String> = when (this) {
    FontRole.BRAND -> SettingsKeys.brandFont
    FontRole.PLAIN -> SettingsKeys.plainFont
}
