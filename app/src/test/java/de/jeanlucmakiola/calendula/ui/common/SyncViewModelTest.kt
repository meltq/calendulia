package de.jeanlucmakiola.calendula.ui.common

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.calendula.MainDispatcherExtension
import de.jeanlucmakiola.calendula.RecordingSyncRequester
import de.jeanlucmakiola.calendula.data.calendar.FakeCalendarDataSource
import de.jeanlucmakiola.calendula.data.calendar.SyncAccount
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
    fun `in flight until the provider next reports a change`(@TempDir dir: Path) = runTest(dispatcher) {
        val (vm, source) = viewModel(dir)

        vm.requestSync()
        runCurrent()

        assertThat(vm.inFlight.value).isTrue()
        assertThat(vm.result.value).isEqualTo(SyncRequestResult.Requested)

        source.tick()
        runCurrent()

        assertThat(vm.inFlight.value).isFalse()
    }

    @Test
    fun `in flight ends on its own when nothing changes`(@TempDir dir: Path) = runTest(dispatcher) {
        val (vm, _) = viewModel(dir)

        vm.requestSync()
        advanceTimeBy(15_001L)

        assertThat(vm.inFlight.value).isFalse()
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
}
