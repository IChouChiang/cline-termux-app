package dev.cline.termux.ui.components

import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SP §0.3: which helper failures the shared unavailable card shows, and with what detail. */
class HelperUnavailableModelTest {

    @Test
    fun `card reasons map one to one`() {
        assertEquals(HelperUnavailableReason.StartTimeout, HelperFailure.StartTimeout.toUnavailableReason())
        assertEquals(HelperUnavailableReason.SocketFailed, HelperFailure.SocketFailed.toUnavailableReason())
        assertEquals(HelperUnavailableReason.BuildUnsupported, HelperFailure.BuildUnsupported.toUnavailableReason())
        assertEquals(HelperUnavailableReason.PortInUse, HelperFailure.PortInUse.toUnavailableReason())
        assertEquals(HelperUnavailableReason.KernelStopped, HelperFailure.KernelStopped.toUnavailableReason())
    }

    @Test
    fun `RUN_COMMAND blocks have no card`() {
        assertNull(HelperFailure.TermuxMissing.toUnavailableReason())
        assertNull(HelperFailure.PermissionMissing.toUnavailableReason())
        assertNull(HelperFailure.ExternalAppsBlocked.toUnavailableReason())
        assertNull(HelperState.Unavailable(HelperFailure.ExternalAppsBlocked, "allow-external-apps is false").toUnavailableModel())
    }

    @Test
    fun `model keeps the detail`() {
        assertEquals(
            HelperUnavailableModel(HelperUnavailableReason.StartTimeout, "Error: listen EADDRINUSE"),
            HelperState.Unavailable(HelperFailure.StartTimeout, "Error: listen EADDRINUSE").toUnavailableModel(),
        )
        assertEquals(
            HelperUnavailableModel(HelperUnavailableReason.PortInUse, null),
            HelperState.Unavailable(HelperFailure.PortInUse, null).toUnavailableModel(),
        )
    }
}
