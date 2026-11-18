package de.jeanlucmakiola.calendula.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.calendula.BuildConfig
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.appname.LauncherNameManager
import de.jeanlucmakiola.calendula.data.calendar.CalendarDataSource
import de.jeanlucmakiola.calendula.data.contacts.ContactSpecialDatesDataSource
import de.jeanlucmakiola.calendula.data.contacts.SpecialDatesScheduler
import de.jeanlucmakiola.calendula.data.contacts.SpecialDatesSyncEngine
import de.jeanlucmakiola.calendula.data.contacts.SpecialDatesSyncResult
import de.jeanlucmakiola.calendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.calendula.data.prefs.SpecialDatesStalledReason
import de.jeanlucmakiola.calendula.data.fonts.CustomFontStore
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.calendula.data.reminders.ReminderScanner
import de.jeanlucmakiola.calendula.domain.FontRole
import de.jeanlucmakiola.calendula.widget.requestWidgetRedraw
import de.jeanlucmakiola.floret.backup.BackupProblem
import de.jeanlucmakiola.floret.backup.BackupResult
import de.jeanlucmakiola.floret.di.IoDispatcher
import de.jeanlucmakiola.floret.locale.AppLanguage
import de.jeanlucmakiola.floret.prefs.get
import de.jeanlucmakiola.floret.prefs.rawValue
import de.jeanlucmakiola.floret.reminders.reminderOverrideFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports and imports Calendula's settings (#481). The file format and what
 * it holds are [SettingsBackupSchema]'s; the import is planned purely by
 * [planSettingsImport], written in one DataStore edit, and then followed by
 * the side effects the settings screens would have run themselves.
 */
