package dev.cline.termux.ui

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.ui.kernel.HubMismatch
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.ChatSheet
import dev.cline.termux.ui.navigation.DeepLink
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelReliability
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.navigation.SupportingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val info = HubInfo("ws://127.0.0.1:25463/hub", 25463, 1, "t", "3.0.68", "b", "v1")

    private class FakeShellKernel(state: KernelState = KernelState.Unknown, gate: KernelGate = KernelGate.Ready) : ShellKernel {
        override val state = MutableStateFlow(state)
        override val gate = MutableStateFlow(gate)
        val ensureCalls = mutableListOf<Pair<Boolean, Boolean>>()
        var ensureGate: CompletableDeferred<Unit>? = null
        var ensureError: Exception? = null
        var restartOutcome: RestartOutcome = RestartOutcome.Restarted
        var restarts = 0

        override suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean) {
            ensureCalls += startIfDown to userInitiated
            ensureGate?.await()
            ensureError?.let { throw it }
        }

        override suspend fun restart(forced: Boolean): RestartOutcome {
            restarts++
            return restartOutcome
        }

        fun set(state: KernelState, gate: KernelGate) {
            this.state.value = state
            this.gate.value = gate
        }
    }

    private class FakeWhatsNew(var pending: String?) : WhatsNewTrigger {
        val seen = mutableListOf<String>()
        override suspend fun pendingId(): String? = pending
        override suspend fun markSeen(id: String) {
            seen += id
            pending = null
        }
    }

    private fun vm(
        kernel: ShellKernel = FakeShellKernel(),
        savedState: SavedStateHandle = SavedStateHandle(),
        whatsNew: WhatsNewTrigger = WhatsNewTrigger.None,
    ) = AppShellViewModel(savedState, kernel = { kernel }, whatsNew = whatsNew, work = main)

    // ---- kernel projection ----------------------------------------------------------------

    @Test
    fun `the ui follows the kernel gate and keeps the last hub port`() = runTest {
        val kernel = FakeShellKernel()
        val vm = vm(kernel)
        assertEquals(KernelGate.Ready, vm.ui.value.gate)
        assertNull(vm.ui.value.hubPort)

        kernel.set(KernelState.Ready(info, null), KernelGate.Ready)
        assertEquals(25463, vm.ui.value.hubPort)
        assertNull(vm.ui.value.banner)

        val asleep = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        kernel.set(KernelState.Unknown, asleep)
        assertEquals(asleep, vm.ui.value.gate)
        assertEquals(25463, vm.ui.value.hubPort)
        assertEquals(KernelAction.StartKernel, vm.ui.value.banner?.action)
    }

    @Test
    fun `missing termux and blocked prerequisites replace the shell`() = runTest {
        val kernel = FakeShellKernel()
        val vm = vm(kernel)
        assertNull(vm.replacement.value)

        kernel.set(KernelState.TermuxMissing(disabled = false), KernelGate.Blocked(CheckId.Termux))
        assertEquals(KernelInstallTermux, vm.replacement.value)

        kernel.set(KernelState.PermissionMissing, KernelGate.Blocked(CheckId.Permission))
        assertEquals(KernelRepair(focus = "Permission", pushed = false), vm.replacement.value)

        kernel.set(KernelState.Ready(info, null), KernelGate.Ready)
        assertNull(vm.replacement.value)
    }

    @Test
    fun `replacement rule`() {
        assertEquals(KernelInstallTermux, AppShellViewModel.replacementFor(KernelState.TermuxMissing(true), KernelGate.Blocked(CheckId.Termux)))
        assertEquals(KernelRepair.of(CheckId.Kernel, false), AppShellViewModel.replacementFor(KernelState.PortMissing, KernelGate.Blocked(CheckId.Kernel)))
        assertNull(AppShellViewModel.replacementFor(KernelState.Unknown, KernelGate.DegradedNoCommands))
    }

    // ---- kernel actions -------------------------------------------------------------------

    @Test
    fun `start kernel is a user initiated ensure connected with start`() = runTest {
        val kernel = FakeShellKernel().apply { ensureGate = CompletableDeferred() }
        val vm = vm(kernel)

        vm.onKernelAction(KernelAction.StartKernel)
        assertEquals(KernelAction.StartKernel, vm.ui.value.kernelActionInFlight)
        // A second tap while the first runs is ignored.
        vm.onKernelAction(KernelAction.TryAgain)
        assertEquals(listOf(true to true), kernel.ensureCalls)

        kernel.ensureGate!!.complete(Unit)
        assertNull(vm.ui.value.kernelActionInFlight)

        kernel.ensureGate = null
        vm.onKernelAction(KernelAction.Connect)
        assertEquals(listOf(true to true, false to true), kernel.ensureCalls)
        assertNull(vm.ui.value.kernelActionInFlight)
    }

    @Test
    fun `a failing kernel call clears the in flight action`() = runTest {
        val kernel = FakeShellKernel().apply { ensureError = IllegalStateException("boom") }
        val vm = vm(kernel)
        vm.onKernelAction(KernelAction.TryAgain)
        assertNull(vm.ui.value.kernelActionInFlight)
        assertEquals(1, kernel.ensureCalls.size)
    }

    @Test
    fun `status actions open the kernel sheet`() = runTest {
        val vm = vm()
        vm.onKernelAction(KernelAction.KernelStatus)
        assertEquals(KernelSheetRequest(diagnostics = false), vm.ui.value.kernelSheet)
        vm.onKernelAction(KernelAction.KernelStatusDiagnostics)
        assertEquals(KernelSheetRequest(diagnostics = true), vm.ui.value.kernelSheet)
        vm.dismissKernelSheet()
        assertNull(vm.ui.value.kernelSheet)
        vm.onKernelAction(KernelAction.Details)
        assertEquals(KernelSheetRequest(diagnostics = false), vm.ui.value.kernelSheet)
    }

    @Test
    fun `Restart kernel opens the status sheet with a one-shot restart request`() = runTest {
        val kernel = FakeShellKernel(KernelState.Ready(info, null), KernelGate.Ready)
        val saved = SavedStateHandle()
        val vm = vm(kernel, savedState = saved)
        vm.onKernelAction(KernelAction.RestartKernel)
        val first = vm.ui.value.kernelSheet!!
        assertEquals(false, first.diagnostics)
        assertNotNull(first.restart)
        assertEquals("the sheet runs the E.7 pre-check and confirm, not the shell", 0, kernel.restarts)
        assertNull(vm.ui.value.hubUpdateDialog)

        vm.dismissKernelSheet()
        vm.onKernelAction(KernelAction.RestartKernel)
        assertTrue("every tap is a new request", vm.ui.value.kernelSheet!!.restart!! > first.restart!!)
        vm.onKernelAction(KernelAction.KernelStatus)
        assertNull("a plain open carries no restart", vm.ui.value.kernelSheet!!.restart)

        vm.onKernelAction(KernelAction.RestartKernel)
        assertEquals("a recreated shell reopens the sheet but never restarts by itself", KernelSheetRequest(diagnostics = false), vm(kernel, savedState = saved).ui.value.kernelSheet)
    }

    @Test
    fun `reliability and fix open root screens`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onKernelAction(KernelAction.WhyDidThisHappen)
            assertEquals(ShellEvent.OpenRoot(KernelReliability), awaitItem())
            vm.onKernelAction(KernelAction.Fix)
            assertEquals(ShellEvent.OpenRoot(KernelRepair(focus = "Permission", pushed = true)), awaitItem())
        }
    }

    // ---- hub update dialog (E.9) ----------------------------------------------------------

    @Test
    fun `protocol unsupported shows the dialog and a restart ends in a snackbar`() = runTest {
        val kernel = FakeShellKernel()
        val vm = vm(kernel)
        vm.events.test {
            kernel.set(KernelState.ProtocolUnsupported(info), KernelGate.Down(KernelGate.DownVariant.Protocol, null))
            assertEquals(HubMismatch.HubNewer, vm.ui.value.hubUpdateDialog?.mismatch)

            vm.confirmHubUpdate()
            assertEquals(1, kernel.restarts)
            assertNull(vm.ui.value.hubUpdateDialog)
            assertEquals(ShellEvent.Snackbar(R.string.kernel_restarted_snackbar), awaitItem())
        }
    }

    @Test
    fun `later hides the dialog until the next start or restart kernel`() = runTest {
        val kernel = FakeShellKernel()
        val vm = vm(kernel)
        kernel.set(KernelState.ProtocolUnsupported(info), KernelGate.Down(KernelGate.DownVariant.Protocol, null))
        vm.dismissHubUpdate()
        assertNull(vm.ui.value.hubUpdateDialog)

        vm.onStart()
        assertNotNull(vm.ui.value.hubUpdateDialog)

        vm.dismissHubUpdate()
        vm.onKernelAction(KernelAction.RestartKernel)
        assertNotNull(vm.ui.value.hubUpdateDialog)

        kernel.set(KernelState.Ready(info, null), KernelGate.Ready)
        assertNull(vm.ui.value.hubUpdateDialog)
    }

    @Test
    fun `a failed restart keeps the dialog with the error`() = runTest {
        val kernel = FakeShellKernel().apply { restartOutcome = RestartOutcome.Failed("nope") }
        val vm = vm(kernel)
        kernel.set(KernelState.ProtocolUnsupported(info), KernelGate.Down(KernelGate.DownVariant.Protocol, null))
        vm.confirmHubUpdate()
        val dialog = vm.ui.value.hubUpdateDialog
        assertNotNull(dialog)
        assertTrue(dialog!!.error.isNotEmpty())
    }

    // ---- kernel provider ------------------------------------------------------------------

    @Test
    fun `the kernel is resolved once`() = runTest {
        var resolutions = 0
        val kernel = FakeShellKernel()
        val vm = AppShellViewModel(SavedStateHandle(), kernel = { resolutions++; kernel }, work = main)
        vm.onKernelAction(KernelAction.StartKernel)
        vm.onKernelAction(KernelAction.Connect)
        assertEquals(1, resolutions)
        assertEquals(2, kernel.ensureCalls.size)
    }

    // ---- what's new -----------------------------------------------------------------------

    @Test
    fun `whats new appears on start and is marked seen on close`() = runTest {
        val whatsNew = FakeWhatsNew("3.0.68-termux.2")
        val vm = vm(whatsNew = whatsNew)
        assertNull(vm.ui.value.whatsNewId)
        vm.onStart()
        assertEquals("3.0.68-termux.2", vm.ui.value.whatsNewId)
        vm.closeWhatsNew()
        assertNull(vm.ui.value.whatsNewId)
        assertEquals(listOf("3.0.68-termux.2"), whatsNew.seen)
        vm.onStart()
        assertNull(vm.ui.value.whatsNewId)
    }

    // ---- drawer, panes, requests ----------------------------------------------------------

    @Test
    fun `closing the drawer ends a rename`() = runTest {
        val vm = vm()
        vm.setDrawerOpen(true)
        vm.setDrawerRenaming(true)
        assertTrue(vm.ui.value.drawerRenaming)
        vm.setDrawerOpen(false)
        assertEquals(false, vm.ui.value.drawerOpen)
        assertEquals(false, vm.ui.value.drawerRenaming)
    }

    @Test
    fun `list pane, supporting pane and sheet survive recreation`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(savedState = handle)
        first.toggleListPane()
        first.showSupporting(SupportingContent.Tool("s-1", "t-1"))
        first.openKernelSheet(diagnostics = true)
        assertTrue(first.ui.value.listPaneCollapsed)

        val second = vm(savedState = handle)
        assertTrue(second.ui.value.listPaneCollapsed)
        assertEquals(SupportingContent.Tool("s-1", "t-1"), second.ui.value.supporting)
        assertEquals(KernelSheetRequest(diagnostics = true), second.ui.value.kernelSheet)

        second.clearSupporting()
        second.dismissKernelSheet()
        second.toggleListPane()
        val third = vm(savedState = handle)
        assertEquals(false, third.ui.value.listPaneCollapsed)
        assertNull(third.ui.value.supporting)
        assertNull(third.ui.value.kernelSheet)
    }

    @Test
    fun `layout is recorded`() = runTest {
        val vm = vm()
        vm.onLayout(ShellLayout(1280, 800))
        assertEquals(ShellLayout(1280, 800), vm.ui.value.layout)
    }

    @Test
    fun `focus and docked search requests reach a listener`() = runTest {
        val vm = vm()
        var focus = 0
        var search = 0
        backgroundScope.launch(main) { vm.composerFocusRequests.collect { focus++ } }
        backgroundScope.launch(main) { vm.dockedSearchRequests.collect { search++ } }
        vm.requestComposerFocus()
        vm.requestDockedSearch()
        vm.requestDockedSearch()
        assertEquals(1, focus)
        assertEquals(2, search)
    }

    // ---- deep links -----------------------------------------------------------------------

    @Test
    fun `a session link waits until consumed and survives recreation`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(savedState = handle)
        val link = DeepLink.Session("s-1", ChatSheet.Approval)
        first.offerDeepLink(link)
        assertEquals(link, first.ui.value.pendingDeepLink)

        val second = vm(savedState = handle)
        assertEquals(link, second.ui.value.pendingDeepLink)
        assertEquals(link, second.consumeDeepLink())
        assertNull(second.ui.value.pendingDeepLink)
        assertNull(second.consumeDeepLink())
        assertNull(vm(savedState = handle).ui.value.pendingDeepLink)
    }

    @Test
    fun `the kernel status link opens the sheet directly`() = runTest {
        val vm = vm()
        vm.offerDeepLink(DeepLink.KernelStatus)
        assertEquals(KernelSheetRequest(diagnostics = false), vm.ui.value.kernelSheet)
        assertNull(vm.ui.value.pendingDeepLink)
    }
}
