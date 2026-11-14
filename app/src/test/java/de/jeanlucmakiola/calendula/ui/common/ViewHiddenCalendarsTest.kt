package de.jeanlucmakiola.calendula.ui.common

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.data.calendar.FakeCalendarDataSource
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.testEvent
import de.jeanlucmakiola.calendula.testRepository
import de.jeanlucmakiola.calendula.testSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ViewHiddenCalendarsTest {

    private val range = Instant.fromEpochMilliseconds(0)..Instant.fromEpochMilliseconds(10_000_000_000L)

    private fun event(id: Long, calendarId: Long) = testEvent(
        id = id,
        calendarId = calendarId,
        title = "E $id",
        start = Instant.fromEpochMilliseconds(1_000_000_000L),
        end = Instant.fromEpochMilliseconds(1_000_003_600L),
        color = 0,
    )

    private fun calendar(id: Long) = CalendarSource(id, "Cal $id", "x@y", "LOCAL", 0, true)

    @Test
    fun `calendarsHiddenIn picks only the calendars hidden in that view`() {
        val map = mapOf(
            1L to setOf(CalendarView.Month),
            2L to setOf(CalendarView.Month, CalendarView.Agenda),
            3L to setOf(CalendarView.Week),
        )
        assertThat(map.calendarsHiddenIn(CalendarView.Month)).containsExactly(1L, 2L)
        assertThat(map.calendarsHiddenIn(CalendarView.Agenda)).containsExactly(2L)
        assertThat(map.calendarsHiddenIn(CalendarView.Day)).isEmpty()
    }

    @Test
    fun `instancesWithout drops a calendar only in the views it is hidden in`(@TempDir tempDir: Path) = runTest {
        val fake = FakeCalendarDataSource().apply {
            calendarsResult = listOf(calendar(1L), calendar(2L))
            instancesResult = { _, _ -> listOf(event(10L, 1L), event(20L, 2L)) }
        }
        val settings = testSettings(tempDir)
        val repo = testRepository(fake, tempDir, UnconfinedTestDispatcher(testScheduler), settings = settings)
        settings.applyCalendarHiddenChanges(2L, listOf(CalendarView.Month).associateWith { true })
        val hidden = settings.perCalendarHiddenViews

        assertThat(repo.instancesWithout(range, hidden.calendarsHiddenIn(CalendarView.Month, backgroundScope)).first().map { it.eventId })
            .containsExactly(10L)
        assertThat(repo.instancesWithout(range, hidden.calendarsHiddenIn(CalendarView.Week, backgroundScope)).first().map { it.eventId })
            .containsExactly(10L, 20L)
        // The repository itself, which reminders and search build on, is untouched.
        assertThat(repo.instances(range).first().map { it.eventId }).containsExactly(10L, 20L)
    }

    @Test
    fun `instancesWithout re-filters live when the setting changes`(@TempDir tempDir: Path) = runTest {
        val fake = FakeCalendarDataSource().apply {
            calendarsResult = listOf(calendar(1L), calendar(2L))
            instancesResult = { _, _ -> listOf(event(10L, 1L), event(20L, 2L)) }
        }
        val settings = testSettings(tempDir)
        val repo = testRepository(fake, tempDir, UnconfinedTestDispatcher(testScheduler), settings = settings)

        val hidden = settings.perCalendarHiddenViews.calendarsHiddenIn(CalendarView.Agenda, backgroundScope)
        repo.instancesWithout(range, hidden).test {
            assertThat(awaitItem().map { it.eventId }).containsExactly(10L, 20L)
            settings.setCalendarHiddenIn(1L, CalendarView.Agenda, hidden = true)
            assertThat(awaitItem().map { it.eventId }).containsExactly(20L)
            settings.setCalendarHiddenIn(1L, CalendarView.Agenda, hidden = false)
            assertThat(awaitItem().map { it.eventId }).containsExactly(10L, 20L)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
