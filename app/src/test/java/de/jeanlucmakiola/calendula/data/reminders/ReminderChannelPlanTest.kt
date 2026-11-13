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
            existingChannelIds = emptyList(),
            existingGroupIds = emptyList(),
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
            existingChannelIds = emptyList(),
            existingGroupIds = emptyList(),
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
            existingChannelIds = emptyList(),
            existingGroupIds = emptyList(),
        )
        assertThat(plan.groups.filter { it.ambiguous }.map { it.accountType })
            .containsExactly("com.google", "bitfire.at.davdroid")
        assertThat(plan.groups.single { it.accountName == "work" }.ambiguous).isFalse()
    }

    @Test
    fun `stale reminder channels and groups go, foreign ones stay`() {
        val current = calendar(1, "me@example.org", "com.google")
        val keptGroup = planReminderChannels(listOf(current), emptyList(), emptyList()).groups.single().id
        val gone = planReminderChannels(listOf(calendar(9, "old", "com.google")), emptyList(), emptyList())
        val plan = planReminderChannels(
            calendars = listOf(current),
            existingChannelIds = listOf(
                reminderChannelId(1), reminderChannelId(9), LEGACY_REMINDER_CHANNEL_ID, "backup",
            ),
            existingGroupIds = listOf(keptGroup, gone.groups.single().id, "other"),
        )
        assertThat(plan.staleChannelIds).containsExactly(reminderChannelId(9))
        assertThat(plan.staleGroupIds).containsExactly(gone.groups.single().id)
    }
}
