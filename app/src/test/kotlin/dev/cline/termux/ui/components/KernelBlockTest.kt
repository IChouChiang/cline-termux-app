package dev.cline.termux.ui.components

import dev.cline.termux.core.kernel.CheckId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one KernelBlock → Kernel setup focus mapping every host's "Open Kernel setup" uses (SP §0.5). */
class KernelBlockTest {

    @Test
    fun `each block opens Kernel setup on its own checklist row`() {
        assertEquals(CheckId.Termux, KernelBlock.TermuxMissing.checkId())
        assertEquals(CheckId.Permission, KernelBlock.PermissionMissing.checkId())
        assertEquals(CheckId.ExternalApps, KernelBlock.ExternalAppsBlocked.checkId())
        assertEquals(CheckId.Kernel, KernelBlock.TimedOut.checkId())
        assertEquals(CheckId.Kernel, KernelBlock.KernelSetup.checkId())
    }
}
