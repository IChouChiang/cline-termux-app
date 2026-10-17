package dev.cline.termux.data.session

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.notifications.NotificationStates.T0
import dev.cline.termux.data.notifications.NotificationStates.ended
import dev.cline.termux.data.notifications.NotificationStates.idle
import dev.cline.termux.data.notifications.NotificationStates.running
import dev.cline.termux.data.notifications.NotificationStates.withApproval
import dev.cline.termux.data.notifications.NotificationStates.withQueued
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The projection the notifier and the keep-alive watch instead of whole session states (docs/07 §4.7). */
class NotifyFactsTest {
    @Test
    fun streamedEventsDuringARunDoNotChangeTheFacts() {
        val running = running("s1", startedAt = T0)
        val later = running.copy(
            facts = running.facts.copy(lastSequence = 90, lastEventAt = T0 + 30_000, currentIteration = 4),
            projection = LiveProjection(messages = listOf(ChatMessage(id = "a:1", sessionId = "s1", role = Role.Assistant, content = "partial", createdAt = T0 + 30_000))),
        )
        assertEquals(NotifyFacts.of(running), NotifyFacts.of(later))
        assertNull(NotifyFacts.of(later).eventAt)
    }

    @Test
    fun withoutAnActiveRunTheLastEventTimeIsKept() {
        val done = running("s1").ended(SessionStatus.Completed, at = T0 + 4_000)
        val facts = NotifyFacts.of(done)
        assertEquals(T0 + 4_000, facts.eventAt)
        assertEquals(SessionStatus.Completed, facts.status)
        assertNull(facts.activeRun)
        assertNull(facts.failureDetail)
    }

    @Test
    fun aFailureCarriesItsErrorRowTrimmed() {
        val failed = running("s1").ended(SessionStatus.Failed, at = T0 + 1_000, error = "  Provider returned 401  ")
        assertEquals("Provider returned 401", NotifyFacts.of(failed).failureDetail)
        // An error row from before this failure is not its text.
        val older = running("s1").ended(SessionStatus.Failed, at = T0 + 1_000, error = "first")
            .ended(SessionStatus.Failed, at = T0 + 2_000)
        assertNull(NotifyFacts.of(older).failureDetail)
    }

    @Test
    fun pendingInputQueuedPromptsAndSequencing() {
        val waiting = NotifyFacts.of(running("s1").withApproval("a1").withQueued(2))
        assertTrue(waiting.hasPendingInput)
        assertTrue(waiting.hasQueuedPrompts)
        assertTrue(waiting.sequenced)
        val seeded = NotifyFacts.of(idle("s2", seq = 0))
        assertFalse(seeded.hasPendingInput)
        assertFalse(seeded.hasQueuedPrompts)
        assertFalse(seeded.sequenced)
        assertEquals("Fix the login redirect", seeded.title)
    }
}
