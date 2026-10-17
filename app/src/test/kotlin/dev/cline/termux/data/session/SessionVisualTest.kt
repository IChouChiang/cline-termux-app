package dev.cline.termux.data.session

import dev.cline.termux.data.chat.ChatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §1.6 precedence plus the desktop `session-status.test.ts` cases. */
class SessionVisualTest {

    private val everything = SessionVisualFlags(
        pendingAction = PendingKind.Delete,
        approvalRequired = true,
        questionAsked = true,
        starting = true,
        unread = true,
    )

    @Test
    fun `action pending wins over everything, even failed`() {
        assertEquals(SessionVisual.ActionPending(PendingKind.Delete), SessionVisual.of("failed", everything))
        assertEquals(SessionVisual.ActionPending(PendingKind.Rename), SessionVisual.of("running", SessionVisualFlags(pendingAction = PendingKind.Rename)))
    }

    @Test
    fun `precedence ladder, one flag removed at a time`() {
        var flags = everything.copy(pendingAction = null)
        assertEquals(SessionVisual.Failed, SessionVisual.of("failed", flags))
        assertEquals(SessionVisual.Failed, SessionVisual.of("error", flags))
        assertEquals(SessionVisual.ApprovalRequired, SessionVisual.of("running", flags))
        flags = flags.copy(approvalRequired = false)
        assertEquals(SessionVisual.QuestionAsked, SessionVisual.of("running", flags))
        flags = flags.copy(questionAsked = false)
        assertEquals(SessionVisual.Pending, SessionVisual.of("pending", flags))
        assertEquals(SessionVisual.Starting, SessionVisual.of("running", flags))
        flags = flags.copy(starting = false)
        assertEquals(SessionVisual.Running, SessionVisual.of("running", flags))
        assertEquals(SessionVisual.Unread, SessionVisual.of("completed", flags))
        flags = flags.copy(unread = false)
        assertEquals(SessionVisual.Completed, SessionVisual.of("completed", flags))
        assertEquals(SessionVisual.Cancelled, SessionVisual.of("aborted", flags))
        assertEquals(SessionVisual.Cancelled, SessionVisual.of("cancelled", flags))
        assertEquals(SessionVisual.Idle, SessionVisual.of("idle", flags))
    }

    @Test
    fun `approval beats pending status and question`() {
        assertEquals(SessionVisual.ApprovalRequired, SessionVisual.of("pending", SessionVisualFlags(approvalRequired = true, questionAsked = true)))
        assertEquals(SessionVisual.QuestionAsked, SessionVisual.of("pending", SessionVisualFlags(questionAsked = true)))
    }

    @Test
    fun `starting comes from the kernel word or the flag and pulses`() {
        assertEquals(SessionVisual.Starting, SessionVisual.of("starting"))
        assertEquals(SessionVisual.Starting, SessionVisual.of("provisioning"))
        assertEquals(SessionVisual.Starting, SessionVisual.of(null, SessionVisualFlags(starting = true)))
        assertTrue(SessionVisual.Starting.pulsing)
        assertFalse(SessionVisual.Running.pulsing)
        assertFalse(SessionVisual.ActionPending(PendingKind.Fork).pulsing)
    }

    @Test
    fun `unread applies to idle-ish sessions only`() {
        val unread = SessionVisualFlags(unread = true)
        for (status in listOf(null, "idle", "completed", "aborted", "cancelled", "unknown")) {
            assertEquals("status=$status", SessionVisual.Unread, SessionVisual.of(status, unread))
        }
        assertEquals(SessionVisual.Running, SessionVisual.of("running", unread))
        assertEquals(SessionVisual.Pending, SessionVisual.of("pending", unread))
        assertEquals(SessionVisual.Idle, SessionVisual.of("stopping", unread))
    }

