package de.jeanlucmakiola.calendula.data.backup

import androidx.datastore.preferences.core.Preferences
import de.jeanlucmakiola.calendula.data.appname.LauncherName
import de.jeanlucmakiola.calendula.data.calendar.ManagedCalendarRow
import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefKeys
import de.jeanlucmakiola.calendula.data.prefs.FixedTimeOverride
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.calendula.data.prefs.fixedTimeOverrideFor
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.contacts.SpecialDateType
import de.jeanlucmakiola.calendula.domain.reminders.FixedTimeReminder
import de.jeanlucmakiola.calendula.domain.reminders.ReminderAlertStyle
import de.jeanlucmakiola.calendula.ui.common.CalendarView
import de.jeanlucmakiola.floret.backup.BackupDocument
import de.jeanlucmakiola.floret.backup.BackupResult
import de.jeanlucmakiola.floret.backup.PrefsSection
import de.jeanlucmakiola.floret.backup.invalidBackup
import de.jeanlucmakiola.floret.backup.optBoolean
import de.jeanlucmakiola.floret.backup.optIntList
import de.jeanlucmakiola.floret.backup.optObject
import de.jeanlucmakiola.floret.backup.optObjectList
import de.jeanlucmakiola.floret.backup.optInt
import de.jeanlucmakiola.floret.backup.optString
import de.jeanlucmakiola.floret.backup.optStringList
import de.jeanlucmakiola.floret.backup.readBackup
import de.jeanlucmakiola.floret.backup.requireString
import de.jeanlucmakiola.floret.prefs.Pref
import de.jeanlucmakiola.floret.prefs.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A calendar as another device can find it again: provider ids are per
 * device, so a calendar is named by its account and display name, and a
 * special-dates mirror by the date type it mirrors.
 */
data class CalendarIdentity(
    val accountName: String,
    val accountType: String,
    val displayName: String,
    val specialDates: SpecialDateType? = null,
)

/** The per-calendar settings of one calendar; null fields inherit the global default. */
data class CalendarSettings(
    val calendar: CalendarIdentity,
    val hidden: Boolean = false,
    val lastUsed: Boolean = false,
    val eventDurationMinutes: Int? = null,
    /** Timed reminder override; an empty list is "no reminder". */
    val reminders: List<Int>? = null,
    val allDayReminders: List<Int>? = null,
    /** The views it is hidden in only (#270). */
    val hiddenInViews: Set<CalendarView> = emptySet(),
    /** False when Calendula posts none of its reminders (#272). */
    val notifications: Boolean = true,
    /** Its alert style (#171); null is a plain notification. */
    val alertStyle: ReminderAlertStyle? = null,
    /** Its set-time reminder override (#459). */
    val fixedTimeReminder: FixedTimeOverride = FixedTimeOverride.Inherit,
    /** False when the daily summary leaves it out (#459). */
    val inDailyDigest: Boolean = true,
    /** The automatic backup's choice for it (#147); null follows the default for its kind. */
    val autoBackup: Boolean? = null,
)

/** The app language a file carries; [tag] null follows the system. */
data class LanguageChoice(val tag: String?)

/** Everything a settings backup holds. Null [language]/[launcherName]: the file didn't say. */
data class SettingsBackup(
    val prefs: Map<Pref<*>, Any>,
    val calendars: List<CalendarSettings>,
    val language: LanguageChoice? = null,
    val launcherName: LauncherName? = null,
)

internal val plainSection = PrefsSection(SettingsBackupSchema.plain)

/**
 * The backup of [snapshot]. Per-calendar entries are named through
 * [calendars] and [managed]; an id neither knows (a deleted calendar's
 * leftover) is dropped.
 */
