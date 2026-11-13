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
        assertThat(groupReminderLeadTimes(listOf(2_880, 10, 0, 1_440, 45, 10)))
            .containsExactly(listOf(0, 10, 45), listOf(1_440, 2_880)).inOrder()
    }

    @Test
    fun `at time of event alone joins the day-scale group`() {
        assertThat(groupReminderLeadTimes(ALLDAY_REMINDER_PRESETS))
            .containsExactly(ALLDAY_REMINDER_PRESETS)
    }

    @Test
    fun `empty groups are dropped`() {
        assertThat(groupReminderLeadTimes(listOf(10))).containsExactly(listOf(10))
        assertThat(groupReminderLeadTimes(listOf(2_880))).containsExactly(listOf(2_880))
        assertThat(groupReminderLeadTimes(emptyList())).isEmpty()
    }
}
