package dev.cline.termux.core.kernel

import org.junit.Assert.assertEquals
import org.junit.Test

class KernelGateTest {
    private val info = HubInfo("ws://127.0.0.1:25463/hub", 25463, 1, "t", "3.0.68", "b", "v1")

    @Test
    fun `every state projects to exactly one gate`() {
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Connecting, KernelGate.BusyLabel.Step(StartStep.Connecting), false), KernelState.Unknown.toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Termux), KernelState.TermuxMissing(false).toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Termux), KernelState.TermuxTooOld("0.1").toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Termux), KernelState.TermuxNotSetUp.toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Permission), KernelState.PermissionMissing.toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.ExternalApps), KernelState.ExternalAppsBlocked("x").toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Kernel), KernelState.PortMissing.toGate(0))
        assertEquals(KernelGate.Blocked(CheckId.Kernel), KernelState.PortTooOld("3.0.67-termux.1").toGate(0))
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Connecting, KernelGate.BusyLabel.Step(StartStep.Connecting), false), KernelState.Connecting(info).toGate(0))
        assertEquals(KernelGate.Ready, KernelState.Ready(info, null).toGate(0))
        assertEquals(KernelGate.DegradedNoCommands, KernelState.Ready(info, null).toGate(0, commandsAvailable = false))
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), KernelState.Reconnecting(info, 2).toGate(0))
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Restarting, KernelGate.BusyLabel.Phase(RestartPhase.Replacing), false), KernelState.Restarting(info, RestartPhase.Replacing).toGate(0))
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Draining, null, false), KernelState.Draining(info).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Protocol, null), KernelState.ProtocolUnsupported(info).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Auth, null), KernelState.AuthFailed(info).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Asleep, null), KernelState.HubDown(DownReason.Asleep, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StartFailed, "boom"), KernelState.HubDown(DownReason.StartFailed, "boom", null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StartFailed, null), KernelState.HubDown(DownReason.StartTimedOut, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StartFailed, null), KernelState.HubDown(DownReason.Unreachable, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.CrashLoop, null), KernelState.HubDown(DownReason.CrashLoop, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Disconnected, null), KernelState.HubDown(DownReason.Disconnected, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Stopped, null), KernelState.HubDown(DownReason.Stopped, null, null).toGate(0))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StoppedInTermux, null), KernelState.HubDown(DownReason.StoppedInTermux, null, null).toGate(0))
    }

    @Test
    fun `a start becomes slow after twenty seconds`() {
        val starting = KernelState.Starting(StartStep.StartingHub, startedAtElapsedMs = 1_000)
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Starting, KernelGate.BusyLabel.Step(StartStep.StartingHub), slow = false), starting.toGate(21_000))
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Starting, KernelGate.BusyLabel.Step(StartStep.StartingHub), slow = true), starting.toGate(21_001))
    }

    @Test
    fun `transitions keep labels only`() {
        val t = KernelTransitions(capacity = 2)
        t.record(1, KernelState.Unknown, KernelState.Starting(StartStep.CheckingTermux, 0))
        t.record(2, KernelState.Starting(StartStep.CheckingTermux, 0), KernelState.HubDown(DownReason.StartFailed, "secret-ish stderr", null))
        t.record(3, KernelState.HubDown(DownReason.StartFailed, "x", null), KernelState.Ready(info, null))
        val snapshot = t.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("Starting(CheckingTermux)", snapshot[0].from)
        assertEquals("HubDown(StartFailed)", snapshot[0].to)
        assertEquals("Ready", snapshot[1].to)
        assertEquals("TermuxMissing(disabled)", KernelTransitions.label(KernelState.TermuxMissing(true)))
        assertEquals("Reconnecting(attempt=3)", KernelTransitions.label(KernelState.Reconnecting(info, 3)))
    }
}
