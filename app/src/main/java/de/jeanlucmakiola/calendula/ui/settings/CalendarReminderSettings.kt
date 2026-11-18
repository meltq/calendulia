package de.jeanlucmakiola.calendula.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.reminders.RING_CHANNEL_ID
import de.jeanlucmakiola.calendula.data.prefs.FixedTimeOverride
import de.jeanlucmakiola.calendula.data.prefs.fixedTimeOverrideFor
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.reminders.ReminderAlertStyle
import de.jeanlucmakiola.calendula.domain.reminders.alertStyleFor
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.FixedTimeReminderPicker
import de.jeanlucmakiola.calendula.ui.common.fixedTimeReminderLabel
import de.jeanlucmakiola.calendula.ui.common.REMINDER_PRESETS
import de.jeanlucmakiola.calendula.ui.common.ReminderDefaultPicker
import de.jeanlucmakiola.calendula.ui.common.accountGroupTitle
import de.jeanlucmakiola.calendula.ui.common.groupByAccount
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.android.openNotificationSettings
import de.jeanlucmakiola.floret.components.ExpandIcon
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.reminderOverrideFor
import de.jeanlucmakiola.floret.ring.canUseFullScreenIntent
import de.jeanlucmakiola.floret.ring.openFullScreenIntentSettings

/**
 * Per-calendar notifications and reminder overrides (#145, #272), reached from
 * Settings → Notifications. Calendars are listed by account as connected groups;
 * a calendar unfolds its options in place, one tone darker, inside its group.
 */
