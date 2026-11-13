package de.jeanlucmakiola.calendula.data.reminders

import de.jeanlucmakiola.calendula.domain.CalendarSource

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
 * One account's channel group. The app's own device calendars share a single
 * group; [ambiguous] marks a name another group shows under a different type,
 * so the label can name the source app as well.
 */
data class ReminderChannelGroupSpec(
    val id: String,
    val accountName: String,
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

data class ReminderChannelPlan(
    val groups: List<ReminderChannelGroupSpec>,
    /** Every channel that should exist; creating an existing one only refreshes its name. */
    val channels: List<ReminderChannelSpec>,
    val staleChannelIds: List<String>,
    val staleGroupIds: List<String>,
)

/**
 * What the reminder channels should look like for [calendars]: one channel per
 * calendar inside a group per account, and the reminder channels and groups in
 * [existingChannelIds] / [existingGroupIds] that no calendar claims any more.
 * Ids outside the reminder namespace (the backup channel, the legacy channel)
 * are never touched.
 */
fun planReminderChannels(
    calendars: List<CalendarSource>,
    existingChannelIds: Collection<String>,
    existingGroupIds: Collection<String>,
): ReminderChannelPlan {
    val groupsByKey = calendars.groupBy(::accountKey)
    val nameCounts = groupsByKey.keys.filterNotNull().groupingBy { it.first }.eachCount()
    val groups = groupsByKey.map { (key, members) ->
        if (key == null) {
            ReminderChannelGroupSpec(
                id = LOCAL_GROUP_ID,
                accountName = members.first().accountName,
                accountType = members.first().accountType,
                isLocal = true,
                ambiguous = false,
            )
        } else {
            val (name, type) = key
            ReminderChannelGroupSpec(
                id = "$GROUP_PREFIX$type/$name",
                accountName = name,
                accountType = type,
                isLocal = false,
                ambiguous = nameCounts.getValue(name) > 1,
            )
        }
    }
    val groupIdByKey = groupsByKey.keys.zip(groups.map { it.id }).toMap()
    val channels = calendars.map { calendar ->
        ReminderChannelSpec(
            id = reminderChannelId(calendar.id),
            calendarId = calendar.id,
            name = calendar.displayName,
            groupId = groupIdByKey.getValue(accountKey(calendar)),
        )
    }
    val wantedChannels = channels.mapTo(HashSet()) { it.id }
    val wantedGroups = groups.mapTo(HashSet()) { it.id }
    return ReminderChannelPlan(
        groups = groups,
        channels = channels,
        staleChannelIds = existingChannelIds.filter { isReminderChannelId(it) && it !in wantedChannels },
        staleGroupIds = existingGroupIds.filter { isReminderGroupId(it) && it !in wantedGroups },
    )
}

/** The app's own device calendars share one group (null); every other account has its own. */
private fun accountKey(calendar: CalendarSource): Pair<String, String>? =
    if (calendar.isLocal) null else calendar.accountName to calendar.accountType
