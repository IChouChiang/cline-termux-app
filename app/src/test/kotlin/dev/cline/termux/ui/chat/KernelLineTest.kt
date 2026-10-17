package dev.cline.termux.ui.chat

import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.ui.chat.rows.ChatTransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** home-chat.md §1.6: KernelGate + socket → KernelLine → Home notice, transport line, composer. */
class KernelLineTest {
    private val connected = HubConnectionState.Connected("ws://127.0.0.1:25463/hub")

    private fun busy(kind: KernelGate.BusyKind) = KernelGate.Busy(kind, KernelGate.BusyLabel.Step(StartStep.Connecting), slow = false)

    @Test
    fun `ready and connected is connected, draining while the hub drains`() {
        assertEquals(KernelLine.Connected, KernelLine.of(KernelGate.Ready, connected, draining = false))
        assertEquals(KernelLine.Draining, KernelLine.of(KernelGate.Ready, connected, draining = true))
        assertEquals(KernelLine.Connected, KernelLine.of(KernelGate.DegradedNoCommands, connected, draining = false))
    }

    @Test
    fun `ready gate follows the socket when it is not connected`() {
        assertEquals(KernelLine.Connecting, KernelLine.of(KernelGate.Ready, HubConnectionState.Connecting, false))
        assertEquals(KernelLine.Reconnecting, KernelLine.of(KernelGate.Ready, HubConnectionState.Reconnecting(2, "closed"), false))
        assertEquals(KernelLine.Reconnecting, KernelLine.of(KernelGate.Ready, HubConnectionState.Disconnected, false))
        assertEquals(KernelLine.Unavailable, KernelLine.of(KernelGate.Ready, HubConnectionState.Unavailable("gave up"), false))
    }

    @Test
    fun `busy gates map to connecting, reconnecting and draining`() {
        assertEquals(KernelLine.Connecting, KernelLine.of(busy(KernelGate.BusyKind.Starting), HubConnectionState.Disconnected, false))
        assertEquals(KernelLine.Connecting, KernelLine.of(busy(KernelGate.BusyKind.Connecting), HubConnectionState.Connecting, false))
        assertEquals(KernelLine.Reconnecting, KernelLine.of(busy(KernelGate.BusyKind.Reconnecting), HubConnectionState.Disconnected, false))
        assertEquals(KernelLine.Reconnecting, KernelLine.of(busy(KernelGate.BusyKind.Restarting), HubConnectionState.Disconnected, false))
        assertEquals(KernelLine.Draining, KernelLine.of(busy(KernelGate.BusyKind.Draining), connected, true))
    }

    @Test
    fun `down variants split into termux asleep and unavailable`() {
        val asleep = listOf(KernelGate.DownVariant.Asleep, KernelGate.DownVariant.Stopped, KernelGate.DownVariant.StoppedInTermux, KernelGate.DownVariant.Disconnected)
        val unavailable = listOf(KernelGate.DownVariant.StartFailed, KernelGate.DownVariant.CrashLoop, KernelGate.DownVariant.Protocol, KernelGate.DownVariant.Auth)
        asleep.forEach { assertEquals(it.name, KernelLine.TermuxAsleep, KernelLine.of(KernelGate.Down(it, null), HubConnectionState.Disconnected, false)) }
        unavailable.forEach { assertEquals(it.name, KernelLine.Unavailable, KernelLine.of(KernelGate.Down(it, "stderr"), HubConnectionState.Disconnected, false)) }
    }

    @Test
    fun `blocked routes to setup and shows nothing here`() {
        val line = KernelLine.of(KernelGate.Blocked(CheckId.Termux), HubConnectionState.Disconnected, false)
        assertEquals(KernelLine.Blocked, line)
        assertNull(line.homeNotice)
        assertEquals(ChatTransportState.Connected, line.transport)
        assertEquals(ComposerAvailability.Hidden, line.composer)
    }

    @Test
    fun `transport lines per HC 3_9`() {
        assertEquals(ChatTransportState.Connected, KernelLine.Connected.transport)
        assertEquals(ChatTransportState.Connecting, KernelLine.Connecting.transport)
        assertEquals(ChatTransportState.Reconnecting, KernelLine.Reconnecting.transport)
        assertEquals(ChatTransportState.Draining, KernelLine.Draining.transport)
        assertEquals(ChatTransportState.TermuxAsleep, KernelLine.TermuxAsleep.transport)
        assertEquals(ChatTransportState.Unavailable, KernelLine.Unavailable.transport)
    }

    @Test
    fun `composer column of HC 1_6`() {
        assertEquals(ComposerAvailability.Enabled, KernelLine.Connected.composer)
        assertEquals(ComposerAvailability.SendDisabled, KernelLine.Connecting.composer)
        assertEquals(ComposerAvailability.QueueLocally, KernelLine.Reconnecting.composer)
        assertEquals(ComposerAvailability.ReadOnly, KernelLine.TermuxAsleep.composer)
        assertEquals(ComposerAvailability.ReadOnly, KernelLine.Unavailable.composer)
        assertTrue(KernelLine.TermuxAsleep.offersStartKernel)
        assertTrue(KernelLine.Unavailable.offersStartKernel)
        assertFalse(KernelLine.Reconnecting.offersStartKernel)
    }

    @Test
    fun `home notices use the DS kernel table copy`() {
        assertNull(KernelLine.Connected.homeNotice)
        assertEquals(HomeKernelNotice(R.string.components_kernel_connecting), KernelLine.Connecting.homeNotice)
        assertEquals(HomeKernelNotice(R.string.components_kernel_reconnecting), KernelLine.Reconnecting.homeNotice)
        assertEquals(HomeKernelNotice(R.string.components_kernel_asleep, R.string.components_kernel_asleep_detail), KernelLine.TermuxAsleep.homeNotice)
        assertEquals(HomeKernelNotice(R.string.components_kernel_unavailable, R.string.components_kernel_unavailable_detail), KernelLine.Unavailable.homeNotice)
    }
}
