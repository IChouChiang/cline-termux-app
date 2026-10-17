package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.data.session.DotTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import dev.cline.termux.ui.kernel.KernelStatusAction as A

/** The E.5 table: header, status value, rows, the one primary and the action rows for every state. */
class KernelStatusProjectionTest {
    private val info = HubInfo(
        url = "ws://127.0.0.1:25463/hub", port = 25463, pid = 23817, startedAt = "2026-10-03T08:00:00Z",
        coreVersion = "0.0.90", buildId = "source-0.0.90", protocolVersion = "v1", hubId = "hub_7f3a",
        release = "3.0.68-termux.2", cliVersion = "3.0.68",
    )
    private val now = Instant.parse("2026-10-03T10:14:05Z").toEpochMilli()
    private val live = HubLiveStatus(draining = false, activeRpcTurns = 1, pendingRuns = 2, lastSequence = 4812, idle = false)

    private fun project(
        state: KernelState,
        commands: Boolean = true,
        lastInfo: HubInfo? = info,
        port: PortVersion? = PortVersion(release = "3.0.68-termux.2", cline = "3.0.68", tag = "v3.0.68-termux.2"),
        lifetime: String? = null,
        local: StatusLocal = StatusLocal(),
        wakeLockError: String? = null,
    ) = KernelStatusProjection.project(
        StatusInputs(
            state = state, commandsAvailable = commands, lastInfo = lastInfo, portVersion = port,
            hubLifetimeRelease = lifetime, wakeLockError = wakeLockError, nowWallMs = now, appVersion = "0.4.0",
        ),
        local,
    )

    private fun res(text: UiText?): Int? = (text as? UiText.Res)?.id

    private data class Expect(val primary: A?, val status: Int, val rows: List<A>, val spinner: Boolean = false)

