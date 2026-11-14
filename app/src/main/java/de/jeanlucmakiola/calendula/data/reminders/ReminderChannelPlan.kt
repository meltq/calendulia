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
    val name: String,
    /** Null only for a channel created before its calendar could be looked up. */
    val groupId: String?,
    /**
     * Whether a sync creates it up front. A calendar that syncs no events never
     * fires, so its channel only appears once something posts on it.
     */
    val upFront: Boolean = true,
)

/** What a channel already looks like in the system, as far as the plan compares it. */
data class ExistingReminderChannel(val id: String, val name: String, val description: String?, val groupId: String?)

data class ReminderChannelPlan(
    val groups: List<ReminderChannelGroupSpec>,
    val channels: List<ReminderChannelSpec>,
    /**
     * The [channels] a sync writes: missing up-front ones, and existing ones
     * whose name or (localized) description changed, or that still lack a group.
     */
    val channelsToWrite: List<ReminderChannelSpec>,
    /** The [groups] holding a channel that exists or is about to be written. */
    val groupsInUse: List<ReminderChannelGroupSpec>,
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
    description: String,
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
            upFront = calendar.isLocal || calendar.syncsEvents,
        )
    }
    val existingById = existing.associateBy { it.id }
    val wantedChannels = channels.mapTo(HashSet()) { it.id }
    val wantedGroups = groups.mapTo(HashSet()) { it.id }
    val toWrite = channels.filter { spec ->
        val current = existingById[spec.id]
        if (current == null) {
            spec.upFront
        } else {
            current.name != spec.name || current.description != description ||
                (current.groupId == null && spec.groupId != null)
        }
    }
    val usedGroupIds = channels
        .filter { it.id in existingById || it in toWrite }
        .mapNotNullTo(HashSet()) { it.groupId }
    return ReminderChannelPlan(
        groups = groups,
        channels = channels,
        channelsToWrite = toWrite,
        groupsInUse = groups.filter { it.id in usedGroupIds },
        staleChannelIds = existingById.keys.filter { isReminderChannelId(it) && it !in wantedChannels },
        staleGroupIds = existingGroupIds.filter { isReminderGroupId(it) && it !in wantedGroups },
    )
}

private fun accountGroupId(type: String, name: String): String = "$GROUP_PREFIX$type/$name"

/** A channel as far as its blocked state goes: switched off itself, and the group it sits in. */
data class ReminderChannelState(val blocked: Boolean, val groupId: String?)

/** Reminder notifications the system blocks, while Calendula would post them. */
data class ReminderChannelStatus(
    val appBlocked: Boolean = false,
    val blockedCalendarIds: Set<Long> = emptySet(),
    /** The blocked ones whose whole account group is off, which only the app's own screen can undo. */
    val groupBlockedCalendarIds: Set<Long> = emptySet(),
)

/**
 * What the system blocks of [calendarIds]' reminders: all of them while the
 * app's notifications are off, otherwise those whose channel, or the group it
 * sits in, is switched off. A calendar without a channel yet isn't blocked.
 */
fun reminderChannelStatus(
    calendarIds: Collection<Long>,
    appEnabled: Boolean,
    channels: Map<String, ReminderChannelState>,
    blockedGroupIds: Set<String>,
): ReminderChannelStatus {
    val groupBlocked = calendarIds.filterTo(HashSet()) { channels[reminderChannelId(it)]?.groupId in blockedGroupIds }
    val blocked = if (!appEnabled) {
        calendarIds.toSet()
    } else {
        calendarIds.filterTo(HashSet()) { channels[reminderChannelId(it)]?.blocked == true } + groupBlocked
    }
    return ReminderChannelStatus(appBlocked = !appEnabled, blockedCalendarIds = blocked, groupBlockedCalendarIds = groupBlocked)
}
