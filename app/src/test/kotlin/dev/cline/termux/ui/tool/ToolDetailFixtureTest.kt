package dev.cline.termux.ui.tool

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.data.tools.ToolCallStore
import dev.cline.termux.data.tools.ToolCallView
import dev.cline.termux.data.tools.ToolLookup
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.fakes.HubFixtures.reply
import dev.cline.termux.fakes.InMemoryTurnStore
import dev.cline.termux.ui.icons.ToolKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture replay (docs/07 §10.1, CT E): recorded Hub traffic through SessionStore → ToolCallStore
 * → [ToolDetailModel], asserting the Tool detail state of `call_mock_run_1`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolDetailFixtureTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val session1 = "1791064689782_kck3p"
    private val session2 = "1791064691937_rkppp"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val f04 by lazy { HubFixtures.load("04-history-and-replay.jsonl") }

    private fun TestScope.newSessionStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = FakeClock(),
        hubLifetimeKey = { "175794:a" },
    )

    private suspend fun TestScope.replay(store: SessionStore, hub: FakeSessionHub, lines: List<HubFixtures.Line>) {
        for (line in lines) {
            line.command?.let { cmd ->
                if (cmd.command == HubCommands.SESSION_SEND_INPUT) {
                    hub.nextRequestIds += cmd.requestId!!
                    store.send(SendRequest(cmd.sessionId!!, cmd.payload!!["prompt"]!!.jsonPrimitive.content))
                }
            }
            line.reply?.let { reply -> hub.sends.firstOrNull { it.requestId == reply.requestId }?.reply?.complete(reply) }
            line.event?.let { event -> store.dispatch(event) }
            runCurrent()
        }
    }

    private fun TestScope.newStore(source: SessionStateSource, sessionId: String) =
        ToolCallStore(sessionId, source, backgroundScope, StandardTestDispatcher(testScheduler))

    private suspend fun ToolCallStore.found(id: String, predicate: (ToolCallView) -> Boolean = { true }): ToolCallView =
        (tool(id).first { it is ToolLookup.Found && predicate(it.view) } as ToolLookup.Found).view

    @Test
    fun `fixture 02 - streaming then completed, two updates with the first empty, success true`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        sessions.open(session1)
        runCurrent()
        val store = newStore(SessionStateSource.of(sessions), session1)

        val cut = f02.indexOfFirst { line ->
            val e = line.event
            e?.event == "tool.updated" && (e.payload?.get("update") as? JsonObject)?.get("chunk")?.jsonPrimitive?.content?.isNotEmpty() == true
        }
        replay(sessions, hub, f02.take(cut + 1))
        val streaming = ToolDetailModel.build(session1, store.found("call_mock_run_1") { it.executions.firstOrNull()?.streamed == true })
        assertEquals(ToolKind.Command, streaming.kind)
        assertEquals(ToolLayout.Command, streaming.layout)
        assertEquals(ToolDetailStatus.Running, streaming.status)
        assertEquals("echo hello-from-mock", streaming.commands.single().query)
        assertTrue(streaming.abortOffered)
        assertTrue(streaming.runningSince != null)

        replay(sessions, hub, f02.drop(cut + 1))
        val done = ToolDetailModel.build(session1, store.found("call_mock_run_1") { !it.entry.inProgress })
        assertEquals(ToolDetailStatus.Completed, done.status)
        val command = done.commands.single()
        assertEquals(true, command.success)
        assertTrue(command.streamed)
        assertEquals("hello-from-mock", command.output.copyText())
        assertFalse(done.abortOffered)
        assertFalse(done.proceedOffered)
        assertNull(done.errorText)
        assertTrue(done.showTerminal)
    }

    @Test
    fun `fixture 03 - the rejected call shows Rejected with the kernel text`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        sessions.open(session2)
        runCurrent()
        val store = newStore(SessionStateSource.of(sessions), session2)
        backgroundScope.launch { store.snapshots.collect {} }
        runCurrent()
        replay(sessions, hub, f03)
        val content = ToolDetailModel.build(session2, store.found("call_mock_run_1") { it.entry.isError })
        assertEquals(ToolDetailStatus.Rejected, content.status)
        assertTrue(content.errorText!!.contains("rejected by the user"))
        assertFalse(content.showTerminal)
    }

    @Test
    fun `fixture 04 - history is Completed without a duration and with the result text`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { HubFixtures.messagesReply(f04, session1) }
        val store = newStore(SessionStateSource.of(sessions), session1)
        val content = ToolDetailModel.build(session1, store.found("call_mock_run_1"))
        assertEquals(ToolDetailStatus.Completed, content.status)
        assertNull(content.durationMs)
        assertNull(content.runningSince)
        assertFalse(content.sessionLive)
        assertEquals("hello-from-mock", content.commands.single().output.copyText())
    }
}