    @Test
    fun `every state has its E5 primary, status value and action rows`() {
        val cases: Map<KernelState, Expect> = linkedMapOf(
            KernelState.Unknown to Expect(null, R.string.kernel_status_connecting, listOf(A.OpenTermux), spinner = true),
            KernelState.Starting(StartStep.StartingHub, 0) to Expect(null, R.string.kernel_status_connecting, listOf(A.OpenTermux), spinner = true),
            KernelState.Connecting(info) to Expect(null, R.string.kernel_status_connecting, listOf(A.OpenTermux), spinner = true),
            KernelState.Ready(info, live) to Expect(A.Restart, R.string.kernel_status_connected, listOf(A.OpenTermux, A.RunDiagnostics, A.Disconnect, A.Stop)),
            KernelState.Reconnecting(info, 2) to Expect(null, R.string.kernel_status_reconnecting, listOf(A.OpenTermux, A.Disconnect), spinner = true),
            KernelState.Restarting(info, RestartPhase.Replacing) to Expect(null, R.string.kernel_status_restarting, emptyList(), spinner = true),
            KernelState.Draining(info) to Expect(null, R.string.kernel_status_draining, listOf(A.OpenTermux), spinner = true),
            KernelState.HubDown(DownReason.Asleep, null, null) to Expect(A.Start, R.string.kernel_status_asleep, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.HubDown(DownReason.Stopped, null, null) to Expect(A.Start, R.string.kernel_status_stopped, listOf(A.OpenTermux)),
            KernelState.HubDown(DownReason.StoppedInTermux, null, null) to Expect(A.Start, R.string.kernel_status_stopped_in_termux, listOf(A.OpenTermux)),
            KernelState.HubDown(DownReason.Disconnected, null, null) to Expect(A.Connect, R.string.kernel_status_disconnected, listOf(A.OpenTermux, A.Stop)),
            KernelState.HubDown(DownReason.StartFailed, "boom", null) to Expect(A.TryAgain, R.string.kernel_status_not_connected, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.HubDown(DownReason.StartTimedOut, null, null) to Expect(A.TryAgain, R.string.kernel_status_not_connected, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.HubDown(DownReason.Unreachable, null, null) to Expect(A.TryAgain, R.string.kernel_status_not_connected, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.HubDown(DownReason.CrashLoop, null, null) to Expect(A.TryAgain, R.string.kernel_status_not_connected, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.ProtocolUnsupported(info) to Expect(A.Restart, R.string.kernel_status_update_required, listOf(A.OpenTermux, A.RunDiagnostics)),
            KernelState.AuthFailed(info) to Expect(A.TryAgain, R.string.kernel_status_not_connected, listOf(A.OpenTermux, A.RunDiagnostics, A.Restart)),
            KernelState.TermuxMissing(disabled = false) to Expect(A.FixKernel, R.string.kernel_status_setup_needed, emptyList()),
            KernelState.TermuxTooOld("0.117") to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
            KernelState.PermissionMissing to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
            KernelState.ExternalAppsBlocked("blocked") to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
            KernelState.TermuxNotSetUp to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
            KernelState.PortMissing to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
            KernelState.PortTooOld("3.0.60-termux.1") to Expect(A.FixKernel, R.string.kernel_status_setup_needed, listOf(A.OpenTermux)),
        )
        cases.forEach { (state, expect) ->
            val ui = project(state)
            assertEquals("primary of $state", expect.primary, ui.primary)
            assertEquals("status of $state", expect.status, res(ui.statusLabel))
            assertEquals("rows of $state", expect.rows, ui.rowActions)
            assertEquals("spinner of $state", expect.spinner, ui.primarySpinner)
            assertFalse("Stop is always last", ui.rowActions.dropLast(1).contains(A.Stop))
        }
    }

    @Test
    fun `ready shows the full detail rows with pid, uptime and runs`() {
        val ui = project(KernelState.Ready(info, live))
        assertEquals(
            listOf(R.string.kernel_row_status, R.string.kernel_row_kernel, R.string.kernel_row_core, R.string.kernel_row_build, R.string.kernel_row_process, R.string.kernel_row_uptime, R.string.kernel_row_active_runs),
            ui.rows.map { it.label },
        )
        assertEquals(UiText.Raw("2h 14m"), ui.rows.single { it.label == R.string.kernel_row_uptime }.value)
        assertEquals(UiText.Res(R.string.kernel_value_pid, listOf(23817)), ui.rows.single { it.label == R.string.kernel_row_process }.value)
        assertEquals(listOf(R.string.kernel_row_protocol, R.string.kernel_row_hub_id, R.string.kernel_row_event_log), ui.moreRows.map { it.label })
        assertNull(ui.errorLine)
        assertTrue(ui.keepAwakeEnabled)
        assertEquals(DotTone.Success, ui.dot)
        assertEquals("25463", ui.hubPortSpoken)
    }

    @Test
    fun `a down kernel shows only status and kernel rows`() {
        val ui = project(KernelState.HubDown(DownReason.Stopped, null, null))
        assertEquals(listOf(R.string.kernel_row_status, R.string.kernel_row_kernel), ui.rows.map { it.label })
        assertTrue(ui.moreRows.isEmpty())
        assertFalse(ui.keepAwakeEnabled)
        assertEquals(R.string.hub_not_connected, res(ui.errorLine))
    }

    @Test
    fun `asleep keeps the last port and says how to continue`() {
        val ui = project(KernelState.HubDown(DownReason.Asleep, null, null))
        assertEquals("25463", ui.hubPortSpoken)
        assertEquals(R.string.kernel_status_asleep_line2, res(ui.errorLine))
    }

    @Test
    fun `a failed start names no port and shows the kernel's text`() {
        val ui = project(KernelState.HubDown(DownReason.StartFailed, "Timed out after 15000ms", null))
        assertNull(ui.hubPortSpoken)
        assertNotNull(ui.errorLine)
    }

    @Test
    fun `prerequisites name no port and focus the repair checklist`() {
        val ui = project(KernelState.PermissionMissing)
        assertNull(ui.hubPortSpoken)
        assertEquals(CheckId.Permission, ui.repairFocus)
        assertEquals(R.string.kernel_fix_kernel, res(ui.errorLine))
    }

    @Test
    fun `degraded ready offers the repair and keeps disconnect`() {
        val ui = project(KernelState.Ready(info, live), commands = false)
        assertEquals(A.FixKernel, ui.primary)
        assertEquals(listOf(A.OpenTermux, A.Disconnect), ui.rowActions)
        assertEquals(R.string.banner_degraded, res(ui.errorLine))
        assertFalse(ui.keepAwakeEnabled)
    }

    @Test
    fun `restarting locks every action and exposes its phase`() {
        val ui = project(KernelState.Restarting(info, RestartPhase.Reconnecting))
        assertTrue(ui.actionsLocked)
        assertEquals(RestartPhase.Reconnecting, ui.restartPhase)
        assertNotNull(ui.statusDetail)
    }

    @Test
    fun `the update row shows only when the running hub predates the installed release`() {
        val ready = KernelState.Ready(info, live)
        assertEquals(UpdateRowUi("3.0.68-termux.2"), project(ready, lifetime = "3.0.68-termux.1").updateRow)
        assertNull(project(ready, lifetime = "3.0.68-termux.2").updateRow)
        assertNull(project(ready, lifetime = null).updateRow)
        assertNull(project(KernelState.HubDown(DownReason.Stopped, null, null), lifetime = "3.0.68-termux.1").updateRow)
    }

    @Test
    fun `local state drives busy, confirm, the switch and its error`() {
        val ready = KernelState.Ready(info, live)
        val busy = project(ready, local = StatusLocal(busy = A.Stop))
        assertEquals(A.Stop, busy.busy)
        assertTrue(busy.actionsLocked)

        val confirm = project(ready, local = StatusLocal(confirm = StatusConfirm.Stop(UiText.Raw("x"))))
        assertTrue(confirm.actionsLocked)

        val loading = project(ready, local = StatusLocal(diagnostics = DiagnosticsUi.Loading))
        assertEquals(A.RunDiagnostics, loading.busy)

        val switching = project(ready, local = StatusLocal(keepAwakeOverride = false))
        assertFalse(switching.keepAwake)
        assertFalse(switching.keepAwakeEnabled)

        val failed = project(ready, local = StatusLocal(wakeLockFailure = UiText.Raw("no")))
        assertEquals(R.string.kernel_keep_awake_failed, res(failed.wakeLockError))
        assertEquals(R.string.kernel_keep_awake_failed, res(project(ready, wakeLockError = " denied ").wakeLockError))
        assertNull(project(ready, wakeLockError = "  ").wakeLockError)
    }

    @Test
    fun `startedAt parses ISO, seconds and milliseconds`() {
        assertEquals(1_000_000_000_000L, KernelStatusProjection.startedAtMs("1000000000"))
        assertEquals(1_759_000_000_000L, KernelStatusProjection.startedAtMs("1759000000000"))
        assertEquals(Instant.parse("2026-10-03T08:00:00Z").toEpochMilli(), KernelStatusProjection.startedAtMs("2026-10-03T08:00:00Z"))
        assertNull(KernelStatusProjection.startedAtMs("yesterday"))
        assertNull(KernelStatusProjection.startedAtMs(" "))
    }
}
