package dev.cline.termux.ui.chat

import app.cash.turbine.test
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.LiveProjection
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §6 "Streaming text": deltas reach the transcript at most every 50 ms; structural changes go out at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamCoalescingTest {
    private data class V(val key: Int, val text: String)

    private val byKey: (V, V) -> Boolean = { a, b -> a.key != b.key }

    @Test
    fun `first value passes, deltas are throttled to one per window with the latest winning`() = runTest {
        val input = Channel<V>(Channel.UNLIMITED)
        input.consumeAsFlow().coalesceUnless(50, byKey).test {
            input.send(V(1, "a"))
            assertEquals(V(1, "a"), awaitItem())
            input.send(V(1, "ab"))
            runCurrent()
            advanceTimeBy(20)
            input.send(V(1, "abc"))
            runCurrent()
            expectNoEvents()
            advanceTimeBy(31)
            assertEquals(V(1, "abc"), awaitItem())
            input.close()
            awaitComplete()
        }
    }

    @Test
    fun `a continuous stream still emits every window`() = runTest {
        val input = MutableSharedFlow<V>(extraBufferCapacity = 64)
        input.coalesceUnless(50, byKey).test {
            input.emit(V(1, "x"))
            assertEquals("x", awaitItem().text)
            var text = "x"
            repeat(10) {
                text += "y"
                input.emit(V(1, text))
                runCurrent()
                advanceTimeBy(10)
            }
            // 100 ms of 10 ms deltas: at least two throttled emissions, never one per delta.
            val first = awaitItem()
            val second = awaitItem()
            assertTrue(first.text.length < second.text.length)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an urgent change goes out at once and drops the pending delta`() = runTest {
        val input = Channel<V>(Channel.UNLIMITED)
        input.consumeAsFlow().coalesceUnless(50, byKey).test {
            input.send(V(1, "a"))
            awaitItem()
            input.send(V(1, "ab"))
            runCurrent()
            input.send(V(2, "new row"))
            assertEquals(V(2, "new row"), awaitItem())
            advanceTimeBy(100)
            expectNoEvents()
            input.close()
            awaitComplete()
        }
    }

    @Test
    fun `the pending value is flushed when upstream completes`() = runTest {
        val input = Channel<V>(Channel.UNLIMITED)
        input.consumeAsFlow().coalesceUnless(50, byKey).test {
            input.send(V(1, "a"))
            awaitItem()
            input.send(V(1, "ab"))
            runCurrent()
            input.close()
            assertEquals(V(1, "ab"), awaitItem())
            awaitComplete()
        }
    }

    // ---------------------------------------------------------------- SessionState structure

    private fun msg(id: String, role: Role, content: String) = ChatMessage(id = id, sessionId = "s", role = role, content = content, createdAt = 1)

    private fun state(
        live: List<ChatMessage>,
        streaming: String? = null,
        phase: RunPhase = RunPhase.Running,
        status: SessionStatus = SessionStatus.Running,
    ) = SessionState(
        sessionId = "s",
        facts = SessionFacts("s", status = status, runPhase = phase),
        projection = LiveProjection(messages = live, streamingMessageId = streaming),
    )

    @Test
    fun `text growth of the streaming message is not urgent, new rows and status changes are`() {
        val base = listOf(msg("u1", Role.User, "go"), msg("a1", Role.Assistant, "par"))
        val grown = listOf(msg("u1", Role.User, "go"), msg("a1", Role.Assistant, "partial"))
        assertFalse(isUrgentTranscriptChange(state(base, "a1"), state(grown, "a1")))
        assertTrue(isUrgentTranscriptChange(state(base, "a1"), state(base + msg("t1", Role.Tool, "{}"), "a1")))
        assertTrue(isUrgentTranscriptChange(state(base, "a1"), state(base, null)))
        assertTrue(isUrgentTranscriptChange(state(base, "a1"), state(base, "a1", RunPhase.Idle, SessionStatus.Completed)))
        // A heartbeat (facts change only) is coalesced.
        val beat = state(grown, "a1").let { it.copy(facts = it.facts.copy(lastEventAt = 42)) }
        assertFalse(isUrgentTranscriptChange(state(grown, "a1"), beat))
    }
}
