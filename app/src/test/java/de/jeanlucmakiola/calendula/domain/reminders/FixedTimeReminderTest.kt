package de.jeanlucmakiola.calendula.domain.reminders

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class FixedTimeReminderTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private val eveningBefore = FixedTimeReminder(daysBefore = 1, minuteOfDay = 20 * 60)

    private fun at(text: String, zone: TimeZone = berlin): Instant = LocalDateTime.parse(text).toInstant(zone)

    @Test
    fun `offset is the distance from the named wall clock to the start`() {
        assertThat(eveningBefore.offsetMinutes(at("2026-06-10T10:00"), berlin)).isEqualTo(14 * 60)
        assertThat(eveningBefore.offsetMinutes(at("2026-06-10T21:30"), berlin)).isEqualTo(25 * 60 + 30)
    }

    @Test
    fun `offset spans a DST change in real minutes`() {
        // Clocks go forward on 29 March 2026: 20:00 on the 28th to 10:00 on the 29th is 13 hours.
        assertThat(eveningBefore.offsetMinutes(at("2026-03-29T10:00"), berlin)).isEqualTo(13 * 60)
    }

    @Test
    fun `an offset reads back as the pattern it was written from`() {
        val start = at("2026-03-29T10:00")
        val offset = eveningBefore.offsetMinutes(start, berlin)
        assertThat(fixedTimeReminderAt(offset, start, berlin)).isEqualTo(eveningBefore)
    }

    @Test
    fun `a reminder on the start's own day or after it is no pattern`() {
        val start = at("2026-06-10T10:00")
        assertThat(fixedTimeReminderAt(60, start, berlin)).isNull()
        assertThat(fixedTimeReminderAt(0, start, berlin)).isNull()
        assertThat(fixedTimeReminderAt(-30, start, berlin)).isNull()
    }

    @Test
    fun `split keeps presets and whole days as lead times`() {
        val start = at("2026-06-10T10:00")
        val split = splitTimedReminders(listOf(10, 1_440, 840, 2 * 1_440), start, berlin, known = emptySet())
        assertThat(split.leadMinutes).containsExactly(10, 1_440, 2_880).inOrder()
        assertThat(split.fixed).containsExactly(eveningBefore)
    }

    @Test
    fun `a whole-day offset landing on a known pattern is fixed-time`() {
        // An event at 20:00 with "1 day before at 20:00" stores exactly a day.
        val start = at("2026-06-10T20:00")
        assertThat(splitTimedReminders(listOf(1_440), start, berlin, known = emptySet()).fixed).isEmpty()
        val split = splitTimedReminders(listOf(1_440), start, berlin, known = setOf(eveningBefore))
        assertThat(split.fixed).containsExactly(eveningBefore)
        assertThat(split.leadMinutes).isEmpty()
    }

    @Test
    fun `fixed reminders sort by when they fire`() {
        val start = at("2026-06-10T10:00")
        val twoDays = FixedTimeReminder(2, 9 * 60)
        val split = splitTimedReminders(
            listOf(eveningBefore.offsetMinutes(start, berlin), twoDays.offsetMinutes(start, berlin)),
            start,
            berlin,
            known = emptySet(),
        )
        assertThat(split.fixed).containsExactly(twoDays, eveningBefore).inOrder()
    }

    @Test
    fun `token round-trips and rejects garbage`() {
        assertThat(FixedTimeReminder.parse(eveningBefore.toToken())).isEqualTo(eveningBefore)
        assertThat(FixedTimeReminder.parse("0@600")).isNull()
        assertThat(FixedTimeReminder.parse("1@1440")).isNull()
        assertThat(FixedTimeReminder.parse("x")).isNull()
        assertThat(FixedTimeReminder.parse(null)).isNull()
    }
}
