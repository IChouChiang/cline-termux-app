package dev.cline.termux.ui.chat

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.fakes.HubFixtures.reply
import dev.cline.termux.fakes.InMemoryTurnStore
import dev.cline.termux.ui.chat.rows.TranscriptGap
import dev.cline.termux.ui.chat.rows.TranscriptRowType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays recorded Hub traffic (docs/08) through `SessionStore` and feeds every resulting state
 * to [TranscriptController]: the indicator rule, approval hiding, streaming, the final fold into
 * a work row and the polite announcements must come out of the real event sequence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptFixtureReplayTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val session1 = "1791064689782_kck3p"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }

    private val strings = TranscriptStrings(
        runError = RunErrorStrings("The run failed: %1\$s", "The run failed before a response was produced.", "%1\$s", "cline", "unknown", "providers"),
        startErrorFormat = "Couldn't start the session: %1\$s",
        resumeErrorFormat = "Couldn't resume this session: %1\$s",
        historyErrorFormat = "Couldn't load this session: %1\$s",
        reasonText = SessionError.Reason.entries.associateWith { it.name },
    )

    private data class Step(val label: String, val ui: TranscriptUiState)

    private fun TestScope.newStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = FakeClock(),
        hubLifetimeKey = { "175794:a" },
    )

    /** Replays [lines] for [sessionId] and returns the controller's view after each line. */
    private suspend fun TestScope.replay(lines: List<HubFixtures.Line>, sessionId: String): List<Step> {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        hub.onGet = { HubFixtures.replyTo(lines, lines.mapNotNull { it.command }.first { it.command == HubCommands.SESSION_CREATE }.requestId!!).payload!! }
        // History stays unavailable so the live projection is what renders (as in SessionFixtureReplayTest).
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        store.open(sessionId)
        runCurrent()
        store.clearError(sessionId)
        runCurrent()

        val controller = TranscriptController(strings)
        val steps = mutableListOf<Step>()
        fun snapshot(label: String) {
            steps += Step(label, controller.reduce(TranscriptInput(sessionId, store.state(sessionId).value)))
        }
        for (line in lines) {
            line.command?.let { cmd ->
                if (cmd.command == HubCommands.SESSION_SEND_INPUT && cmd.sessionId == sessionId) {
                    hub.nextRequestIds += cmd.requestId!!
                    store.send(SendRequest(sessionId, cmd.payload!!["prompt"]!!.jsonPrimitive.content))
                    runCurrent()
                    snapshot("send")
                }
            }
            line.reply?.let { reply -> hub.sends.firstOrNull { it.requestId == reply.requestId }?.reply?.complete(reply) }
            line.event?.let { event ->
                store.dispatch(event)
                runCurrent()
                if (event.sessionId == sessionId) snapshot(event.event)
            }
        }
        return steps
    }

    @Test
    fun `fixture 02 - thinking until output, hidden for the approval, folded when complete`() = runTest {
        val steps = replay(f02, session1)

        val send = steps.first { it.label == "send" }.ui
        assertEquals(ChatStatus.Starting, send.status)
        assertNotNull("Thinking... appears immediately after send", send.indicator)
        assertEquals(TranscriptGap.Block, send.indicator!!.gapAbove)
        assertEquals(1, send.userMessageCount)

        val approval = steps.first { it.label == "approval.requested" }.ui
        assertNull("the approval sheet replaces the indicator", approval.indicator)
        assertEquals(1, approval.pendingApprovals)

        val streaming = steps.filter { it.label == "assistant.delta" }.map { it.ui }
        assertTrue(streaming.isNotEmpty())
        streaming.forEach { assertNull("no indicator while an answer streams", it.indicator) }

        val final = steps.last().ui
        assertEquals(ChatStatus.Completed, final.status)
        assertNull(final.indicator)
        assertEquals(listOf(TranscriptRowType.User, TranscriptRowType.Work, TranscriptRowType.Assistant), final.model.rows.map { it.type })
        assertEquals(1, final.userMessageCount)
        assertTrue(final.lastAssistantText!!.startsWith("The command ran successfully"))
        assertEquals(2500L, final.usage!!.totals.inputTokens)

        // The Hub reports `idle` before `run.completed`; the completion is still announced once.
        val announcer = TranscriptAnnouncer(strings)
        val announcements = steps.mapNotNull { announcer.next(it.ui) }
        assertEquals(TranscriptAnnouncement.Working, announcements.first())
        val completed = announcements.filterIsInstance<TranscriptAnnouncement.Completed>().single()
        assertTrue(completed.excerpt.startsWith("The command ran successfully"))
        assertTrue(completed.excerpt.length <= ANNOUNCE_EXCERPT_CHARS)
    }

    @Test
    fun `fixture 03 - rejected tool and abort leave the run unfolded with no indicator`() = runTest {
        val sessionId = f03.mapNotNull { it.command }.first { it.command == HubCommands.SESSION_SEND_INPUT }.sessionId!!
        val steps = replay(f03, sessionId)
        val final = steps.last().ui
        assertNull(final.indicator)
        assertTrue(final.status == ChatStatus.Completed || final.status == ChatStatus.Cancelled)
        assertTrue(final.model.rows.isNotEmpty())
        steps.filter { it.label == "approval.requested" }.forEach { assertNull(it.ui.indicator) }
    }
}
