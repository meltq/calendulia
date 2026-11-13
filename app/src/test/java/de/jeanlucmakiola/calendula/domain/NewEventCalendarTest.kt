package de.jeanlucmakiola.calendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NewEventCalendarTest {

    private fun cal(
        id: Long,
        writable: Boolean = true,
        visible: Boolean = true,
        managed: Boolean = false,
    ) = CalendarSource(
        id = id,
        displayName = "Cal $id",
        accountName = "account",
        accountType = "com.google",
        color = 0,
        isVisibleInSystem = visible,
        canModifyContents = writable,
        isManaged = managed,
    )

    private val calendars = listOf(
        cal(1L),
        cal(2L),
        cal(3L),
        cal(4L, writable = false),
        cal(5L, visible = false),
        cal(6L, managed = true),
    )

    @Test
    fun `a user pick always wins`() {
        val hints = NewEventCalendarHints(requested = 2L, default = 3L, lastUsed = 1L)
        assertThat(resolveEventCalendar(4L, hints, calendars)).isEqualTo(4L)
    }

    @Test
    fun `a requested calendar beats the default`() {
        val hints = NewEventCalendarHints(requested = 2L, default = 3L)
        assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(2L)
    }

    @Test
    fun `a requested calendar only needs to be writable`() {
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(requested = 5L, default = 3L), calendars))
            .isEqualTo(5L)
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(requested = 4L, default = 3L), calendars))
            .isEqualTo(3L)
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(requested = 99L, default = 3L), calendars))
            .isEqualTo(3L)
    }

    @Test
    fun `the default beats the last used calendar`() {
        val hints = NewEventCalendarHints(default = 3L, lastUsed = 2L)
        assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(3L)
    }

    @Test
    fun `a default that is no target falls back to the last used calendar`() {
        listOf(4L, 5L, 6L, 99L).forEach { default ->
            val hints = NewEventCalendarHints(default = default, lastUsed = 2L)
            assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(2L)
            assertThat(effectiveDefaultCalendar(default, calendars)).isNull()
        }
    }

    @Test
    fun `without hints the first target is used`() {
        val withoutFirst = listOf(cal(4L, writable = false), cal(5L, visible = false), cal(2L))
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(lastUsed = 5L), withoutFirst)).isEqualTo(2L)
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(), emptyList())).isNull()
    }

    @Test
    fun `the effective default is the stored calendar while it is a target`() {
        assertThat(effectiveDefaultCalendar(3L, calendars)?.id).isEqualTo(3L)
        assertThat(effectiveDefaultCalendar(null, calendars)).isNull()
    }
}