    @Test
    fun `neutral states hide the dot, failed is error, pending family is warning`() {
        assertNull(SessionVisual.Completed.dot)
        assertNull(SessionVisual.Cancelled.dot)
        assertNull(SessionVisual.Idle.dot)
        assertEquals(DotTone.Error, SessionVisual.Failed.dot)
        assertEquals(DotTone.Warning, SessionVisual.ApprovalRequired.dot)
        assertEquals(DotTone.Warning, SessionVisual.QuestionAsked.dot)
        assertEquals(DotTone.Warning, SessionVisual.Pending.dot)
        assertEquals(DotTone.Warning, SessionVisual.Starting.dot)
        assertEquals(DotTone.Success, SessionVisual.Running.dot)
        assertEquals(DotTone.Info, SessionVisual.Unread.dot)
    }

    @Test
    fun `glyphs follow the icon column`() {
        assertEquals(StatusGlyph.Failed, SessionVisual.Failed.glyph)
        assertEquals(StatusGlyph.ApprovalRequired, SessionVisual.ApprovalRequired.glyph)
        assertEquals(StatusGlyph.QuestionAsked, SessionVisual.QuestionAsked.glyph)
        assertEquals(StatusGlyph.Pending, SessionVisual.Pending.glyph)
        assertEquals(StatusGlyph.Running, SessionVisual.Running.glyph)
        assertEquals(StatusGlyph.Completed, SessionVisual.Completed.glyph)
        assertEquals(StatusGlyph.Cancelled, SessionVisual.Cancelled.glyph)
        assertNull(SessionVisual.Starting.glyph)
        assertNull(SessionVisual.Unread.glyph)
        assertNull(SessionVisual.Idle.glyph)
    }

    // Desktop lib/session-status.test.ts, "sessionStatusTone".

    @Test
    fun `desktop - marks running sessions as running`() {
        assertEquals(SessionVisual.Running, SessionVisual.of("running"))
    }

    @Test
    fun `desktop - marks failed and error sessions as errors`() {
        assertEquals(SessionVisual.Failed, SessionVisual.of("failed"))
        assertEquals(SessionVisual.Failed, SessionVisual.of("error"))
    }

    @Test
    fun `desktop - treats every other status as neutral`() {
        for (status in listOf("idle", "stopping", "completed", "cancelled", null)) {
            val visual = SessionVisual.of(status)
            assertTrue("status=$status", visual == SessionVisual.Idle || visual == SessionVisual.Completed || visual == SessionVisual.Cancelled)
            assertNull("status=$status", visual.dot)
        }
    }

    @Test
    fun `status words are case and whitespace tolerant`() {
        assertEquals(SessionVisual.Running, SessionVisual.of(" Running "))
        assertEquals(SessionVisual.Idle, SessionVisual.of("something-new"))
    }

    @Test
    fun `chat status overload shares the table`() {
        assertEquals(SessionVisual.Running, SessionVisual.of(ChatStatus.Running))
        assertEquals(SessionVisual.Starting, SessionVisual.of(ChatStatus.Starting))
        assertEquals(SessionVisual.Failed, SessionVisual.of(ChatStatus.Failed))
        assertEquals(SessionVisual.Cancelled, SessionVisual.of(ChatStatus.Cancelled))
        assertEquals(SessionVisual.Completed, SessionVisual.of(ChatStatus.Completed))
        assertEquals(SessionVisual.Idle, SessionVisual.of(ChatStatus.Stopping))
        assertEquals(SessionVisual.Unread, SessionVisual.of(ChatStatus.Idle, SessionVisualFlags(unread = true)))
    }

    @Test
    fun `kernel visuals follow the second table`() {
        assertEquals(DotTone.Success, KernelVisual.Connected(25463).dot)
        assertFalse(KernelVisual.Connected(25463).pulsing)
        assertEquals(DotTone.Warning, KernelVisual.Connecting.dot)
        assertTrue(KernelVisual.Connecting.pulsing)
        assertTrue(KernelVisual.Reconnecting.pulsing)
        assertEquals(DotTone.Outline, KernelVisual.Unavailable.dot)
        assertEquals(DotTone.Outline, KernelVisual.TermuxAsleep.dot)
        assertFalse(KernelVisual.TermuxAsleep.pulsing)
    }
}
