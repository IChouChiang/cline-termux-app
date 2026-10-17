package dev.cline.termux.ui.kernel

import app.cash.turbine.test
import dev.cline.termux.R
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.HubSessionActivity
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelCoordinator.RestartPrecheck
import dev.cline.termux.core.kernel.KernelCoordinator.StopOutcome
import dev.cline.termux.core.kernel.KernelCoordinator.WakeLockOutcome
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.PortVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E.7 / E.10 flows of the status sheet against a scripted kernel (fake-kernel scenarios). */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelStatusViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val info = HubInfo(
        url = "ws://127.0.0.1:25463/hub", port = 25463, pid = 23817, startedAt = "2026-10-03T08:00:00Z",
        coreVersion = "0.0.90", buildId = "source-0.0.90", protocolVersion = "v1",
    )
    private val live = HubLiveStatus(draining = false, activeRpcTurns = 0, pendingRuns = 0, lastSequence = 10, idle = true)

    private inner class FakeKernel(initial: KernelState) : StatusKernel {
        override val state = MutableStateFlow(initial)
        override val commandsAvailable = MutableStateFlow(true)
        override val portVersion = MutableStateFlow<PortVersion?>(PortVersion("3.0.68-termux.2", "3.0.68", "v3.0.68-termux.2"))
        override val wakeLockError = MutableStateFlow<String?>(null)
        override val lastInfo: HubInfo? get() = info

        val calls = mutableListOf<String>()
        var precheck = RestartPrecheck(HubSessionActivity.NONE, ownBusy = false, unknown = false)
        var restartOutcome: RestartOutcome = RestartOutcome.Restarted
        var stopOutcome: StopOutcome = StopOutcome.Stopped
        var wakeOutcome: WakeLockOutcome = WakeLockOutcome.Applied
        var answers = false

        override suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean) {
            calls += "ensureConnected($startIfDown,$userInitiated)"
            if (startIfDown || answers) state.value = KernelState.Ready(info, live)
        }
        override suspend fun refreshHubStatus(): HubLiveStatus? {
            calls += "refreshHubStatus"
            return live
        }
        override suspend fun hubAnswers(): Boolean = answers.also { calls += "hubAnswers" }
        override suspend fun beginRestart(): RestartPrecheck = precheck.also { calls += "beginRestart" }
        override fun cancelRestart() {
            calls += "cancelRestart"
        }
        override suspend fun restart(forced: Boolean): RestartOutcome {
            calls += "restart($forced)"
            return restartOutcome
        }
        override suspend fun stopPrecheck(): RestartPrecheck = precheck.also { calls += "stopPrecheck" }
        override suspend fun stopKernel(): StopOutcome {
            calls += "stopKernel"
            if (stopOutcome == StopOutcome.Stopped) state.value = KernelState.HubDown(DownReason.Stopped, null, null)
            return stopOutcome
        }
        override suspend fun disconnect() {
            calls += "disconnect"
            state.value = KernelState.HubDown(DownReason.Disconnected, null, null)
        }
        override suspend fun setKeepAwake(enabled: Boolean): WakeLockOutcome {
            calls += "setKeepAwake($enabled)"
            return wakeOutcome
        }
    }

    private class FakePrefs : StatusPrefs {
        val keep = MutableStateFlow(true)
        var lifetime: String? = "3.0.68-termux.2"
        override val keepAwake: Flow<Boolean> = keep
        override suspend fun setKeepAwake(value: Boolean) {
            keep.value = value
        }
        override suspend fun hubLifetimeRelease(): String? = lifetime
    }

    private class FakeRunner : KernelDiagnostics.Runner {
        var doctorRuns = 0
        val summary = DoctorSummary("3.0.68", "0.0.90", true, 23817, null, emptyList(), emptyList(), 0, emptyList())
        override suspend fun doctor(): DoctorOutcome {
            doctorRuns++
            return DoctorOutcome.Report(summary)
        }
        override suspend fun fix(): DoctorOutcome = DoctorOutcome.Report(summary)
    }

    private inner class Harness(
        val kernel: FakeKernel,
        val prefs: FakePrefs,
        val runner: FakeRunner,
        val ownBusy: MutableStateFlow<Boolean>,
        val vm: KernelStatusViewModel,
        val events: MutableList<StatusEvent>,
    )

    private fun TestScope.harness(initial: KernelState = KernelState.Ready(info, live)): Harness {
        val kernel = FakeKernel(initial)
        val prefs = FakePrefs()
        val runner = FakeRunner()
        val diagnostics = KernelDiagnostics(runner, backgroundScope)
        val ownBusy = MutableStateFlow(false)
        val vm = KernelStatusViewModel(
            kernel = { kernel },
            prefs = prefs,
            reliability = { ReliabilitySummary(3, 5, requiredMissing = true) },
            diagnostics = { diagnostics },
            ownSessionsBusy = ownBusy,
            appVersion = "0.4.0",
            nowWallMs = { 0L },
            work = main,
            refreshIntervalMs = 5_000,
        )
        val events = mutableListOf<StatusEvent>()
        backgroundScope.launch { vm.ui.collect {} }
        backgroundScope.launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        return Harness(kernel, prefs, runner, ownBusy, vm, events)
    }

    private fun snackbar(id: Int) = StatusEvent.Snackbar(UiText.Res(id))

    @Test
    fun `restart with nobody busy upgrades without a confirm`() = runTest(main) {
        val h = harness()
        h.vm.onAction(KernelStatusAction.Restart)
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "restart(false)"), h.kernel.calls)
        assertEquals(listOf(snackbar(R.string.kernel_restarted_snackbar)), h.events)
        assertNull(h.vm.ui.value.busy)
        assertNull(h.vm.ui.value.confirm)
    }

    @Test
    fun `a Restart kernel request from the chat runs the sheet's restart once per request`() = runTest(main) {
        val h = harness()
        h.kernel.precheck = RestartPrecheck(HubSessionActivity(2, 3), ownBusy = false, unknown = false)
        h.vm.restartOnOpen(1L)
        advanceUntilIdle()
        assertTrue("the same confirm as the sheet's own button", h.vm.ui.value.confirm is StatusConfirm.Restart)
        h.vm.dismissConfirm()
        advanceUntilIdle()
        // The sheet recomposes or reopens with the same request: nothing runs again.
        h.vm.restartOnOpen(1L)
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "cancelRestart"), h.kernel.calls)
        h.vm.restartOnOpen(2L)
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "cancelRestart", "beginRestart"), h.kernel.calls)
    }

    @Test
    fun `restart with other clients asks first and cancel leaves the hub alone`() = runTest(main) {
        val h = harness()
        h.kernel.precheck = RestartPrecheck(HubSessionActivity(2, 3), ownBusy = false, unknown = false)
        h.vm.onAction(KernelStatusAction.Restart)
        advanceUntilIdle()
        val confirm = h.vm.ui.value.confirm as StatusConfirm.Restart
        assertTrue(confirm.forced)
        assertTrue(h.vm.ui.value.actionsLocked)
        assertEquals(R.string.kernel_restart_confirm_body, (confirm.text as UiText.Res).id)

        h.vm.dismissConfirm()
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "cancelRestart"), h.kernel.calls)
        assertNull(h.vm.ui.value.confirm)
        assertNull(h.vm.ui.value.busy)
    }

    @Test
    fun `an unknown session count forces the confirmed restart`() = runTest(main) {
        val h = harness()
        h.kernel.precheck = RestartPrecheck(HubSessionActivity.UNKNOWN, ownBusy = false, unknown = true)
        h.vm.onAction(KernelStatusAction.Restart)
        advanceUntilIdle()
        assertTrue((h.vm.ui.value.confirm as StatusConfirm.Restart).forced)
        h.vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "restart(true)"), h.kernel.calls)
    }

    @Test
    fun `the app's own running session asks with the own-task sentence and does not force`() = runTest(main) {
        val h = harness()
        h.ownBusy.value = true
        h.vm.onAction(KernelStatusAction.Restart)
        advanceUntilIdle()
        val confirm = h.vm.ui.value.confirm as StatusConfirm.Restart
        assertFalse(confirm.forced)
        assertEquals(UiText.Res(R.string.kernel_restart_confirm_own), confirm.text)
        h.vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("beginRestart", "restart(false)"), h.kernel.calls)
    }

    @Test
    fun `a refused upgrade shows the kernel text and the hint`() = runTest(main) {
        val h = harness()
        h.kernel.restartOutcome = RestartOutcome.StillServing("Hub still serving 1 session\n")
        h.vm.onAction(KernelStatusAction.Restart)
        advanceUntilIdle()
        assertEquals(
            listOf(UiText.Raw("Hub still serving 1 session"), UiText.Res(R.string.kernel_restart_busy_hint)),
            h.vm.ui.value.actionError,
        )
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `restart on an outdated hub opens the update dialog`() = runTest(main) {
        val h = harness(KernelState.ProtocolUnsupported(info.copy(minClientProtocolVersion = "v2")))
        h.vm.onAction(KernelStatusAction.Restart)
        // The event is sent synchronously; only the background collector has work left.
        runCurrent()
        assertEquals(listOf<StatusEvent>(StatusEvent.OpenHubUpdate), h.events)
        assertTrue(h.kernel.calls.isEmpty())
    }

    @Test
    fun `stop with nobody busy stops and reports it`() = runTest(main) {
        val h = harness()
        h.vm.onAction(KernelStatusAction.Stop)
        advanceUntilIdle()
        assertEquals(listOf("stopPrecheck", "stopKernel"), h.kernel.calls)
        assertEquals(listOf(snackbar(R.string.kernel_stopped_snackbar)), h.events)
        assertEquals(KernelStatusAction.Start, h.vm.ui.value.primary)
    }

    @Test
    fun `stop with a running task asks first`() = runTest(main) {
        val h = harness()
        h.kernel.precheck = RestartPrecheck(HubSessionActivity.NONE, ownBusy = true, unknown = false)
        h.vm.onAction(KernelStatusAction.Stop)
        advanceUntilIdle()
        assertEquals(StatusConfirm.Stop(UiText.Res(R.string.kernel_stop_confirm_own)), h.vm.ui.value.confirm)
        h.vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("stopPrecheck", "stopKernel"), h.kernel.calls)
    }

    @Test
    fun `a disconnected kernel stops without the session pre-check`() = runTest(main) {
        val h = harness(KernelState.HubDown(DownReason.Disconnected, null, null))
        h.vm.onAction(KernelStatusAction.Stop)
        advanceUntilIdle()
        assertEquals(listOf("stopKernel"), h.kernel.calls)
    }

    @Test
    fun `a hub that did not stop shows the E10 line`() = runTest(main) {
        val h = harness()
        h.kernel.stopOutcome = StopOutcome.NotStopped("still running")
        h.vm.onAction(KernelStatusAction.Stop)
        advanceUntilIdle()
        assertEquals(listOf(UiText.Res(R.string.kernel_stop_not_stopped)), h.vm.ui.value.actionError)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `disconnect confirms with a snackbar and offers connect`() = runTest(main) {
        val h = harness()
        h.vm.onAction(KernelStatusAction.Disconnect)
        advanceUntilIdle()
        assertEquals(listOf(snackbar(R.string.kernel_disconnected_snackbar)), h.events)
        assertEquals(KernelStatusAction.Connect, h.vm.ui.value.primary)

        h.vm.onAction(KernelStatusAction.Connect)
        advanceUntilIdle()
        assertEquals("ensureConnected(false,true)", h.kernel.calls.last())
    }

    @Test
    fun `start kernel is a user-initiated start`() = runTest(main) {
        val h = harness(KernelState.HubDown(DownReason.Asleep, null, null))
        h.vm.onAction(KernelStatusAction.Start)
        advanceUntilIdle()
        assertEquals(listOf("ensureConnected(true,true)"), h.kernel.calls)
        assertEquals(KernelStatusAction.Restart, h.vm.ui.value.primary)
    }

    @Test
    fun `a failed wake lock rolls the switch back with the kernel's text`() = runTest(main) {
        val h = harness()
        h.kernel.wakeOutcome = WakeLockOutcome.Failed("termux-wake-lock: not found")
        h.vm.setKeepAwake(false)
        advanceUntilIdle()
        assertEquals("setKeepAwake(false)", h.kernel.calls.single())
        assertTrue(h.prefs.keep.value)
        assertTrue(h.vm.ui.value.keepAwake)
        assertEquals(
            UiText.Res(R.string.kernel_keep_awake_failed, UiText.Raw("termux-wake-lock: not found")),
            h.vm.ui.value.wakeLockError,
        )
    }

    @Test
    fun `a wake lock without an answer uses the generic reason`() = runTest(main) {
        val h = harness()
        h.kernel.wakeOutcome = WakeLockOutcome.Failed(null)
        h.vm.setKeepAwake(true)
        advanceUntilIdle()
        assertEquals(
            UiText.Res(R.string.kernel_keep_awake_failed, UiText.Res(R.string.kernel_status_termux_no_answer)),
            h.vm.ui.value.wakeLockError,
        )
    }

    @Test
    fun `the visible sheet picks up a hub that came back without starting one`() = runTest(main) {
        val h = harness(KernelState.HubDown(DownReason.Asleep, null, null))
        h.vm.setVisible(true)
        runCurrent()
        assertEquals(listOf("hubAnswers"), h.kernel.calls)

        h.kernel.answers = true
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("hubAnswers", "hubAnswers", "ensureConnected(false,false)"), h.kernel.calls)
        assertTrue(h.vm.ui.value.primary == KernelStatusAction.Restart)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("refreshHubStatus", h.kernel.calls.last())

        h.vm.setVisible(false)
        val before = h.kernel.calls.size
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(before, h.kernel.calls.size)
    }

    @Test
    fun `a disconnected kernel is never reconnected by the refresh`() = runTest(main) {
        val h = harness(KernelState.HubDown(DownReason.Disconnected, null, null))
        h.kernel.answers = true
        h.vm.setVisible(true)
        runCurrent()
        h.vm.setVisible(false)
        runCurrent()
        assertTrue(h.kernel.calls.isEmpty())
    }

    @Test
    fun `a timed-out start runs diagnostics on open, once`() = runTest(main) {
        val h = harness(KernelState.HubDown(DownReason.StartTimedOut, null, null))
        h.vm.onOpened(diagnostics = false)
        advanceUntilIdle()
        assertEquals(1, h.runner.doctorRuns)
        assertTrue(h.vm.ui.value.diagnostics is DiagnosticsUi.Loaded)
        h.vm.onOpened(diagnostics = true)
        advanceUntilIdle()
        assertEquals(1, h.runner.doctorRuns)
    }

    @Test
    fun `opening on a ready kernel runs diagnostics only when asked`() = runTest(main) {
        val h = harness()
        h.vm.onOpened(diagnostics = false)
        advanceUntilIdle()
        assertEquals(0, h.runner.doctorRuns)
        h.vm.onOpened(diagnostics = true)
        advanceUntilIdle()
        assertEquals(1, h.runner.doctorRuns)
    }

    @Test
    fun `the update row and reliability summary come from the extras`() = runTest(main) {
        val h = harness()
        assertEquals(ReliabilitySummary(3, 5, requiredMissing = true), h.vm.ui.value.reliability)
        assertNull(h.vm.ui.value.updateRow)
        h.prefs.lifetime = "3.0.68-termux.1"
        h.vm.setVisible(true)
        runCurrent()
        h.vm.setVisible(false)
        runCurrent()
        assertEquals(UpdateRowUi("3.0.68-termux.2"), h.vm.ui.value.updateRow)
    }

    @Test
    fun `open termux failures surface as a snackbar`() = runTest(main) {
        val h = harness()
        h.vm.reportOpenTermuxFailed()
        runCurrent()
        assertEquals(listOf(snackbar(R.string.kernel_status_open_termux_failed)), h.events)
    }

    @Test
    fun `the ui follows the kernel through a restart`() = runTest(main) {
        val h = harness()
        h.vm.ui.test {
            assertEquals(KernelStatusAction.Restart, awaitItem().primary)
            h.kernel.state.value = KernelState.Restarting(info, dev.cline.termux.core.kernel.RestartPhase.Replacing)
            val restarting = awaitItem()
            assertNull(restarting.primary)
            assertTrue(restarting.primarySpinner)
            assertTrue(restarting.actionsLocked)
            h.kernel.state.value = KernelState.Ready(info, live)
            assertEquals(KernelStatusAction.Restart, awaitItem().primary)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
