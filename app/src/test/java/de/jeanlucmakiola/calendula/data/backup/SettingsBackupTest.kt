package de.jeanlucmakiola.calendula.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.data.appname.LauncherName
import de.jeanlucmakiola.calendula.data.calendar.ManagedCalendarRow
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.FontRole
import de.jeanlucmakiola.calendula.domain.contacts.SpecialDateType
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.calendula.ui.theme.FONT_CUSTOM_TOKEN
import de.jeanlucmakiola.floret.backup.BackupProblem
import de.jeanlucmakiola.floret.backup.BackupResult
import de.jeanlucmakiola.floret.prefs.ThemeMode
import de.jeanlucmakiola.floret.prefs.get
import de.jeanlucmakiola.floret.prefs.set
import org.junit.jupiter.api.Test

class SettingsBackupTest {

    private fun calendar(id: Long, name: String, account: String = "me@example.org", managed: Boolean = false) =
        CalendarSource(
            id = id,
            displayName = name,
            accountName = account,
            accountType = "com.google",
            color = 0,
            isVisibleInSystem = true,
            isManaged = managed,
        )

    private val work = calendar(1, "Work")
    private val home = calendar(2, "Home")
    private val birthdays = calendar(3, "Birthdays", account = "Calendula", managed = true)

    private fun environment(
        calendars: List<CalendarSource>? = listOf(work, home, birthdays),
        managed: List<ManagedCalendarRow> = listOf(ManagedCalendarRow(3, SpecialDateType.Birthday)),
        contacts: Boolean = true,
        fonts: Set<FontRole> = emptySet(),
        folder: Boolean = false,
        languages: List<String> = listOf("de", "en-US", "fr"),
    ) = ImportEnvironment(calendars, managed, contacts, fonts, folder, languages)

    private fun roundTrip(source: MutablePreferences, calendars: List<CalendarSource> = listOf(work, home, birthdays)): SettingsBackup {
        val backup = buildSettingsBackup(
            snapshot = source,
            calendars = calendars,
            managed = listOf(ManagedCalendarRow(3, SpecialDateType.Birthday)),
            language = LanguageChoice("de"),
            launcherName = LauncherName.CALENDAR,
        )
        val decoded = decodeSettingsBackup(encodeSettingsBackup(backup, 1_700_000_000_000, "2.25.0"))
        return (decoded as BackupResult.Success).value
    }

    private fun import(backup: SettingsBackup, current: MutablePreferences, env: ImportEnvironment = environment()) =
        planSettingsImport(backup, current, env).also { it.applyTo(current) }

