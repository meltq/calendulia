package de.jeanlucmakiola.calendula.data.reminders

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.domain.CalendarSource
import org.junit.jupiter.api.Test

class ReminderChannelPlanTest {

    private fun calendar(id: Long, account: String, type: String, local: Boolean = false) = CalendarSource(
        id = id,
        displayName = "Cal $id",
        accountName = account,
        accountType = type,
        color = 0,
        isVisibleInSystem = true,
        isLocal = local,
    )

    @Test
    fun `one channel per calendar, grouped by account`() {
        val plan = planReminderChannels(
            calendars = listOf(
                calendar(1, "me@example.org", "com.google"),
                calendar(2, "me@example.org", "com.google"),
                calendar(3, "dav", "bitfire.at.davdroid"),
            ),
            existing = emptyList(),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
        )
        assertThat(plan.groups).hasSize(2)
        assertThat(plan.channels.map { it.id })
            .containsExactly(reminderChannelId(1), reminderChannelId(2), reminderChannelId(3))
        assertThat(plan.channels[0].groupId).isEqualTo(plan.channels[1].groupId)
        assertThat(plan.channels[2].groupId).isNotEqualTo(plan.channels[0].groupId)
        assertThat(plan.staleChannelIds).isEmpty()
    }

    @Test
    fun `device calendars share one group`() {
        val plan = planReminderChannels(
            calendars = listOf(
                calendar(1, "Calendula", "LOCAL", local = true),
                calendar(2, "Other", "LOCAL", local = true),
            ),
            existing = emptyList(),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
        )
        assertThat(plan.groups).hasSize(1)
        assertThat(plan.groups.single().isLocal).isTrue()
    }

    @Test
    fun `same account name under two types is ambiguous`() {
        val plan = planReminderChannels(
            calendars = listOf(
                calendar(1, "me@example.org", "com.google"),
                calendar(2, "me@example.org", "bitfire.at.davdroid"),
                calendar(3, "work", "bitfire.at.davdroid"),
            ),
            existing = emptyList(),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
        )
        assertThat(plan.groups.filter { it.ambiguous }.map { it.accountType })
            .containsExactly("com.google", "bitfire.at.davdroid")
        assertThat(plan.groups.single { it.label == "work" }.ambiguous).isFalse()
    }

    @Test
    fun `stale reminder channels and groups go, foreign ones stay`() {
        val current = calendar(1, "me@example.org", "com.google")
        fun plan(calendars: List<CalendarSource>, existing: List<String> = emptyList(), groups: List<String> = emptyList()) =
            planReminderChannels(calendars, existing.map { ExistingReminderChannel(it, "Cal ${it.last()}", DESC, null) }, groups, "Unnamed", DESC)
        val keptGroup = plan(listOf(current)).groups.single().id
        val goneGroup = plan(listOf(calendar(9, "old", "com.google"))).groups.single().id
        val result = plan(
            calendars = listOf(current),
            existing = listOf(reminderChannelId(1), reminderChannelId(9), LEGACY_REMINDER_CHANNEL_ID, "backup"),
            groups = listOf(keptGroup, goneGroup, "other"),
        )
        assertThat(result.staleChannelIds).containsExactly(reminderChannelId(9))
        assertThat(result.staleGroupIds).containsExactly(goneGroup)
    }

    @Test
    fun `only missing or renamed channels are written`() {
        val plan = planReminderChannels(
            calendars = listOf(
                calendar(1, "a", "com.google"),
                calendar(2, "a", "com.google"),
                calendar(3, "a", "com.google").copy(displayName = ""),
                calendar(4, "a", "com.google"),
            ),
            existing = listOf(
                ExistingReminderChannel(reminderChannelId(1), "Cal 1", DESC, "g"),
                ExistingReminderChannel(reminderChannelId(4), "Cal 4", "Old description", "g"),
                ExistingReminderChannel(reminderChannelId(2), "Old name", DESC, "g"),
            ),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
        )
        assertThat(plan.channelsToWrite.map { it.calendarId }).containsExactly(2L, 3L, 4L)
        assertThat(plan.channelsToWrite.single { it.calendarId == 3L }.name).isEqualTo("Unnamed")
    }

