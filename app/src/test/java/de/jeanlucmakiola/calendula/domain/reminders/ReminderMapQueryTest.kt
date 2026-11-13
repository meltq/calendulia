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
        assertThat(reminderMapQuery("<https://teams.microsoft.com/l/meetup-join/x>")).isNull()
        assertThat(reminderMapQuery("https://a.example\nhttps://b.example")).isNull()
    }

    @Test
    fun `dotted place names are still places`() {
        assertThat(reminderMapQuery("Hauptstr. 12/3, Wien")).isEqualTo("Hauptstr. 12/3, Wien")
        assertThat(reminderMapQuery("St.Gallen/Zentrum")).isEqualTo("St.Gallen/Zentrum")
    }

    @Test
    fun `a place with a link beside it is still looked up`() {
        assertThat(reminderMapQuery("Room 4, https://example.com/map")).isEqualTo("Room 4, https://example.com/map")
    }
}
