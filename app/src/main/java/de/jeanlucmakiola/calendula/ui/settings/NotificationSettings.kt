package de.jeanlucmakiola.calendula.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.prefs.FixedTimeOverride
import de.jeanlucmakiola.calendula.data.prefs.fixedTimeOverrideFor
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.reminders.DailyDigestSettings
import de.jeanlucmakiola.calendula.domain.reminders.DigestDay
import de.jeanlucmakiola.calendula.domain.reminders.FixedTimeReminder
import de.jeanlucmakiola.calendula.ui.common.FixedTimeReminderPicker
import de.jeanlucmakiola.calendula.ui.common.fixedTimeReminderLabel
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.DurationPicker
import de.jeanlucmakiola.calendula.ui.common.REMINDER_PRESETS
import de.jeanlucmakiola.calendula.ui.common.ReminderDefaultPicker
import de.jeanlucmakiola.calendula.ui.common.TimePickerAlert
import de.jeanlucmakiola.calendula.ui.common.durationLabel
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.android.hasPermission
import de.jeanlucmakiola.floret.android.openNotificationSettings
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.ExpandIcon
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.minutesOrEmpty
import de.jeanlucmakiola.floret.reminders.reminderOverrideFor
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

/**
 * Reminder-notifications toggle (v1.4), mirroring the onboarding step.
 * Turning it on re-requests `POST_NOTIFICATIONS` when missing (API 33+) —
 * the pref is set either way; the OS permission is the real gate.
 */