    @Test
    fun `non-syncing calendars keep a channel but get none up front`() {
        val quiet = calendar(2, "a", "com.google").copy(syncsEvents = false)
        val fresh = planReminderChannels(listOf(quiet), emptyList(), emptyList(), "Unnamed", DESC)
        assertThat(fresh.channelsToWrite).isEmpty()
        assertThat(fresh.groupsInUse).isEmpty()
        val posted = planReminderChannels(
            calendars = listOf(quiet),
            existing = listOf(ExistingReminderChannel(reminderChannelId(2), "Cal 2", DESC, null)),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
        )
        assertThat(posted.staleChannelIds).isEmpty()
        assertThat(posted.channelsToWrite.map { it.calendarId }).containsExactly(2L)
        assertThat(posted.groupsInUse).hasSize(1)
    }

    @Test
    fun `blocked calendars follow app, channel and group`() {
        val channels = mapOf(
            reminderChannelId(1) to ReminderChannelState(blocked = true, groupId = "g1"),
            reminderChannelId(2) to ReminderChannelState(blocked = false, groupId = "g2"),
            reminderChannelId(3) to ReminderChannelState(blocked = false, groupId = "g1"),
        )
        val ids = listOf(1L, 2L, 3L, 4L)
        val groupOff = reminderChannelStatus(ids, appEnabled = true, channels, blockedGroupIds = setOf("g2"))
        assertThat(groupOff.blockedCalendarIds).containsExactly(1L, 2L)
        assertThat(groupOff.groupBlockedCalendarIds).containsExactly(2L)
        assertThat(reminderChannelStatus(ids, appEnabled = true, channels, blockedGroupIds = emptySet()).blockedCalendarIds)
            .containsExactly(1L)
        val appOff = reminderChannelStatus(ids, appEnabled = false, channels, blockedGroupIds = emptySet())
        assertThat(appOff.appBlocked).isTrue()
        assertThat(appOff.blockedCalendarIds).containsExactly(1L, 2L, 3L, 4L)
    }

    @Test
    fun `channel ids round-trip with versions`() {
        assertThat(parseReminderChannelId(reminderChannelId(7))).isEqualTo(7L to 0)
        assertThat(parseReminderChannelId(reminderChannelId(7, 3))).isEqualTo(7L to 3)
        assertThat(parseReminderChannelId("reminders.calendar.7.v0")).isNull()
        assertThat(parseReminderChannelId("reminders.calendar.x")).isNull()
        assertThat(parseReminderChannelId(LEGACY_REMINDER_CHANNEL_ID)).isNull()
    }

    @Test
    fun `a version bump retires the old channel unless it is showing`() {
        val existing = listOf(
            ExistingReminderChannel(reminderChannelId(1), "Cal 1", DESC, "g"),
            ExistingReminderChannel(reminderChannelId(2), "Cal 2", DESC, "g"),
        )
        val plan = planReminderChannels(
            calendars = listOf(calendar(1, "a", "com.google"), calendar(2, "a", "com.google")),
            existing = existing,
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
            description = DESC,
            versions = mapOf(1L to 2, 2L to 1),
            showingChannelIds = setOf(reminderChannelId(2)),
        )
        assertThat(plan.channels.map { it.id }).containsExactly(reminderChannelId(1, 2), reminderChannelId(2, 1))
        assertThat(plan.staleChannelIds).containsExactly(reminderChannelId(1))
        assertThat(plan.keptChannelIds).containsExactly(reminderChannelId(2))
    }

    @Test
    fun `the current version is the highest that exists, else the high-water mark`() {
        val versions = currentReminderChannelVersions(
            existingChannelIds = listOf(reminderChannelId(1), reminderChannelId(1, 2), reminderChannelId(3), "backup"),
            highWater = mapOf(1L to 5, 2L to 4),
        )
        // Calendar 1 reserved v5 but never wrote it, so v2 stays current.
        assertThat(versions).containsExactly(1L, 2, 2L, 4, 3L, 0)
    }

    @Test
    fun `status reads the current channel version`() {
        val channels = mapOf(
            reminderChannelId(1) to ReminderChannelState(blocked = true, groupId = null),
            reminderChannelId(1, 2) to ReminderChannelState(blocked = false, groupId = null),
        )
        val status = reminderChannelStatus(listOf(1L), true, channels, emptySet(), versions = mapOf(1L to 2))
        assertThat(status.blockedCalendarIds).isEmpty()
    }

    private companion object {
        const val DESC = "Reminders"
    }
}
