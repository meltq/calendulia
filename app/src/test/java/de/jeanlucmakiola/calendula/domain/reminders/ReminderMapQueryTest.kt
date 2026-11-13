package de.jeanlucmakiola.calendula.domain.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReminderMapQueryTest {

    @Test
    fun `an address is looked up as written`() {
        assertThat(reminderMapQuery("  Main St 1, Berlin ")).isEqualTo("Main St 1, Berlin")
    }

    @Test
    fun `no location means no lookup`() {
        assertThat(reminderMapQuery(null)).isNull()
        assertThat(reminderMapQuery("   ")).isNull()
    }

    @Test
    fun `a bare meeting link is not a place`() {
        assertThat(reminderMapQuery("https://meet.google.com/abc-defg-hij")).isNull()
        assertThat(reminderMapQuery("HTTP://example.com")).isNull()
        assertThat(reminderMapQuery("www.example.com/room")).isNull()
    }

    @Test
    fun `a place with a link beside it is still looked up`() {
        assertThat(reminderMapQuery("Room 4, https://example.com/map")).isEqualTo("Room 4, https://example.com/map")
    }
}