@Composable
internal fun NotificationsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSpecialDates: () -> Unit,
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* The pref is already on; a denial just leaves the OS gate shut. */ }
    val toggleReminders: (Boolean) -> Unit = { enabled ->
        viewModel.setRemindersEnabled(enabled)
        val needsPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (needsPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val toggleDigest: (Boolean) -> Unit = { enabled ->
        viewModel.setDailyDigestEnabled(enabled)
        val needsPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (needsPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showDefaultReminder by remember { mutableStateOf(false) }
    var showAllDayReminder by remember { mutableStateOf(false) }
    var showAllDayReminderTime by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    var showFixedDefault by remember { mutableStateOf(false) }
    var fixedOverrideFor by remember { mutableStateOf<Long?>(null) }
    var showDigestDay by remember { mutableStateOf(false) }
    var showDigestTime by remember { mutableStateOf(false) }
    var overrideDialog by remember { mutableStateOf<OverrideTarget?>(null) }
    var calendarSectionExpanded by remember { mutableStateOf(false) }
    var expandedCalendars by remember { mutableStateOf(emptySet<Long>()) }
    val scope = rememberCoroutineScope()
    val openCalendarSound: (Long) -> Unit = { calendarId ->
        scope.launch { context.openNotificationSettings(viewModel.reminderChannelFor(calendarId)) }
    }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_notifications),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        GroupedSwitchRow(
            title = stringResource(R.string.settings_reminders),
            checked = state.remindersEnabled,
            onCheckedChange = toggleReminders,
            summary = stringResource(R.string.settings_reminders_hint),
            position = Position.Top,
        )
        GroupedRow(
            title = stringResource(R.string.settings_notification_sound),
            summary = stringResource(R.string.settings_notification_sound_hint),
            position = Position.Middle,
            trailing = { ExternalLinkIcon() },
            onClick = {
                scope.launch {
                    viewModel.syncReminderChannels()
                    context.openNotificationSettings()
                }
            },
        )
        GroupedRow(
            title = stringResource(R.string.settings_default_reminder),
            summary = reminderChoiceLabel(state.defaultReminderMinutes),
            position = Position.Middle,
            onClick = { showDefaultReminder = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_fixed_reminder),
            summary = fixedReminderChoiceLabel(state.defaultFixedTimeReminder),
            position = Position.Middle,
            onClick = { showFixedDefault = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_default_reminder_allday),
            summary = reminderChoiceLabel(state.defaultAllDayReminderMinutes),
            position = Position.Middle,
            onClick = { showAllDayReminder = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_allday_reminder_time),
            summary = stringResource(
                R.string.settings_allday_reminder_time_hint,
                settingsTimeOfDay(state.allDayReminderTimeMinutes),
            ),
            position = Position.Bottom,
            onClick = { showAllDayReminderTime = true },
        )

        Spacer(Modifier.height(24.dp))
        DailyDigestRows(
            digest = state.dailyDigest,
            onEnable = { enabled ->
                toggleDigest(enabled)
            },
            onPickDay = { showDigestDay = true },
            onPickTime = { showDigestTime = true },
            onOpenSound = {
                scope.launch { viewModel.digestChannel()?.let { context.openNotificationSettings(it) } }
            },
        )

        // Reliability is a soft, optional battery-optimisation exemption via
        // a system-settings deep-link, shown as live status.
        Spacer(Modifier.height(24.dp))
        val batteryExempt = rememberBatteryOptimizationExempt()
        GroupedRow(
            title = stringResource(R.string.settings_reliable_delivery),
            summary = if (batteryExempt) {
                stringResource(R.string.settings_reliable_delivery_exempt)
            } else {
                stringResource(R.string.settings_reliable_delivery_hint)
            },
            position = Position.Top,
            trailing = if (batteryExempt) {
                { SelectedCheck() }
            } else {
                null
            },
            onClick = { openBatteryOptimizationSettings(context) },
        )

        GroupedRow(
            title = stringResource(R.string.settings_snooze_duration),
            summary = durationLabel(state.snoozeMinutes),
            position = Position.Bottom,
            onClick = { showSnooze = true },
        )

        // Per-calendar settings, folded behind one header: every switched-on
        // calendar gets its notification switch and sound (#145, #272); the ones
        // you can edit also keep, drop or replace the default reminders.
        if (state.reminderCalendars.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            GroupedRow(
                title = stringResource(R.string.settings_calendar_reminders_title),
                summary = stringResource(R.string.settings_calendar_reminders_hint),
                position = Position.Alone,
                trailing = { ExpandIcon(calendarSectionExpanded) },
                onClick = { calendarSectionExpanded = !calendarSectionExpanded },
            )
            AnimatedVisibility(
                visible = calendarSectionExpanded,
                enter = expandEnter(),
                exit = collapseExit(),
            ) {
                Column {
                    state.reminderCalendars.forEach { calendar ->
                        Spacer(Modifier.height(16.dp))
                        val expanded = calendar.id in expandedCalendars
                        val notifying = calendar.id !in state.mutedReminderCalendarIds
                        GroupedRow(
                            title = calendar.displayName.orUnnamed(),
                            summary = if (notifying) null else stringResource(R.string.settings_calendar_notifications_off),
                            position = if (expanded) Position.Top else Position.Alone,
                            leading = { CalendarColorChip(calendar.color) },
                            trailing = { ExpandIcon(expanded) },
                            onClick = {
                                expandedCalendars = if (expanded) {
                                    expandedCalendars - calendar.id
                                } else {
                                    expandedCalendars + calendar.id
                                }
                            },
                        )
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandEnter(),
                            exit = collapseExit(),
                        ) {
                            CalendarNotificationRows(
                                calendar = calendar,
                                state = state,
                                notifying = notifying,
                                onSetNotifying = { viewModel.setCalendarRemindersEnabled(calendar.id, it) },
                                onOpenSound = { openCalendarSound(calendar.id) },
                                onOpenSpecialDates = onOpenSpecialDates,
                                onEditOverride = { overrideDialog = it },
                                onEditFixedOverride = { fixedOverrideFor = calendar.id },
                                onSetInDigest = { viewModel.setCalendarInDailyDigest(calendar.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFixedDefault) {
        FixedTimeReminderPicker(
            title = stringResource(R.string.settings_fixed_reminder),
            selected = state.defaultFixedTimeReminder?.let { FixedTimeOverride.At(it) } ?: FixedTimeOverride.Off,
            onSelect = { viewModel.setDefaultFixedTimeReminder((it as? FixedTimeOverride.At)?.reminder) },
            onDismiss = { showFixedDefault = false },
        )
    }
    fixedOverrideFor?.let { calendarId ->
        FixedTimeReminderPicker(
            title = stringResource(R.string.settings_fixed_reminder),
            selected = state.perCalendarFixedTimeReminderOverride.fixedTimeOverrideFor(calendarId),
            onSelect = { viewModel.setCalendarFixedTimeReminderOverride(calendarId, it) },
            onDismiss = { fixedOverrideFor = null },
            inheritedLabel = fixedReminderChoiceLabel(state.defaultFixedTimeReminder),
        )
    }
    if (showDigestDay) {
        OptionPicker(
            title = stringResource(R.string.settings_digest_day),
            options = DigestDay.entries,
            selected = state.dailyDigest.day,
            label = { stringResource(digestDayLabel(it)) },
            onSelect = viewModel::setDailyDigestDay,
            onDismiss = { showDigestDay = false },
        )
    }
    if (showDigestTime) {
        TimePickerAlert(
            initial = LocalTime(state.dailyDigest.timeMinutes / 60, state.dailyDigest.timeMinutes % 60),
            onConfirm = {
                viewModel.setDailyDigestTimeMinutes(it.hour * 60 + it.minute)
                showDigestTime = false
            },
            onDismiss = { showDigestTime = false },
        )
    }
    if (showSnooze) {
        DurationPicker(
            title = stringResource(R.string.settings_snooze_duration),
            presets = SNOOZE_PRESETS,
            selected = state.snoozeMinutes,
            label = { durationLabel(it) },
            // No inherit row here, so a pick is never null.
            onSelect = { minutes -> minutes?.let { viewModel.setSnoozeMinutes(it) } },
            onDismiss = { showSnooze = false },
        )
    }
    if (showDefaultReminder) {
        ReminderDefaultPicker(
            title = stringResource(R.string.settings_default_reminder),
            presets = REMINDER_PRESETS,
            selected = state.defaultReminderMinutes.toReminderChoice(),
            allowInherit = false,
            onSelect = { viewModel.setDefaultReminderMinutes(it.minutesOrEmpty()) },
            onDismiss = { showDefaultReminder = false },
        )
    }
    if (showAllDayReminder) {
        ReminderDefaultPicker(
            title = stringResource(R.string.settings_default_reminder_allday),
            presets = ALLDAY_REMINDER_PRESETS,
            selected = state.defaultAllDayReminderMinutes.toReminderChoice(),
            allowInherit = false,
            onSelect = { viewModel.setDefaultAllDayReminderMinutes(it.minutesOrEmpty()) },
            onDismiss = { showAllDayReminder = false },
            leadTimeSummary = allDayFiringTimeSummary(state.allDayReminderTimeMinutes),
        )
    }
    if (showAllDayReminderTime) {
        TimePickerAlert(
            initial = LocalTime(
                state.allDayReminderTimeMinutes / 60,
                state.allDayReminderTimeMinutes % 60,
            ),
            onConfirm = {
                viewModel.setAllDayReminderTimeMinutes(it.hour * 60 + it.minute)
                showAllDayReminderTime = false
            },
            onDismiss = { showAllDayReminderTime = false },
        )
    }
    overrideDialog?.let { target ->
        val map = if (target.isAllDay) {
            state.perCalendarAllDayReminderOverride
        } else {
            state.perCalendarReminderOverride
        }
        ReminderDefaultPicker(
            title = stringResource(
                if (target.isAllDay) {
                    R.string.settings_default_reminder_allday
                } else {
                    R.string.settings_default_reminder
                },
            ),
            presets = if (target.isAllDay) ALLDAY_REMINDER_PRESETS else REMINDER_PRESETS,
            selected = map.reminderOverrideFor(target.calendarId),
            allowInherit = true,
            onSelect = {
                if (target.isAllDay) {
                    viewModel.setCalendarAllDayReminderOverride(target.calendarId, it)
                } else {
                    viewModel.setCalendarReminderOverride(target.calendarId, it)
                }
            },
            onDismiss = { overrideDialog = null },
            leadTimeSummary = if (target.isAllDay) {
                allDayFiringTimeSummary(state.allDayReminderTimeMinutes)
            } else {
                null
            },
        )
    }
}

/**
 * One calendar's expanded rows: its notification switch and channel deep-link
 * for every calendar, then the default-reminder overrides where it takes them —
 * or, for a special-dates calendar, a link to the section that owns them.
 */
@Composable
private fun CalendarNotificationRows(
    calendar: CalendarSource,
    state: SettingsUiState,
    notifying: Boolean,
    onSetNotifying: (Boolean) -> Unit,
    onOpenSound: () -> Unit,
    onOpenSpecialDates: () -> Unit,
    onEditOverride: (OverrideTarget) -> Unit,
    onEditFixedOverride: () -> Unit,
    onSetInDigest: (Boolean) -> Unit,
) {
    val managed = calendar.id in state.managedCalendarIds
    val takesOverrides = calendar.canModifyContents && !managed
    val digestOn = state.dailyDigest.enabled
    Column {
        GroupedSwitchRow(
            title = stringResource(R.string.settings_calendar_notifications),
            checked = notifying,
            onCheckedChange = onSetNotifying,
            summary = stringResource(R.string.settings_calendar_notifications_hint),
            position = Position.Middle,
        )
        GroupedRow(
            title = stringResource(R.string.settings_notification_sound),
            summary = stringResource(R.string.settings_calendar_notification_sound_hint),
            position = if (managed || takesOverrides || digestOn) Position.Middle else Position.Bottom,
            trailing = { ExternalLinkIcon() },
            onClick = onOpenSound,
        )
        if (digestOn) {
            GroupedSwitchRow(
                title = stringResource(R.string.settings_calendar_in_digest),
                checked = calendar.id !in state.dailyDigest.excludedCalendarIds,
                onCheckedChange = onSetInDigest,
                summary = stringResource(R.string.settings_calendar_in_digest_hint),
                position = if (managed || takesOverrides) Position.Middle else Position.Bottom,
            )
        }
        if (managed) {
            GroupedRow(
                title = stringResource(R.string.settings_default_reminder_allday),
                summary = stringResource(R.string.settings_calendar_reminders_managed_hint),
                position = Position.Bottom,
                trailing = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenSpecialDates,
            )
        } else if (takesOverrides) {
            val timed = state.perCalendarReminderOverride.reminderOverrideFor(calendar.id)
            GroupedRow(
                title = stringResource(R.string.settings_default_reminder),
                summary = calendarOverrideSummary(timed, state.defaultReminderMinutes),
                position = Position.Middle,
                onClick = { onEditOverride(OverrideTarget(calendar.id, isAllDay = false)) },
            )
            GroupedRow(
                title = stringResource(R.string.settings_fixed_reminder),
                summary = when (val fixed = state.perCalendarFixedTimeReminderOverride.fixedTimeOverrideFor(calendar.id)) {
                    FixedTimeOverride.Inherit -> stringResource(
                        R.string.settings_calendar_reminder_inherits,
                        fixedReminderChoiceLabel(state.defaultFixedTimeReminder),
                    )
                    FixedTimeOverride.Off -> stringResource(R.string.reminder_none)
                    is FixedTimeOverride.At -> fixedTimeReminderLabel(fixed.reminder)
                },
                position = Position.Middle,
                onClick = onEditFixedOverride,
            )
            val allDay = state.perCalendarAllDayReminderOverride.reminderOverrideFor(calendar.id)
            GroupedRow(
                title = stringResource(R.string.settings_default_reminder_allday),
                summary = calendarOverrideSummary(allDay, state.defaultAllDayReminderMinutes),
                position = Position.Bottom,
                onClick = { onEditOverride(OverrideTarget(calendar.id, isAllDay = true)) },
            )
        }
    }
}

/**
 * The daily summary (#459): its switch, and once on, which day it lists, when,
 * and its channel's system settings.
 */
@Composable
private fun DailyDigestRows(
    digest: DailyDigestSettings,
    onEnable: (Boolean) -> Unit,
    onPickDay: () -> Unit,
    onPickTime: () -> Unit,
    onOpenSound: () -> Unit,
) {
    GroupedSwitchRow(
        title = stringResource(R.string.settings_digest),
        checked = digest.enabled,
        onCheckedChange = onEnable,
        summary = if (digest.enabled) {
            stringResource(
                R.string.settings_digest_summary,
                stringResource(digestDayLabel(digest.day)),
                settingsTimeOfDay(digest.timeMinutes),
            )
        } else {
            stringResource(R.string.settings_digest_hint)
        },
        position = if (digest.enabled) Position.Top else Position.Alone,
    )
    AnimatedVisibility(visible = digest.enabled, enter = expandEnter(), exit = collapseExit()) {
        Column {
            GroupedRow(
                title = stringResource(R.string.settings_digest_day),
                summary = stringResource(digestDayLabel(digest.day)),
                position = Position.Middle,
                onClick = onPickDay,
            )
            GroupedRow(
                title = stringResource(R.string.settings_digest_time),
                summary = settingsTimeOfDay(digest.timeMinutes),
                position = Position.Middle,
                onClick = onPickTime,
            )
            GroupedRow(
                title = stringResource(R.string.settings_notification_sound),
                summary = stringResource(R.string.settings_digest_sound_hint),
                position = Position.Bottom,
                trailing = { ExternalLinkIcon() },
                onClick = onOpenSound,
            )
        }
    }
}

private fun digestDayLabel(day: DigestDay): Int = when (day) {
    DigestDay.Today -> R.string.settings_digest_day_today
    DigestDay.Tomorrow -> R.string.settings_digest_day_tomorrow
}

/** A fixed-time default's label, or "None". */
@Composable
private fun fixedReminderChoiceLabel(reminder: FixedTimeReminder?): String =
    reminder?.let { fixedTimeReminderLabel(it) } ?: stringResource(R.string.reminder_none)

@Composable
private fun ExternalLinkIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Which calendar + event kind a per-calendar reminder-override dialog targets. */
private data class OverrideTarget(val calendarId: Long, val isAllDay: Boolean)

/** A global default (empty = none) as a picker choice for selection highlighting. */
private fun List<Int>.toReminderChoice(): ReminderOverride =
    if (isEmpty()) ReminderOverride.None else ReminderOverride.Minutes(this)

/** Row summary for a calendar: its override, or the inherited global default. */
@Composable
private fun calendarOverrideSummary(
    choice: ReminderOverride,
    globalDefault: List<Int>,
): String = when (choice) {
    ReminderOverride.Inherit ->
        stringResource(R.string.settings_calendar_reminder_inherits, reminderChoiceLabel(globalDefault))
    ReminderOverride.None -> stringResource(R.string.reminder_none)
    is ReminderOverride.Minutes -> reminderChoiceLabel(choice.minutes)
}

/** Snooze delays offered for the notification "Snooze" action, in minutes. */
private val SNOOZE_PRESETS = listOf(5, 10, 15, 30, 60)

/**
 * Whether Calendula is exempt from battery optimisation, re-read on every
 * `ON_RESUME` so a change made in system settings shows up at once.
 */
@Composable
private fun rememberBatteryOptimizationExempt(): Boolean {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Open the direct `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog, falling back to
 * the optimisation list if the OS refuses it.
 */
private fun openBatteryOptimizationSettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    )
    if (runCatching { context.startActivity(direct) }.isFailure) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