@Singleton
class SettingsBackupEngine @Inject constructor(
    private val store: DataStore<Preferences>,
    private val calendars: CalendarDataSource,
    private val contacts: ContactSpecialDatesDataSource,
    private val specialDates: SpecialDatesSyncEngine,
    private val settingsPrefs: SettingsPrefs,
    private val reminderScanner: ReminderScanner,
    private val launcherNames: LauncherNameManager,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun export(): String = withContext(io) {
        val supported = supportedLanguages()
        val snapshot = store.data.first()
        // Without the list the per-calendar settings would silently drop out of the file.
        val list = if (snapshot.hasCalendarSettings()) calendars.calendars() else emptyList()
        val backup = buildSettingsBackup(
            snapshot = snapshot,
            calendars = list,
            managed = if (list.any { it.isManaged }) calendars.findManagedCalendars() else emptyList(),
            language = LanguageChoice(AppLanguage.currentTag(context, supported)),
            launcherName = launcherNames.current(),
        )
        encodeSettingsBackup(backup, System.currentTimeMillis(), BuildConfig.VERSION_NAME)
    }

    /** Validates [text] in full, then replaces the settings; nothing is written for an invalid file. */
    suspend fun import(text: String): BackupResult<SettingsImportSummary> = withContext(io) {
        val backup = when (val decoded = decodeSettingsBackup(text)) {
            is BackupResult.Failure -> return@withContext decoded
            is BackupResult.Success -> decoded.value
        }
        val current = store.data.first()
        val supported = supportedLanguages()
        val environment = environment(current, backup, supported)
        val plan = planSettingsImport(backup, current, environment)
        val existingMirrors = environment.managed.map { it.id }.toSet()
        // Once the write starts, the side effects have to follow it, whoever cancels.
        withContext(NonCancellable) {
            try {
                store.edit { plan.applyTo(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Settings import could not be written", e)
                return@withContext BackupResult.Failure(BackupProblem.NOT_APPLIED, e.message.orEmpty())
            }
            val lateSkips = afterImport(plan, current, supported, existingMirrors)
            BackupResult.Success(plan.summary(plan.calendars.orEmpty().size + lateSkips.matched, lateSkips.skipped))
        }
    }

    private class LateMatches(val matched: Int, val skipped: List<SkippedSetting>)

    /**
     * Brings what reads the settings outside DataStore in line: the mirror,
     * reminder alarms, scheduled backups, the launcher alias and the language.
     * Each step stands alone, so one failing never undoes the import.
     */
    private suspend fun afterImport(
        plan: SettingsImportPlan,
        before: Preferences,
        supported: List<String>,
        existingMirrors: Set<Long>,
    ): LateMatches {
        val after = store.data.first()
        val wasMirroring = before[SettingsKeys.specialDatesEnabled]
        val mirroring = after[SettingsKeys.specialDatesEnabled]
        var late = LateMatches(0, plan.pendingSpecialDates.map { it.calendar.skipped() })
        step("special dates") {
            if (mirroring) {
                SpecialDatesScheduler.apply(context, enabled = true)
                val result = specialDates.sync()
                if (result != SpecialDatesSyncResult.Disabled) {
                    val stalled = SpecialDatesStalledReason.PermissionRevoked
                        .takeIf { result == SpecialDatesSyncResult.PermissionMissing }
                    settingsPrefs.recordSpecialDatesRun(System.currentTimeMillis(), stalled)
                }
                late = matchSpecialDates(plan, before, existingMirrors, synced = result == SpecialDatesSyncResult.Success)
            } else if (wasMirroring) {
                SpecialDatesScheduler.apply(context, enabled = false)
                specialDates.teardown()
            }
        }
        step("reminders") { reminderScanner.scan() }
        step("auto backup") {
            BackupScheduler.apply(
                context = context,
                enabled = after[SettingsKeys.autoBackupEnabled],
                intervalMinutes = after[SettingsKeys.autoBackupIntervalMinutes],
                hasFolder = after[SettingsKeys.autoBackupFolderUri] != null,
            )
        }
        plan.launcherName?.let { name ->
            step("launcher name") { if (launcherNames.current() != name) launcherNames.set(name) }
        }
        plan.language?.let { choice ->
            step("language") {
                if (AppLanguage.currentTag(context, supported) != choice.tag) {
                    withContext(Dispatchers.Main) { AppLanguage.apply(context, choice.tag) }
                    requestWidgetRedraw(context)
                }
            }
        }
        return late
    }

    /**
     * Gives late-created mirror calendars their entries (only after a
     * successful [synced] run), then rewrites each mirror whose all-day
     * reminders differ from what its events carry: the pre-import override for
     * a calendar that already existed, the sync's seed for a new one. A mirror
     * the file doesn't override inherits again.
     */
    private suspend fun matchSpecialDates(
        plan: SettingsImportPlan,
        before: Preferences,
        existingMirrors: Set<Long>,
        synced: Boolean,
    ): LateMatches {
        val managed = calendars.findManagedCalendars()
        val skipped = mutableListOf<SkippedSetting>()
        var matched = 0
        val resolved = if (!synced) {
            skipped += plan.pendingSpecialDates.map { it.calendar.skipped() }
            emptyList()
        } else {
            plan.pendingSpecialDates.mapNotNull { entry ->
                val id = managed.firstOrNull { it.type == entry.calendar.specialDates }?.id
                if (id == null) skipped += entry.calendar.skipped() else matched++
                id?.let { it to entry }
            }
        }
        if (resolved.isNotEmpty()) {
            store.edit { prefs ->
                resolved.forEach { (id, entry) -> prefs.addCalendarSettings(id, entry.copy(allDayReminders = null)) }
            }
        }
        if (plan.calendars != null) {
            val wanted = (plan.calendars.values + resolved.map { it.second })
                .mapNotNull { entry -> entry.calendar.specialDates?.let { it to entry.allDayReminders } }
                .toMap()
            val carried = before[SettingsKeys.perCalendarAllDayReminderOverride]
            val seeded = store.data.first()[SettingsKeys.perCalendarAllDayReminderOverride]
            managed.distinctBy { it.type }.forEach { (id, type) ->
                val minutes = wanted[type]
                val onEvents = if (id in existingMirrors) carried[id] else seeded[id]
                if (onEvents == minutes) return@forEach
                val desired = minutes?.let { mapOf(id to it) }.orEmpty()
                specialDates.applyReminders(type, desired.reminderOverrideFor(id))
            }
        }
        return LateMatches(matched, skipped)
    }

    /** The highest byte count [import] is worth reading. */
    val maxImportBytes: Int get() = SettingsBackupSchema.format.maxChars

    private fun environment(
        current: Preferences,
        backup: SettingsBackup,
        supported: List<String>,
    ): ImportEnvironment = ImportEnvironment(
        // An empty list here is a failed read as far as the import can tell, never "wipe them all".
        calendars = if (backup.calendars.isEmpty()) {
            emptyList()
        } else {
            runCatching { calendars.calendars() }.getOrNull()?.takeIf { it.isNotEmpty() }
        },
        managed = runCatching { calendars.findManagedCalendars() }.getOrDefault(emptyList()),
        contactsPermission = contacts.hasPermission(),
        customFonts = FontRole.entries.filter { CustomFontStore.exists(context, it) }.toSet(),
        hasBackupFolder = current[SettingsKeys.autoBackupFolderUri] != null,
        supportedLanguages = supported,
    )

    private fun Preferences.hasCalendarSettings(): Boolean =
        SettingsBackupSchema.perCalendar.any { rawValue(it) != null }

    private fun supportedLanguages(): List<String> = AppLanguage.supportedTags(context, R.xml.locales_config)

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Settings import: $name could not be refreshed", e)
        }
    }

    private companion object {
        const val TAG = "SettingsBackup"
    }
}
