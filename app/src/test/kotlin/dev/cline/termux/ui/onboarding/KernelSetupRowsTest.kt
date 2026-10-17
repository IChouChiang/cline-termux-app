package dev.cline.termux.ui.onboarding

import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.StartFailure
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.ui.kernel.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** onboarding-kernel.md A.7: kernel state → the five rows. */
class KernelSetupRowsTest {
    private val S = CheckStatus.Passed
    private val W = CheckStatus.Waiting
    private val C = CheckStatus.Checking
    private val N = CheckStatus.NeedsAction
    private val F = CheckStatus.Failed

    private val info = HubInfo(
        url = "ws://127.0.0.1:25463", port = 25463, pid = 4242, startedAt = "t", coreVersion = "0.0.40",
        buildId = "b", protocolVersion = "1", release = "3.0.68-termux.2",
    )
    private val facts = SetupFacts(
        presence = TermuxPresence.Installed(118, "0.118.3"),
        permissionGranted = true,
        portVersion = PortVersion("3.0.68-termux.2", "3.0.68", "v3.0.68-termux.2"),
    )

    private fun statuses(state: KernelState, f: SetupFacts = facts) = KernelSetupRows.rows(state, f).map { it.status }
    private fun row(state: KernelState, id: CheckId, f: SetupFacts = facts) = KernelSetupRows.rows(state, f).first { it.id == id }

    @Test
    fun `rows are always the five checks in order`() {
        val states = listOf(
            KernelState.Unknown, KernelState.TermuxMissing(false), KernelState.PermissionMissing, KernelState.PortMissing,
            KernelState.Starting(StartStep.StartingHub, 0), KernelState.Ready(info, null),
            KernelState.HubDown(DownReason.Stopped, null, null),
        )
        states.forEach { assertEquals(KernelSetupRows.ORDER, KernelSetupRows.rows(it, facts).map { r -> r.id }) }
    }

    @Test
    fun `prerequisite failures stop at their row and the rest wait`() {
        assertEquals(listOf(C, W, W, W, W), statuses(KernelState.Unknown))
        assertEquals(listOf(N, W, W, W, W), statuses(KernelState.TermuxMissing(disabled = false)))
        assertEquals(CheckFix.InstallTermux, row(KernelState.TermuxMissing(false), CheckId.Termux).fix)
        assertEquals(CheckFix.OpenTermuxSettings, row(KernelState.TermuxMissing(true), CheckId.Termux).fix)
        assertEquals(UiText.Res(R.string.check_termux_too_old, "0.101.0"), row(KernelState.TermuxTooOld("0.101.0"), CheckId.Termux).supporting)
        assertEquals(listOf(S, N, W, W, W), statuses(KernelState.PermissionMissing))
        assertEquals(listOf(S, S, N, W, W), statuses(KernelState.ExternalAppsBlocked("blocked")))
        assertEquals(CheckFix.RunInTermux(KernelSetupRows.ALLOW_EXTERNAL_APPS_COMMAND), row(KernelState.ExternalAppsBlocked("x"), CheckId.ExternalApps).fix)
        assertEquals(listOf(S, S, S, N, W), statuses(KernelState.TermuxNotSetUp))
        assertEquals(CheckFix.OpenTermux, row(KernelState.TermuxNotSetUp, CheckId.Kernel).fix)
        assertEquals(CheckFix.RunInTermux(KernelSetupRows.INSTALLER_COMMAND), row(KernelState.PortMissing, CheckId.Kernel).fix)
        assertEquals(UiText.Res(R.string.check_kernel_too_old, "3.0.60-termux.1"), row(KernelState.PortTooOld("3.0.60-termux.1"), CheckId.Kernel).supporting)
        assertEquals(UiText.Res(R.string.check_kernel_too_old_unknown), row(KernelState.PortTooOld(null), CheckId.Kernel).supporting)
    }

