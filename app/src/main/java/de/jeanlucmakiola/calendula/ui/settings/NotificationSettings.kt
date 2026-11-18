package de.jeanlucmakiola.calendula.ui.settings

import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.reminders.DailyDigestSettings
import de.jeanlucmakiola.calendula.domain.reminders.DigestDay
import de.jeanlucmakiola.calendula.data.prefs.FixedTimeOverride
import de.jeanlucmakiola.calendula.domain.reminders.FixedTimeReminder
import de.jeanlucmakiola.calendula.ui.common.FixedTimeReminderPicker
import de.jeanlucmakiola.calendula.ui.common.fixedTimeReminderLabel
import de.jeanlucmakiola.calendula.ui.common.DurationPicker
import de.jeanlucmakiola.calendula.ui.common.REMINDER_PRESETS
import de.jeanlucmakiola.calendula.ui.common.ReminderDefaultPicker
import de.jeanlucmakiola.calendula.ui.common.TimePickerAlert
import de.jeanlucmakiola.calendula.ui.common.durationLabel
import de.jeanlucmakiola.floret.android.hasPermission
import de.jeanlucmakiola.floret.android.notificationSoundPickerIntent
import de.jeanlucmakiola.floret.android.openNotificationSettings
import de.jeanlucmakiola.floret.android.pickedSound
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.IconInfoCard
import de.jeanlucmakiola.floret.components.OptionPicker
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
    // Turning either kind of notification on asks for the permission if missing.
    fun askingPermission(set: (Boolean) -> Unit): (Boolean) -> Unit = { enabled ->
        set(enabled)
        val needsPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (needsPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val toggleReminders = askingPermission(viewModel::setRemindersEnabled)
    val toggleDigest = askingPermission(viewModel::setDailyDigestEnabled)

    var showDefaultReminder by remember { mutableStateOf(false) }
    var showAllDayReminder by remember { mutableStateOf(false) }
    var showAllDayReminderTime by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    var showFixedDefault by remember { mutableStateOf(false) }
    var showDigestDay by remember { mutableStateOf(false) }
    var showDigestTime by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val channelStatus by viewModel.channelStatus.collectAsStateWithLifecycle()
    val digestBlocked by viewModel.digestBlocked.collectAsStateWithLifecycle()
    val calendarIds = state.reminderCalendars.map { it.id }
    LifecycleResumeEffect(calendarIds) {
        viewModel.refreshChannelStatus(calendarIds)
        onPauseOrDispose { }
    }
    val soundPicker = rememberReminderSoundPicker(viewModel, calendarIds)
    val openAppSettings = soundPicker.openAppSettings
    val loadedSounds = soundPicker.loadedSounds
    val pickSound = soundPicker.pick

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
                    openAppSettings()
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
        if (calendarIds.isNotEmpty()) {
            GroupedRow(
                title = stringResource(R.string.settings_notification_sound),
                summary = loadedSounds?.let { globalSoundLabel(it, calendarIds) },
                position = Position.Middle,
                onClick = { pickSound(ALL_CALENDARS) },
            )
        }
        GroupedRow(
            title = stringResource(R.string.settings_notification_more),
            summary = if (channelStatus.appBlocked) {
                stringResource(R.string.settings_notifications_blocked_hint)
            } else {
                stringResource(R.string.settings_notification_sound_hint)
            },
            position = Position.Middle,
            trailing = { ExternalLinkIcon() },
            onClick = openAppSettings,
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
            blocked = channelStatus.appBlocked || digestBlocked,
            onEnable = toggleDigest,
            onPickDay = { showDigestDay = true },
            onPickTime = { showDigestTime = true },
            onOpenSound = {
                // The app's own page while the app is blocked: the channel's would be greyed out.
                scope.launch {
                    context.openNotificationSettings(viewModel.digestChannel().takeUnless { channelStatus.appBlocked })
                }
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

    if (showFixedDefault) {
        FixedTimeReminderPicker(
            title = stringResource(R.string.settings_fixed_reminder),
            selected = state.defaultFixedTimeReminder?.let { FixedTimeOverride.At(it) } ?: FixedTimeOverride.Off,
            onSelect = { viewModel.setDefaultFixedTimeReminder((it as? FixedTimeOverride.At)?.reminder) },
            onDismiss = { showFixedDefault = false },
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
}

/**
 * The daily summary (#459): its switch, and once on, which day it lists, when,
 * and its channel's system settings.
 */
@Composable
private fun DailyDigestRows(
    digest: DailyDigestSettings,
    blocked: Boolean,
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
                summary = if (blocked) {
                    stringResource(R.string.settings_notifications_blocked_hint)
                } else {
                    stringResource(R.string.settings_digest_sound_hint)
                },
                position = Position.Bottom,
                trailing = { ExternalLinkIcon() },
                onClick = onOpenSound,
            )
        }
    }
}

/** [soundTarget] meaning every calendar at once. */
internal const val ALL_CALENDARS = Long.MIN_VALUE

/** What the calendars that have a channel share, sound-wise. */
private sealed interface SharedSound {
    data object None : SharedSound
    data object Mixed : SharedSound
    data class Same(val sound: ReminderSound) : SharedSound
}

private fun sharedSound(sounds: Map<Long, ReminderSound>, calendarIds: List<Long>): SharedSound {
    val distinct = calendarIds.mapNotNull { sounds[it] }.distinctBy { it.uri }
    return when (distinct.size) {
        0 -> SharedSound.None
        1 -> SharedSound.Same(distinct.single())
        else -> SharedSound.Mixed
    }
}

@Composable
internal fun soundLabel(sound: ReminderSound?): String = when {
    sound == null -> stringResource(R.string.settings_notification_sound_default)
    sound.uri == null -> stringResource(R.string.settings_notification_sound_silent)
    sound.uri == DEFAULT_NOTIFICATION_URI -> sound.title
        ?.let { stringResource(R.string.settings_notification_sound_default_named, it) }
        ?: stringResource(R.string.settings_notification_sound_default)
    else -> sound.title ?: stringResource(R.string.settings_notification_sound_custom)
}

@Composable
private fun globalSoundLabel(sounds: Map<Long, ReminderSound>, calendarIds: List<Long>): String =
    when (val shared = sharedSound(sounds, calendarIds)) {
        SharedSound.None -> soundLabel(null)
        SharedSound.Mixed -> stringResource(R.string.settings_notification_sound_mixed)
        is SharedSound.Same -> soundLabel(shared.sound)
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

private fun digestDayLabel(day: DigestDay): Int = when (day) {
    DigestDay.Today -> R.string.settings_digest_day_today
    DigestDay.Tomorrow -> R.string.settings_digest_day_tomorrow
}

/** A fixed-time default's label, or "None". */
@Composable
internal fun fixedReminderChoiceLabel(reminder: FixedTimeReminder?): String =
    reminder?.let { fixedTimeReminderLabel(it) } ?: stringResource(R.string.reminder_none)

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
private fun rememberBatteryOptimizationExempt(): Boolean = rememberOnResume(::isIgnoringBatteryOptimizations)

/** [read], re-read on every `ON_RESUME` so a change made in system settings shows up at once. */
@Composable
internal fun rememberOnResume(read: (Context) -> Boolean): Boolean {
    val context = LocalContext.current
    var value by remember { mutableStateOf(read(context)) }
    LifecycleResumeEffect(context) {
        value = read(context)
        onPauseOrDispose {}
    }
    return value
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

/** The reminder-sound picker (#518) shared by Notifications (all calendars) and the per-calendar page. */
internal class ReminderSoundPicker(
    val loadedSounds: Map<Long, ReminderSound>?,
    val pick: (Long) -> Unit,
    val openAppSettings: () -> Unit,
    val openCalendarSettings: (Long) -> Unit,
)

@Composable
internal fun rememberReminderSoundPicker(viewModel: SettingsViewModel, calendarIds: List<Long>): ReminderSoundPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openCalendarSettings: (Long) -> Unit = { calendarId ->
        scope.launch { context.openNotificationSettings(viewModel.soundSettingsChannel(calendarId)) }
    }
    val openAppSettings: () -> Unit = {
        scope.launch {
            viewModel.syncReminderChannels()
            context.openNotificationSettings()
        }
    }
    val loadedSounds by viewModel.channelSounds.collectAsStateWithLifecycle()
    val sounds = loadedSounds.orEmpty()
    val soundFailedMessage = stringResource(R.string.settings_notification_sound_failed)
    val soundUnreadableMessage = stringResource(R.string.settings_notification_sound_unreadable)
    LaunchedEffect(Unit) {
        viewModel.soundFailed.collect { failure ->
            val message = if (failure == SoundFailure.Unreadable) soundUnreadableMessage else soundFailedMessage
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    // ALL_CALENDARS, a calendar id, or null when no pick is pending.
    var soundTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = soundTarget
        soundTarget = null
        val picked = pickedSound(result.resultCode, result.data) ?: return@rememberLauncherForActivityResult
        when (target) {
            null -> Unit
            ALL_CALENDARS -> viewModel.setReminderSoundForAll(picked.uri)
            else -> viewModel.setReminderSound(target, picked.uri)
        }
    }
    val pickSoundTitle = stringResource(R.string.settings_notification_sound)
    val pickSound: (Long) -> Unit = pick@{ target ->
        if (soundTarget != null) return@pick
        val current = if (target == ALL_CALENDARS) {
            (sharedSound(sounds, calendarIds) as? SharedSound.Same)?.sound
        } else {
            sounds[target]
        }
        soundTarget = target
        try {
            // A missing or mixed sound preselects Default; a silent one, Silent.
            val preselect = if (current == null) DEFAULT_NOTIFICATION_URI else current.uri
            soundPicker.launch(notificationSoundPickerIntent(preselect, pickSoundTitle))
        } catch (e: RuntimeException) {
            // No picker, or one that refuses to start: the system screen still has the sound.
            soundTarget = null
            if (target == ALL_CALENDARS) openAppSettings() else openCalendarSettings(target)
        }
    }
    return ReminderSoundPicker(loadedSounds, pickSound, openAppSettings, openCalendarSettings)
}
