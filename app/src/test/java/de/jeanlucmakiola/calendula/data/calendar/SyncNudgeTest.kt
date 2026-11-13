package de.jeanlucmakiola.calendula.data.calendar

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.RecordingSyncRequester
import de.jeanlucmakiola.calendula.RecordingSyncRequester.Request
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.domain.EventForm
import de.jeanlucmakiola.calendula.domain.FormTime
import de.jeanlucmakiola.calendula.domain.ics.ParsedIcsEvent
import de.jeanlucmakiola.calendula.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Instant

class SyncNudgeTest {

    private val dav = SyncAccount("me@dav", "bitfire.at.davdroid")
    private val google = SyncAccount("me@gmail.com", "com.google")

    private fun cal(id: Long, account: SyncAccount, visible: Boolean = true) = CalendarSource(
        id = id,
        displayName = "Cal $id",
        accountName = account.name,
        accountType = account.type,
        color = 0,
        isVisibleInSystem = visible,
        isLocal = account.type == "LOCAL",
    )

    private val local = SyncAccount("Calendula", "LOCAL")

    private fun fake() = FakeCalendarDataSource().apply {
        calendarsResult = listOf(cal(1L, dav), cal(2L, dav), cal(3L, google), cal(4L, local))
        eventCalendars += mapOf(10L to 1L, 30L to 3L, 40L to 4L)
    }

    private fun form(calendarId: Long) = EventForm(
        calendarId = calendarId,
        title = "Stand-up",
        start = FormTime(LocalDateTime(LocalDate(2026, 6, 12), LocalTime(9, 0))),
        end = FormTime(LocalDateTime(LocalDate(2026, 6, 12), LocalTime(9, 15))),
    )

    private fun auto(account: SyncAccount) = Request(account, userInitiated = false)

    @Test
    fun `a write to a synced calendar nudges its account once, not manually`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.createEvent(form(1L))

