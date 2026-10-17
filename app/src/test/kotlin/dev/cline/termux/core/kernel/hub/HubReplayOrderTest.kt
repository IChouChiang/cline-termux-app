package dev.cline.termux.core.kernel.hub

import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.session.HubClientSessionHub
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.InMemoryCursorStore
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A reconnect while a run kept going (docs/02 step 6, docs/07 §3.1, HC §1.3.4): the Hub pages
 * the global replay with a yield between pages and serves frames concurrently, while a
 * cursor-less per-session subscribe is live at once. The real [HubClient] and [SessionStore]
 * together must apply the whole backlog in sequence order, and the store's drain wait must not
 * end before the backlog is in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubReplayOrderTest {

    private fun tool(name: String, sequence: Long, toolCallId: String) = HubEventEnvelope(
        event = name,
        eventId = "evt-$sequence",
        sessionId = "s1",
        sequence = sequence,
        timestamp = 1_000L + sequence,
        payload = buildJsonObject {
            put("toolCallId", toolCallId)
            put("toolName", "read_file")
        },
    )

    private fun delta(sequence: Long) = HubEventEnvelope(
        event = HubEvents.ASSISTANT_DELTA,
        eventId = "evt-$sequence",
        sessionId = "s1",
        sequence = sequence,
        timestamp = 1_000L + sequence,
        payload = buildJsonObject { put("text", "chunk $sequence ") },
    )

    private fun TestScope.finish() = coroutineContext.job.cancelChildren()

    @Test
    fun `live per-session events never overtake the paged replay, and the store applies the whole backlog`() = runTest {
        // Cursor 10, log head 20: the backlog is (10, 20]; the run goes on with 21 meanwhile.
        val backlog = listOf(tool(HubEvents.TOOL_STARTED, 11, "t1")) + (12L..19L).map(::delta) + tool(HubEvents.TOOL_FINISHED, 20, "t1")
        val live = tool(HubEvents.TOOL_STARTED, 21, "t2")
        val hub = FakeHubTransport().apply { lastSequence = 20 }
        hub.replay = { sub ->
            val socket = hub.current
            if (sub.sessionId == null) {
                // browser-websocket.ts: the first page at once, the next after a setTimeout(0)
                // yield, then the live events buffered meanwhile.
                launch {
                    delay(1)
                    backlog.drop(PAGE).forEach { socket.inbound.trySend(HubFrame.Event(it)) }
                    socket.inbound.trySend(HubFrame.Event(live))
                }
                backlog.take(PAGE)
            } else {
                // Live-only: the newest event goes out the moment the subscribe is handled.
                listOf(live)
            }
        }
        val client = HubClient(
            transport = hub,
            identity = HubClientIdentity(clientId = "android-test", appVersion = "0.1.0", workspaceRoot = "/w"),
            scope = this,
            cursors = InMemoryCursorStore(10),
            credentials = { HubClient.HubCredentials("ws://127.0.0.1:25463/hub", "tok") },
            random = Random(5),
        )
        val seen = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.collect { seen += it.sequence!! } }
        val store = SessionStore(
            hub = HubClientSessionHub(client, HubSessionApi(client)),
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = FakeClock(),
            hubLifetimeKey = { "1:a" },
        )
        store.start()
        runCurrent()
        // The session is open, so its per-session subscribe is due on every (re)connect.
        client.subscribe("s1")

        // What SessionStore.awaitReplayDrained waits for: applied up to hub.status.lastSequence.
        var toolAtDrain: ToolStatus? = null
        backgroundScope.launch {
            store.appliedSequence.first { it >= 20 }
            toolAtDrain = store.state("s1").value.facts.tools["t1"]?.status
        }

        client.connect()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("one ordered stream, duplicates dropped", (11L..21L).toList(), seen)
        val socket = hub.sockets.single()
        assertEquals(listOf(null, "s1"), socket.subscribes.map { it.sessionId })
        val facts = store.state("s1").value.facts
        assertEquals(21L, facts.lastSequence)
        assertEquals("the backlog's tool.finished was applied", ToolStatus.Finished(null), facts.tools["t1"]?.status)
        assertEquals(ToolStatus.Running, facts.tools["t2"]?.status)
        assertNotNull("the drain wait ended", toolAtDrain)
        assertEquals("the drain wait ended only once the backlog was applied", ToolStatus.Finished(null), toolAtDrain)
        assertTrue(client.replayDrained.value)
        client.disconnect()
        finish()
    }

    private companion object {
        const val PAGE = 4
    }
}
