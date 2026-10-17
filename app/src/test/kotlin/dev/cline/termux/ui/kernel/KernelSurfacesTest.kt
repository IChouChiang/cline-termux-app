package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelCommandResult
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelGate.BusyKind
import dev.cline.termux.core.kernel.KernelGate.DownVariant
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.ui.components.KernelBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KernelSurfacesTest {
    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    // ---- ComposerGate (CA §0.3, KS 0.10, F.3) ------------------------------------------------

    @Test
    fun `ready, degraded and draining leave the composer usable`() {
        assertEquals(ComposerGate.Usable, ComposerGate.of(KernelGate.Ready, 25463))
        assertEquals(ComposerGate.Usable, ComposerGate.of(KernelGate.DegradedNoCommands, 25463))
        assertEquals(ComposerGate.Usable, ComposerGate.of(KernelGate.Busy(BusyKind.Draining, null, false), 25463))
    }

    @Test
    fun `busy variants keep the draft editable but block sends`() {
        for (kind in listOf(BusyKind.Starting, BusyKind.Connecting, BusyKind.Reconnecting, BusyKind.Restarting)) {
            val gate = ComposerGate.of(KernelGate.Busy(kind, null, false), 25463)
            assertFalse(gate.readOnly)
            assertFalse(gate.kernelAllowsSend)
            assertNull(gate.placeholder)
            assertEquals(ComposerKernelRow.Busy(KernelCopy.chip(KernelGate.Busy(kind, null, false), 25463).line1), gate.row)
        }
    }

    @Test
    fun `down variants are read-only with the F3 placeholder and the F2 primary`() {
        val asleep = ComposerGate.of(KernelGate.Down(DownVariant.Asleep, null), null)
        assertTrue(asleep.readOnly)
        assertFalse(asleep.kernelAllowsSend)
        assertEquals(res(R.string.kernel_status_asleep_line2), asleep.placeholder)
        assertEquals(ComposerKernelRow.Down(res(R.string.kernel_status_asleep), KernelAction.StartKernel), asleep.row)

        val disconnected = ComposerGate.of(KernelGate.Down(DownVariant.Disconnected, null), 25463)
        assertEquals(res(R.string.hub_not_connected), disconnected.placeholder)
        assertEquals(ComposerKernelRow.Down(res(R.string.hub_at_port, "25463"), KernelAction.Connect), disconnected.row)

        val failed = ComposerGate.of(KernelGate.Down(DownVariant.StartFailed, "x"), null)
        assertEquals(ComposerKernelRow.Down(res(R.string.hub_at_unknown), KernelAction.TryAgain), failed.row)

        val protocol = ComposerGate.of(KernelGate.Down(DownVariant.Protocol, null), 25463)
        assertEquals(KernelAction.RestartKernel, (protocol.row as ComposerKernelRow.Down).action)
    }

    @Test
    fun `blocked shows the repair title and no button`() {
        val blocked = ComposerGate.of(KernelGate.Blocked(CheckId.Permission), null)
        assertTrue(blocked.readOnly)
        assertEquals(res(R.string.kernel_fix_kernel), blocked.placeholder)
        assertEquals(ComposerKernelRow.Down(res(R.string.hub_at_unknown), null), blocked.row)
    }

    @Test
    fun `the row button shows its busy label while the tap is pending`() {
        assertEquals(res(R.string.kernel_start), ComposerKernelButton(KernelAction.StartKernel, inFlight = false).label)
        val pending = ComposerKernelButton(KernelAction.StartKernel, inFlight = true)
        assertEquals(res(R.string.kernel_starting), pending.label)
        assertFalse(pending.enabled)
    }

    // ---- KernelBlockedCard (SP §0.5) ----------------------------------------------------------

    @Test
    fun `termux failures map to their cards`() {
        assertEquals(KernelBlock.TermuxMissing, KernelBlock.of(TermuxUnavailable.NotInstalled()))
        assertEquals(KernelBlock.PermissionMissing, KernelBlock.of(TermuxUnavailable.PermissionMissing()))
        assertEquals(KernelBlock.ExternalAppsBlocked, KernelBlock.of(TermuxUnavailable.ExternalAppsBlocked("allow-external-apps")))
        assertEquals(KernelBlock.TimedOut, KernelBlock.of(TermuxUnavailable.TimedOut("cat file", 60_000)))
        assertNull(KernelBlock.of(TermuxUnavailable.StartFailed(IllegalStateException("x"))))
        assertEquals(KernelBlock.TermuxMissing, KernelBlock.of(KernelGate.Blocked(CheckId.Termux)))
        assertEquals(KernelBlock.PermissionMissing, KernelBlock.of(KernelGate.Blocked(CheckId.Permission)))
        assertEquals(KernelBlock.ExternalAppsBlocked, KernelBlock.of(KernelGate.Blocked(CheckId.ExternalApps)))
        assertEquals(KernelBlock.KernelSetup, KernelBlock.of(KernelGate.Blocked(CheckId.Kernel)))
    }

    // ---- KernelDiagnostics (E.6) --------------------------------------------------------------

    private val summary = DoctorSummary(
        cliVersion = "3.0.68", coreVersion = "0.0.90", hubHealthy = true, hubPid = 23817, hubUptime = "2h 14m",
        listeningPids = listOf(23817, 24102), staleHubPids = emptyList(), hubStartupLocks = 2, staleCliPids = listOf(999),
    )

    @Test
    fun `doctor rows follow E6`() {
        val rows = KernelDiagnostics.rows(summary)
        assertEquals(
            listOf(
                DiagnosticRow(R.string.diag_hub_healthy, res(R.string.diag_yes_pid, 23817)),
                DiagnosticRow(R.string.diag_listeners, UiText.Raw("23817, 24102")),
                DiagnosticRow(R.string.diag_stale, res(R.string.diag_none)),
                DiagnosticRow(R.string.diag_locks, UiText.Raw("2")),
                DiagnosticRow(R.string.diag_cli, UiText.Raw("999")),
                DiagnosticRow(R.string.diag_cli_version, UiText.Raw("3.0.68")),
                DiagnosticRow(R.string.diag_uptime, UiText.Raw("2h 14m")),
            ),
            rows,
        )
        val down = KernelDiagnostics.rows(summary.copy(hubHealthy = false, hubPid = null, listeningPids = emptyList(), hubStartupLocks = 0, staleCliPids = emptyList(), cliVersion = null, hubUptime = null))
        assertEquals(
            listOf(
                DiagnosticRow(R.string.diag_hub_healthy, res(R.string.diag_no)),
                DiagnosticRow(R.string.diag_listeners, res(R.string.diag_none)),
                DiagnosticRow(R.string.diag_stale, res(R.string.diag_none)),
                DiagnosticRow(R.string.diag_locks, res(R.string.diag_none)),
                DiagnosticRow(R.string.diag_cli, res(R.string.diag_none)),
            ),
            down,
        )
    }

    @Test
    fun `doctor command results become outcomes`() {
        val json = """{"cliVersion":"3.0.68","hubHealthy":true,"hubPid":7,"listeningPids":[7],"staleHubPids":[],"hubStartupLocks":[],"staleCliPids":[]}"""
        val ok = KernelDiagnostics.outcomeOf(KernelCommandResult.Completed("progress\n$json\n", "", 0))
        assertEquals(7, (ok as DoctorOutcome.Report).summary.hubPid)
        assertEquals(
            DoctorOutcome.Failed(UiText.Raw("cline: command not found")),
            KernelDiagnostics.outcomeOf(KernelCommandResult.Completed("", "boom\ncline: command not found\n", 127)),
        )
        assertEquals(DoctorOutcome.Failed(res(R.string.diag_failed_timeout)), KernelDiagnostics.outcomeOf(KernelCommandResult.TimedOut(30_000)))
        assertEquals(
            DoctorOutcome.Failed(res(R.string.diag_failed)),
            KernelDiagnostics.outcomeOf(KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.NotInstalled, null)),
        )
        assertEquals(
            DoctorOutcome.Failed(UiText.Raw("Failed to execute")),
            KernelDiagnostics.outcomeOf(KernelCommandResult.Completed("", "", -1, err = 150, errmsg = "Failed to execute")),
        )
    }

    @Test
    fun `run and fix drive the shared state`() = runTest {
        val doctorGate = CompletableDeferred<DoctorOutcome>()
        var fixes = 0
        val diagnostics = KernelDiagnostics(
            object : KernelDiagnostics.Runner {
                override suspend fun doctor(): DoctorOutcome = doctorGate.await()
                override suspend fun fix(): DoctorOutcome {
                    fixes += 1
                    return DoctorOutcome.Report(summary.copy(hubHealthy = false, hubPid = null))
                }
            },
            backgroundScope,
        )
        assertEquals(DiagnosticsUi.Idle, diagnostics.state.value)
        assertNotNull(diagnostics.run())
        runCurrent()
        assertEquals(DiagnosticsUi.Loading, diagnostics.state.value)
        assertNull("a second run while one is in flight is ignored", diagnostics.run())
        doctorGate.complete(DoctorOutcome.Report(summary))
        runCurrent()
        assertTrue((diagnostics.state.value as DiagnosticsUi.Loaded).hubHealthy)

        diagnostics.fix()
        runCurrent()
        assertEquals(1, fixes)
        assertFalse(diagnostics.fixing.value)
        assertFalse((diagnostics.state.value as DiagnosticsUi.Loaded).hubHealthy)

        diagnostics.clear()
        assertEquals(DiagnosticsUi.Idle, diagnostics.state.value)
    }

    // ---- Hub update dialog (E.9) --------------------------------------------------------------

    private fun info(protocol: String = "v2", min: String? = "v2", max: String? = "v2", core: String? = "0.1.4") = HubInfo(
        url = "ws://127.0.0.1:25463/hub", port = 25463, pid = 1, startedAt = "t", coreVersion = core, buildId = "b",
        protocolVersion = protocol, minClientProtocolVersion = min, maxClientProtocolVersion = max,
    )

    @Test
    fun `mismatch direction and copy`() {
        assertEquals(HubMismatch.HubNewer, HubMismatch.of(info()))
        assertEquals(HubMismatch.HubOlder, HubMismatch.of(info(protocol = "v0", min = "v0", max = "v0")))
        assertEquals(HubMismatch.HubNewer, HubMismatch.of(info(protocol = "garbage")))

        val newer = HubUpdateDialogState(HubMismatch.HubNewer, "0.1.4")
        assertEquals(res(R.string.hub_updated_title), newer.title)
        assertEquals(listOf(res(R.string.hub_updated_body, "0.1.4"), res(R.string.hub_updated_body_android)), newer.body)
        assertFalse(newer.showsInstaller)
        assertEquals(res(R.string.kernel_restart), newer.confirmLabel)
        assertEquals(res(R.string.hub_updated_body_no_core), HubUpdateDialogState(HubMismatch.HubNewer, " ").body.first())

        val older = HubUpdateDialogState(HubMismatch.HubOlder, null, stillUnsupported = true)
        assertEquals(res(R.string.hub_outdated_title), older.title)
        assertEquals(listOf(res(R.string.hub_outdated_body), res(R.string.hub_still_unsupported)), older.body)
        assertTrue(older.showsInstaller)
        assertEquals(res(R.string.hub_update_try_again), older.confirmLabel)
        assertEquals(res(R.string.kernel_status_restarting), older.copy(busy = true).confirmLabel)
    }

    @Test
    fun `restart outcomes inside the dialog`() {
        val base = HubUpdateDialogState(HubMismatch.HubNewer, "0.1.4")
        assertNull(HubUpdatePresenter.reduce(base, RestartOutcome.Restarted))
        assertEquals(
            listOf(UiText.Raw("Hub is still serving sessions after the wait window; not replacing it."), res(R.string.kernel_restart_busy_hint)),
            HubUpdatePresenter.reduce(base, RestartOutcome.StillServing("Hub is still serving sessions after the wait window; not replacing it.\n"))!!.error,
        )
        assertEquals(listOf(res(R.string.kernel_restart_failed)), HubUpdatePresenter.reduce(base, RestartOutcome.Failed(null))!!.error)
        assertEquals(listOf(UiText.Raw("port in use")), HubUpdatePresenter.reduce(base, RestartOutcome.Failed("port in use"))!!.error)
        val still = HubUpdatePresenter.reduce(base, RestartOutcome.Down(KernelState.ProtocolUnsupported(info(core = "0.1.5"))))!!
        assertTrue(still.stillUnsupported)
        assertEquals("0.1.5", still.coreVersion)
        assertEquals(
            listOf(res(R.string.kernel_failure_timed_out)),
            HubUpdatePresenter.reduce(base, RestartOutcome.Down(KernelState.HubDown(DownReason.StartTimedOut, null, null)))!!.error,
        )
        assertEquals(
            listOf(res(R.string.kernel_restart_failed)),
            HubUpdatePresenter.reduce(base, RestartOutcome.Down(KernelState.HubDown(DownReason.StartFailed, null, null)))!!.error,
        )
    }

    @Test
    fun `presenter shows, dismisses until the next entry or ON_START, and restarts`() = runTest {
        val restart = CompletableDeferred<RestartOutcome>()
        var restarts = 0
        val presenter = HubUpdatePresenter(backgroundScope) {
            restarts += 1
            restart.await()
        }
        val unsupported = KernelState.ProtocolUnsupported(info())
        presenter.onKernelState(unsupported)
        assertEquals(HubMismatch.HubNewer, presenter.dialog.value!!.mismatch)

        presenter.later()
        assertNull(presenter.dialog.value)
        presenter.onKernelState(unsupported)
        assertNull("a dismissal holds while the state stays", presenter.dialog.value)

        presenter.onStart()
        assertNotNull("ON_START brings it back", presenter.dialog.value)
        presenter.later()
        presenter.onKernelState(KernelState.Connecting(info()))
        presenter.onKernelState(unsupported)
        assertNotNull("the next reconnect brings it back", presenter.dialog.value)

        presenter.confirm()
        runCurrent()
        assertTrue(presenter.dialog.value!!.busy)
        presenter.later()
        assertNotNull("busy dialogs cannot be dismissed", presenter.dialog.value)
        presenter.onKernelState(KernelState.Restarting(info(), dev.cline.termux.core.kernel.RestartPhase.Replacing))
        assertNotNull("state changes during the restart keep the dialog", presenter.dialog.value)
        restart.complete(RestartOutcome.Restarted)
        runCurrent()
        assertNull(presenter.dialog.value)
        assertEquals(1, presenter.restarted.value)
        assertEquals(1, restarts)

        presenter.onKernelState(KernelState.Ready(info(protocol = "v1", min = "v1", max = "v1"), null))
        presenter.reopen()
        assertNull("reopen needs an unsupported state", presenter.dialog.value)
    }
}
