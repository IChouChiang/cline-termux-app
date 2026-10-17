package dev.cline.termux.ui.kernel

import dev.cline.termux.R
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubSessionActivity
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelGate.BusyKind
import dev.cline.termux.core.kernel.KernelGate.BusyLabel
import dev.cline.termux.core.kernel.KernelGate.DownVariant
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.StartFailure
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.data.session.DotTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The F.2 copy table of onboarding-kernel.md, row by row. */
class KernelCopyTest {
    private fun down(variant: DownVariant, detail: String? = null) = KernelGate.Down(variant, detail)
    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    @Test
    fun `panel rows follow F2`() {
        val asleep = KernelCopy.panel(down(DownVariant.Asleep))!!
        assertEquals(KernelPanelIcon.Bedtime, asleep.icon)
        assertEquals(res(R.string.kernel_status_asleep), asleep.title)
        assertEquals(listOf(res(R.string.state_asleep_body)), asleep.body)
        assertEquals(KernelAction.StartKernel, asleep.primary)
        assertEquals(KernelAction.WhyDidThisHappen, asleep.secondary)

        val stopped = KernelCopy.panel(down(DownVariant.Stopped))!!
        assertEquals(KernelPanelIcon.StopCircle, stopped.icon)
        assertEquals(res(R.string.kernel_status_stopped), stopped.title)
        assertEquals(listOf(res(R.string.state_stopped_body)), stopped.body)
        assertEquals(KernelAction.StartKernel to null, stopped.primary to stopped.secondary)

        val inTermux = KernelCopy.panel(down(DownVariant.StoppedInTermux))!!
        assertEquals(listOf(res(R.string.state_stopped_in_termux_body)), inTermux.body)
        assertEquals(KernelAction.StartKernel to KernelAction.KernelStatus, inTermux.primary to inTermux.secondary)

        val disconnected = KernelCopy.panel(down(DownVariant.Disconnected))!!
        assertEquals(KernelPanelIcon.LinkOff, disconnected.icon)
        assertEquals(res(R.string.kernel_status_disconnected), disconnected.title)
        assertEquals(KernelAction.Connect to null, disconnected.primary to disconnected.secondary)

        val failed = KernelCopy.panel(down(DownVariant.StartFailed, "exit 1: boom"))!!
        assertEquals(KernelPanelIcon.Error, failed.icon)
        assertEquals(res(R.string.state_failed_title), failed.title)
        assertEquals(listOf(res(R.string.hub_not_connected)), failed.body)
        assertEquals(UiText.Raw("exit 1: boom"), failed.detail)
        assertEquals(KernelAction.TryAgain to KernelAction.KernelStatusDiagnostics, failed.primary to failed.secondary)

        val loop = KernelCopy.panel(down(DownVariant.CrashLoop))!!
        assertEquals(listOf(res(R.string.hub_not_connected), res(R.string.state_crash_loop)), loop.body)

        val protocol = KernelCopy.panel(down(DownVariant.Protocol))!!
        assertEquals(res(R.string.state_connect_failed_title), protocol.title)
        assertEquals(listOf(res(R.string.kernel_protocol_unsupported)), protocol.body)
        assertEquals(KernelAction.RestartKernel to KernelAction.KernelStatus, protocol.primary to protocol.secondary)

        val auth = KernelCopy.panel(down(DownVariant.Auth))!!
        assertEquals(listOf(res(R.string.kernel_auth_failed)), auth.body)
        assertEquals(KernelAction.TryAgain to KernelAction.KernelStatus, auth.primary to auth.secondary)
    }

    @Test
    fun `busy panel shows the step and the slow hint, no primary`() {
        val starting = KernelCopy.panel(KernelGate.Busy(BusyKind.Starting, BusyLabel.Step(StartStep.StartingHub), slow = false))!!
        assertEquals(KernelPanelIcon.Progress, starting.icon)
        assertEquals(res(R.string.kernel_status_connecting), starting.title)
        assertEquals(listOf(res(R.string.kernel_step_starting_hub)), starting.body)
        assertNull(starting.primary)
        assertEquals(KernelAction.KernelStatus, starting.secondary)

        val slow = KernelCopy.panel(KernelGate.Busy(BusyKind.Connecting, BusyLabel.Step(StartStep.Connecting), slow = true))!!
        assertEquals(listOf(res(R.string.kernel_step_connecting), res(R.string.kernel_slow)), slow.body)
    }

