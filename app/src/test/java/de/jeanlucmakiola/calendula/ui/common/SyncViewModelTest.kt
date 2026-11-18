package de.jeanlucmakiola.calendula.ui.common

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.MainDispatcherExtension
import de.jeanlucmakiola.calendula.RecordingSyncRequester
import de.jeanlucmakiola.calendula.data.calendar.FakeCalendarDataSource
import de.jeanlucmakiola.calendula.data.calendar.SyncAccount
import de.jeanlucmakiola.calendula.data.calendar.SyncActivity
import de.jeanlucmakiola.calendula.data.calendar.SyncRequestResult
import de.jeanlucmakiola.calendula.data.calendar.SyncTargets
import kotlinx.coroutines.launch
import de.jeanlucmakiola.calendula.domain.CalendarSource
import de.jeanlucmakiola.calendula.testPrefs
import de.jeanlucmakiola.calendula.testRepository
import de.jeanlucmakiola.calendula.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    @JvmField @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(StandardTestDispatcher())
    private val dispatcher = mainDispatcher.dispatcher

    private val synced = CalendarSource(1L, "Work", "me@dav", "bitfire.at.davdroid", 0, true)
    private val davAccount = SyncAccount("me@dav", "bitfire.at.davdroid")

    private fun viewModel(dir: Path, sync: RecordingSyncRequester = RecordingSyncRequester()):
        Pair<SyncViewModel, FakeCalendarDataSource> {
        val source = FakeCalendarDataSource().apply { calendarsResult = listOf(synced) }
        val repo = testRepository(
            source, dir, dispatcher,
            prefs = testPrefs(dir, CoroutineScope(dispatcher)),
            settings = testSettings(dir, CoroutineScope(dispatcher)),
            syncRequester = sync,
        )
        return SyncViewModel(repo) to source
    }

    @Test
    fun `completed once the reached account has synced`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester()
        var state = SyncActivity.Pending
        sync.activity = { state }
        val (vm, _) = viewModel(dir, sync)

        vm.requestSync()
        runCurrent()
        assertThat(vm.inFlight.value).isTrue()
        assertThat(vm.result.value).isNull()

        state = SyncActivity.Active
        sync.activityChanges.tryEmit(Unit)
        runCurrent()
        assertThat(vm.inFlight.value).isTrue()

        state = SyncActivity.Idle
        sync.activityChanges.tryEmit(Unit)
        runCurrent()

        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Completed)
    }

    @Test
    fun `a sync already running when asked has to finish and the asked one run`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester()
        var state = SyncActivity.Active
        sync.activity = { state }
        val (vm, _) = viewModel(dir, sync)

        vm.requestSync()
        runCurrent()
        state = SyncActivity.Pending
        sync.activityChanges.tryEmit(Unit)
        runCurrent()
        assertThat(vm.inFlight.value).isTrue()

        state = SyncActivity.Idle
        sync.activityChanges.tryEmit(Unit)
        runCurrent()

        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Completed)
    }

    @Test
    fun `a sync that never shows up counts as done after the grace`(@TempDir dir: Path) = runTest(dispatcher) {
        val (vm, _) = viewModel(dir)

        vm.requestSync()
        advanceTimeBy(4_000L)
        assertThat(vm.inFlight.value).isTrue()

        advanceTimeBy(1_500L)
        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Completed)
    }

    @Test
    fun `a sync still running after a minute stays unconfirmed`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester().apply { activity = { SyncActivity.Active } }
        val (vm, _) = viewModel(dir, sync)

        vm.requestSync()
        advanceTimeBy(59_000L)
        assertThat(vm.inFlight.value).isTrue()

        advanceTimeBy(1_500L)
        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Requested)
    }

    @Test
    fun `unreadable sync state says requested at once`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester().apply { activity = { null } }
        val (vm, _) = viewModel(dir, sync)

        vm.requestSync()
        runCurrent()

        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Requested)
    }

    @Test
    fun `a tap while in flight asks only once`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester()
        val (vm, _) = viewModel(dir, sync)

        vm.requestSync()
        runCurrent()
        vm.requestSync()
        runCurrent()

        assertThat(sync.requests).hasSize(1)
    }

    @Test
    fun `a refused request stops at once and says so`(@TempDir dir: Path) = runTest(dispatcher) {
        val (vm, _) = viewModel(dir, RecordingSyncRequester(accepts = false))

        vm.requestSync()
        runCurrent()

        assertThat(vm.inFlight.value).isFalse()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Failed)

        vm.consumeResult()
        assertThat(vm.result.value).isNull()
    }

    @Test
    fun `refreshAccess picks up a grant`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester(hiddenTypes = setOf(davAccount.type))
        sync.knownAccounts = setOf(davAccount)
        val (vm, _) = viewModel(dir, sync)
        backgroundScope.launch { vm.targets.collect {} }
        runCurrent()

        assertThat(vm.targets.value).isEqualTo(SyncTargets(emptySet(), setOf(davAccount)))

        sync.hiddenTypes = setOf("other")
        vm.refreshAccess()
        runCurrent()

        assertThat(vm.targets.value).isEqualTo(SyncTargets(setOf(davAccount), emptySet()))
    }

    @Test
    fun `dismissing a hidden account takes it out of the grant list`(@TempDir dir: Path) = runTest(dispatcher) {
        val sync = RecordingSyncRequester(hiddenTypes = setOf(davAccount.type))
        sync.knownAccounts = setOf(davAccount)
        val (vm, _) = viewModel(dir, sync)
        backgroundScope.launch { vm.targets.collect {} }
        runCurrent()
        assertThat(vm.targets.value?.needsAccess).containsExactly(davAccount)

        vm.dismissAccess(davAccount)
        runCurrent()

        assertThat(vm.targets.value).isEqualTo(SyncTargets(emptySet(), emptySet(), dismissed = setOf(davAccount)))
    }

    @Test
    fun `a failed read of the targets is retried rather than hiding Sync now for good`(@TempDir dir: Path) =
        runTest(dispatcher) {
            val (vm, source) = viewModel(dir)
            source.calendarsError = IllegalStateException("provider gone")
            backgroundScope.launch { vm.targets.collect {} }
            runCurrent()
            assertThat(vm.targets.value?.reachable.orEmpty()).isEmpty()

            source.calendarsError = null
            advanceTimeBy(31_000)
            runCurrent()

            assertThat(vm.targets.value?.reachable).containsExactly(davAccount)
        }
}
