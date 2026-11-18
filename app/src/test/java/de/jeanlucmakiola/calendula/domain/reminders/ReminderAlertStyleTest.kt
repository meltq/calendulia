package de.jeanlucmakiola.calendula.domain.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReminderAlertStyleTest {

    @Test
    fun `styles round-trip through their stored form`() {
        val styles = mapOf(9L to ReminderAlertStyle.FullScreenRing, 3L to ReminderAlertStyle.FullScreen)

        assertThat(parseAlertStyles(styles.toStoredAlertStyles())).isEqualTo(styles)
    }

    @Test
    fun `plain notifications are not stored`() {
        val stored = mapOf(1L to ReminderAlertStyle.Notification, 2L to ReminderAlertStyle.FullScreen).toStoredAlertStyles()

        assertThat(stored).isEqualTo("2=full_screen")
    }

    @Test
    fun `garbled entries are dropped, the rest survive`() {
        assertThat(parseAlertStyles("x=full_screen;4=loud;5;6=full_screen_ring"))
            .containsExactly(6L, ReminderAlertStyle.FullScreenRing)
        assertThat(parseAlertStyles(null)).isEmpty()
    }

    @Test
    fun `a calendar without a style uses notifications`() {
        val styles = mapOf(1L to ReminderAlertStyle.FullScreen)

        assertThat(styles.alertStyleFor(1L)).isEqualTo(ReminderAlertStyle.FullScreen)
        assertThat(styles.alertStyleFor(2L)).isEqualTo(ReminderAlertStyle.Notification)
        assertThat(ReminderAlertStyle.Notification.isFullScreen).isFalse()
        assertThat(ReminderAlertStyle.FullScreenRing.isFullScreen).isTrue()
    }
}
