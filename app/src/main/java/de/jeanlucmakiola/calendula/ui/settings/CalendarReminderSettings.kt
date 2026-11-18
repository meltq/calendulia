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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.ui.common.CalendarColorChip
import de.jeanlucmakiola.calendula.ui.common.REMINDER_PRESETS
import de.jeanlucmakiola.calendula.ui.common.ReminderDefaultPicker
import de.jeanlucmakiola.calendula.ui.common.accountGroupTitle
import de.jeanlucmakiola.calendula.ui.common.groupByAccount
import de.jeanlucmakiola.calendula.ui.common.orUnnamed
import de.jeanlucmakiola.floret.android.openNotificationSettings
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.ExpandIcon
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.GroupedSwitchRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.reminderOverrideFor
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedCalendars by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var overrideDialog by remember { mutableStateOf<OverrideTarget?>(null) }
    val channelStatus by viewModel.channelStatus.collectAsStateWithLifecycle()
    val calendarIds = state.reminderCalendars.map { it.id }
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
                CalendarReminderEntry(
                    calendar = calendar,
                    state = state,
                    position = positionOf(index, calendars.size),
                    expanded = expanded,
                    notifying = notifying,
                    blocked = notifying && calendar.id in channelStatus.blockedCalendarIds,
                    onToggleExpand = {
                        expandedCalendars = if (expanded) {
                            expandedCalendars - calendar.id
                        } else {
                            expandedCalendars + calendar.id
                        }
                    },
                    onSetNotifying = { viewModel.setCalendarRemindersEnabled(calendar.id, it) },
                    onOpenSound = {
                        scope.launch {
                            context.openNotificationSettings(viewModel.soundSettingsChannel(calendar.id))
                        }
                    },
                    onOpenSpecialDates = onOpenSpecialDates,
                    onEditOverride = { overrideDialog = it },
                )
            }
        }
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
    onToggleExpand: () -> Unit,
    onSetNotifying: (Boolean) -> Unit,
    onOpenSound: () -> Unit,
    onOpenSpecialDates: () -> Unit,
    onEditOverride: (OverrideTarget) -> Unit,
) {
    val opensGroup = position == Position.Top || position == Position.Alone
    val closesGroup = position == Position.Bottom || position == Position.Alone
    GroupedRow(
        title = calendar.displayName.orUnnamed(),
        summary = when {
            !notifying -> stringResource(R.string.settings_calendar_notifications_off)
            blocked -> stringResource(R.string.settings_notifications_blocked)
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
                summary = if (blocked) {
                    stringResource(R.string.settings_notifications_blocked_hint)
                } else {
                    stringResource(R.string.settings_calendar_notification_sound_hint)
                },
                position = if (managed || takesOverrides) Position.Middle else Position.Bottom,
                container = nested,
                trailing = { ExternalLinkIcon() },
                onClick = onOpenSound,
            )
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