    @Test
    fun `the permission row asks, asks again, then points at app settings`() {
        PermissionMode.entries.forEach { mode ->
            val r = row(KernelState.PermissionMissing, CheckId.Permission, facts.copy(permissionMode = mode))
            assertEquals(CheckFix.RequestPermission(mode), r.fix)
            val body = if (mode == PermissionMode.OpenSettings) R.string.check_permission_denied else R.string.check_permission_body
            assertEquals(UiText.Res(body), r.supporting)
        }
    }

    @Test
    fun `start steps light the rows they check`() {
        assertEquals(listOf(C, W, W, W, W), statuses(KernelState.Starting(StartStep.CheckingTermux, 0)))
        assertEquals(listOf(S, S, C, C, W), statuses(KernelState.Starting(StartStep.CheckingKernel, 0)))
        listOf(StartStep.StartingHub, StartStep.KeepingAwake, StartStep.ReadingCredentials, StartStep.Connecting).forEach {
            assertEquals(listOf(S, S, S, S, C), statuses(KernelState.Starting(it, 0)))
        }
        assertEquals(listOf(S, S, S, S, C), statuses(KernelState.Connecting(info)))
        assertEquals(listOf(S, S, S, S, C), statuses(KernelState.Reconnecting(info, 1)))
        assertEquals(listOf(S, S, S, S, C), statuses(KernelState.Restarting(info, RestartPhase.Replacing)))
        assertTrue(KernelSetupRows.busyLabel(KernelState.Starting(StartStep.StartingHub, 0)) != null)
        assertTrue(KernelSetupRows.busyLabel(KernelState.Restarting(info, RestartPhase.Replacing)) != null)
        assertNull(KernelSetupRows.busyLabel(KernelState.Ready(info, null)))
    }

    @Test
    fun `a tapped start shows row 5 checking at once unless a prerequisite fails`() {
        val tapped = facts.copy(startRequested = true)
        assertEquals(listOf(S, S, S, S, C), statuses(KernelState.HubDown(DownReason.Stopped, null, null), tapped))
        assertEquals(listOf(S, S, S, S, C), statuses(KernelState.Starting(StartStep.CheckingTermux, 0), tapped))
        assertEquals(listOf(S, N, W, W, W), statuses(KernelState.PermissionMissing, tapped))
        assertEquals(listOf(S, S, S, S, S), statuses(KernelState.Ready(info, null), tapped))
    }

    @Test
    fun `ready passes all five with the port and core versions`() {
        val rows = KernelSetupRows.rows(KernelState.Ready(info, null), facts)
        assertEquals(5, KernelSetupRows.readyCount(rows))
        assertNull(KernelSetupRows.firstOpen(rows))
        assertEquals(UiText.Res(R.string.check_termux_passed, "0.118.3"), rows[0].supporting)
        assertEquals(UiText.Res(R.string.check_kernel_passed, "3.0.68-termux.2"), rows[3].supporting)
        assertEquals(UiText.Res(R.string.check_hub_passed, "25463", "0.0.40"), rows[4].supporting)
        val noCore = KernelSetupRows.rows(KernelState.Draining(info.copy(coreVersion = null)), facts.copy(portVersion = null))
        assertEquals(UiText.Res(R.string.check_hub_passed_no_core, "25463"), noCore[4].supporting)
        assertEquals(UiText.Res(R.string.check_kernel_passed, "3.0.68-termux.2"), noCore[3].supporting)
    }

    @Test
    fun `degraded ready without the permission reopens row 2`() {
        assertEquals(listOf(S, N, W, W, W), statuses(KernelState.Ready(info, null), facts.copy(permissionGranted = false)))
    }

