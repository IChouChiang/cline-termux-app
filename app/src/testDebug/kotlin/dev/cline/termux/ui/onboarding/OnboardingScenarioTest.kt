package dev.cline.termux.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.InMemoryKernelPreferences
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.debug.fake.FakeKernelScenario
import dev.cline.termux.debug.fake.ScriptedKernel
import dev.cline.termux.fakes.FakeOnboardingPrefs
import dev.cline.termux.fakes.FakeReliabilityProbe
import dev.cline.termux.fakes.FakeTokenCache
import dev.cline.termux.fakes.InMemoryCursorStore
import dev.cline.termux.fakes.TestKernelClock
import dev.cline.termux.ui.kernel.DiagnosticsUi
import dev.cline.termux.ui.kernel.KernelDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Kernel setup (A) over the real `KernelCoordinator` and Hub client driven by the debug
 * fake-kernel scenarios (docs/06): the timelines the debug build's `fake_kernel` switch plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingScenarioTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private inner class Rig(val scope: TestScope, val scenario: FakeKernelScenario) {
        val kernel = ScriptedKernel(scenario, scope, wallClockMs = { 1_760_000_000_000L + scope.testScheduler.currentTime }, random = Random(3))
        lateinit var coordinator: KernelCoordinator
        private val client = HubClient(
            transport = kernel.transport,
            identity = HubClientIdentity("android-fake", appVersion = "0.1"),
            scope = scope,
            cursors = InMemoryCursorStore(),
            credentials = { coordinator.currentCredentials() },
            random = Random(7),
        )
        private val clock = TestKernelClock(scope.testScheduler)

        init {
            coordinator = KernelCoordinator(
                commands = kernel.commands, prober = kernel.prober, tokens = FakeTokenCache(), prefs = InMemoryKernelPreferences(),
                hubClient = client, scope = scope, clock = clock,
            )
        }

        val setupKernel = SetupKernel.of(
            coordinator,
            clock,
            termux = { kernel.presence },
            permission = { scenario != FakeKernelScenario.PermissionMissing },
        )

        fun vm(variant: SetupVariant = SetupVariant.FirstRun) = KernelSetupViewModel(
            kernel = setupKernel,
            diagnosticsUseCase = KernelDiagnostics(KernelDiagnostics.Runner.of(coordinator, kernel.commands), scope),
            prefs = FakeOnboardingPrefs(),
            reliability = FakeReliabilityProbe(),
            savedState = SavedStateHandle(),
            variant = variant,
        )
    }

    private fun TestScope.finish(vararg vms: KernelSetupViewModel) {
        vms.forEach { it.viewModelScope.cancel() }
        runCurrent()
        coroutineContext.job.cancelChildren()
    }

    @Test
    fun `prerequisite scenarios stop at their row on entry`() = runTest(main) {
        val expected = mapOf(
            FakeKernelScenario.TermuxMissing to (CheckId.Termux to CheckFix.InstallTermux),
            FakeKernelScenario.TermuxDisabled to (CheckId.Termux to CheckFix.OpenTermuxSettings),
            FakeKernelScenario.TermuxOld to (CheckId.Termux to CheckFix.InstallTermux),
            FakeKernelScenario.ExternalAppsBlocked to (CheckId.ExternalApps to CheckFix.RunInTermux(KernelSetupRows.ALLOW_EXTERNAL_APPS_COMMAND)),
            FakeKernelScenario.TermuxNotSetUp to (CheckId.Kernel to CheckFix.OpenTermux),
            FakeKernelScenario.PortMissing to (CheckId.Kernel to CheckFix.RunInTermux(KernelSetupRows.INSTALLER_COMMAND)),
            FakeKernelScenario.PortOld to (CheckId.Kernel to CheckFix.RunInTermux(KernelSetupRows.INSTALLER_COMMAND)),
        )
        for ((scenario, pair) in expected) {
            val rig = Rig(this, scenario)
            val vm = rig.vm()
            advanceTimeBy(10_000)
            runCurrent()
            val open = KernelSetupRows.firstOpen(vm.ui.value.checks)!!
            assertEquals(scenario.id, pair.first, open.id)
            assertEquals(scenario.id, CheckStatus.NeedsAction, open.status)
            assertEquals(scenario.id, pair.second, open.fix)
            assertFalse(vm.ui.value.canContinue)
            vm.viewModelScope.cancel()
        }
        finish()
    }

    @Test
    fun `hub_start_slow counts up, says it is slow after 20 s, then passes all five`() = runTest(main) {
        val rig = Rig(this, FakeKernelScenario.HubStartSlow)
        val vm = rig.vm()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CheckFix.StartKernel, vm.ui.value.checks.last().fix)
        vm.startKernel()
        advanceTimeBy(21_000)
        runCurrent()
        val slow = vm.ui.value
        assertEquals(CheckStatus.Checking, slow.checks.last().status)
        assertTrue(slow.slowStart)
        assertTrue("elapsed ${slow.startElapsedSec}", slow.startElapsedSec!! >= 20)
        assertFalse(slow.canContinue)
        advanceTimeBy(10_000)
        runCurrent()
        val done = vm.ui.value
        assertTrue("state ${rig.coordinator.state.value}", rig.coordinator.state.value is KernelState.Ready)
        assertTrue(done.canContinue)
        assertEquals(5, done.readyCount)
        assertFalse(done.slowStart)
        finish(vm)
    }

    @Test
    fun `hub_start_hang fails row 5 as timed out with the doctor rows filled`() = runTest(main) {
        val rig = Rig(this, FakeKernelScenario.HubStartHang)
        val vm = rig.vm()
        advanceTimeBy(5_000)
        vm.startKernel()
        advanceTimeBy(5 * 60_000)
        runCurrent()
        val hub = vm.ui.value.checks.last()
        assertEquals(CheckStatus.Failed, hub.status)
        assertEquals(CheckFix.StartFailed(null, timedOut = true), hub.fix)
        assertTrue(vm.ui.value.diagnostics is DiagnosticsUi.Loaded)
        finish(vm)
    }

    @Test
    fun `hub_start_fail shows the kernel's error text and offers Try again`() = runTest(main) {
        val rig = Rig(this, FakeKernelScenario.HubStartFail)
        val vm = rig.vm()
        advanceTimeBy(5_000)
        vm.startKernel()
        advanceTimeBy(2 * 60_000)
        runCurrent()
        val hub = vm.ui.value.checks.last()
        assertEquals(CheckStatus.Failed, hub.status)
        val fix = hub.fix as CheckFix.StartFailed
        assertFalse(fix.timedOut)
        assertTrue("detail ${fix.detail}", fix.detail != null)
        finish(vm)
    }

    @Test
    fun `the blocking repair over a stopped hub starts it and resolves connected`() = runTest(main) {
        val rig = Rig(this, FakeKernelScenario.ProvidersAnthropic)
        val vm = rig.vm(SetupVariant.Repair(CheckId.Hub, pushed = false))
        val events = mutableListOf<KernelSetupEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue("state ${rig.coordinator.state.value}", rig.coordinator.state.value is KernelState.Ready)
        assertEquals(listOf<KernelSetupEvent>(KernelSetupEvent.RepairResolved(connected = true)), events)
        finish(vm)
    }
}