    @Test
    fun `plain settings survive the round trip`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.themeMode] = ThemeMode.BLACK
        source[SettingsKeys.multiDayCount] = 5
        source[SettingsKeys.defaultView] = CalendarView.Month
        source[SettingsKeys.defaultReminderMinutes] = listOf(10, 60)

        val target = mutablePreferencesOf()
        import(roundTrip(source), target)

        assertThat(target[SettingsKeys.themeMode]).isEqualTo(ThemeMode.BLACK)
        assertThat(target[SettingsKeys.multiDayCount]).isEqualTo(5)
        assertThat(target[SettingsKeys.defaultView]).isEqualTo(CalendarView.Month)
        assertThat(target[SettingsKeys.defaultReminderMinutes]).containsExactly(10, 60).inOrder()
    }

    @Test
    fun `a setting the file leaves at its default resets here`() {
        val target = mutablePreferencesOf()
        target[SettingsKeys.showWeekNumbers] = true

        import(roundTrip(mutablePreferencesOf()), target)

        assertThat(target[SettingsKeys.showWeekNumbers]).isFalse()
    }

    @Test
    fun `device state is neither exported nor touched`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.autoBackupFolderUri] = "content://tree/backup"
        source[SettingsKeys.recentTimeZones] = listOf("Europe/Berlin")
        val target = mutablePreferencesOf()
        target[SettingsKeys.autoBackupLastRun] = 42L

        val backup = roundTrip(source)
        import(backup, target)

        assertThat(backup.prefs.keys.map { it.name }).doesNotContain("auto_backup_folder_uri")
        assertThat(target[SettingsKeys.autoBackupFolderUri]).isNull()
        assertThat(target[SettingsKeys.autoBackupLastRun]).isEqualTo(42L)
    }

    @Test
    fun `per-calendar settings follow the calendar to its new id`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(1L)
        source[CalendarPrefKeys.lastUsedCalendarId] = 2L
        source[SettingsKeys.perCalendarEventDuration] = mapOf(1L to 30)
        source[SettingsKeys.perCalendarReminderOverride] = mapOf(2L to emptyList())
        val moved = listOf(calendar(11, "Work"), calendar(12, "Home"))

        val target = mutablePreferencesOf()
        val plan = import(roundTrip(source), target, environment(calendars = moved, managed = emptyList()))

        assertThat(target[CalendarPrefKeys.hiddenCalendarIds]).containsExactly(11L)
        assertThat(target[CalendarPrefKeys.lastUsedCalendarId]).isEqualTo(12L)
        assertThat(target[SettingsKeys.perCalendarEventDuration]).containsExactly(11L, 30)
        assertThat(target[SettingsKeys.perCalendarReminderOverride]).containsExactly(12L, emptyList<Int>())
        assertThat(plan.calendars).hasSize(2)
    }

    @Test
    fun `a calendar missing here is reported, and its old id is not reused`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.perCalendarEventDuration] = mapOf(1L to 30)
        val target = mutablePreferencesOf()
        target[SettingsKeys.perCalendarEventDuration] = mapOf(1L to 45)

        val plan = import(roundTrip(source), target, environment(calendars = listOf(calendar(1, "Other"))))

        assertThat(target[SettingsKeys.perCalendarEventDuration]).isEmpty()
        assertThat(plan.skipped).containsExactly(SkippedSetting.Calendar("Work", "me@example.org"))
    }

    @Test
    fun `two calendars with the same name and account match neither`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(1L)

        val target = mutablePreferencesOf()
        val plan = import(roundTrip(source), target, environment(calendars = listOf(calendar(5, "Work"), calendar(6, "Work"))))

        assertThat(target[CalendarPrefKeys.hiddenCalendarIds]).isEmpty()
        assertThat(plan.skipped).containsExactly(SkippedSetting.Calendar("Work", "me@example.org", ambiguous = true))
    }

    @Test
    fun `every per-calendar key travels with its calendar`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(1L)
        source[CalendarPrefKeys.lastUsedCalendarId] = 1L
        source[SettingsKeys.perCalendarEventDuration] = mapOf(1L to 30)
        source[SettingsKeys.perCalendarReminderOverride] = mapOf(1L to listOf(5))
        source[SettingsKeys.perCalendarAllDayReminderOverride] = mapOf(1L to listOf(0))

        val target = mutablePreferencesOf()
        import(roundTrip(source), target, environment(calendars = listOf(calendar(9, "Work")), managed = emptyList()))

        // One assertion per key in the schema: a new per-calendar key fails here until it is carried.
        SettingsBackupSchema.perCalendar.forEach { pref ->
            assertThat(target.asMap().keys.map { it.name }).contains(pref.name)
        }
        assertThat(target[SettingsKeys.perCalendarAllDayReminderOverride]).containsExactly(9L, listOf(0))
    }

    @Test
    fun `a mirror type the import switches off is left out`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.specialDatesEnabled] = true
        source[SettingsKeys.specialDateTypeEnabled.getValue(SpecialDateType.Birthday)] = false
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(3L)

        val target = mutablePreferencesOf()
        val plan = import(roundTrip(source), target)

        assertThat(target[CalendarPrefKeys.hiddenCalendarIds]).isEmpty()
        assertThat(plan.pendingSpecialDates).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `an out-of-range calendar length is clamped, not refused`() {
        val text = """{"format":"calendula-settings","version":1,"exportedAt":1,"calendars":[
            {"account_name":"me@example.org","account_type":"com.google","display_name":"Work","event_duration_minutes":5000}]}"""
        val backup = (decodeSettingsBackup(text) as BackupResult.Success).value

        val target = mutablePreferencesOf()
        import(backup, target)

        assertThat(target[SettingsKeys.perCalendarEventDuration]).containsExactly(1L, 1440)
    }

    @Test
    fun `unreadable calendars leave the per-calendar settings alone`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(1L)
        val target = mutablePreferencesOf()
        target[SettingsKeys.perCalendarEventDuration] = mapOf(4L to 45)

        val plan = import(roundTrip(source), target, environment(calendars = null))

        assertThat(target[SettingsKeys.perCalendarEventDuration]).containsExactly(4L, 45)
        assertThat(plan.skipped).containsExactly(SkippedSetting.CalendarsUnavailable)
    }

    @Test
    fun `mirror calendars are left out when the import turns the mirror off`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(3L)

        val target = mutablePreferencesOf()
        val plan = import(roundTrip(source), target)

        assertThat(target[CalendarPrefKeys.hiddenCalendarIds]).isEmpty()
        assertThat(plan.pendingSpecialDates).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `a stale id in the prefs is dropped on export`() {
        val source = mutablePreferencesOf()
        source[CalendarPrefKeys.hiddenCalendarIds] = setOf(1L, 99L)

        assertThat(roundTrip(source).calendars.map { it.calendar.displayName }).containsExactly("Work")
    }

    @Test
    fun `a mirror calendar is matched by its date type`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.specialDatesEnabled] = true
        source[SettingsKeys.perCalendarAllDayReminderOverride] = mapOf(3L to listOf(0, 10080))

        val target = mutablePreferencesOf()
        val renamed = listOf(calendar(7, "Geburtstage", account = "Calendula", managed = true))
        import(roundTrip(source), target, environment(calendars = renamed, managed = listOf(ManagedCalendarRow(7, SpecialDateType.Birthday))))

        assertThat(target[SettingsKeys.perCalendarAllDayReminderOverride]).containsExactly(7L, listOf(0, 10080))
    }

    @Test
    fun `a mirror calendar that doesn't exist yet waits for the sync`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.specialDatesEnabled] = true
        source[SettingsKeys.perCalendarAllDayReminderOverride] = mapOf(3L to listOf(0))

        val target = mutablePreferencesOf()
        val plan = import(roundTrip(source), target, environment(managed = emptyList()))
        plan.pendingSpecialDates.forEach { target.addCalendarSettings(8L, it) }

        assertThat(plan.skipped).isEmpty()
        assertThat(target[SettingsKeys.perCalendarAllDayReminderOverride]).containsExactly(8L, listOf(0))
    }

    @Test
    fun `a custom font without its file keeps the current font`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.brandFont] = FONT_CUSTOM_TOKEN
        source[SettingsKeys.plainFont] = FONT_CUSTOM_TOKEN
        val target = mutablePreferencesOf()
        target[SettingsKeys.brandFont] = "lora"

        val plan = import(roundTrip(source), target, environment(fonts = setOf(FontRole.PLAIN)))

        assertThat(target[SettingsKeys.brandFont]).isEqualTo("lora")
        assertThat(target[SettingsKeys.plainFont]).isEqualTo(FONT_CUSTOM_TOKEN)
        assertThat(plan.skipped).containsExactly(SkippedSetting.CustomFont(FontRole.BRAND))
    }

    @Test
    fun `contact dates without the permission stay as they are`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.specialDatesEnabled] = true
        val target = mutablePreferencesOf()

        val plan = import(roundTrip(source), target, environment(contacts = false))

        assertThat(target[SettingsKeys.specialDatesEnabled]).isFalse()
        assertThat(plan.skipped).contains(SkippedSetting.SpecialDates)
    }

    @Test
    fun `automatic backup without a folder here stays off, its interval comes along`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.autoBackupEnabled] = true
        source[SettingsKeys.autoBackupIntervalMinutes] = 120L
        val target = mutablePreferencesOf()

        val plan = import(roundTrip(source), target)

        assertThat(target[SettingsKeys.autoBackupEnabled]).isFalse()
        assertThat(target[SettingsKeys.autoBackupIntervalMinutes]).isEqualTo(120L)
        assertThat(plan.skipped).contains(SkippedSetting.AutoBackupFolder)
    }

    @Test
    fun `automatic backup keeps going where a folder is already set`() {
        val source = mutablePreferencesOf()
        source[SettingsKeys.autoBackupEnabled] = true
        val target = mutablePreferencesOf()

        import(roundTrip(source), target, environment(folder = true))

        assertThat(target[SettingsKeys.autoBackupEnabled]).isTrue()
    }

    @Test
    fun `the language resolves against what this build ships`() {
        val backup = roundTrip(mutablePreferencesOf())

        assertThat(planSettingsImport(backup.copy(language = LanguageChoice("en-US")), mutablePreferencesOf(), environment()).language)
            .isEqualTo(LanguageChoice("en-US"))
        assertThat(planSettingsImport(backup.copy(language = LanguageChoice(null)), mutablePreferencesOf(), environment()).language)
            .isEqualTo(LanguageChoice(null))
        val missing = planSettingsImport(backup.copy(language = LanguageChoice("ja")), mutablePreferencesOf(), environment())
        assertThat(missing.language).isNull()
        assertThat(missing.skipped).containsExactly(SkippedSetting.Language("ja"))
    }

    @Test
    fun `app-level choices travel along`() {
        val backup = roundTrip(mutablePreferencesOf())

        assertThat(backup.language).isEqualTo(LanguageChoice("de"))
        assertThat(backup.launcherName).isEqualTo(LauncherName.CALENDAR)
    }

    @Test
    fun `unknown fields from a later build are ignored`() {
        val text = """
            {"format":"calendula-settings","version":1,"exportedAt":1,"future":{"x":1},
             "settings":{"multi_day_count":4,"some_new_key":true},
             "calendars":[{"account_name":"a","account_type":"t","display_name":"d","colour":"red"}],
             "app":{"launcher_name":"SOMETHING_NEW"}}
        """.trimIndent()

        val backup = (decodeSettingsBackup(text) as BackupResult.Success).value

        assertThat(backup.prefs.values).containsExactly(4)
        assertThat(backup.calendars).hasSize(1)
        assertThat(backup.launcherName).isNull()
        assertThat(backup.language).isNull()
    }

    @Test
    fun `a malformed known field rejects the whole file`() {
        listOf(
            """{"format":"calendula-settings","version":1,"exportedAt":1,"settings":{"multi_day_count":"4"}}""",
            """{"format":"calendula-settings","version":1,"exportedAt":1,"calendars":[{"account_name":"a"}]}""",
            """{"format":"calendula-settings","version":1,"exportedAt":1,"calendars":[{"account_name":"a","account_type":"t","display_name":"d","reminders":["x"]}]}""",
        ).forEach { text ->
            val result = decodeSettingsBackup(text) as BackupResult.Failure
            assertThat(result.problem).isEqualTo(BackupProblem.INVALID_DATA)
        }
    }

    @Test
    fun `a newer schema is refused`() {
        val result = decodeSettingsBackup("""{"format":"calendula-settings","version":2,"exportedAt":1}""")

        assertThat((result as BackupResult.Failure).problem).isEqualTo(BackupProblem.NEWER_VERSION)
    }
}