    @Test
    fun `hub failures map to their fixes`() {
        val failed = row(KernelState.HubDown(DownReason.StartFailed, "  bun: crashed  ", null), CheckId.Hub)
        assertEquals(F, failed.status)
        assertEquals(CheckFix.StartFailed(UiText.Raw("bun: crashed"), timedOut = false), failed.fix)
        assertEquals(
            CheckFix.StartFailed(UiText.Res(R.string.check_hub_crash_loop), timedOut = false),
            row(KernelState.HubDown(DownReason.CrashLoop, null, null), CheckId.Hub).fix,
        )
        assertEquals(
            CheckFix.StartFailed(UiText.Res(R.string.check_hub_credentials_unreadable), timedOut = false),
            row(KernelState.HubDown(DownReason.StartFailed, "x", null, StartFailure.CredentialsUnreadable), CheckId.Hub).fix,
        )
        assertEquals(
            CheckFix.StartFailed(UiText.Res(R.string.check_hub_handshake), timedOut = false),
            row(KernelState.HubDown(DownReason.StartFailed, null, null, StartFailure.HandshakeRefused), CheckId.Hub).fix,
        )
        assertEquals(CheckFix.StartFailed(null, timedOut = true), row(KernelState.HubDown(DownReason.StartTimedOut, null, null), CheckId.Hub).fix)
        assertEquals(CheckFix.UpdateHub, row(KernelState.ProtocolUnsupported(info), CheckId.Hub).fix)
        assertEquals(F, row(KernelState.AuthFailed(info), CheckId.Hub).status)
    }

    @Test
    fun `an idle hub offers Start kernel, and says it stopped after this screen saw it ready`() {
        listOf(DownReason.Asleep, DownReason.Stopped, DownReason.StoppedInTermux, DownReason.Disconnected, DownReason.Unreachable).forEach { reason ->
            val idle = row(KernelState.HubDown(reason, null, null), CheckId.Hub)
            assertEquals(N, idle.status)
            assertEquals(CheckFix.StartKernel, idle.fix)
            assertEquals(UiText.Res(R.string.check_hub_idle), idle.supporting)
            assertEquals(UiText.Res(R.string.check_hub_stopped_meanwhile), row(KernelState.HubDown(reason, null, null), CheckId.Hub, facts.copy(sawReady = true)).supporting)
        }
    }

    @Test
    fun `prerequisite and busy classification`() {
        assertTrue(KernelSetupRows.isPrerequisiteFailure(KernelState.PortTooOld(null)))
        assertFalse(KernelSetupRows.isPrerequisiteFailure(KernelState.HubDown(DownReason.Stopped, null, null)))
        assertTrue(KernelSetupRows.isBusy(KernelState.Unknown))
        assertTrue(KernelSetupRows.isBusy(KernelState.Starting(StartStep.StartingHub, 0)))
        assertFalse(KernelSetupRows.isBusy(KernelState.Ready(info, null)))
    }

    @Test
    fun `a failed start's detail is an app sentence for known causes and raw kernel text otherwise`() {
        // The screen sets UiText.Raw in mono and forced LTR; the app's sentences are body text (cjk-i18n-06).
        val creds = KernelState.HubDown(DownReason.StartFailed, "EACCES", null, StartFailure.CredentialsUnreadable)
        assertEquals(UiText.Res(R.string.check_hub_credentials_unreadable), KernelSetupRows.startFailedDetail(creds))
        val handshake = KernelState.HubDown(DownReason.StartFailed, "401", null, StartFailure.HandshakeRefused)
        assertEquals(UiText.Res(R.string.check_hub_handshake), KernelSetupRows.startFailedDetail(handshake))
        assertEquals(UiText.Res(R.string.check_hub_crash_loop), KernelSetupRows.startFailedDetail(KernelState.HubDown(DownReason.CrashLoop, "boom", null)))
        assertEquals(UiText.Raw("Error: port 25463 in use"), KernelSetupRows.startFailedDetail(KernelState.HubDown(DownReason.StartFailed, " Error: port 25463 in use\n", null)))
        assertNull(KernelSetupRows.startFailedDetail(KernelState.HubDown(DownReason.StartFailed, "  ", null)))
    }
}
