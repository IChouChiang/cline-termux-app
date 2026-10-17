package dev.cline.termux.ui.settings

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalWriteFailure
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.CompactionMode
import dev.cline.termux.data.session.CompactionSaveError
import dev.cline.termux.data.session.CompactionSaveException
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class SettingsHomeViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSources : SettingsHomeSources {
        override val appearance = MutableStateFlow(AppearancePrefs())
        override val notificationSettings = MutableStateFlow(NotificationSettings.DEFAULT)
        override val kernelGate = MutableStateFlow<KernelGate>(KernelGate.Busy(KernelGate.BusyKind.Starting, null, slow = false))
        override val hubInfo = MutableStateFlow<HubInfo?>(null)
        override val catalog = MutableStateFlow(CatalogState())
        override val account = MutableStateFlow<Pair<String?, String?>>(null to null)
        override val signInPending = MutableStateFlow<SignInPending?>(null)
        override val helperState = MutableStateFlow<HelperState>(HelperState.Idle)
        override val telemetryLocked = MutableStateFlow(false)
        override val scheduleCounts = MutableStateFlow<ScheduleCounts?>(null)
        override val installedInvalidations: Flow<Unit> get() = invalidations
        val invalidations = MutableSharedFlow<Unit>()

        var permission = SummaryPermission.Granted
        var block: KernelBlock? = null
        var optOut: () -> Boolean = { false }
        var installed: InstalledCounts? = InstalledCounts(2, 1, 0, 0)
        var settled: HelperState = HelperState.Ready
        var write: suspend (Boolean) -> Boolean = { it }

        val installedLoads = mutableListOf<Boolean>()
        var scheduleRefreshes = 0
        var presenceRefreshes = 0
        val acquired = mutableListOf<String>()
        val released = mutableListOf<String>()
        var retries = 0
        val writes = mutableListOf<Boolean>()

        override fun notificationPermission() = permission
        override fun termuxBlock() = block
        override suspend fun readTelemetryOptOut() = optOut()
        override suspend fun refreshPresence() {
            presenceRefreshes++
        }
        override suspend fun loadInstalled(force: Boolean): InstalledCounts? {
            installedLoads += force
            return installed
        }
        override suspend fun refreshSchedules() {
            scheduleRefreshes++
        }
        override suspend fun acquireHelper(owner: String) {
            acquired += owner
        }
        override suspend fun retryHelper() {
            retries++
        }
        override fun releaseHelper(owner: String) {
            released += owner
        }
        override suspend fun awaitHelper() = settled
        override suspend fun setTelemetryOptOut(optOut: Boolean): Boolean {
            writes += optOut
            return write(optOut)
        }

        override val compactionMode = MutableStateFlow<CompactionMode?>(null)
        var compactionRefreshes = 0
        val compactionWrites = mutableListOf<CompactionMode>()
        var compactionWrite: suspend (CompactionMode) -> CompactionMode = { it }

        override suspend fun refreshCompaction() {
            compactionRefreshes++
        }

        override suspend fun setCompactionMode(mode: CompactionMode): CompactionMode {
            compactionWrites += mode
            val adopted = compactionWrite(mode)
            compactionMode.value = adopted
            return adopted
        }

        override val approvalMode = MutableStateFlow(ApprovalMode.Unknown)
        override val approvalWriting = MutableStateFlow(false)
        override val approvalPending = MutableStateFlow(false)
        var approvalRefreshes = 0
        var approvalRead: ApprovalMode? = null
        val approvalWrites = mutableListOf<Boolean>()
        var approvalWrite: suspend (Boolean) -> ApprovalWriteResult = { auto ->
            approvalMode.value = ApprovalMode.of(auto)
            ApprovalWriteResult.Written
        }

        override suspend fun refreshApprovalMode() {
            approvalRefreshes++
            approvalRead?.let { approvalMode.value = it }
        }

        override suspend fun setAutoApprove(auto: Boolean): ApprovalWriteResult {
            approvalWrites += auto
            return approvalWrite(auto)
        }

        override val messageDetails = MutableStateFlow(MessageDetails.Brief)
        val detailWrites = mutableListOf<MessageDetails>()
        override suspend fun setMessageDetails(mode: MessageDetails) {
            detailWrites += mode
            messageDetails.value = mode
        }
    }

    private val sources = FakeSources()

    private fun TestScope.viewModel(handle: SavedStateHandle = SavedStateHandle()): SettingsHomeViewModel {
        val vm = SettingsHomeViewModel(handle, sources, appVersion = "1.0.0", dynamicColorSupported = true)
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    private fun hub(version: String?) = HubInfo(
        url = "ws://127.0.0.1:25463",
        port = 25463,
        pid = 1,
        startedAt = null,
        coreVersion = version,
        buildId = null,
        protocolVersion = null,
    )

    @Test
    fun `local summaries render before any kernel answer`() = runTest(main) {
        sources.appearance.value = AppearancePrefs(themeMode = ThemeMode.Dark, dynamicColor = false, fontSize = 17)
        sources.permission = SummaryPermission.Denied
        val vm = viewModel()
        vm.onResume()
        val state = vm.uiState.value
        assertEquals(AppearanceSummary(ThemeMode.Dark, false, 17), state.appearanceSummary)
        assertTrue(state.notifications.blocked)
        assertEquals(4, state.notifications.enabledCount)
        assertFalse(state.kernelConnected)
        assertEquals("1.0.0", state.appVersion)
    }

    @Test
    fun `resume reads telemetry and provider presence`() = runTest(main) {
        sources.optOut = { true }
        val vm = viewModel()
        assertTrue(vm.uiState.value.telemetry.loading)
        vm.onResume()
        val telemetry = vm.uiState.value.telemetry
        assertEquals(false, telemetry.enabled)
        assertFalse(telemetry.loading)
        assertTrue(telemetry.switchEnabled)
        assertEquals(1, sources.presenceRefreshes)
    }

    @Test
    fun `termux block shows the card and hides provider and account summaries`() = runTest(main) {
        sources.block = KernelBlock.TermuxMissing
        sources.account.value = "Ada" to null
        val vm = viewModel()
        vm.onResume()
        val state = vm.uiState.value
        assertEquals(KernelBlock.TermuxMissing, state.blocked)
        assertNull(state.providers)
        assertEquals(AccountSummary.Unknown, state.account)
        assertNull(state.telemetry.enabled)
        assertFalse(state.telemetry.loading)
        assertEquals(0, sources.presenceRefreshes)
    }

    @Test
    fun `a blocked telemetry read raises the card but a timeout does not`() = runTest(main) {
        sources.optOut = { throw TermuxUnavailable.TimedOut("cat", 15_000) }
        val vm = viewModel()
        vm.onResume()
        assertNull(vm.uiState.value.blocked)
        assertFalse(vm.uiState.value.telemetry.loading)

        sources.optOut = { throw TermuxUnavailable.ExternalAppsBlocked("allow-external-apps") }
        vm.onResume()
        assertEquals(KernelBlock.ExternalAppsBlocked, vm.uiState.value.blocked)
    }

    @Test
    fun `hub summaries load on connect while visible and hide when disconnected`() = runTest(main) {
        sources.scheduleCounts.value = ScheduleCounts(3, 1)
        val vm = viewModel()
        vm.onResume()
        assertTrue(sources.installedLoads.isEmpty())
        assertNull(vm.uiState.value.installed)
        assertNull(vm.uiState.value.schedules)

        sources.hubInfo.value = hub("3.0.68")
        sources.kernelGate.value = KernelGate.Ready
        val state = vm.uiState.value
        assertEquals(listOf(false), sources.installedLoads)
        assertEquals(1, sources.scheduleRefreshes)
        assertTrue(state.kernelConnected)
        assertEquals(25463, state.kernelPort)
        assertEquals(InstalledCounts(2, 1, 0, 0), state.installed)
        assertEquals(ScheduleCounts(3, 1), state.schedules)

        sources.kernelGate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        assertNull(vm.uiState.value.installed)
        assertNull(vm.uiState.value.schedules)
    }

    @Test
    fun `a connect while stopped waits for the next resume`() = runTest(main) {
        val vm = viewModel()
        sources.kernelGate.value = KernelGate.Ready
        assertTrue(sources.installedLoads.isEmpty())
        vm.onResume()
        assertEquals(listOf(false), sources.installedLoads)
    }

    @Test
    fun `invalidations force an installed reload only while connected`() = runTest(main) {
        val vm = viewModel()
        sources.invalidations.emit(Unit)
        assertTrue(sources.installedLoads.isEmpty())
        sources.kernelGate.value = KernelGate.DegradedNoCommands
        sources.installed = InstalledCounts(0, 0, 4, 0)
        sources.invalidations.emit(Unit)
        assertEquals(listOf(true), sources.installedLoads)
        assertEquals(InstalledCounts(0, 0, 4, 0), vm.uiState.value.installed)
    }

    @Test
    fun `the kernel version survives a hub restart`() = runTest(main) {
        val vm = viewModel()
        sources.hubInfo.value = hub("3.0.68")
        assertEquals("3.0.68", vm.uiState.value.kernelVersion)
        sources.hubInfo.value = null
        assertEquals("3.0.68", vm.uiState.value.kernelVersion)
        assertNull(vm.uiState.value.kernelPort)
    }

    @Test
    fun `select is saved under the section key`() = runTest(main) {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.select(SettingsSection.Notifications)
        assertEquals("notifications", handle[SettingsHomeViewModel.KEY_SECTION])
        assertEquals(SettingsSection.Notifications, vm.uiState.value.selected)
        vm.select(null)
        assertNull(vm.uiState.value.selected)
    }

    @Test
    fun `an explicit section argument preselects the detail`() = runTest(main) {
        val vm = viewModel(SavedStateHandle(mapOf(SettingsHomeViewModel.KEY_SECTION to "about")))
        assertEquals(SettingsSection.About, vm.uiState.value.selected)
    }

    @Test
    fun `telemetry write is optimistic then adopts the reply`() = runTest(main) {
        val gate = CompletableDeferred<Boolean>()
        sources.write = { gate.await() }
        val vm = viewModel()
        vm.onResume()
        vm.setTelemetryEnabled(false)
        val saving = vm.uiState.value.telemetry
        assertEquals(false, saving.enabled)
        assertTrue(saving.saving)
        assertFalse(saving.switchEnabled)
        assertEquals(listOf(SettingsHomeViewModel.HELPER_OWNER), sources.acquired)
        assertEquals(listOf(true), sources.writes)

        gate.complete(true)
        val done = vm.uiState.value.telemetry
        assertEquals(false, done.enabled)
        assertFalse(done.saving)
        assertNull(done.error)
    }

    @Test
    fun `starting helper replaces the supporting line while saving`() = runTest(main) {
        val gate = CompletableDeferred<Boolean>()
        sources.write = { gate.await() }
        val vm = viewModel()
        vm.onResume()
        vm.setTelemetryEnabled(false)
        sources.helperState.value = HelperState.Starting(0L)
        assertTrue(vm.uiState.value.telemetry.startingHelper)
        gate.complete(true)
        assertFalse(vm.uiState.value.telemetry.startingHelper)
    }

    @Test
    fun `a failed write rolls back with the message`() = runTest(main) {
        sources.write = { throw IllegalStateException("disk full") }
        val vm = viewModel()
        vm.onResume()
        vm.setTelemetryEnabled(false)
        val telemetry = vm.uiState.value.telemetry
        assertEquals(true, telemetry.enabled)
        assertFalse(telemetry.saving)
        assertEquals(TelemetryError.Message("disk full"), telemetry.error)
        assertTrue(telemetry.switchEnabled)
    }

    @Test
    fun `an unavailable helper rolls back and the next toggle retries it`() = runTest(main) {
        sources.settled = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        val vm = viewModel()
        vm.onResume()
        vm.setTelemetryEnabled(false)
        assertEquals(TelemetryError.HelperUnavailable, vm.uiState.value.telemetry.error)
        assertEquals(true, vm.uiState.value.telemetry.enabled)
        assertTrue(sources.writes.isEmpty())

        sources.helperState.value = HelperState.Unavailable(HelperFailure.StartTimeout, "port busy")
        sources.settled = HelperState.Unavailable(HelperFailure.StartTimeout, "port busy")
        vm.setTelemetryEnabled(false)
        assertEquals(1, sources.retries)
        assertEquals(1, sources.acquired.size)
        assertEquals(TelemetryError.Message("port busy"), vm.uiState.value.telemetry.error)
    }

    @Test
    fun `telemetry is read-only when locked or the helper is known unsupported`() = runTest(main) {
        val vm = viewModel()
        vm.onResume()
        sources.telemetryLocked.value = true
        assertFalse(vm.uiState.value.telemetry.writable)
        vm.setTelemetryEnabled(false)
        assertTrue(sources.acquired.isEmpty())
        assertEquals(true, vm.uiState.value.telemetry.enabled)

        sources.telemetryLocked.value = false
        sources.helperState.value = HelperState.Unavailable(HelperFailure.BuildUnsupported, null)
        assertFalse(vm.uiState.value.telemetry.writable)
        vm.setTelemetryEnabled(false)
        assertTrue(sources.acquired.isEmpty())

        sources.helperState.value = HelperState.Unavailable(HelperFailure.StartTimeout, null)
        assertTrue(vm.uiState.value.telemetry.writable)
    }

    @Test
    fun `the helper is released on stop and after a write that ends while stopped`() = runTest(main) {
        val gate = CompletableDeferred<Boolean>()
        sources.write = { gate.await() }
        val vm = viewModel()
        vm.onResume()
        vm.setTelemetryEnabled(false)
        vm.onStop()
        assertTrue(sources.released.isEmpty())
        gate.complete(true)
        assertEquals(listOf(SettingsHomeViewModel.HELPER_OWNER), sources.released)

        vm.onResume()
        sources.write = { it }
        vm.setTelemetryEnabled(true)
        assertEquals(1, sources.released.size)
        vm.onStop()
        assertEquals(2, sources.released.size)
        vm.onStop()
        assertEquals(2, sources.released.size)
    }

    @Test
    fun `toggles are ignored before the value is known`() = runTest(main) {
        val vm = viewModel()
        vm.setTelemetryEnabled(false)
        assertTrue(sources.acquired.isEmpty())
        assertNull(vm.uiState.value.telemetry.enabled)
    }

    // ---- Context compaction (A11 row 2) ----------------------------------------------------------

    private fun TestScope.events(vm: SettingsHomeViewModel): MutableList<SettingsHomeEvent> {
        val events = mutableListOf<SettingsHomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        return events
    }

    @Test
    fun `settings open re-reads the compaction mode and the row shows it`() = runTest(main) {
        val vm = viewModel()
        assertNull(vm.uiState.value.compaction.mode)
        vm.onResume()
        assertEquals(1, sources.compactionRefreshes)
        sources.compactionMode.value = CompactionMode.Truncation
        val row = vm.uiState.value.compaction
        assertEquals(CompactionMode.Truncation, row.mode)
        assertTrue(row.enabled)
        assertFalse(row.saving)
    }

    @Test
    fun `each option is written and adopted`() = runTest(main) {
        sources.compactionMode.value = CompactionMode.Llm
        val vm = viewModel()
        vm.onResume()
        vm.setCompactionMode(CompactionMode.Off)
        assertEquals(CompactionMode.Off, vm.uiState.value.compaction.mode)
        vm.setCompactionMode(CompactionMode.Truncation)
        vm.setCompactionMode(CompactionMode.Llm)
        assertEquals(listOf(CompactionMode.Off, CompactionMode.Truncation, CompactionMode.Llm), sources.compactionWrites)
        assertEquals(CompactionMode.Llm, vm.uiState.value.compaction.mode)
        assertFalse(vm.uiState.value.compaction.saving)
        // The current choice writes nothing.
        vm.setCompactionMode(CompactionMode.Llm)
        assertEquals(3, sources.compactionWrites.size)
    }

    @Test
    fun `saving shows the new choice with a spinner and ignores another pick`() = runTest(main) {
        sources.compactionMode.value = CompactionMode.Llm
        val gate = CompletableDeferred<Unit>()
        sources.compactionWrite = { mode ->
            gate.await()
            mode
        }
        val vm = viewModel()
        vm.onResume()
        vm.setCompactionMode(CompactionMode.Off)
        assertEquals(CompactionRowState(mode = CompactionMode.Off, saving = true), vm.uiState.value.compaction)
        assertFalse(vm.uiState.value.compaction.enabled)
        vm.setCompactionMode(CompactionMode.Truncation)
        assertEquals(listOf(CompactionMode.Off), sources.compactionWrites)
        gate.complete(Unit)
        assertEquals(CompactionRowState(mode = CompactionMode.Off), vm.uiState.value.compaction)
    }

    @Test
    fun `a failed save rolls back and reports the reason`() = runTest(main) {
        sources.compactionMode.value = CompactionMode.Truncation
        sources.compactionWrite = { throw CompactionSaveException(CompactionSaveError.FileUnreadable) }
        val vm = viewModel()
        val events = events(vm)
        vm.onResume()
        vm.setCompactionMode(CompactionMode.Off)
        assertEquals(CompactionRowState(mode = CompactionMode.Truncation), vm.uiState.value.compaction)
        assertEquals(listOf(SettingsHomeEvent.CompactionSaveFailed(CompactionSaveError.FileUnreadable)), events)

        sources.compactionWrite = { error("stderr line") }
        vm.setCompactionMode(CompactionMode.Llm)
        assertEquals(CompactionMode.Truncation, vm.uiState.value.compaction.mode)
        assertEquals(SettingsHomeEvent.CompactionSaveFailed(CompactionSaveError.Failed("stderr line")), events.last())
    }

    @Test
    fun `without Termux commands the row is disabled and writes nothing`() = runTest(main) {
        sources.block = KernelBlock.PermissionMissing
        sources.compactionMode.value = CompactionMode.Llm
        val vm = viewModel()
        vm.onResume()
        assertTrue(vm.uiState.value.compaction.needsKernel)
        assertFalse(vm.uiState.value.compaction.enabled)
        assertEquals(0, sources.compactionRefreshes)
        vm.setCompactionMode(CompactionMode.Off)
        assertTrue(sources.compactionWrites.isEmpty())

        // RUN_COMMAND revoked while connected, or the kernel not installed, also disable it; a Hub-only problem does not.
        sources.block = null
        vm.onResume()
        sources.kernelGate.value = KernelGate.DegradedNoCommands
        assertTrue(vm.uiState.value.compaction.needsKernel)
        sources.kernelGate.value = KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Kernel)
        assertTrue(vm.uiState.value.compaction.needsKernel)
        sources.kernelGate.value = KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Hub)
        assertFalse(vm.uiState.value.compaction.needsKernel)
        sources.kernelGate.value = KernelGate.Ready
        vm.setCompactionMode(CompactionMode.Off)
        assertEquals(listOf(CompactionMode.Off), sources.compactionWrites)
    }

    // ---- Auto-approve all (decided A1) ------------------------------------------------------------

    @Test
    fun `Auto-approve all is disabled until read, then follows the kernel's setting`() = runTest(main) {
        sources.kernelGate.value = KernelGate.Ready
        val vm = viewModel()
        assertFalse(vm.uiState.value.autoApprove.enabled)

        sources.approvalRead = ApprovalMode.Auto
        vm.onResume()
        assertEquals(1, sources.approvalRefreshes)
        val row = vm.uiState.value.autoApprove
        assertTrue(row.enabled)
        assertTrue(row.checked)
    }

    @Test
    fun `Auto-approve all writes the kernel's setting and shows a failure under the switch`() = runTest(main) {
        sources.kernelGate.value = KernelGate.Ready
        sources.approvalRead = ApprovalMode.Ask
        val vm = viewModel()
        vm.onResume()

        vm.setAutoApprove(true)
        assertEquals(listOf(true), sources.approvalWrites)
        assertTrue(vm.uiState.value.autoApprove.checked)

        sources.approvalWrite = { ApprovalWriteResult.Failed(ApprovalWriteFailure.Unreadable) }
        vm.setAutoApprove(false)
        val row = vm.uiState.value.autoApprove
        assertTrue(row.checked)
        assertEquals(AutoApproveError(turningOn = false, failure = ApprovalWriteFailure.Unreadable), row.error)

        // The next attempt clears the line first.
        sources.approvalWrite = { auto ->
            sources.approvalMode.value = ApprovalMode.of(auto)
            ApprovalWriteResult.Written
        }
        vm.setAutoApprove(false)
        assertNull(vm.uiState.value.autoApprove.error)
        assertFalse(vm.uiState.value.autoApprove.checked)
    }

    @Test
    fun `Auto-approve all needs Termux commands and shows the pending sentence`() = runTest(main) {
        sources.approvalMode.value = ApprovalMode.Ask
        sources.kernelGate.value = KernelGate.DegradedNoCommands
        val vm = viewModel()
        assertFalse(vm.uiState.value.autoApprove.enabled)

        sources.kernelGate.value = KernelGate.Ready
        sources.approvalPending.value = true
        val row = vm.uiState.value.autoApprove
        assertTrue(row.enabled)
        assertTrue(row.pending)
    }

    // ------------------------------------------------------------ A11.9 Message details

    @Test
    fun `message details row shows the stored mode, Brief by default`() = runTest(main) {
        val vm = viewModel()
        assertEquals(MessageDetails.Brief, vm.uiState.value.messageDetails)
        sources.messageDetails.value = MessageDetails.Verbose
        assertEquals(MessageDetails.Verbose, vm.uiState.value.messageDetails)
    }

    @Test
    fun `the dialog's choice writes the preference`() = runTest(main) {
        val vm = viewModel()
        vm.setMessageDetails(MessageDetails.Verbose)
        assertEquals(listOf(MessageDetails.Verbose), sources.detailWrites)
        assertEquals(MessageDetails.Verbose, vm.uiState.value.messageDetails)
        vm.setMessageDetails(MessageDetails.Brief)
        assertEquals(listOf(MessageDetails.Verbose, MessageDetails.Brief), sources.detailWrites)
        assertEquals(MessageDetails.Brief, vm.uiState.value.messageDetails)
    }
}
