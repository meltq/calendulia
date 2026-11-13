package de.jeanlucmakiola.calendula.domain

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.domain.reminders.FixedTimeReminder
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

class FixedTimeReminderFormTest {

    private val zone = TimeZone.of("Europe/Berlin")
    private val evening = FixedTimeReminder(1, 20 * 60)

    private fun form(start: String, reminders: List<Int> = emptyList(), allDay: Boolean = false) = EventForm(
        calendarId = 1,
        isAllDay = allDay,
        start = FormTime(LocalDateTime.parse(start)),
        end = FormTime(LocalDateTime.parse(start)),
        reminders = reminders,
    )

    @Test
    fun `a moved start re-derives the offset so the wall clock stays`() {
        val loaded = form("2026-06-10T10:00", reminders = listOf(10, 840))
            .withFixedTimeRemindersRead(known = setOf(evening), deviceZone = zone)
        assertThat(loaded.reminders).containsExactly(10)
        assertThat(loaded.fixedReminders).containsExactly(evening)
        assertThat(loaded.fixedReminderOffsets(zone)).containsExactly(840)

        val moved = loaded.copy(start = FormTime(LocalDateTime.parse("2026-06-10T15:30")))
        assertThat(moved.fixedReminderOffsets(zone)).containsExactly(19 * 60 + 30)
    }

    @Test
    fun `all-day forms keep their reminders and store no fixed ones`() {
        val allDay = form("2026-06-10T00:00", reminders = listOf(840), allDay = true)
        assertThat(allDay.withFixedTimeRemindersRead(setOf(evening), zone)).isEqualTo(allDay)
        assertThat(allDay.copy(fixedReminders = listOf(evening)).fixedReminderOffsets(zone)).isEmpty()
    }

    @Test
    fun `fixed reminders count as a populated reminders section on a timed event only`() {
        val withFixed = form("2026-06-10T10:00").copy(fixedReminders = listOf(evening))
        assertThat(withFixed.populatedFields()).contains(EventFormField.Reminders)
        assertThat(withFixed.copy(isAllDay = true).populatedFields()).doesNotContain(EventFormField.Reminders)
    }
}
