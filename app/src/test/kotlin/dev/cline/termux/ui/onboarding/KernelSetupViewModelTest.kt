package dev.cline.termux.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.fakes.FakeOnboardingPrefs
import dev.cline.termux.fakes.FakeReliabilityProbe
import dev.cline.termux.fakes.FakeSetupKernel
import dev.cline.termux.fakes.fakeDiagnostics
import dev.cline.termux.ui.kernel.DiagnosticsUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class KernelSetupViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val diagScope = CoroutineScope(main)

    private val info = HubInfo(
        url = "ws://127.0.0.1:25463", port = 25463, pid = 4242, startedAt = "t", coreVersion = "0.0.40",
        buildId = "b", protocolVersion = "1", release = "3.0.68-termux.2",
    )
    private val ready = KernelState.Ready(info, null)

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        diagScope.cancel()
        Dispatchers.resetMain()
    }

    private fun kernel(initial: KernelState = KernelState.Unknown) = FakeSetupKernel(initial, clock = { main.scheduler.currentTime })

    private fun vm(
        kernel: FakeSetupKernel,
        variant: SetupVariant = SetupVariant.FirstRun,
        prefs: FakeOnboardingPrefs = FakeOnboardingPrefs(),
        probe: FakeReliabilityProbe = FakeReliabilityProbe(),
        reliabilityAvailable: Boolean = true,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = KernelSetupViewModel(kernel, fakeDiagnostics(diagScope), prefs, probe, savedState, variant, reliabilityAvailable)

    private fun TestScope.done(vm: KernelSetupViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun `entry re-checks once and the rows follow the kernel`() = runTest(main) {
        val kernel = kernel()
        kernel.onRecheck = { emit(KernelState.HubDown(DownReason.Stopped, null, null)) }
        val vm = vm(kernel)
        assertEquals(listOf("recheck"), kernel.calls)
        val ui = vm.ui.value
        assertEquals(4, ui.readyCount)
        assertEquals(CheckFix.StartKernel, ui.checks.last().fix)
        assertFalse(ui.canContinue)
        kernel.emit(KernelState.PermissionMissing)
        assertEquals(1, vm.ui.value.readyCount)
        assertEquals(CheckId.Permission, vm.ui.value.announcement?.row)
        done(vm)
    }

    @Test
    fun `entry does not re-check while a start is already running`() = runTest(main) {
        val kernel = kernel(KernelState.Starting(StartStep.StartingHub, 0))
        val vm = vm(kernel)
        assertTrue(kernel.calls.isEmpty())
        assertEquals(CheckStatus.Checking, vm.ui.value.checks.last().status)
        done(vm)
    }

    /** `hub_start_slow`: Starting for 25 s, "taking longer than usual" after 20 s, then Ready. */
    @Test
    fun `a slow start counts seconds, flags slow after 20 s and then allows Continue`() = runTest(main) {
        val kernel = kernel(KernelState.HubDown(DownReason.Stopped, null, null))
        kernel.onStart = {
            emit(KernelState.Starting(StartStep.StartingHub, elapsedMs()))
            delay(25_000)
            emit(KernelState.Connecting(info))
            delay(500)
            emit(ready)
        }
        val vm = vm(kernel)
        vm.startKernel()
        assertEquals(listOf("recheck", "start"), kernel.calls)
        assertEquals(CheckStatus.Checking, vm.ui.value.checks.last().status)
        advanceTimeBy(5_001)
        assertEquals(5, vm.ui.value.startElapsedSec)
        assertFalse(vm.ui.value.slowStart)
        advanceTimeBy(16_000)
        assertTrue(vm.ui.value.slowStart)
        assertTrue(vm.ui.value.startElapsedSec!! >= 20)
        vm.startKernel() // a second tap while starting is ignored
        assertEquals(1, kernel.calls.count { it == "start" })
        advanceTimeBy(5_000)
        val ui = vm.ui.value
        assertTrue(ui.canContinue)
        assertEquals(5, ui.readyCount)
        assertNull(ui.startElapsedSec)
        assertFalse(ui.slowStart)
        assertEquals(SetupAnnouncement(null, null, ui.announcement!!.serial), ui.announcement)
        done(vm)
    }

    @Test
    fun `a timed-out start pre-fills the diagnostics from the doctor report`() = runTest(main) {
        val doctor = DoctorSummary("3.0.68", "0.0.40", false, null, null, emptyList(), listOf(77), 1, emptyList())
        val kernel = kernel(KernelState.HubDown(DownReason.StartTimedOut, null, doctor))
        val vm = vm(kernel)
        assertTrue(vm.ui.value.diagnostics is DiagnosticsUi.Loaded)
        assertEquals(CheckFix.StartFailed(null, timedOut = true), vm.ui.value.checks.last().fix)
        done(vm)
    }

    @Test
    fun `Run diagnostics shows the doctor result`() = runTest(main) {
        val kernel = kernel(KernelState.HubDown(DownReason.StartFailed, "boom", null))
        val vm = vm(kernel)
        vm.runDiagnostics()
        assertTrue(vm.ui.value.diagnostics is DiagnosticsUi.Error)
        done(vm)
    }

    @Test
    fun `Continue goes to the reliability step the first time a verifiable item is open`() = runTest(main) {
        val vm = vm(kernel(ready))
        vm.events.test {
            vm.onContinue()
            assertEquals(KernelSetupEvent.ToReliability, awaitItem())
        }
        done(vm)
    }

    @Test
    fun `Continue skips reliability when its items are done, and records it as shown`() = runTest(main) {
        val prefs = FakeOnboardingPrefs()
        val vm = vm(kernel(ready), prefs = prefs, probe = FakeReliabilityProbe(done = true))
        vm.events.test {
            vm.onContinue()
            assertEquals(KernelSetupEvent.ToConnect, awaitItem())
        }
        assertEquals(1, prefs.marks)
        done(vm)
    }

    @Test
    fun `Continue goes to Connect once reliability was shown, or when no reliability step exists`() = runTest(main) {
        val shown = FakeOnboardingPrefs(shownAt = 5L)
        val a = vm(kernel(ready), prefs = shown)
        a.events.test {
            a.onContinue()
            assertEquals(KernelSetupEvent.ToConnect, awaitItem())
        }
        assertEquals(0, shown.marks)
        done(a)

        val none = FakeOnboardingPrefs()
        val b = vm(kernel(ready), prefs = none, reliabilityAvailable = false)
        b.events.test {
            b.onContinue()
            assertEquals(KernelSetupEvent.ToConnect, awaitItem())
        }
        assertEquals(0, none.marks)
        done(b)
    }

    @Test
    fun `Continue does nothing before all five pass`() = runTest(main) {
        val vm = vm(kernel(KernelState.HubDown(DownReason.Stopped, null, null)))
        vm.events.test {
            vm.onContinue()
            expectNoEvents()
        }
        done(vm)
    }

    @Test
    fun `the permission row moves from Allow to Allow again to app settings`() = runTest(main) {
        val kernel = kernel(KernelState.PermissionMissing)
        val saved = SavedStateHandle()
        val vm = vm(kernel, savedState = saved)
        fun fix() = vm.ui.value.checks[1].fix
        assertEquals(CheckFix.RequestPermission(PermissionMode.Ask), fix())
        vm.onPermissionResult(granted = false, showRationale = true)
        assertEquals(CheckFix.RequestPermission(PermissionMode.AskAgain), fix())
        vm.onPermissionResult(granted = false, showRationale = false)
        assertEquals(CheckFix.RequestPermission(PermissionMode.OpenSettings), fix())

        // The mode survives process death through the SavedStateHandle.
        val again = vm(kernel, savedState = saved)
        assertEquals(CheckFix.RequestPermission(PermissionMode.OpenSettings), again.ui.value.checks[1].fix)
        done(vm)
        done(again)
    }

    @Test
    fun `a granted permission re-checks and moves focus to the next row`() = runTest(main) {
        val kernel = kernel(KernelState.PermissionMissing)
        kernel.onRecheck = { emit(KernelState.ExternalAppsBlocked("blocked")) }
        val vm = vm(kernel)
        kernel.calls.clear()
        vm.onPermissionResult(granted = true, showRationale = false)
        assertEquals(listOf("recheck"), kernel.calls)
        assertEquals(CheckId.ExternalApps, vm.ui.value.focusRequest?.row)
        assertEquals(CheckStatus.NeedsAction, vm.ui.value.checks[2].status)
        assertNull(vm.ui.value.checkingRow)
        done(vm)
    }

    @Test
    fun `resume re-checks while a prerequisite fails, and once after leaving for a fix`() = runTest(main) {
        val kernel = kernel(KernelState.ExternalAppsBlocked("x"))
        val vm = vm(kernel)
        kernel.calls.clear()
        vm.onResume()
        assertEquals(listOf("recheck"), kernel.calls)

        kernel.emit(KernelState.HubDown(DownReason.Stopped, null, null))
        kernel.calls.clear()
        vm.onResume()
        assertTrue(kernel.calls.isEmpty())
        vm.onLeftForFix()
        vm.onResume()
        assertEquals(listOf("recheck"), kernel.calls)
        done(vm)
    }

    @Test
    fun `Install Termux pushes B`() = runTest(main) {
        val vm = vm(kernel(KernelState.TermuxMissing(false)))
        vm.events.test {
            vm.installTermux()
            assertEquals(KernelSetupEvent.ToInstallTermux, awaitItem())
        }
        done(vm)
    }

    @Test
    fun `the blocking repair auto-starts an unreachable hub and leaves 600 ms after it connects`() = runTest(main) {
        val kernel = kernel(KernelState.HubDown(DownReason.Unreachable, null, null))
        kernel.onAutoStart = {
            emit(KernelState.Starting(StartStep.StartingHub, elapsedMs()))
            delay(3_000)
            emit(ready)
        }
        val vm = vm(kernel, variant = SetupVariant.Repair(CheckId.Hub, pushed = false))
        vm.events.test {
            assertTrue("autoStart" in kernel.calls)
            advanceTimeBy(3_001)
            expectNoEvents()
            advanceTimeBy(600)
            assertEquals(KernelSetupEvent.RepairResolved(connected = true), awaitItem())
        }
        assertEquals(1, kernel.calls.count { it == "autoStart" })
        done(vm)
    }

    @Test
    fun `a hub stopped by the user is not auto-started`() = runTest(main) {
        val kernel = kernel(KernelState.HubDown(DownReason.Stopped, null, null))
        val vm = vm(kernel, variant = SetupVariant.Repair(CheckId.Hub, pushed = true))
        assertFalse("autoStart" in kernel.calls)
        done(vm)
    }

    @Test
    fun `the pushed repair leaves once its focused row passes, even with the hub down`() = runTest(main) {
        val kernel = kernel(KernelState.PermissionMissing)
        kernel.onRecheck = { emit(KernelState.HubDown(DownReason.Stopped, null, null)) }
        kernel.mutableState.value = KernelState.PermissionMissing
        val vm = vm(kernel, variant = SetupVariant.Repair(CheckId.Permission, pushed = true))
        vm.events.test {
            advanceTimeBy(601)
            assertEquals(KernelSetupEvent.RepairResolved(connected = false), awaitItem())
        }
        assertTrue(vm.ui.value.pushed)
        done(vm)
    }

    @Test
    fun `the repair summary counts the passed rows above the first open one`() = runTest(main) {
        val vm = vm(kernel(KernelState.PortMissing), variant = SetupVariant.Repair(CheckId.Kernel, pushed = false))
        assertEquals(3, vm.ui.value.okAbove)
        assertFalse(vm.ui.value.summaryExpanded)
        vm.toggleSummary()
        assertTrue(vm.ui.value.summaryExpanded)
        done(vm)
    }

    @Test
    fun `Check again shows its row spinner until the re-check returns`() = runTest(main) {
        val kernel = kernel(KernelState.PortMissing)
        kernel.onRecheck = { delay(1_000) }
        val vm = vm(kernel)
        advanceTimeBy(1_001)
        vm.recheckFrom(CheckId.Kernel)
        assertEquals(CheckId.Kernel, vm.ui.value.checkingRow)
        vm.recheckFrom(CheckId.ExternalApps) // ignored while one runs
        assertEquals(CheckId.Kernel, vm.ui.value.checkingRow)
        advanceTimeBy(1_001)
        assertNull(vm.ui.value.checkingRow)
        done(vm)
    }

    @Test
    fun `Check again keeps the failing row and its fix while the probe runs, then shows the result`() = runTest(main) {
        val kernel = kernel(KernelState.PortTooOld("3.0.68-termux.1"))
        val vm = vm(kernel)
        advanceTimeBy(1_001)
        kernel.onRecheck = {
            emit(KernelState.Starting(StartStep.CheckingKernel, 0))
            delay(1_000)
            emit(KernelState.PortMissing)
        }
        val before = vm.ui.value.checks[3]
        assertEquals(CheckStatus.NeedsAction, before.status)
        vm.recheckFrom(CheckId.Kernel)
        assertEquals(CheckId.Kernel, vm.ui.value.checkingRow)
        assertEquals(before, vm.ui.value.checks[3])
        assertEquals(CheckStatus.Passed, vm.ui.value.checks[2].status)
        advanceTimeBy(1_001)
        assertNull(vm.ui.value.checkingRow)
        assertEquals(CheckStatus.NeedsAction, vm.ui.value.checks[3].status)
        assertTrue(vm.ui.value.checks[3].fix is CheckFix.RunInTermux)
        done(vm)
    }
}