    @Test
    fun `variants that keep the content have no panel`() {
        listOf(
            KernelGate.Ready,
            KernelGate.DegradedNoCommands,
            KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Permission),
            KernelGate.Busy(BusyKind.Reconnecting, null, false),
            KernelGate.Busy(BusyKind.Restarting, BusyLabel.Phase(RestartPhase.Replacing), false),
            KernelGate.Busy(BusyKind.Draining, null, false),
        ).forEach { assertNull("$it", KernelCopy.panel(it)) }
    }

    @Test
    fun `panel shows only the secondary when a composer is under it`() {
        val asleep = KernelCopy.panel(down(DownVariant.Asleep))!!
        assertEquals(null to KernelAction.WhyDidThisHappen, asleep.actions(hasComposer = true))
        assertEquals(KernelAction.StartKernel to KernelAction.WhyDidThisHappen, asleep.actions(hasComposer = false))
    }

    @Test
    fun `banner lines and the action rule`() {
        val asleep = KernelCopy.banner(down(DownVariant.Asleep), hasComposer = true)!!
        assertEquals(res(R.string.kernel_status_asleep), asleep.line1)
        assertEquals(res(R.string.kernel_status_asleep_line2), asleep.line2)
        assertTrue(asleep.line2Error)
        assertEquals(DotTone.Outline, asleep.tone)
        assertEquals(KernelAction.KernelStatus, asleep.action)
        assertEquals(KernelAction.StartKernel, KernelCopy.banner(down(DownVariant.Asleep), hasComposer = false)!!.action)

        val stopped = KernelCopy.banner(down(DownVariant.StoppedInTermux), hasComposer = false)!!
        assertEquals(res(R.string.banner_stopped) to res(R.string.hub_not_connected), stopped.line1 to stopped.line2)
        assertEquals(KernelAction.StartKernel, stopped.action)

        val disconnected = KernelCopy.banner(down(DownVariant.Disconnected), hasComposer = false)!!
        assertEquals(res(R.string.banner_disconnected), disconnected.line1)
        assertNull(disconnected.line2)
        assertEquals(KernelAction.Connect, disconnected.action)

        val failed = KernelCopy.banner(down(DownVariant.StartFailed), hasComposer = false)!!
        assertEquals(res(R.string.hub_at_unknown) to res(R.string.hub_not_connected), failed.line1 to failed.line2)
        assertEquals(KernelAction.TryAgain, failed.action)

        val protocol = KernelCopy.banner(down(DownVariant.Protocol), hasComposer = false)!!
        assertEquals(res(R.string.state_connect_failed_title) to res(R.string.kernel_protocol_unsupported), protocol.line1 to protocol.line2)
        assertEquals(KernelAction.RestartKernel, protocol.action)

        val auth = KernelCopy.banner(down(DownVariant.Auth), hasComposer = true)!!
        assertEquals(res(R.string.kernel_auth_failed), auth.line2)
        assertEquals(KernelAction.KernelStatus, auth.action)
    }

    @Test
    fun `busy, draining and degraded banners`() {
        val starting = KernelCopy.banner(KernelGate.Busy(BusyKind.Starting, BusyLabel.Step(StartStep.KeepingAwake), false), hasComposer = false)!!
        assertTrue(starting.busy)
        assertEquals(res(R.string.kernel_status_connecting) to res(R.string.kernel_step_keeping_awake), starting.line1 to starting.line2)
        assertFalse(starting.line2Error)
        assertEquals(KernelAction.KernelStatus, starting.action)

        val reconnecting = KernelCopy.banner(KernelGate.Busy(BusyKind.Reconnecting, null, false), hasComposer = true)!!
        assertEquals(res(R.string.kernel_status_reconnecting), reconnecting.line1)
        assertEquals(KernelCopy.RECONNECT_BANNER_DELAY_MS, reconnecting.showAfterMs)

        assertNull("an intended restart shows no banner", KernelCopy.banner(KernelGate.Busy(BusyKind.Restarting, null, false), hasComposer = true))

        val draining = KernelCopy.banner(KernelGate.Busy(BusyKind.Draining, null, false), hasComposer = true)!!
        assertEquals(res(R.string.kernel_draining_note), draining.line1)
        assertEquals(DotTone.Warning, draining.tone)
        assertFalse(draining.busy)
        assertEquals(KernelAction.Details, draining.action)

        val degraded = KernelCopy.banner(KernelGate.DegradedNoCommands, hasComposer = true)!!
        assertEquals(res(R.string.banner_degraded) to res(R.string.banner_degraded_line2), degraded.line1 to degraded.line2)
        assertEquals(DotTone.Success, degraded.tone)
        assertEquals(KernelAction.Fix, degraded.action)

        assertNull(KernelCopy.banner(KernelGate.Ready, hasComposer = true))
        assertNull(KernelCopy.banner(KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Termux), hasComposer = false))
    }

    @Test
    fun `banner precedence follows F4`() {
        val order = listOf(
            KernelGate.DegradedNoCommands,
            KernelGate.Busy(BusyKind.Draining, null, false),
            KernelGate.Busy(BusyKind.Reconnecting, null, false),
            KernelGate.Busy(BusyKind.Starting, null, false),
            down(DownVariant.Disconnected),
            down(DownVariant.Stopped),
            down(DownVariant.Asleep),
            down(DownVariant.StartFailed),
        ).map { KernelCopy.banner(it, hasComposer = true)!!.precedence }
        assertEquals(order.sorted(), order)
        assertEquals(order.toSet().size, order.size)
        assertEquals(
            KernelCopy.banner(down(DownVariant.StartFailed), true)!!.precedence,
            KernelCopy.banner(down(DownVariant.Protocol), true)!!.precedence,
        )
    }

    @Test
    fun `chip line 1 and dot per variant`() {
        fun chip(gate: KernelGate, port: Int? = 25463) = KernelCopy.chip(gate, port)
        assertEquals(KernelChipModel(DotTone.Success, false, res(R.string.hub_at_port, "25463"), null), chip(KernelGate.Ready))
        assertEquals(KernelChipModel(DotTone.Success, false, res(R.string.hub_at_port, "25463"), null), chip(KernelGate.DegradedNoCommands))
        assertEquals(KernelChipModel(DotTone.Warning, true, res(R.string.kernel_status_connecting), null), chip(KernelGate.Busy(BusyKind.Starting, null, false)))
        assertEquals(KernelChipModel(DotTone.Warning, true, res(R.string.kernel_status_reconnecting), null), chip(KernelGate.Busy(BusyKind.Reconnecting, null, false)))
        assertEquals(KernelChipModel(DotTone.Warning, true, res(R.string.kernel_status_restarting), null), chip(KernelGate.Busy(BusyKind.Restarting, null, false)))
        assertEquals(KernelChipModel(DotTone.Warning, false, res(R.string.hub_at_port, "25463"), null), chip(KernelGate.Busy(BusyKind.Draining, null, false)))
        assertEquals(KernelChipModel(DotTone.Outline, false, res(R.string.kernel_status_asleep), null), chip(down(DownVariant.Asleep)))
        assertEquals(KernelChipModel(DotTone.Outline, false, res(R.string.hub_at_unknown), null), chip(down(DownVariant.Stopped)))
        assertEquals(
            KernelChipModel(DotTone.Outline, false, res(R.string.hub_at_unknown), res(R.string.hub_not_connected)),
            chip(down(DownVariant.StartFailed)),
        )
        assertEquals(KernelChipModel(DotTone.Outline, false, res(R.string.hub_at_port, "25463"), null), chip(down(DownVariant.Disconnected)))
        assertEquals(KernelChipModel(DotTone.Outline, false, res(R.string.hub_at_unknown), null), chip(down(DownVariant.Auth), port = null))
    }

    @Test
    fun `step and phase labels`() {
        val steps = StartStep.entries.map { (KernelCopy.stepLabel(it) as UiText.Res).id }
        assertEquals(
            listOf(
                R.string.kernel_step_checking_termux, R.string.kernel_step_checking_kernel, R.string.kernel_step_starting_hub,
                R.string.kernel_step_keeping_awake, R.string.kernel_step_reading_credentials, R.string.kernel_step_connecting,
            ),
            steps,
        )
        val phases = RestartPhase.entries.map { (KernelCopy.phaseLabel(it) as UiText.Res).id }
        assertEquals(
            listOf(
                R.string.kernel_restart_phase_checking, R.string.kernel_restart_phase_disconnecting, R.string.kernel_restart_phase_replacing,
                R.string.kernel_restart_phase_starting, R.string.kernel_restart_phase_reconnecting,
            ),
            phases,
        )
        assertEquals(res(R.string.kernel_restart_phase_replacing), KernelCopy.busyLabel(BusyLabel.Phase(RestartPhase.Replacing)))
        assertNull(KernelCopy.busyLabel(null))
    }

    @Test
    fun `down detail prefers the app sentence, then the timeout, then kernel text`() {
        assertEquals(
            res(R.string.kernel_failure_credentials_unreadable),
            KernelCopy.downDetail(KernelState.HubDown(DownReason.StartFailed, null, null, StartFailure.CredentialsUnreadable)),
        )
        assertEquals(
            res(R.string.kernel_failure_handshake),
            KernelCopy.downDetail(KernelState.HubDown(DownReason.StartFailed, "1006", null, StartFailure.HandshakeRefused)),
        )
        assertEquals(res(R.string.kernel_failure_timed_out), KernelCopy.downDetail(KernelState.HubDown(DownReason.StartTimedOut, null, null)))
        assertEquals(UiText.Raw("Timed out after 15000ms"), KernelCopy.downDetail(KernelState.HubDown(DownReason.StartFailed, "  Timed out after 15000ms\n", null)))
        assertNull(KernelCopy.downDetail(KernelState.HubDown(DownReason.Asleep, null, null)))
        assertNull(KernelCopy.downDetail(KernelState.Unknown))
        assertNull(KernelCopy.gateDetail(down(DownVariant.StartFailed, "  ")))
    }

    @Test
    fun `primary per down variant`() {
        assertEquals(KernelAction.StartKernel, KernelCopy.primary(down(DownVariant.Asleep)))
        assertEquals(KernelAction.StartKernel, KernelCopy.primary(down(DownVariant.StoppedInTermux)))
        assertEquals(KernelAction.Connect, KernelCopy.primary(down(DownVariant.Disconnected)))
        assertEquals(KernelAction.TryAgain, KernelCopy.primary(down(DownVariant.CrashLoop)))
        assertEquals(KernelAction.RestartKernel, KernelCopy.primary(down(DownVariant.Protocol)))
        assertNull(KernelCopy.primary(KernelGate.Ready))
    }

    @Test
    fun `describeOutdatedHubSessions cases`() {
        assertEquals(res(R.string.hub_sessions_fallback), KernelCopy.describeSessions(HubSessionActivity.UNKNOWN))
        assertEquals(res(R.string.hub_sessions_fallback), KernelCopy.describeSessions(HubSessionActivity.NONE))
        assertEquals(UiText.Plural(R.plurals.hub_sessions_count, 2, listOf(2)), KernelCopy.describeSessions(HubSessionActivity(2, 0)))
        val sessions = UiText.Plural(R.plurals.hub_sessions_count, 1, listOf(1))
        assertEquals(UiText.Plural(R.plurals.hub_sessions_from_clients, 2, listOf(sessions, 2)), KernelCopy.describeSessions(HubSessionActivity(1, 2)))
    }

    @Test
    fun `connected is announced only when coming back to Ready`() {
        assertEquals(res(R.string.kernel_connected_announce), KernelCopy.connectedAnnouncement(down(DownVariant.Asleep), KernelGate.Ready))
        assertNull(KernelCopy.connectedAnnouncement(null, KernelGate.Ready))
        assertNull(KernelCopy.connectedAnnouncement(KernelGate.Ready, KernelGate.Ready))
        assertNull(KernelCopy.connectedAnnouncement(down(DownVariant.Asleep), down(DownVariant.Stopped)))
    }

    @Test
    fun `action labels`() {
        assertEquals(res(R.string.kernel_start), KernelAction.StartKernel.label())
        assertEquals(res(R.string.kernel_starting), KernelAction.StartKernel.busyLabel())
        assertEquals(res(R.string.kernel_restarting), KernelAction.RestartKernel.busyLabel())
        assertEquals(res(R.string.state_kernel_status), KernelAction.KernelStatusDiagnostics.label())
        assertEquals(res(R.string.banner_fix), KernelAction.Fix.label())
    }
}
