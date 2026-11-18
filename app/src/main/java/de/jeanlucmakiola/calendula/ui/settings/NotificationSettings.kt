package de.jeanlucmakiola.calendula.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.ui.common.DurationPicker
import de.jeanlucmakiola.calendula.ui.common.REMINDER_PRESETS
import de.jeanlucmakiola.calendula.ui.common.ReminderDefaultPicker
import de.jeanlucmakiola.calendula.ui.common.TimePickerAlert
import de.jeanlucmakiola.calendula.ui.common.durationLabel
import de.jeanlucmakiola.floret.android.hasPermission
import de.jeanlucmakiola.floret.android.openNotificationSettings
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.IconInfoCard
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.minutesOrEmpty
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
    onOpenCalendarReminders: () -> Unit,
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

    var showDefaultReminder by remember { mutableStateOf(false) }
    var showAllDayReminder by remember { mutableStateOf(false) }
    var showAllDayReminderTime by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val channelStatus by viewModel.channelStatus.collectAsStateWithLifecycle()
    val calendarIds = state.reminderCalendars.map { it.id }
    LifecycleResumeEffect(calendarIds) {
        viewModel.refreshChannelStatus(calendarIds)
        onPauseOrDispose { }
    }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_notifications),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        if (IS_SAMSUNG && !state.notificationCategoryHintDismissed) {
            NotificationCategoryHint(
                onOpen = {
                    viewModel.dismissNotificationCategoryHint()
                    scope.launch {
                        viewModel.syncReminderChannels()
                        context.openNotificationSettings()
                    }
                },
                onDismiss = viewModel::dismissNotificationCategoryHint,
            )
            Spacer(Modifier.height(16.dp))
        }
        GroupedSwitchRow(
            title = stringResource(R.string.settings_reminders),
            checked = state.remindersEnabled,
            onCheckedChange = toggleReminders,
            summary = stringResource(R.string.settings_reminders_hint),
            position = Position.Top,
        )
        GroupedRow(
            title = stringResource(R.string.settings_notification_sound),
            summary = if (channelStatus.appBlocked) {
                stringResource(R.string.settings_notifications_blocked_hint)
            } else {
                stringResource(R.string.settings_notification_sound_hint)
            },
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

        if (state.reminderCalendars.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            GroupedRow(
                title = stringResource(R.string.settings_calendar_reminders_title),
                summary = stringResource(R.string.settings_calendar_reminders_subtitle),
                position = Position.Alone,
                trailing = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenCalendarReminders,
            )
        }
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
}

/** Samsung's One UI can start a new notification category switched off (#145). */
private val IS_SAMSUNG = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/** The one-time Samsung note, with a way into the app's notification settings. */
@Composable
private fun NotificationCategoryHint(onOpen: () -> Unit, onDismiss: () -> Unit) {
    IconInfoCard(
        icon = Icons.Outlined.Info,
        iconContentDescription = null,
        iconAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(R.string.settings_notification_category_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reminder_action_dismiss)) }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.settings_notification_category_hint_open)) }
        }
    }
}

@Composable
internal fun ExternalLinkIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A global default (empty = none) as a picker choice for selection highlighting. */
private fun List<Int>.toReminderChoice(): ReminderOverride =
    if (isEmpty()) ReminderOverride.None else ReminderOverride.Minutes(this)

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
