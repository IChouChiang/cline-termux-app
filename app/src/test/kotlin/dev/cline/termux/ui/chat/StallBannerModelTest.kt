package dev.cline.termux.ui.chat

import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.StallFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A4: when the stall notice shows, and "Keep waiting" hiding it until the next episode. */
class StallBannerModelTest {
    private val stall = StallFact(since = 30_000, requestId = "r1")

    @Test
    fun `a stall of a running session on a live line shows`() {
        assertEquals(stall, StallBannerModel.shown("s1", stall, RunPhase.Running, KernelLine.Connected, dismissed = null))
        assertEquals(stall, StallBannerModel.shown("s1", stall, RunPhase.Starting, KernelLine.Draining, dismissed = null))
    }

    @Test
    fun `no stall, no session, no notice`() {
        assertNull(StallBannerModel.shown("s1", null, RunPhase.Running, KernelLine.Connected, dismissed = null))
        assertNull(StallBannerModel.shown(null, stall, RunPhase.Running, KernelLine.Connected, dismissed = null))
    }

    @Test
    fun `Keep waiting hides the episode until the next one`() {
        val dismissed = StallBannerModel.dismiss("s1", stall)
        assertNull(StallBannerModel.shown("s1", stall, RunPhase.Running, KernelLine.Connected, dismissed))
        val next = StallFact(since = 90_000, requestId = "r1")
        assertEquals("the kernel answered and went silent again", next, StallBannerModel.shown("s1", next, RunPhase.Running, KernelLine.Connected, dismissed))
    }

    @Test
    fun `Keep waiting in one session leaves another's episode alone`() {
        val dismissed = StallBannerModel.dismiss("s1", stall)
        assertEquals(stall, StallBannerModel.shown("s2", stall, RunPhase.Running, KernelLine.Connected, dismissed))
    }

    @Test
    fun `the kernel banner speaks while the line is down`() {
        for (line in KernelLine.entries.filter { it != KernelLine.Connected && it != KernelLine.Draining }) {
            assertNull(line.name, StallBannerModel.shown("s1", stall, RunPhase.Running, line, dismissed = null))
        }
    }

    @Test
    fun `a run that waits for the user, stops or ended shows none`() {
        for (phase in RunPhase.entries.filter { it != RunPhase.Running && it != RunPhase.Starting }) {
            assertNull(phase.name, StallBannerModel.shown("s1", stall, phase, KernelLine.Connected, dismissed = null))
        }
    }
}
