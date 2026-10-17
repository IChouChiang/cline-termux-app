package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.buildChatRenderItems
import dev.cline.termux.data.chat.parseToolPayload
import dev.cline.termux.data.chat.type
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.fakes.HubFixtures.reply
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the recorded Hub traffic (docs/08) through the session core: `SessionFactsReducer`
 * + `ChatProjector` (via `SessionStore.dispatch`) and `Reconciler`, asserting both the facts
 * and the projected messages for each fixture (docs/07 §10.1, D14). Formerly
 * `SessionReducerFixtureTest`; every assertion it made is kept in its new shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFixtureReplayTest {
    private val clock = FakeClock()
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val f04 by lazy { HubFixtures.load("04-history-and-replay.jsonl") }
    private val f05 by lazy { HubFixtures.load("05-resume-after-restart.jsonl") }
    private val session1 = "1791064689782_kck3p"
    private val session2 = "1791064691937_rkppp"

    private fun TestScope.newStore(hub: FakeSessionHub, lifetime: () -> String? = { "175794:a" }) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = lifetime,
    )

    /**
     * Drives the store like the app: our `send_input` commands go through [SessionStore.send]
     * (with the recorded requestId), their recorded replies complete the pending reply, events
     * are dispatched (both recorded copies, so dedupe is exercised).
     */
    private suspend fun TestScope.replay(
        store: SessionStore,
        hub: FakeSessionHub,
        lines: List<HubFixtures.Line>,
        before: (HubEventEnvelope) -> Unit = {},
    ) {
        for (line in lines) {
            line.command?.let { cmd ->
                if (cmd.command == HubCommands.SESSION_SEND_INPUT) {
                    hub.nextRequestIds += cmd.requestId!!
                    store.send(SendRequest(cmd.sessionId!!, cmd.payload!!["prompt"]!!.jsonPrimitive.content))
                }
            }
            line.reply?.let { reply ->
                hub.sends.firstOrNull { it.requestId == reply.requestId }?.reply?.complete(reply)
            }
            line.event?.let { event ->
                before(event)
                store.dispatch(event)
            }
            runCurrent()
        }
    }

    /**
     * Opens [sessionId] with `session.messages` failing, so terminal events keep the live rows
     * (a failed reconcile leaves live untouched) and the projector output can be asserted.
     * Unopened, never-seeded sessions drop closed live rows instead (`requestReconcile`).
     */
    private suspend fun TestScope.openWithoutHistory(store: SessionStore, hub: FakeSessionHub, sessionId: String, record: JsonObject? = null) {
        record?.let { r -> hub.onGet = { r } }
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        store.open(sessionId)
        runCurrent()
    }

    private fun rows(state: SessionState, role: Role? = null): List<ChatMessage> = state.messages.filter { role == null || it.role == role }

    private fun toolPayload(message: ChatMessage): JsonObject = parseToolPayload(message.content)!!

    @Test
    fun `fixture 02 - approved run_commands turn reduces to the expected facts and live rows`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        replay(store, hub, f02)
        val state = store.state(session1).value
        val facts = state.facts

        // Facts.
        assertEquals(SessionStatus.Completed, facts.status)
        assertEquals(RunPhase.Idle, facts.runPhase)
        assertEquals(ChatStatus.Completed, state.chatStatus)
        assertTrue(facts.approvals.isEmpty())
        assertNull(facts.activeRun)
        assertEquals(2500L, facts.usage!!.inputTokens)
        assertEquals(120L, facts.usage!!.outputTokens)
        assertEquals("openai-compatible", facts.providerId)
        assertEquals("mock-1", facts.modelId)
        assertEquals("android", facts.source)
        assertEquals("Run echo hello-from-mock and summarize", facts.title)
        val tool = facts.tools.getValue("call_mock_run_1")
        assertEquals("run_commands", tool.toolName)
        assertTrue(tool.status is ToolStatus.Finished && (tool.status as ToolStatus.Finished).ok)
        assertEquals("approval_1791064690209_v5gc4", tool.approvalId)
        assertEquals(1, tool.executions.size)
        val exec = tool.executions.single()
        assertEquals("echo hello-from-mock", exec.query)
        assertEquals("hello-from-mock\n", exec.text(OutputStream.Stdout))
        assertEquals(true, exec.success)
        assertEquals("hello-from-mock\n", exec.result)
        assertFalse("first update is the empty announce", exec.gapped)
        assertEquals("hello-from-mock\n", tool.output!!.jsonArray[0].jsonObject["result"]!!.jsonPrimitive.content)

        // Live rows: optimistic bubble, narration, tool, answer (ids per HC §1.3.1).
        val requestId = "session-send_input-002"
        assertEquals(
            listOf("local:$requestId", "a:$requestId:1:0", "t:call_mock_run_1", "a:$requestId:2:0"),
            state.messages.map { it.id },
        )
        assertEquals("Run echo hello-from-mock and summarize", state.messages[0].content)
        assertEquals("I'll run the command now.", state.messages[1].content)
        val toolRow = state.messages[2]
        assertEquals("hello-from-mock\n", toolRow.meta.toolOutput)
        assertNull(toolRow.meta.hookEventName)
        assertEquals("hello-from-mock\n", toolPayload(toolRow)["result"]!!.jsonArray[0].jsonObject["result"]!!.jsonPrimitive.content)
        assertTrue(state.messages[3].content.startsWith("The command ran successfully"))
        assertNull(state.streamingMessageId)

        // Turn record: ours, started, closed as completed.
        val turn = state.turns.single()
        assertEquals(requestId, turn.requestId)
        assertTrue(turn.started && !turn.open && turn.ours)
        assertEquals(TurnOutcome.Completed, turn.outcome)

        // Render pipeline: the finished run folds its tool call behind the answer.
        val items = buildChatRenderItems(state.messages, state.chatStatus)
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
    }

    @Test
    fun `fixture 02 - approval_requested opens the tool row before tool_started`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        val firstApproval = f02.indexOfFirst { it.event?.event == HubEvents.APPROVAL_REQUESTED }
        assertTrue(firstApproval > 0)
        assertTrue(f02.take(firstApproval).none { it.event?.event == HubEvents.TOOL_STARTED })
        // Both recorded copies of the request are delivered, so the dedupe is exercised.
        val upToApproval = f02.take(firstApproval + 1) + f02.drop(firstApproval + 1).takeWhile { it.event?.event != HubEvents.TOOL_STARTED }
        replay(store, hub, upToApproval)
        val state = store.state(session1).value
        val toolRows = state.messages.filter { it.id == "t:call_mock_run_1" }
        assertEquals(1, toolRows.size)
        assertEquals("run_commands", toolPayload(toolRows.single())["toolName"]!!.jsonPrimitive.content)
        assertEquals(
            "echo hello-from-mock",
            toolPayload(toolRows.single())["input"]!!.jsonObject["commands"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun `fixture 02 - duplicate deliveries on global and session subscriptions do not double rows`() = runTest {
        val lines = f02
        val eventLines = lines.count { it.event != null }
        val distinct = HubFixtures.events(lines).size
        assertTrue("fixture should contain duplicates ($eventLines lines, $distinct sequences)", eventLines > distinct)
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        replay(store, hub, lines)
        val state = store.state(session1).value
        assertEquals(1, rows(state, Role.Tool).size)
        assertEquals(2, rows(state, Role.Assistant).size)
        assertEquals(1, rows(state, Role.User).size)
    }

    @Test
    fun `fixture 02 then 04 - reconciliation replaces live rows with history and keeps every renderKey`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        replay(store, hub, f02)
        val before = store.state(session1).value
        val liveKeys = before.messages.map { it.renderKey }

        val mapping = HistoryMapper.map(session1, HubPayloads.messagesOf(HubFixtures.messagesReply(f04, session1)))
        val result = Reconciler.reconcile(before.canonical, before.canonicalCount, before.live, mapping, emptySet())
        val merged = Reconciler.merge(result.canonical, result.live)

        // (a) no duplicate user/assistant/tool rows.
        assertEquals(4, merged.size)
        assertEquals(listOf(Role.User, Role.Assistant, Role.Tool, Role.Assistant), merged.map { it.role })
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        // (b) every renderKey of a matched row survives.
        assertEquals(liveKeys, merged.map { it.renderKey })
        assertTrue(result.live.isEmpty())
        assertEquals(4, result.canonicalCount)
        // History ids replaced the live ids.
        assertTrue(merged[1].id.startsWith("h:"))
        assertEquals("t:call_mock_run_1", merged[2].id)
    }

    @Test
    fun `fixture 02 - an open session reconciles on run_completed through the store`() = runTest {
        val hub = FakeSessionHub(clientId)
        val createReply = HubFixtures.replyTo(f02, "session-create-001").payload!!
        hub.onGet = { createReply }
        val store = newStore(hub)
        store.open(session1)
        runCurrent()
        assertEquals(listOf(session1), hub.subscribed)
        assertTrue(store.state(session1).value.seed is SeedState.Seeded)
        assertTrue(store.state(session1).value.live_)

        val history = HubPayloads.messagesOf(HubFixtures.messagesReply(f04, session1))
        replay(store, hub, f02) { event -> if (event.event == HubEvents.RUN_COMPLETED) hub.history = history }
        runCurrent()
        val state = store.state(session1).value
        assertEquals(4, state.canonical.size)
        assertTrue("closed turn's live rows are gone", state.live.none { it.role in setOf(Role.User, Role.Assistant, Role.Tool) })
        assertEquals("local:session-send_input-002", state.messages.first().renderKey)
        assertEquals(listOf(Role.User, Role.Assistant, Role.Tool, Role.Assistant), state.messages.map { it.role })
    }

    @Test
    fun `fixture 03 - rejected approval marks the tool rejected and abort ends the run`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session2)
        replay(store, hub, f03)
        val state = store.state(session2).value
        val facts = state.facts

        val tool = facts.tools.values.singleOrNull()
        // run.started of the second turn cleared the first run's tools; the rejection lives in the messages.
        assertNull(tool)
        val toolRows = rows(state, Role.Tool)
        assertEquals(1, toolRows.size)
        val payload = toolPayload(toolRows.single())
        assertEquals(true, payload["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(payload["result"]!!.jsonObject["error"]!!.jsonPrimitive.content.startsWith(SessionFactsReducer.REJECTED_MARKER))

        assertEquals(SessionStatus.Cancelled, facts.status)
        assertEquals(RunPhase.Idle, facts.runPhase)
        assertEquals(ChatStatus.Cancelled, state.chatStatus)
        assertTrue(facts.approvals.isEmpty())
        assertNull(state.streamingMessageId)
        assertEquals(listOf(TurnOutcome.Completed, TurnOutcome.Aborted), state.turns.map { it.outcome })
        assertEquals(2, rows(state, Role.User).size)
    }

    @Test
    fun `fixture 03 - the rejected tool record is Rejected while its run is current`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        // Stop right after the rejected tool finished (sequence 71).
        val cut = f03.indexOfFirst { it.event?.sequence == 72L }
        replay(store, hub, f03.subList(0, cut))
        val facts = store.state(session2).value.facts
        val tool = facts.tools.getValue("call_mock_run_1")
        assertEquals(ToolStatus.Rejected, tool.status)
        assertTrue(tool.error!!.contains(SessionFactsReducer.REJECTED_MARKER))
        assertTrue(facts.approvals.isEmpty())
        assertEquals(RunPhase.Running, facts.runPhase)
    }

    @Test
    fun `fixture 03 - approval pending moves the phase to AwaitingUser and resolution back to Running`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        val requested = f03.indexOfFirst { it.event?.event == HubEvents.APPROVAL_REQUESTED }
        replay(store, hub, f03.subList(0, requested + 1))
        var facts = store.state(session2).value.facts
        assertEquals(RunPhase.AwaitingUser, facts.runPhase)
        assertEquals(SessionStatus.Pending, facts.status)
        assertEquals(1, facts.approvals.size)
        assertEquals(ToolStatus.AwaitingApproval, facts.tools.getValue("call_mock_run_1").status)
        // Re-issued approvals (every subscribe, KC) are deduped.
        val again = f03[requested].event!!.copy(sequence = null)
        store.dispatch(again)
        assertEquals(1, store.state(session2).value.facts.approvals.size)

        val resolved = f03.indexOfFirst { it.event?.event == HubEvents.APPROVAL_RESOLVED }
        replay(store, hub, f03.subList(requested + 1, resolved + 1).filter { it.command == null })
        facts = store.state(session2).value.facts
        assertTrue(facts.approvals.isEmpty())
        assertEquals(ToolStatus.Rejected, facts.tools.getValue("call_mock_run_1").status)
    }

    @Test
    fun `fixture 04 - session messages history maps to canonical rows with the wrapper kept raw`() {
        val payload = HubFixtures.messagesReply(f04, session1)
        val mapping = HistoryMapper.map(session1, HubPayloads.messagesOf(payload))
        assertEquals(listOf(Role.User, Role.Assistant, Role.Tool, Role.Assistant), mapping.messages.map { it.role })
        assertEquals(listOf(0, 1, 1, 3), mapping.rawIndex)
        assertEquals(4, mapping.rawCount)
        val user = mapping.messages[0]
        assertEquals("<user_input mode=\"act\">Run echo hello-from-mock and summarize</user_input>", user.content)
        assertEquals("Run echo hello-from-mock and summarize", UserInputFormat.formatDisplayUserInput(user.content))
        assertEquals(PromptMode.Act, UserInputFormat.parse(user.content).mode)
        assertTrue(user.createdAt > 0)
        val tool = mapping.messages[2]
        assertEquals("t:call_mock_run_1", tool.id)
        assertEquals("run_commands", tool.meta.toolName)
        assertNull("result merged", tool.meta.hookEventName)
        assertNotNull(toolPayload(tool)["result"])
        assertEquals(1200, mapping.messages[1].meta.inputTokens)
    }

    @Test
    fun `fixture 04 - replayed events already applied are ignored`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session2)
        replay(store, hub, f03)
        val before = store.state(session2).value
        replay(store, hub, f04)
        val after = store.state(session2).value
        assertEquals(before.facts, after.facts)
        assertEquals(before.messages, after.messages)
    }

    @Test
    fun `fixture 05 - shutdown projections without an open turn, session_not_found auto-resume, resumed turn`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        replay(store, hub, f02)

        // Replay across the restart: shutdown session.updated pairs, no open turn → no interruption row.
        val events = f05.mapNotNull { it.event }
        events.filter { (it.sequence ?: 0) in 107..114 }.forEach { store.dispatch(it) }
        runCurrent()
        var state = store.state(session1).value
        assertEquals(SessionStatus.Completed, state.facts.status)
        assertTrue(state.messages.none { it.role == Role.Error })

        // Send to the not-yet-resumed session: run.started 115, then the session_not_found reply.
        hub.onMessages = { HubFixtures.messagesReply(f05, session1, nth = 0) }
        hub.onCreate = { HubFixtures.replyTo(f05, "session-create-009").payload!! }
        hub.nextRequestIds += "session-send_input-006"
        hub.nextRequestIds += "session-send_input-010"
        val outcome = store.send(SendRequest(session1, "Are you there?"))
        assertEquals(SendOutcome.Sent("session-send_input-006"), outcome)
        store.dispatch(events.first { it.sequence == 115L })
        hub.sends.single { it.requestId == "session-send_input-006" }.reply.complete(HubFixtures.replyTo(f05, "session-send_input-006"))
        runCurrent()

        // Resumed once with the askQuestion contribution, then resent under the same bubble key.
        val create = hub.creates.single()
        val payload = create.payload()
        assertEquals(session1, payload["sessionConfig"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals(4, payload["initialMessages"]!!.jsonArray.size)
        assertEquals("android", payload["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals(
            HubCapabilities.ASK_QUESTION,
            payload["runtimeOptions"]!!.jsonObject["clientContributions"]!!.jsonArray[0].jsonObject["capabilityName"]!!.jsonPrimitive.content,
        )
        assertEquals(true, payload["sessionConfig"]!!.jsonObject["checkpoint"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("session-send_input-006", "session-send_input-010"), hub.sends.drop(1).map { it.requestId })
        state = store.state(session1).value
        assertTrue(state.live_)
        assertFalse(state.persistedHint)
        // The 002 bubble stays live because this test opened the session without history.
        val bubbles = state.messages.filter { it.id.startsWith("local:") && it.id != "local:session-send_input-002" }
        assertEquals(listOf("local:session-send_input-010"), bubbles.map { it.id })
        assertEquals("local:session-send_input-006", bubbles.single().renderKey)
        assertEquals(listOf("session-send_input-002", "session-send_input-010"), state.turns.map { it.requestId })

        // The resumed turn runs to completion (117-137); its reconcile reads the post-resume history.
        hub.onMessages = { HubFixtures.messagesReply(f05, session1, nth = 1) }
        events.filter { (it.sequence ?: 0) in 117..137 }.forEach { store.dispatch(it) }
        runCurrent()
        state = store.state(session1).value
        assertEquals(SessionStatus.Completed, state.facts.status)
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertEquals(TurnOutcome.Completed, state.turns.last().outcome)
        assertEquals(3900L, state.facts.usage!!.inputTokens) // 2500 + 1400 (FX 05 step 6)
        assertEquals("The command printed `hello-from-mock` on a single line of stdout.", state.messages.last { it.role == Role.Assistant }.content)
    }

    @Test
    fun `run_failed payload shapes all decode into one error row`() = runTest {
        val shapes = listOf(
            """{"reason":"error","error":"401 Unauthorized"}""",
            """{"reason":"failed","text":"maximum context tokens exceeded"}""",
            """{"reason":"failed"}""",
            """{"reason":"error","error":"boom","result":{"text":"partial"},"snapshot":{"status":"failed"}}""",
        )
        shapes.forEachIndexed { index, json ->
            val hub = FakeSessionHub(clientId)
            val store = newStore(hub)
            store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r$index","clientId":"other"}"""))
            store.dispatch(event(HubEvents.RUN_FAILED, 2, json))
            val state = store.state("s1").value
            val error = state.messages.single { it.role == Role.Error }
            assertEquals("e:r$index", error.id)
            assertEquals(SessionStatus.Failed, state.facts.status)
            assertEquals(RunPhase.Idle, state.facts.runPhase)
            assertEquals(ChatStatus.Failed, state.chatStatus)
            when (index) {
                0 -> { assertEquals("401 Unauthorized", error.content); assertEquals("credentials", error.meta.reason) }
                1 -> { assertEquals("maximum context tokens exceeded", error.content); assertNull(error.meta.reason) }
                2 -> { assertEquals("", error.content); assertEquals("credentials", error.meta.reason) }
                3 -> assertEquals("boom", error.content)
            }
        }
    }

    @Test
    fun `the error row of a failed turn survives reconciliation`() = runTest {
        val hub = FakeSessionHub(clientId)
        val store = newStore(hub)
        openWithoutHistory(store, hub, session1, HubFixtures.replyTo(f02, "session-create-001").payload)
        replay(store, hub, f02.takeWhile { it.event?.event != HubEvents.RUN_COMPLETED })
        store.dispatch(event(HubEvents.RUN_FAILED, 500, """{"reason":"error","error":"provider exploded"}""", sessionId = session1, timestamp = 1_900_000_000_000L))
        runCurrent()
        val before = store.state(session1).value
        val mapping = HistoryMapper.map(session1, HubPayloads.messagesOf(HubFixtures.messagesReply(f04, session1)))
        val result = Reconciler.reconcile(before.canonical, before.canonicalCount, before.live, mapping, emptySet())
        val merged = Reconciler.merge(result.canonical, result.live)
        assertEquals(Role.Error, merged.last().role)
        assertEquals("e:session-send_input-002", merged.last().id)
        assertEquals(1, merged.count { it.role == Role.User })
    }

    private fun event(name: String, sequence: Long, payload: String, sessionId: String = "s1", timestamp: Long = 1_000L + sequence) = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = sequence,
        timestamp = timestamp,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

}
