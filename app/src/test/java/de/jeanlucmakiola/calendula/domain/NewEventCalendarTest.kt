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

    private fun ref(id: Long) = DefaultCalendarRef.of(cal(id))

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
        val hints = NewEventCalendarHints(requested = 2L, default = ref(3L), lastUsed = 1L)
        assertThat(resolveEventCalendar(4L, hints, calendars)).isEqualTo(4L)
    }

    @Test
    fun `a requested calendar beats the default`() {
        val hints = NewEventCalendarHints(requested = 2L, default = ref(3L))
        assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(2L)
    }

    @Test
    fun `a requested calendar that is no target falls back to the default`() {
        listOf(4L, 5L, 6L, 99L).forEach { requested ->
            val hints = NewEventCalendarHints(requested = requested, default = ref(3L))
            assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(3L)
        }
    }

    @Test
    fun `the default beats the last used calendar`() {
        val hints = NewEventCalendarHints(default = ref(3L), lastUsed = 2L)
        assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(3L)
    }

    @Test
    fun `a default that is no target falls back to the last used calendar`() {
        listOf(4L, 5L, 6L).forEach { id ->
            val default = DefaultCalendarRef.of(calendars.single { it.id == id })
            val hints = NewEventCalendarHints(default = default, lastUsed = 2L)
            assertThat(resolveEventCalendar(null, hints, calendars)).isEqualTo(2L)
            assertThat(effectiveDefaultCalendar(default, calendars)).isNull()
            assertThat(unavailableDefaultCalendar(default, calendars)?.id).isEqualTo(id)
        }
        assertThat(effectiveDefaultCalendar(ref(99L), calendars)).isNull()
        assertThat(unavailableDefaultCalendar(ref(99L), calendars)).isNull()
    }

    @Test
    fun `without hints the first target is used`() {
        val withoutFirst = listOf(cal(4L, writable = false), cal(5L, visible = false), cal(2L))
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(lastUsed = 5L), withoutFirst)).isEqualTo(2L)
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(), emptyList())).isNull()
    }

    @Test
    fun `the effective default is the stored calendar while it is a target`() {
        assertThat(effectiveDefaultCalendar(ref(3L), calendars)?.id).isEqualTo(3L)
        assertThat(unavailableDefaultCalendar(ref(3L), calendars)).isNull()
        assertThat(effectiveDefaultCalendar(null, calendars)).isNull()
    }

    @Test
    fun `a reused id under another calendar is not the default`() {
        val reused = ref(3L).copy(displayName = "Deleted calendar")
        assertThat(effectiveDefaultCalendar(reused, calendars)).isNull()
        assertThat(effectiveDefaultCalendar(ref(3L).copy(accountName = "other"), calendars)).isNull()
        assertThat(resolveEventCalendar(null, NewEventCalendarHints(default = reused, lastUsed = 2L), calendars))
            .isEqualTo(2L)
    }

    @Test
    fun `the stored reference round-trips`() {
        val stored = DefaultCalendarRef(12L, "com.google", "me@example.com", "Work | Team")
        assertThat(DefaultCalendarRef.decode(stored.encode())).isEqualTo(stored)
        assertThat(DefaultCalendarRef.decode(null)).isNull()
        assertThat(DefaultCalendarRef.decode("12")).isNull()
        assertThat(DefaultCalendarRef.decode("x\u001Fa\u001Fb\u001Fc")).isNull()
    }
}