@Composable
internal fun CalendarRemindersScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSpecialDates: () -> Unit,
    visible: Boolean = true,
) {
    var expandedCalendars by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var overrideDialog by remember { mutableStateOf<OverrideTarget?>(null) }
    var alertStylePicker by remember { mutableStateOf<Long?>(null) }
    var alertSoundPicker by remember { mutableStateOf<Long?>(null) }
    val canFullScreen = rememberCanUseFullScreenIntent()
    val context = LocalContext.current
    var fixedOverrideFor by remember { mutableStateOf<Long?>(null) }
    val channelStatus by viewModel.channelStatus.collectAsStateWithLifecycle()
    val calendarIds = state.reminderCalendars.map { it.id }
    val soundPicker = rememberReminderSoundPicker(viewModel, calendarIds)
    LifecycleResumeEffect(calendarIds) {
        viewModel.refreshChannelStatus(calendarIds)
        onPauseOrDispose { }
    }

    CollapsingScaffold(
        title = stringResource(R.string.settings_calendar_reminders_title),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        GroupedHint(stringResource(R.string.settings_calendar_reminders_hint), style = MaterialTheme.typography.bodySmall)
        state.reminderCalendars.groupByAccount().forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) Spacer(Modifier.height(8.dp))
            GroupedSectionHeader(accountGroupTitle(group))
            val calendars = group.calendars
            calendars.forEachIndexed { index, calendar ->
                val expanded = calendar.id in expandedCalendars
                val notifying = calendar.id !in state.mutedReminderCalendarIds
                val blocked = notifying && calendar.id in channelStatus.blockedCalendarIds
                CalendarReminderEntry(
                    calendar = calendar,
                    state = state,
                    position = positionOf(index, calendars.size),
                    expanded = expanded,
                    notifying = notifying,
                    blocked = blocked,
                    heldBack = fullScreenHeldBack(
                        style = state.reminderAlertStyles.alertStyleFor(calendar.id),
                        notifying = notifying,
                        blocked = blocked,
                        ringBlocked = channelStatus.ringBlocked,
                        canFullScreen = canFullScreen,
                    ),
                    onToggleExpand = {
                        expandedCalendars = if (expanded) {
                            expandedCalendars - calendar.id
                        } else {
                            expandedCalendars + calendar.id
                        }
                    },
                    onSetNotifying = { viewModel.setCalendarRemindersEnabled(calendar.id, it) },
                    soundLabel = soundPicker.loadedSounds?.let { soundLabel(it[calendar.id]) },
                    onPickSound = { soundPicker.pick(calendar.id) },
                    onOpenSettings = { soundPicker.openCalendarSettings(calendar.id) },
                    onOpenRingSettings = { context.openNotificationSettings(RING_CHANNEL_ID) },
                    onEditAlertStyle = { alertStylePicker = calendar.id },
                    onEditAlertSound = { alertSoundPicker = calendar.id },
                    onAllowFullScreen = { context.openFullScreenIntentSettings() },
                    onOpenSpecialDates = onOpenSpecialDates,
                    onEditOverride = { overrideDialog = it },
                    onEditFixedOverride = { fixedOverrideFor = calendar.id },
                    onSetInDigest = { viewModel.setCalendarInDailyDigest(calendar.id, it) },
                )
            }
        }
    }

    alertStylePicker?.let { calendarId ->
        val current = state.reminderAlertStyles.alertStyleFor(calendarId)
        OptionPicker(
            title = stringResource(R.string.settings_alert_style),
            options = listOf(ReminderAlertStyle.Notification, ReminderAlertStyle.FullScreen),
            selected = if (current.isFullScreen) ReminderAlertStyle.FullScreen else ReminderAlertStyle.Notification,
            label = { alertStyleLabel(it) },
            summary = {
                stringResource(
                    if (it.isFullScreen) {
                        R.string.settings_alert_style_full_screen_summary
                    } else {
                        R.string.settings_alert_style_notification_summary
                    },
                )
            },
            onSelect = { picked ->
                if (picked.isFullScreen != current.isFullScreen) {
                    viewModel.setCalendarAlertStyle(calendarId, picked)
                    // Guide the user straight to the switch Android 14+ may hold back.
                    if (picked.isFullScreen && !canFullScreen) context.openFullScreenIntentSettings()
                }
            },
            onDismiss = { alertStylePicker = null },
        )
    }
    alertSoundPicker?.let { calendarId ->
        OptionPicker(
            title = stringResource(R.string.settings_alert_sound),
            options = listOf(ReminderAlertStyle.FullScreen, ReminderAlertStyle.FullScreenRing),
            selected = state.reminderAlertStyles.alertStyleFor(calendarId),
            label = { alertSoundLabel(it) },
            summary = {
                stringResource(
                    if (it == ReminderAlertStyle.FullScreenRing) {
                        R.string.settings_alert_sound_ring_summary
                    } else {
                        R.string.settings_alert_sound_once_summary
                    },
                )
            },
            onSelect = { viewModel.setCalendarAlertStyle(calendarId, it) },
            onDismiss = { alertSoundPicker = null },
        )
    }
    fixedOverrideFor?.let { calendarId ->
        FixedTimeReminderPicker(
            title = stringResource(R.string.settings_fixed_reminder),
            selected = state.perCalendarFixedTimeReminderOverride.fixedTimeOverrideFor(calendarId),
            onSelect = { viewModel.setCalendarFixedTimeReminderOverride(calendarId, it) },
            onDismiss = { fixedOverrideFor = null },
            inheritedLabel = fixedReminderChoiceLabel(state.defaultFixedTimeReminder),
            inherited = state.defaultFixedTimeReminder,
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
 * One calendar in its account group, with its options unfolding beneath it:
 * the notification switch and channel deep-link for every calendar, then the
 * default-reminder overrides where it takes them — or, for a special-dates
 * calendar, a link to the section that owns them.
 */
@Composable
private fun CalendarReminderEntry(
    calendar: CalendarSource,
    state: SettingsUiState,
    position: Position,
    expanded: Boolean,
    notifying: Boolean,
    blocked: Boolean,
    heldBack: FullScreenHeldBack,
    onToggleExpand: () -> Unit,
    onSetNotifying: (Boolean) -> Unit,
    soundLabel: String?,
    onPickSound: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRingSettings: () -> Unit,
    onEditAlertStyle: () -> Unit,
    onEditAlertSound: () -> Unit,
    onAllowFullScreen: () -> Unit,
    onOpenSpecialDates: () -> Unit,
    onEditOverride: (OverrideTarget) -> Unit,
    onEditFixedOverride: () -> Unit,
    onSetInDigest: (Boolean) -> Unit,
) {
    val opensGroup = position == Position.Top || position == Position.Alone
    val closesGroup = position == Position.Bottom || position == Position.Alone
    GroupedRow(
        title = calendar.displayName.orUnnamed(),
        summary = when {
            // Still in the summary, so only its reminders are off.
            !notifying && state.dailyDigest.enabled && calendar.id !in state.dailyDigest.excludedCalendarIds ->
                stringResource(R.string.settings_calendar_reminders_off)
            !notifying -> stringResource(R.string.settings_calendar_notifications_off)
            blocked -> stringResource(R.string.settings_notifications_blocked)
            heldBack.any -> stringResource(R.string.settings_full_screen_held_back)
            else -> null
        },
        position = when {
            !expanded -> position
            opensGroup -> Position.Top
            else -> Position.Middle
        },
        leading = { CalendarColorChip(calendar.color) },
        trailing = { ExpandIcon(expanded) },
        onClick = onToggleExpand,
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandEnter(),
        exit = collapseExit(),
    ) {
        val managed = calendar.id in state.managedCalendarIds
        val takesOverrides = calendar.canModifyContents && !managed
        val alertStyle = state.reminderAlertStyles.alertStyleFor(calendar.id)
        val digestOn = state.dailyDigest.enabled
        val nested = MaterialTheme.colorScheme.surfaceContainerHighest
        // The options round off their calendar; mid-group they keep the row gap.
        Column(if (closesGroup) Modifier else Modifier.padding(bottom = 2.dp)) {
            GroupedSwitchRow(
                title = stringResource(R.string.settings_calendar_notifications),
                checked = notifying,
                onCheckedChange = onSetNotifying,
                summary = stringResource(R.string.settings_calendar_notifications_hint),
                position = Position.Middle,
                container = nested,
            )
            GroupedRow(
                title = stringResource(R.string.settings_notification_sound),
                summary = soundLabel,
                position = Position.Middle,
                container = nested,
                onClick = onPickSound,
            )
            GroupedRow(
                title = stringResource(R.string.settings_notification_more),
                summary = if (blocked) {
                    stringResource(R.string.settings_notifications_blocked_hint)
                } else {
                    stringResource(R.string.settings_calendar_notification_sound_hint)
                },
                position = Position.Middle,
                container = nested,
                trailing = { ExternalLinkIcon() },
                onClick = onOpenSettings,
            )
            val lastAlertRow = !(managed || takesOverrides || digestOn)
            GroupedRow(
                title = stringResource(R.string.settings_alert_style),
                summary = alertStyleLabel(alertStyle),
                position = if (lastAlertRow && !alertStyle.isFullScreen) Position.Bottom else Position.Middle,
                container = nested,
                onClick = onEditAlertStyle,
            )
            if (alertStyle.isFullScreen) {
                GroupedRow(
                    title = stringResource(R.string.settings_alert_sound),
                    summary = alertSoundLabel(alertStyle),
                    position = if (lastAlertRow && !heldBack.any) Position.Bottom else Position.Middle,
                    container = nested,
                    onClick = onEditAlertSound,
                )
            }
            if (heldBack.ring) {
                GroupedRow(
                    title = stringResource(R.string.reminder_ring_channel_name),
                    summary = stringResource(R.string.settings_ring_blocked_hint),
                    position = if (lastAlertRow && !heldBack.permission) Position.Bottom else Position.Middle,
                    container = nested,
                    trailing = { ExternalLinkIcon() },
                    onClick = onOpenRingSettings,
                )
            }
            if (heldBack.permission) {
                GroupedRow(
                    title = stringResource(R.string.settings_full_screen_permission),
                    summary = stringResource(R.string.settings_full_screen_permission_hint),
                    position = if (lastAlertRow) Position.Bottom else Position.Middle,
                    container = nested,
                    trailing = { ExternalLinkIcon() },
                    onClick = onAllowFullScreen,
                )
            }
            if (digestOn) {
                GroupedSwitchRow(
                    title = stringResource(R.string.settings_calendar_in_digest),
                    checked = calendar.id !in state.dailyDigest.excludedCalendarIds,
                    onCheckedChange = onSetInDigest,
                    summary = stringResource(R.string.settings_calendar_in_digest_hint),
                    position = if (managed || takesOverrides) Position.Middle else Position.Bottom,
                    container = nested,
                )
            }
            if (managed) {
                GroupedRow(
                    title = stringResource(R.string.settings_default_reminder_allday),
                    summary = stringResource(R.string.settings_calendar_reminders_managed_hint),
                    position = Position.Bottom,
                    container = nested,
                    trailing = { ChevronIcon() },
                    onClick = onOpenSpecialDates,
                )
            } else if (takesOverrides) {
                OverrideRow(
                    calendarId = calendar.id,
                    isAllDay = false,
                    choice = state.perCalendarReminderOverride.reminderOverrideFor(calendar.id),
                    globalDefault = state.defaultReminderMinutes,
                    position = Position.Middle,
                    container = nested,
                    onEditOverride = onEditOverride,
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
                    container = nested,
                    onClick = onEditFixedOverride,
                )
                OverrideRow(
                    calendarId = calendar.id,
                    isAllDay = true,
                    choice = state.perCalendarAllDayReminderOverride.reminderOverrideFor(calendar.id),
                    globalDefault = state.defaultAllDayReminderMinutes,
                    position = Position.Bottom,
                    container = nested,
                    onEditOverride = onEditOverride,
                )
            }
        }
    }
}

@Composable
private fun OverrideRow(
    calendarId: Long,
    isAllDay: Boolean,
    choice: ReminderOverride,
    globalDefault: List<Int>,
    position: Position,
    container: Color,
    onEditOverride: (OverrideTarget) -> Unit,
) {
    GroupedRow(
        title = stringResource(
            if (isAllDay) R.string.settings_default_reminder_allday else R.string.settings_default_reminder,
        ),
        summary = calendarOverrideSummary(choice, globalDefault),
        position = position,
        container = container,
        onClick = { onEditOverride(OverrideTarget(calendarId, isAllDay)) },
    )
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Which calendar + event kind a per-calendar reminder-override dialog targets. */
private data class OverrideTarget(val calendarId: Long, val isAllDay: Boolean)

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

/** What keeps a full-screen calendar from alerting as chosen: the missing permission, or the blocked ring channel. */
private data class FullScreenHeldBack(val permission: Boolean, val ring: Boolean) {
    val any: Boolean get() = permission || ring
}

private fun fullScreenHeldBack(
    style: ReminderAlertStyle,
    notifying: Boolean,
    blocked: Boolean,
    ringBlocked: Boolean,
    canFullScreen: Boolean,
): FullScreenHeldBack {
    val live = notifying && !blocked
    return FullScreenHeldBack(
        permission = live && style.isFullScreen && !canFullScreen,
        ring = live && style == ReminderAlertStyle.FullScreenRing && ringBlocked,
    )
}

@Composable
private fun alertStyleLabel(style: ReminderAlertStyle): String = stringResource(
    if (style.isFullScreen) R.string.settings_alert_style_full_screen else R.string.settings_alert_style_notification,
)

@Composable
private fun alertSoundLabel(style: ReminderAlertStyle): String = stringResource(
    if (style == ReminderAlertStyle.FullScreenRing) R.string.settings_alert_sound_ring else R.string.settings_alert_sound_once,
)

/** Whether Android lets full-screen alerts open, re-read on every `ON_RESUME`. */
@Composable
private fun rememberCanUseFullScreenIntent(): Boolean = rememberOnResume { it.canUseFullScreenIntent() }
