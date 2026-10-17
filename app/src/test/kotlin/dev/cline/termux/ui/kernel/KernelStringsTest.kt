package dev.cline.termux.ui.kernel

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.cline.termux.R
import dev.cline.termux.core.kernel.HubSessionActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The desktop-verbatim rows of onboarding-kernel.md §9 (D), resolved through the real
 * resources, so a stray "…" for "..." or a reworded sentence fails here (KS §10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KernelStringsTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun text(id: Int, vararg args: Any) = UiText.Res(id, args.toList()).resolve(resources)

    @Test
    fun `desktop strings are verbatim`() {
        assertEquals("Cline Hub @25463", text(R.string.hub_at_port, "25463"))
        assertEquals("Cline Hub @unknown", text(R.string.hub_at_unknown))
        assertEquals("Cline Hub is not connected.", text(R.string.hub_not_connected))
        assertEquals("Connecting...", text(R.string.kernel_status_connecting))
        assertEquals("Reconnecting...", text(R.string.kernel_status_reconnecting))
        assertEquals("Restarting…", text(R.string.kernel_status_restarting))
        assertEquals("Reconnecting...", text(R.string.kernel_restart_phase_reconnecting))
        assertEquals("Try again", text(R.string.kernel_try_again))
        assertEquals("Cline Hub was updated", text(R.string.hub_updated_title))
        assertEquals(
            "Another Cline installation updated the shared Cline Hub (core 0.1.4), and it no longer matches this app.",
            text(R.string.hub_updated_body, "0.1.4"),
        )
        assertEquals(
            "Another Cline installation updated the shared Cline Hub, and it no longer matches this app.",
            text(R.string.hub_updated_body_no_core),
        )
        assertEquals("Cline Hub update required", text(R.string.hub_outdated_title))
        assertEquals("Later", text(R.string.hub_update_later))
        assertEquals("Try again", text(R.string.hub_update_try_again))
        assertEquals("Termux asleep", text(R.string.kernel_status_asleep))
        assertEquals("Start kernel to continue.", text(R.string.kernel_status_asleep_line2))
    }

    @Test
    fun `reliability row warning dot has words, not only colour`() {
        // DS §8.1.3: the merged row reads "Background reliability, Required step not done, 3 of 5 done".
        assertEquals("Background reliability", text(R.string.kernel_reliability_row))
        assertEquals("Required step not done", text(R.string.kernel_reliability_required_missing))
        assertEquals("3 of 5 done", text(R.string.reliability_progress, 3, 5))
    }

    @Test
    fun `describeOutdatedHubSessions resolves like the desktop`() {
        fun describe(sessions: Int, clients: Int) = KernelCopy.describeSessions(HubSessionActivity(sessions, clients)).resolve(resources)
        assertEquals("1 active session from 1 connected Cline client", describe(1, 1))
        assertEquals("1 active session from 2 connected Cline clients", describe(1, 2))
        assertEquals("2 active sessions", describe(2, 0))
        assertEquals("active sessions from other Cline clients", describe(0, 0))
        assertEquals(
            "The Cline Hub is still serving 2 active sessions from 3 connected Cline clients. Restart kernel stops that Hub and interrupts its sessions.",
            UiText.Res(R.string.kernel_restart_confirm_body, KernelCopy.describeSessions(HubSessionActivity(2, 3))).resolve(resources),
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `plurals follow English rules on a device whose locale has one form`() {
        fun describe(sessions: Int, clients: Int) = KernelCopy.describeSessions(HubSessionActivity(sessions, clients)).resolve(resources)
        assertEquals("1 active session from 1 connected Cline client", describe(1, 1))
        assertEquals("2 active sessions from 1 connected Cline client", describe(2, 1))
        assertEquals("1 step OK", UiText.Plural(R.plurals.kernel_setup_ok_summary, 1, listOf(1)).resolve(resources))
    }

    @Test
    fun `the F2 panel copy resolves`() {
        val asleep = KernelCopy.panel(dev.cline.termux.core.kernel.KernelGate.Down(dev.cline.termux.core.kernel.KernelGate.DownVariant.Asleep, null))!!
        assertEquals(
            "Android stopped Termux while it was in the background, and the Cline Hub stopped with it. Your sessions are saved. Start kernel to continue.",
            asleep.body.single().resolve(resources),
        )
        assertEquals("Why did this happen?", asleep.secondary!!.label().resolve(resources))
    }
}
