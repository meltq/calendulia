package de.jeanlucmakiola.calendula.data.backup

import de.jeanlucmakiola.calendula.data.prefs.CalendarPrefKeys
import de.jeanlucmakiola.calendula.data.prefs.ReminderStateKeys
import de.jeanlucmakiola.calendula.data.prefs.SettingsKeys
import de.jeanlucmakiola.floret.backup.BackupFormat
import de.jeanlucmakiola.floret.prefs.Pref

/**
 * Which stored keys a settings backup carries (#481), and how. Every key of
 * the settings store is in exactly one of the three lists; a test holds that,
 * so a new setting cannot ship without deciding. See docs/SETTINGS_BACKUP.md.
 */
internal object SettingsBackupSchema {

    val format = BackupFormat(name = "calendula-settings", version = 1)

    /** Carried as stored and restored over the current value; an absent key restores the default. */
    val plain: List<Pref<*>> = listOf(
        SettingsKeys.themeMode,
        SettingsKeys.dynamicColor,
        SettingsKeys.weekStart,
        SettingsKeys.softenCalendarColors,
        SettingsKeys.brandFont,
        SettingsKeys.plainFont,
        SettingsKeys.timeFormat,
        SettingsKeys.showHourGrid,
        SettingsKeys.pastEventDisplay,
        SettingsKeys.dimCompletedEvents,
        SettingsKeys.showWeekNumbers,
        SettingsKeys.monthShowStartTimes,
        SettingsKeys.monthViewStyle,
        SettingsKeys.timelineScale,
        SettingsKeys.multiDayCount,
        SettingsKeys.timelineHoursEnabled,
        SettingsKeys.timelineHoursStart,
        SettingsKeys.timelineHoursEnd,
        SettingsKeys.todayButtonInToolbar,
        SettingsKeys.dragToReschedule,
        SettingsKeys.tapOpensEditor,
        SettingsKeys.agendaScreenRange,
        SettingsKeys.agendaShowRangeBar,
        SettingsKeys.agendaShowToday,
        SettingsKeys.defaultView,
        SettingsKeys.quickSwitchConfig,
        SettingsKeys.drawerViewOrder,
        SettingsKeys.defaultFormFields,
        SettingsKeys.autofocusEventTitle,
        // Names its calendar by account and display name, so it finds it again on another device.
        SettingsKeys.defaultNewEventCalendar,
        SettingsKeys.defaultEventDurationMinutes,
        SettingsKeys.remindersEnabled,
        SettingsKeys.allowColorOnUnsupportedCalendars,
        SettingsKeys.defaultReminderMinutes,
        SettingsKeys.defaultAllDayReminderMinutes,
        SettingsKeys.allDayReminderTimeMinutes,
        SettingsKeys.snoozeMinutes,
        SettingsKeys.defaultFixedTimeReminder,
        SettingsKeys.dailyDigestEnabled,
        SettingsKeys.dailyDigestTimeMinutes,
        SettingsKeys.dailyDigestDay,
        SettingsKeys.autoBackupEnabled,
        SettingsKeys.autoBackupIntervalMinutes,
        SettingsKeys.autoBackupCombinedStored,
        SettingsKeys.autoBackupKeep,
        SettingsKeys.autoBackupOnChange,
        SettingsKeys.specialDatesEnabled,
        SettingsKeys.specialDatesShowYear,
    ) + SettingsKeys.specialDateTypeEnabled.values + SettingsKeys.specialDatesTitleTemplate.values

    /**
     * Keyed by calendar id on disk, so carried per calendar under a portable
     * identity instead ([CalendarSettings]) and re-keyed to this device's ids.
     */
    val perCalendar: List<Pref<*>> = listOf(
        CalendarPrefKeys.hiddenCalendarIds,
        CalendarPrefKeys.lastUsedCalendarId,
        SettingsKeys.perCalendarEventDuration,
        SettingsKeys.perCalendarReminderOverride,
        SettingsKeys.perCalendarAllDayReminderOverride,
        SettingsKeys.perCalendarHiddenViews,
        SettingsKeys.mutedReminderCalendarIds,
        SettingsKeys.reminderAlertStyles,
        SettingsKeys.perCalendarFixedTimeReminderOverride,
        SettingsKeys.digestExcludedCalendarIds,
        SettingsKeys.autoBackupIncluded,
        SettingsKeys.autoBackupExcluded,
    )

    /** Never exported: this install's own state, bookkeeping, or meaningless on another device. */
    val excluded: List<Pref<*>> = (
        listOf(
            // Cache-busting counters for the locally imported font file.
            SettingsKeys.brandFontStamp,
            SettingsKeys.plainFontStamp,
            // Legacy defaults for unconfigured widgets; widget configuration stays out.
            SettingsKeys.agendaWidgetRange,
            SettingsKeys.widgetSize,
            // Usage history, not a setting.
            SettingsKeys.recentTimeZones,
            SettingsKeys.recentFixedTimeReminders,
            // A dismissed hint of this install.
            SettingsKeys.notificationCategoryHintDismissed,
            // This device's notification channels.
            SettingsKeys.reminderChannelVersions,
            // Sync-request grants of this device's accounts.
            CalendarPrefKeys.syncAccessDismissed,
            // First-run wizard progress of this install.
            SettingsKeys.onboardingWizardArmed,
            CalendarPrefKeys.visibilityNoticePending,
            // A SAF grant, which does not survive a reinstall or another device.
            SettingsKeys.autoBackupFolderUri,
            // Run status.
            SettingsKeys.autoBackupLastRun,
            SettingsKeys.autoBackupLastSuccess,
            SettingsKeys.autoBackupFailures,
            SettingsKeys.specialDatesLastRun,
            SettingsKeys.specialDatesStalled,
            SettingsKeys.specialDatesLastForegroundSync,
            SettingsKeys.dailyDigestArmedAt,
            ReminderStateKeys.lastDigestDay,
            // What the automatic backup wrote to this install's folder.
            SettingsKeys.autoBackupLedger,
            SettingsKeys.autoBackupFingerprints,
            // Switch-offs still owed to the provider on this device.
            CalendarPrefKeys.pendingDisabledCalendarIds,
            // The reminder delivery watermark.
            ReminderStateKeys.lastScanMillis,
        ) +
            SettingsKeys.onboardingDone.values +
            // The mirror re-adopts its calendars by their provider marker.
            SettingsKeys.specialDatesCalendarId.values
        ).distinct()
}
