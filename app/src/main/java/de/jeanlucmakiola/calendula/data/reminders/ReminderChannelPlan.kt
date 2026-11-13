package de.jeanlucmakiola.calendula.data.reminders

import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.ui.common.groupByAccount

/** The single channel every reminder posted on before they moved per calendar (#145). */
const val LEGACY_REMINDER_CHANNEL_ID = "reminders"

private const val CHANNEL_PREFIX = "reminders.calendar."
private const val GROUP_PREFIX = "reminders.account."
private const val LOCAL_GROUP_ID = GROUP_PREFIX + "local"

/** The notification channel [calendarId]'s reminders post on. */
fun reminderChannelId(calendarId: Long): String = CHANNEL_PREFIX + calendarId

/** Whether [channelId] is one of the per-calendar reminder channels. */
fun isReminderChannelId(channelId: String): Boolean = channelId.startsWith(CHANNEL_PREFIX)

/** Whether [groupId] is one of the per-account reminder channel groups. */
fun isReminderGroupId(groupId: String): Boolean = groupId.startsWith(GROUP_PREFIX)

/**
 * One account's channel group, labelled as the calendar manager labels it. The
 * app's own device calendars share a single group ([isLocal]); [ambiguous]
 * marks a [label] another group shows too, so it should name the source app.
 */
data class ReminderChannelGroupSpec(
    val id: String,
    val label: String,
    val accountType: String,
    val isLocal: Boolean,
    val ambiguous: Boolean,
)

data class ReminderChannelSpec(
    val id: String,
    val calendarId: Long,
    /** The calendar's display name; blank when it has none. */
    val name: String,
    val groupId: String,
)

/** What a channel already looks like in the system, as far as the plan compares it. */
data class ExistingReminderChannel(val id: String, val name: String)

data class ReminderChannelPlan(
    val groups: List<ReminderChannelGroupSpec>,
    val channels: List<ReminderChannelSpec>,
    /** The [channels] that are missing or whose name changed. */
    val channelsToWrite: List<ReminderChannelSpec>,
    val staleChannelIds: List<String>,
    val staleGroupIds: List<String>,
)

/**
 * What the reminder channels should look like for [calendars]: one channel per
 * calendar inside a group per account, which of them need writing, and the
 * reminder channels and groups in [existing] / [existingGroupIds] no calendar
 * claims any more. Ids outside the reminder namespace (the backup channel, the
 * legacy channel) are never touched.
 */
fun planReminderChannels(
    calendars: List<CalendarSource>,
    existing: Collection<ExistingReminderChannel>,
    existingGroupIds: Collection<String>,
    unnamed: String,
): ReminderChannelPlan {
    val (local, synced) = calendars.partition { it.isLocal }
    val groups = buildList {
        if (local.isNotEmpty()) {
            add(ReminderChannelGroupSpec(LOCAL_GROUP_ID, "", local.first().accountType, isLocal = true, ambiguous = false))
        }
        synced.groupByAccount().forEach { group ->
            add(
                ReminderChannelGroupSpec(
                    id = accountGroupId(group.key.type, group.key.name),
                    label = group.label,
                    accountType = group.accountType,
                    isLocal = false,
                    ambiguous = group.ambiguous,
                ),
            )
        }
    }
    val channels = calendars.map { calendar ->
        ReminderChannelSpec(
            id = reminderChannelId(calendar.id),
            calendarId = calendar.id,
            name = calendar.displayName.ifBlank { unnamed },
            groupId = if (calendar.isLocal) LOCAL_GROUP_ID else accountGroupId(calendar.accountType, calendar.accountName),
        )
    }
    val existingById = existing.associateBy { it.id }
    val wantedChannels = channels.mapTo(HashSet()) { it.id }
    val wantedGroups = groups.mapTo(HashSet()) { it.id }
    return ReminderChannelPlan(
        groups = groups,
        channels = channels,
        channelsToWrite = channels.filter { spec ->
            val current = existingById[spec.id]
            current == null || current.name != spec.name
        },
        staleChannelIds = existingById.keys.filter { isReminderChannelId(it) && it !in wantedChannels },
        staleGroupIds = existingGroupIds.filter { isReminderGroupId(it) && it !in wantedGroups },
    )
}

private fun accountGroupId(type: String, name: String): String = "$GROUP_PREFIX$type/$name"
