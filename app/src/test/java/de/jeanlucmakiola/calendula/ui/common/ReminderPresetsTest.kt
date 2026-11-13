package de.jeanlucmakiola.calendula.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReminderPresetsTest {

    @Test
    fun `presets are sorted and unique`() {
        for (presets in listOf(REMINDER_PRESETS, ALLDAY_REMINDER_PRESETS)) {
            assertThat(presets).isInStrictOrder()
        }
    }

    @Test
    fun `all-day presets are whole days`() {
        assertThat(ALLDAY_REMINDER_PRESETS.all { it % 1_440 == 0 }).isTrue()
    }

    @Test
    fun `offers two to five days and two and three weeks`() {
        val extra = listOf(2_880, 4_320, 5_760, 7_200, 20_160, 30_240)
        assertThat(REMINDER_PRESETS).containsAtLeastElementsIn(extra)
        assertThat(ALLDAY_REMINDER_PRESETS).containsAtLeastElementsIn(extra)
    }

    @Test
    fun `groups split sub-day from day-scale lead times, sorted`() {
        assertThat(groupReminderLeadTimes(listOf(2_880, 10, 0, 1_440, 45), splitSubDay = true))
            .containsExactly(listOf(0, 10, 45), listOf(1_440, 2_880)).inOrder()
    }

    @Test
    fun `unsplit list is one sorted group`() {
        assertThat(groupReminderLeadTimes(listOf(2_880, 0, 30), splitSubDay = false))
            .containsExactly(listOf(0, 30, 2_880))
    }

    @Test
    fun `empty groups are dropped`() {
        assertThat(groupReminderLeadTimes(listOf(0, 1_440), splitSubDay = true))
            .containsExactly(listOf(0), listOf(1_440)).inOrder()
        assertThat(groupReminderLeadTimes(listOf(2_880), splitSubDay = true)).containsExactly(listOf(2_880))
        assertThat(groupReminderLeadTimes(emptyList(), splitSubDay = true)).isEmpty()
        assertThat(groupReminderLeadTimes(emptyList(), splitSubDay = false)).isEmpty()
    }
}