        assertThat(sync.requests).containsExactly(auto(dav))
    }

    @Test
    fun `a write to a local calendar asks for no sync`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.createEvent(form(4L))
        repo.deleteEvent(40L)

        assertThat(sync.requests).isEmpty()
    }

    @Test
    fun `event writes resolve the account from the event's calendar`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.updateEventFromOccurrence(10L, 0L, form(1L), form(1L))
        repo.deleteOccurrence(30L, 0L)

        assertThat(sync.requests).containsExactly(auto(dav), auto(google)).inOrder()
    }

    @Test
    fun `a move between accounts nudges both, each once`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.moveEvent(10L, 3L, form(1L), form(3L))

        assertThat(sync.requests).containsExactly(auto(dav), auto(google))
    }

    @Test
    fun `a move between calendars of one account nudges it once`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.moveEvent(10L, 2L, form(1L), form(2L))

        assertThat(sync.requests).containsExactly(auto(dav))
    }

    @Test
    fun `an import nudges once at the end, however many events`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)
        val events = (1..5).map {
            ParsedIcsEvent(
                uid = "u$it",
                summary = "E$it",
                start = Instant.fromEpochMilliseconds(1_000_000_000L),
                end = Instant.fromEpochMilliseconds(1_000_003_600L),
                isAllDay = false,
                zoneId = "UTC",
            )
        }

        repo.importEvents(3L, events)

        assertThat(sync.requests).containsExactly(auto(google))
    }

    @Test
    fun `a failed write still nudges, since part of it may have landed`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val source = fake().apply { writeError = IllegalStateException("boom") }
        val repo = testRepository(source, dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThrows<IllegalStateException> { repo.deleteEvent(10L) }

        assertThat(sync.requests).containsExactly(auto(dav))
    }

    @Test
    fun `a throwing requester never fails the save`(@TempDir dir: Path) = runTest {
        val throwing = RecordingSyncRequester(accepts = false)
        val source = fake().apply { nextInsertId = 77L }
        val repo = testRepository(source, dir, Dispatchers.Unconfined, syncRequester = throwing)

        assertThat(repo.createEvent(form(1L))).isEqualTo(77L)
    }

    @Test
    fun `manual sync asks each visible synced account once, as manual`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val source = fake().apply {
            calendarsResult = calendarsResult + cal(5L, SyncAccount("hidden@dav", "bitfire.at.davdroid"), visible = false)
        }
        val repo = testRepository(source, dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.Requested)
        assertThat(sync.requests).containsExactly(Request(dav, true), Request(google, true))
    }

    @Test
    fun `manual sync with only local calendars has nothing to sync`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester()
        val source = FakeCalendarDataSource().apply { calendarsResult = listOf(cal(4L, local)) }
        val repo = testRepository(source, dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.NothingToSync)
        assertThat(sync.requests).isEmpty()
    }

    @Test
    fun `manual sync the system refuses reports a failure`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(accepts = false)
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.Failed)
    }

    @Test
    fun `syncAccounts skips local, invisible and blank accounts`() {
        val calendars = listOf(
            cal(1L, dav),
            cal(2L, dav),
            cal(3L, google, visible = false),
            cal(4L, local),
            cal(5L, SyncAccount("", "com.google")),
        )

        assertThat(calendars.syncAccounts()).containsExactly(dav)
    }

    @Test
    fun `accounts without a calendar sync adapter are never asked`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(adapterTypes = setOf(dav.type))
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.createEvent(form(3L))
        assertThat(sync.requests).isEmpty()

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.Requested)
        assertThat(sync.requests).containsExactly(Request(dav, true))
    }

    @Test
    fun `syncTargets needs a visible calendar with an adapter`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(adapterTypes = setOf(google.type))
        val source = fake().apply {
            calendarsResult = listOf(cal(1L, dav), cal(3L, google, visible = false), cal(4L, local))
        }
        val repo = testRepository(source, dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.syncTargets.first()).isEqualTo(SyncTargets.None)

        sync.adapterTypes = setOf(dav.type)
        assertThat(repo.syncTargets.first()).isEqualTo(SyncTargets(setOf(dav), emptySet()))
    }

    @Test
    fun `manual sync refused for some accounts says so`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(refusedTypes = setOf(google.type))
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.PartlyFailed)
        assertThat(sync.requests).containsExactly(Request(dav, true))
    }

    @Test
    fun `accounts the app can't see wait for access instead of being asked`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(hiddenTypes = setOf(google.type))
        sync.knownAccounts = setOf(dav, google)
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.moveEvent(10L, 3L, form(1L), form(3L))
        assertThat(sync.requests).containsExactly(auto(dav))

        assertThat(repo.syncTargets.first()).isEqualTo(SyncTargets(setOf(dav), setOf(google)))

        sync.requests.clear()
        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.PartlyFailed)
        assertThat(sync.requests).containsExactly(Request(dav, true))
    }

    @Test
    fun `once access is granted the account is asked by name`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(hiddenTypes = setOf(google.type))
        sync.knownAccounts = setOf(dav, google)
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        sync.hiddenTypes = setOf("other")

        assertThat(repo.syncTargets.first()).isEqualTo(SyncTargets(setOf(dav, google), emptySet()))
        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.Requested)
    }

    @Test
    fun `only hidden accounts make manual sync fail without asking anyone`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(hiddenTypes = setOf(dav.type, google.type))
        sync.knownAccounts = setOf(dav, google)
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        assertThat(repo.requestSync()).isEqualTo(SyncRequestResult.Failed)
        repo.createEvent(form(1L))
        assertThat(sync.requests).isEmpty()
    }

    @Test
    fun `unreadable account visibility asks no one`(@TempDir dir: Path) = runTest {
        val sync = RecordingSyncRequester(hiddenTypes = setOf("other"))
        sync.visibilityError = SecurityException("no")
        val repo = testRepository(fake(), dir, Dispatchers.Unconfined, syncRequester = sync)

        repo.createEvent(form(1L))

        assertThat(sync.requests).isEmpty()
    }
}