fun buildSettingsBackup(
    snapshot: Preferences,
    calendars: List<CalendarSource>,
    managed: List<ManagedCalendarRow>,
    language: LanguageChoice?,
    launcherName: LauncherName?,
): SettingsBackup {
    val hidden = snapshot[CalendarPrefKeys.hiddenCalendarIds]
    val lastUsed = snapshot[CalendarPrefKeys.lastUsedCalendarId]
    val durations = snapshot[SettingsKeys.perCalendarEventDuration]
    val reminders = snapshot[SettingsKeys.perCalendarReminderOverride]
    val allDay = snapshot[SettingsKeys.perCalendarAllDayReminderOverride]
    val hiddenViews = snapshot[SettingsKeys.perCalendarHiddenViews]
    val muted = snapshot[SettingsKeys.mutedReminderCalendarIds]
    val alertStyles = snapshot[SettingsKeys.reminderAlertStyles]
    val fixedTime = snapshot[SettingsKeys.perCalendarFixedTimeReminderOverride]
    val notInDigest = snapshot[SettingsKeys.digestExcludedCalendarIds]
    val backupIncluded = snapshot[SettingsKeys.autoBackupIncluded]
    val backupExcluded = snapshot[SettingsKeys.autoBackupExcluded]
    val ids = hidden + listOfNotNull(lastUsed) + durations.keys + reminders.keys + allDay.keys +
        hiddenViews.keys + muted + alertStyles.keys + fixedTime.keys + notInDigest + backupIncluded + backupExcluded
    val byId = calendars.associateBy { it.id }
    val managedType = managed.associate { it.id to it.type }
    val entries = ids.sorted().mapNotNull { id ->
        val calendar = byId[id] ?: return@mapNotNull null
        CalendarSettings(
            calendar = CalendarIdentity(
                accountName = calendar.accountName,
                accountType = calendar.accountType,
                displayName = calendar.displayName,
                specialDates = managedType[id],
            ),
            hidden = id in hidden,
            lastUsed = id == lastUsed,
            eventDurationMinutes = durations[id],
            reminders = reminders[id],
            allDayReminders = allDay[id],
            hiddenInViews = hiddenViews[id].orEmpty(),
            notifications = id !in muted,
            alertStyle = alertStyles[id]?.takeUnless { it == ReminderAlertStyle.Notification },
            fixedTimeReminder = fixedTime.fixedTimeOverrideFor(id),
            inDailyDigest = id !in notInDigest,
            autoBackup = when (id) {
                in backupIncluded -> true
                in backupExcluded -> false
                else -> null
            },
        )
    }
    return SettingsBackup(
        prefs = plainSection.snapshot(snapshot),
        calendars = entries,
        language = language,
        launcherName = launcherName,
    )
}

fun encodeSettingsBackup(backup: SettingsBackup, exportedAtMillis: Long, appVersion: String?): String {
    val body = buildJsonObject {
        put(KEY_SETTINGS, plainSection.encode(backup.prefs))
        put(KEY_CALENDARS, JsonArray(backup.calendars.map { it.toJson() }))
        put(
            KEY_APP,
            buildJsonObject {
                backup.language?.let { put(KEY_LANGUAGE, it.tag?.let(::JsonPrimitive) ?: JsonNull) }
                backup.launcherName?.let { put(KEY_LAUNCHER_NAME, JsonPrimitive(it.name)) }
            },
        )
    }
    val document = BackupDocument(
        version = SettingsBackupSchema.format.version,
        exportedAtMillis = exportedAtMillis,
        appVersion = appVersion,
        body = body,
    )
    return SettingsBackupSchema.format.encode(document)
}

/** Validates the whole file before anything is applied; see docs/SETTINGS_BACKUP.md. */
fun decodeSettingsBackup(text: String): BackupResult<SettingsBackup> {
    val document = when (val decoded = SettingsBackupSchema.format.decode(text)) {
        is BackupResult.Failure -> return decoded
        is BackupResult.Success -> decoded.value
    }
    return readBackup {
        val body = document.body
        val app = body.optObject(KEY_APP)
        SettingsBackup(
            prefs = plainSection.read(body.optObject(KEY_SETTINGS) ?: invalidBackup("$KEY_SETTINGS: missing")),
            calendars = body.optObjectList(KEY_CALENDARS).orEmpty().map { it.toCalendarSettings() },
            language = app?.takeIf { KEY_LANGUAGE in it }?.let { LanguageChoice(it.optString(KEY_LANGUAGE)) },
            launcherName = app?.optString(KEY_LAUNCHER_NAME)
                ?.let { name -> LauncherName.entries.firstOrNull { it.name == name } },
        )
    }
}

