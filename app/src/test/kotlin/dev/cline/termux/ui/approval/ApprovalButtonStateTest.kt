package dev.cline.termux.ui.approval

import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header control's three states (auto-approve-control.md §2A, decided A1) and their glyphs (A15). */
class ApprovalButtonStateTest {
    private fun facts(sessionAuto: Boolean?, running: Boolean, source: String? = "android", status: SessionStatus? = null) = SessionFacts(
        sessionId = "s1",
        status = status ?: if (running) SessionStatus.Running else SessionStatus.Idle,
        runPhase = if (running) RunPhase.Running else RunPhase.Idle,
        metadata = buildJsonObject { sessionAuto?.let { put("autoApproveTools", it) } },
        source = source,
    )

    /** As the chat passes it: the open session's facts and `SessionState.autoApproveTools`. */
    private fun of(mode: ApprovalMode, facts: SessionFacts? = null, gate: KernelGate = KernelGate.Ready, writing: Boolean = false, lastSeen: Boolean? = null) =
        facts?.let { SessionState(it.sessionId, facts = it, runtimeAutoApprove = lastSeen) }
            .let { s -> ApprovalButtonState.of(mode, writing, gate, s?.facts, s?.autoApproveTools) }

    @Test
    fun `the global mode decides the shield, on Home too`() {
        assertEquals(ApprovalVisual.Auto, of(ApprovalMode.Auto).visual)
        assertEquals(ApprovalVisual.Ask, of(ApprovalMode.Ask).visual)
        assertNull(of(ApprovalMode.Ask).sessionId)
        // A session's own old policies never show as the mode (decided A1).
        assertEquals(ApprovalVisual.Auto, of(ApprovalMode.Auto, facts(sessionAuto = false, running = false)).visual)
        assertEquals(ApprovalVisual.Ask, of(ApprovalMode.Ask, facts(sessionAuto = true, running = false)).visual)
    }

    @Test
    fun `pending while a running session still auto-approves after a switch to Ask`() {
        val pending = of(ApprovalMode.Ask, facts(sessionAuto = true, running = true))
        assertTrue(pending.pending)
        assertEquals(ApprovalVisual.Pending, pending.visual)
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = false, running = true)).pending)
        assertFalse(of(ApprovalMode.Auto, facts(sessionAuto = true, running = true)).pending)
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = true, running = false)).pending)
    }

    @Test
    fun `a pin that dropped the key from the record keeps the pending state and the desktop note`() {
        // The Hub strips `autoApproveTools` from a `session.update` with metadata; the runtime keeps auto-approving.
        val pinned = facts(sessionAuto = null, running = true, source = "desktop")
        assertTrue(of(ApprovalMode.Ask, pinned, lastSeen = true).pending)
        assertTrue(of(ApprovalMode.Ask, pinned, lastSeen = true).desktopSession)
        assertFalse(of(ApprovalMode.Ask, pinned, lastSeen = false).pending)
        // Never seen: no claim either way.
        assertFalse(of(ApprovalMode.Ask, pinned, lastSeen = null).pending)
        // The record's own key wins over the value last seen.
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = false, running = true), lastSeen = true).pending)
    }

    @Test
    fun `disabled before the first read and without Termux commands`() {
        assertFalse(of(ApprovalMode.Unknown).enabled)
        assertFalse(of(ApprovalMode.Auto, gate = KernelGate.DegradedNoCommands).enabled)
        assertFalse(of(ApprovalMode.Auto, gate = KernelGate.Blocked(CheckId.Termux)).enabled)
        assertTrue(of(ApprovalMode.Auto, gate = KernelGate.Down(KernelGate.DownVariant.Stopped, null)).enabled)
        assertTrue(of(ApprovalMode.Ask).enabled)
    }

    @Test
    fun `the desktop note shows only for a desktop session that still auto-approves`() {
        assertTrue(of(ApprovalMode.Ask, facts(sessionAuto = true, running = false, source = "desktop")).desktopSession)
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = false, running = false, source = "desktop")).desktopSession)
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = true, running = false, source = "cli")).desktopSession)
    }

    @Test
    fun `a stopped run is remembered for the announcement`() {
        assertTrue(of(ApprovalMode.Ask, facts(sessionAuto = false, running = false, status = SessionStatus.Cancelled)).runStopped)
        assertFalse(of(ApprovalMode.Ask, facts(sessionAuto = false, running = false)).runStopped)
    }

    @Test
    fun `Ask is the ticked shield and Auto the bolt, both plain`() {
        // Decided A15: the ticked shield is the guarded state; no disc, no ring, no dot.
        assertEquals(ApprovalGlyph.TickedShield, ApprovalVisual.Ask.glyph)
        assertFalse(ApprovalVisual.Ask.waitsForRunEnd)
        assertEquals(ApprovalGlyph.Bolt, ApprovalVisual.Auto.glyph)
        assertFalse(ApprovalVisual.Auto.waitsForRunEnd)
    }

    @Test
    fun `pending draws the target state's glyph, Ask's, with the dot until the run ends`() {
        val pending = of(ApprovalMode.Ask, facts(sessionAuto = true, running = true)).visual
        assertEquals(ApprovalVisual.Pending, pending)
        // The run still auto-approves, but the glyph is already the one it switches to.
        assertEquals(ApprovalGlyph.TickedShield, pending.glyph)
        assertTrue(pending.waitsForRunEnd)
        // The run ends: the same glyph stays and only the dot and the low emphasis go.
        val applied = of(ApprovalMode.Ask, facts(sessionAuto = true, running = false)).visual
        assertEquals(pending.glyph, applied.glyph)
        assertFalse(applied.waitsForRunEnd)
    }

    @Test
    fun `before the first read the disabled glyph is the ticked shield without the dot`() {
        val unknown = of(ApprovalMode.Unknown)
        assertFalse(unknown.enabled)
        assertEquals(ApprovalGlyph.TickedShield, unknown.visual.glyph)
        assertFalse(unknown.visual.waitsForRunEnd)
    }
}
