package dev.cline.termux.ui.marketplace

import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.HelperUnavailableReason
import dev.cline.termux.ui.components.KernelBlock
import org.junit.Assert.assertEquals
import org.junit.Test

/** CR §3.4 helper unavailable: the shared SP §0.3 card, or the SP §0.5 block card. */
class MarketplaceHelperViewTest {

    private fun view(reason: HelperFailure, detail: String? = null, kernel: KernelGate = KernelGate.Ready) =
        HelperState.Unavailable(reason, detail).marketplaceView(kernel)

    @Test
    fun `start failures show the shared card with the detail`() {
        assertEquals(
            MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.StartTimeout, "error: listen EADDRINUSE")),
            view(HelperFailure.StartTimeout, "error: listen EADDRINUSE"),
        )
        assertEquals(
            MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.SocketFailed, null)),
            view(HelperFailure.SocketFailed),
        )
    }

    @Test
    fun `unsupported build, busy port and stopped kernel keep their reason`() {
        assertEquals(
            MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.BuildUnsupported, "HTTP 426")),
            view(HelperFailure.BuildUnsupported, "HTTP 426"),
        )
        assertEquals(MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.PortInUse)), view(HelperFailure.PortInUse))
        assertEquals(MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.KernelStopped)), view(HelperFailure.KernelStopped))
    }

    @Test
    fun `card reasons show even while the gate is blocked`() {
        assertEquals(
            MarketplaceHelperView.Card(HelperUnavailableModel(HelperUnavailableReason.StartTimeout)),
            view(HelperFailure.StartTimeout, kernel = KernelGate.Blocked(CheckId.Termux)),
        )
    }

    @Test
    fun `RUN_COMMAND blocks show their KernelBlockedCard`() {
        assertEquals(MarketplaceHelperView.Blocked(KernelBlock.TermuxMissing), view(HelperFailure.TermuxMissing))
        assertEquals(MarketplaceHelperView.Blocked(KernelBlock.PermissionMissing), view(HelperFailure.PermissionMissing))
        assertEquals(
            MarketplaceHelperView.Blocked(KernelBlock.ExternalAppsBlocked),
            view(HelperFailure.ExternalAppsBlocked, "allow-external-apps is false"),
        )
    }

    @Test
    fun `a blocked gate already shows the block card`() {
        val blocked = KernelGate.Blocked(CheckId.ExternalApps)
        assertEquals(MarketplaceHelperView.None, view(HelperFailure.ExternalAppsBlocked, kernel = blocked))
        assertEquals(MarketplaceHelperView.None, view(HelperFailure.TermuxMissing, kernel = blocked))
    }
}
