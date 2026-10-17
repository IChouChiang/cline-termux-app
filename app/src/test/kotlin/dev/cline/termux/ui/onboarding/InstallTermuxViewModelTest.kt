package dev.cline.termux.ui.onboarding

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.fakes.FakeSetupKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
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
class InstallTermuxViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `B variants follow P1 first, then the coordinator state`() {
        val ok = TermuxPresence.Installed(118, "0.118.3")
        assertEquals(InstallTermuxVariant.Missing, InstallTermuxViewModel.variantOf(TermuxPresence.Missing, null))
        assertEquals(InstallTermuxVariant.Disabled, InstallTermuxViewModel.variantOf(TermuxPresence.Disabled, KernelState.Unknown))
        assertEquals(InstallTermuxVariant.TooOld("0.101.0"), InstallTermuxViewModel.variantOf(TermuxPresence.Installed(101, "0.101.0"), null))
        assertEquals(InstallTermuxVariant.NotSetUp, InstallTermuxViewModel.variantOf(ok, KernelState.TermuxNotSetUp))
        assertEquals(InstallTermuxVariant.TooOld("0.100"), InstallTermuxViewModel.variantOf(ok, KernelState.TermuxTooOld("0.100")))
        assertNull(InstallTermuxViewModel.variantOf(ok, null))
        assertNull(InstallTermuxViewModel.variantOf(ok, KernelState.PortMissing))
    }

    @Test
    fun `a missing Termux stays on B without asking the kernel`() = runTest(main) {
        val kernel = FakeSetupKernel(KernelState.TermuxMissing(false), presence = TermuxPresence.Missing)
        val vm = InstallTermuxViewModel(kernel, pushedFromSetup = true)
        assertEquals(InstallTermuxVariant.Missing, vm.ui.value.variant)
        assertTrue(vm.ui.value.pushedFromSetup)
        vm.onResume()
        assertTrue(kernel.calls.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `Check again shows Checking for at least 400 ms`() = runTest(main) {
        val kernel = FakeSetupKernel(KernelState.TermuxMissing(false), presence = TermuxPresence.Missing)
        val vm = InstallTermuxViewModel(kernel, pushedFromSetup = false)
        vm.checkAgain()
        assertTrue(vm.ui.value.checking)
        advanceTimeBy(401)
        assertFalse(vm.ui.value.checking)
        assertEquals(InstallTermuxVariant.Missing, vm.ui.value.variant)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `an installed Termux without its bootstrap shows Not set up`() = runTest(main) {
        val kernel = FakeSetupKernel(KernelState.Unknown)
        kernel.onRecheck = { emit(KernelState.TermuxNotSetUp) }
        val vm = InstallTermuxViewModel(kernel, pushedFromSetup = true)
        assertEquals(InstallTermuxVariant.NotSetUp, vm.ui.value.variant)
        assertFalse(vm.ui.value.ready)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `installing Termux leaves B 600 ms after it is ready`() = runTest(main) {
        val kernel = FakeSetupKernel(KernelState.TermuxMissing(false), presence = TermuxPresence.Missing)
        kernel.onRecheck = { emit(KernelState.HubDown(DownReason.Stopped, null, null)) }
        val vm = InstallTermuxViewModel(kernel, pushedFromSetup = true)
        vm.events.test {
            kernel.presence = TermuxPresence.Installed(118, "0.118.3")
            vm.onPackageChanged()
            assertTrue(vm.ui.value.ready)
            assertEquals(listOf("recheck"), kernel.calls)
            expectNoEvents()
            advanceTimeBy(601)
            assertEquals(InstallTermuxEvent.Done, awaitItem())
        }
        vm.onResume() // ready: nothing more to check
        assertEquals(1, kernel.calls.size)
        vm.viewModelScope.cancel()
    }
}
