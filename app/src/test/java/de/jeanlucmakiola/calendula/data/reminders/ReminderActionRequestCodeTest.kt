package de.jeanlucmakiola.calendula.data.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReminderActionRequestCodeTest {

    private val alert = ReminderAlert(
        key = 123_456_789L,
        eventId = 1L,
        calendarId = 1L,
        beginMillis = 1_800_000_000_000L,
        endMillis = 1_800_003_600_000L,
        title = "Dentist",
        location = "Main St 1",
        isAllDay = false,
    )

    @Test
    fun `every notification action gets its own request code`() {
        val codes = listOf(
            ReminderActionReceiver.ACTION_SNOOZE,
            ReminderActionReceiver.ACTION_DISMISS,
            ReminderActionReceiver.ACTION_OPEN,
            ReminderActionReceiver.ACTION_MAP,
        ).map { ReminderActionReceiver.requestCode(alert, it) }

        assertThat(codes).containsNoDuplicates()
    }
}