private fun CalendarSettings.toJson(): JsonObject = buildJsonObject {
    put(KEY_ACCOUNT_NAME, JsonPrimitive(calendar.accountName))
    put(KEY_ACCOUNT_TYPE, JsonPrimitive(calendar.accountType))
    put(KEY_DISPLAY_NAME, JsonPrimitive(calendar.displayName))
    calendar.specialDates?.let { put(KEY_SPECIAL_DATES, JsonPrimitive(it.name)) }
    if (hidden) put(KEY_HIDDEN, JsonPrimitive(true))
    if (lastUsed) put(KEY_LAST_USED, JsonPrimitive(true))
    eventDurationMinutes?.let { put(KEY_EVENT_DURATION, JsonPrimitive(it)) }
    reminders?.let { put(KEY_REMINDERS, JsonArray(it.map(::JsonPrimitive))) }
    allDayReminders?.let { put(KEY_ALL_DAY_REMINDERS, JsonArray(it.map(::JsonPrimitive))) }
    if (hiddenInViews.isNotEmpty()) {
        put(KEY_HIDDEN_IN_VIEWS, JsonArray(hiddenInViews.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
    }
    if (!notifications) put(KEY_NOTIFICATIONS, JsonPrimitive(false))
    alertStyle?.let { put(KEY_ALERT_STYLE, JsonPrimitive(it.storageValue)) }
    when (val fixed = fixedTimeReminder) {
        FixedTimeOverride.Inherit -> Unit
        FixedTimeOverride.Off -> put(KEY_FIXED_TIME_REMINDER, JsonPrimitive(FIXED_TIME_OFF))
        is FixedTimeOverride.At -> put(KEY_FIXED_TIME_REMINDER, JsonPrimitive(fixed.reminder.toToken()))
    }
    if (!inDailyDigest) put(KEY_IN_DAILY_DIGEST, JsonPrimitive(false))
    autoBackup?.let { put(KEY_AUTO_BACKUP, JsonPrimitive(it)) }
}

/**
 * Strict about what it reads. A special-dates type this build doesn't know
 * reads as an ordinary calendar, which then simply finds no match.
 */
private fun JsonObject.toCalendarSettings(): CalendarSettings {
    return CalendarSettings(
        calendar = CalendarIdentity(
            accountName = requireString(KEY_ACCOUNT_NAME),
            accountType = requireString(KEY_ACCOUNT_TYPE),
            displayName = requireString(KEY_DISPLAY_NAME),
            specialDates = optString(KEY_SPECIAL_DATES)?.let { name -> SpecialDateType.entries.firstOrNull { it.name == name } },
        ),
        hidden = optBoolean(KEY_HIDDEN) ?: false,
        lastUsed = optBoolean(KEY_LAST_USED) ?: false,
        eventDurationMinutes = optInt(KEY_EVENT_DURATION),
        reminders = optIntList(KEY_REMINDERS)?.cleanReminders(),
        allDayReminders = optIntList(KEY_ALL_DAY_REMINDERS)?.cleanReminders(),
        hiddenInViews = optStringList(KEY_HIDDEN_IN_VIEWS).orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> CalendarView.entries.firstOrNull { it.name == name } },
        notifications = optBoolean(KEY_NOTIFICATIONS) ?: true,
        alertStyle = optString(KEY_ALERT_STYLE)?.let(ReminderAlertStyle::fromStorage)
            ?.takeUnless { it == ReminderAlertStyle.Notification },
        fixedTimeReminder = when (val token = optString(KEY_FIXED_TIME_REMINDER)) {
            null -> FixedTimeOverride.Inherit
            FIXED_TIME_OFF -> FixedTimeOverride.Off
            else -> FixedTimeReminder.parse(token)?.let(FixedTimeOverride::At) ?: FixedTimeOverride.Inherit
        },
        inDailyDigest = optBoolean(KEY_IN_DAILY_DIGEST) ?: true,
        autoBackup = optBoolean(KEY_AUTO_BACKUP),
    )
}

/** As the reminder setters store them: no negative leads, each once, ascending. */
private fun List<Int>.cleanReminders(): List<Int> = filter { it >= 0 }.distinct().sorted()

private const val KEY_SETTINGS = "settings"
private const val KEY_CALENDARS = "calendars"
private const val KEY_APP = "app"
private const val KEY_LANGUAGE = "language"
private const val KEY_LAUNCHER_NAME = "launcher_name"
private const val KEY_ACCOUNT_NAME = "account_name"
private const val KEY_ACCOUNT_TYPE = "account_type"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_SPECIAL_DATES = "special_dates"
private const val KEY_HIDDEN = "hidden"
private const val KEY_LAST_USED = "last_used"
private const val KEY_EVENT_DURATION = "event_duration_minutes"
private const val KEY_REMINDERS = "reminders"
private const val KEY_ALL_DAY_REMINDERS = "all_day_reminders"
private const val KEY_HIDDEN_IN_VIEWS = "hidden_in_views"
private const val KEY_NOTIFICATIONS = "notifications"
private const val KEY_ALERT_STYLE = "alert_style"
private const val KEY_FIXED_TIME_REMINDER = "fixed_time_reminder"
private const val FIXED_TIME_OFF = "off"
private const val KEY_IN_DAILY_DIGEST = "in_daily_digest"
private const val KEY_AUTO_BACKUP = "auto_backup"
