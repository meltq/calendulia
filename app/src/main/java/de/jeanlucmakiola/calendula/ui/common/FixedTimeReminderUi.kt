package de.jeanlucmakiola.calendula.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.calendula.R
import de.jeanlucmakiola.calendula.data.prefs.FixedTimeOverride
import de.jeanlucmakiola.calendula.domain.reminders.FixedTimeReminder
import de.jeanlucmakiola.floret.components.CustomExpandRow
import de.jeanlucmakiola.floret.components.DialogAmountField
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedHint
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.locale.currentLocale
import kotlinx.datetime.LocalTime

/** "1 day before at 20:00", in the app's 12/24-hour convention. */
@Composable
fun fixedTimeReminderLabel(reminder: FixedTimeReminder): String = stringResource(
    R.string.reminder_fixed_time,
    pluralStringResource(R.plurals.reminder_days, reminder.daysBefore, reminder.daysBefore),
    formatMinuteOfDay(reminder.minuteOfDay, LocalUse24HourFormat.current, currentLocale()),
)

/**
 * The "N days before at HH:MM" editor (#459): a day count and a time, read as
 * one sentence, plus a confirm. Seeded from [initial]; [onConfirm] gets the
 * finished reminder. [connected] gives it the shape that meets a row above it.
 */
@Composable
fun FixedTimeReminderEditor(
    initial: FixedTimeReminder,
    confirmLabel: String,
    onConfirm: (FixedTimeReminder) -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
) {
    var daysText by rememberSaveable { mutableStateOf(initial.daysBefore.toString()) }
    var minuteOfDay by rememberSaveable { mutableStateOf(initial.minuteOfDay) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val days = daysText.toIntOrNull()?.takeIf { it in 1..FixedTimeReminder.MAX_DAYS_BEFORE }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = if (connected) {
            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
        } else {
            RoundedCornerShape(22.dp)
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DialogAmountField(value = daysText, onValueChange = { daysText = it }, placeholder = "1")
                Spacer(Modifier.width(12.dp))
                Text(
                    text = pluralStringResource(R.plurals.reminder_fixed_days_before_at, days ?: 1),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = { pickingTime = true }) {
                    Text(formatMinuteOfDay(minuteOfDay, LocalUse24HourFormat.current, currentLocale()))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = days?.let { fixedTimeReminderLabel(FixedTimeReminder(it, minuteOfDay)) }
                        ?: stringResource(R.string.reminder_fixed_days_range, FixedTimeReminder.MAX_DAYS_BEFORE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(
                    enabled = days != null,
                    onClick = { days?.let { onConfirm(FixedTimeReminder(it, minuteOfDay)) } },
                ) { Text(confirmLabel) }
            }
        }
    }
    if (pickingTime) {
        TimePickerAlert(
            initial = LocalTime(minuteOfDay / 60, minuteOfDay % 60),
            onConfirm = {
                minuteOfDay = it.hour * 60 + it.minute
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * Picker for a fixed-time default (#459), full-screen: off, or a reminder at a
 * set time edited in place — and, on a per-calendar picker ([inheritedLabel]
 * non-null), deferring to the global default. Applies live; the user leaves via
 * back.
 */
@Composable
fun FixedTimeReminderPicker(
    title: String,
    selected: FixedTimeOverride,
    onSelect: (FixedTimeOverride) -> Unit,
    onDismiss: () -> Unit,
    inheritedLabel: String? = null,
) {
    var current by rememberSaveable(stateSaver = FixedTimeOverrideSaver) { mutableStateOf(selected) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // Mirrors [selected] until the first pick, as ReminderDefaultPicker does.
    var userEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!userEdited) current = selected
    }
    fun apply(choice: FixedTimeOverride) {
        userEdited = true
        current = choice
        onSelect(choice)
    }
    val chosen = (current as? FixedTimeOverride.At)?.reminder

    FullScreenPicker(title = title, onDismiss = onDismiss) {
        GroupedHint(text = stringResource(R.string.settings_fixed_reminder_hint))
        Spacer(Modifier.height(8.dp))
        val rowCount = if (inheritedLabel != null) 3 else 2
        if (inheritedLabel != null) {
            GroupedRow(
                title = stringResource(R.string.reminder_use_default),
                summary = inheritedLabel,
                position = Position.Top,
                checked = current is FixedTimeOverride.Inherit,
                onClick = { apply(FixedTimeOverride.Inherit) },
            )
        }
        GroupedRow(
            title = stringResource(R.string.reminder_none),
            position = if (rowCount == 3) Position.Middle else Position.Top,
            checked = current is FixedTimeOverride.Off,
            onClick = { apply(FixedTimeOverride.Off) },
        )
        CustomExpandRow(
            title = stringResource(R.string.reminder_fixed_at_set_time),
            summary = chosen?.let { fixedTimeReminderLabel(it) },
            position = Position.Bottom,
            expanded = editing,
            onExpandedChange = { editing = it },
            checked = chosen != null,
        ) {
            FixedTimeReminderEditor(
                initial = chosen ?: DEFAULT_FIXED_TIME_REMINDER,
                confirmLabel = stringResource(R.string.reminder_custom_set),
                onConfirm = {
                    apply(FixedTimeOverride.At(it))
                    editing = false
                },
            )
        }
    }
}

/** What the editor offers first: the evening before. */
val DEFAULT_FIXED_TIME_REMINDER = FixedTimeReminder(daysBefore = 1, minuteOfDay = 20 * 60)

private val FixedTimeOverrideSaver = Saver<FixedTimeOverride, String>(
    save = {
        when (it) {
            FixedTimeOverride.Inherit -> "inherit"
            FixedTimeOverride.Off -> "off"
            is FixedTimeOverride.At -> it.reminder.toToken()
        }
    },
    restore = {
        when (it) {
            "inherit" -> FixedTimeOverride.Inherit
            "off" -> FixedTimeOverride.Off
            else -> FixedTimeReminder.parse(it)?.let(FixedTimeOverride::At) ?: FixedTimeOverride.Off
        }
    },
)
