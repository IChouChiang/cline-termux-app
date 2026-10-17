package dev.cline.termux.data.session

import dev.cline.termux.data.chat.ChatStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HC §1.3.4 / §3.16: after a Hub restart cuts a run short the session reads Idle, although the
 * restarted Hub's record says the run failed (or was cancelled / completed).
 */
class InterruptedStatusTest {
    private val interrupted = TurnRecord(requestId = "req1", sessionId = "s1", sentAt = 0L, open = false, outcome = TurnOutcome.Interrupted)

    private fun state(status: SessionStatus, vararg turns: TurnRecord) =
        SessionState(sessionId = "s1", facts = SessionFacts("s1", status = status), turns = turns.toList())

    @Test
    fun `an interrupted turn shows Idle whatever the restarted Hub reports`() {
        assertEquals(ChatStatus.Idle, state(SessionStatus.Failed, interrupted).chatStatus)
        assertEquals(ChatStatus.Idle, state(SessionStatus.Cancelled, interrupted).chatStatus)
        assertEquals(ChatStatus.Idle, state(SessionStatus.Completed, interrupted).chatStatus)
    }

    @Test
    fun `a later turn's own outcome wins`() {
        val failed = TurnRecord(requestId = "req2", sessionId = "s1", sentAt = 0L, open = false, outcome = TurnOutcome.Failed)
        assertEquals(ChatStatus.Failed, state(SessionStatus.Failed, interrupted, failed).chatStatus)
    }

    @Test
    fun `a run in progress after the interruption is not hidden`() {
        val running = TurnRecord(requestId = "req2", sessionId = "s1", sentAt = 0L, open = true)
        assertEquals(ChatStatus.Running, state(SessionStatus.Running, interrupted, running).chatStatus)
    }
}
