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
        )
        assertThat(plan.groups.filter { it.ambiguous }.map { it.accountType })
            .containsExactly("com.google", "bitfire.at.davdroid")
        assertThat(plan.groups.single { it.label == "work" }.ambiguous).isFalse()
    }

    @Test
    fun `stale reminder channels and groups go, foreign ones stay`() {
        val current = calendar(1, "me@example.org", "com.google")
        fun plan(calendars: List<CalendarSource>, existing: List<String> = emptyList(), groups: List<String> = emptyList()) =
            planReminderChannels(calendars, existing.map { ExistingReminderChannel(it, "Cal ${it.last()}") }, groups, "Unnamed")
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
            ),
            existing = listOf(
                ExistingReminderChannel(reminderChannelId(1), "Cal 1"),
                ExistingReminderChannel(reminderChannelId(2), "Old name"),
            ),
            existingGroupIds = emptyList(),
            unnamed = "Unnamed",
        )
        assertThat(plan.channelsToWrite.map { it.calendarId }).containsExactly(2L, 3L)
        assertThat(plan.channelsToWrite.last().name).isEqualTo("Unnamed")
    }
}
