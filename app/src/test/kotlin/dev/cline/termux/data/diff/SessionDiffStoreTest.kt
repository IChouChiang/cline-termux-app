package dev.cline.termux.data.diff

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.ChatProjector
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.FakeSessionStateSource
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.fakes.HubFixtures.reply
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDiffStoreTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val session1 = "1791064689782_kck3p"
    private val session2 = "1791064691937_rkppp"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val f04 by lazy { HubFixtures.load("04-history-and-replay.jsonl") }
    private val clock = FakeClock()

    private fun createInput(path: String, text: String) = buildJsonObject { put("path", path); put("new_text", text) }
    private fun createOutput(path: String) = buildJsonObject { put("success", true); put("result", "File created: $path") }
    private fun replaceInput(path: String) = buildJsonObject { put("path", path); put("old_text", "before"); put("new_text", "after") }
    private fun replaceOutput(path: String, body: String) = buildJsonObject { put("success", true); put("result", "Edited $path\n```diff\n$body\n```") }

    private fun historyRow(id: String, name: String, input: JsonElement?, result: JsonElement?, isError: Boolean? = null) = ChatMessage(
        id = ChatProjector.toolId(id),
        sessionId = "s",
        role = Role.Tool,
        content = ChatProjector.toolContent(name, input, result, isError),
        createdAt = 1,
        meta = Meta(toolName = name, toolCallId = id),
    )

    private fun live(id: String, name: String, input: JsonElement?, output: JsonElement?, status: ToolStatus = ToolStatus.Finished(null), endedAt: Long = 10) = ToolRecord(
        toolCallId = id,
        toolName = name,
        input = input,
        status = status,
        iteration = 1,
        requestId = "r",
        startedAt = endedAt - 1,
        endedAt = endedAt,
        output = output,
        error = (status as? ToolStatus.Finished)?.error,
    )

    private fun seeded(canonical: List<ChatMessage> = emptyList(), tools: List<ToolRecord> = emptyList(), cwd: String? = "/tmp/qa-ws") = SessionState(
        "s",
        facts = SessionFacts("s", cwd = cwd, workspaceRoot = cwd, tools = tools.associateBy { it.toolCallId }),
        canonical = canonical,
        seed = SeedState.Seeded(0),
    )

    // ---- DiffBuilder -------------------------------------------------------------------------

    @Test
    fun `seeds from history pairs, appends live results, and highlights changed paths`() {
        val builder = DiffBuilder()
        val history = listOf(historyRow("t1", "editor", createInput("journal.txt", "line one\nline two"), createOutput("journal.txt")))
        val first = builder.apply(seeded(history), 100) as DiffStoreState.Ready
        assertEquals(listOf("journal.txt"), first.diff.fileDiffs.map { it.path })
        assertEquals(2, first.diff.summary.additions)
        assertTrue("no highlight on the first build", first.changedPaths.isEmpty())
        assertEquals("/tmp/qa-ws", first.cwd)
        assertEquals(100, first.updatedAt)

        val second = builder.apply(
            seeded(history, listOf(live("t2", "editor", replaceInput("/tmp/qa-ws/journal.txt"), replaceOutput("/tmp/qa-ws/journal.txt", "+3: line three")))),
            200,
        ) as DiffStoreState.Ready
        assertEquals(1, second.diff.fileDiffs.size)
        assertEquals(3, second.diff.fileDiffs.single().additions)
        assertEquals(setOf("journal.txt"), second.changedPaths)
        assertEquals(200, second.updatedAt)
    }

    @Test
    fun `a toolCallId already seen is ignored (replay, double delivery, reconciliation)`() {
        val builder = DiffBuilder()
        val record = live("t1", "editor", createInput("a.txt", "x"), createOutput("a.txt"))
        builder.apply(seeded(tools = listOf(record)), 1)
        // The reconciliation adds the same call to the canonical rows: nothing changes.
        val again = builder.apply(seeded(listOf(historyRow("t1", "editor", record.input, record.output)), listOf(record)), 2)
        assertNull(again)
    }

    @Test
    fun `only finished editor and apply_patch calls count`() {
        val builder = DiffBuilder()
        val state = seeded(
            tools = listOf(
                live("t1", "run_commands", null, null),
                live("t2", "editor", createInput("a.txt", "x"), createOutput("a.txt"), status = ToolStatus.Running),
                live("t3", "editor", createInput("b.txt", "x"), createOutput("b.txt"), status = ToolStatus.Rejected),
                live("t4", "editor", createInput("c.txt", "x"), createOutput("c.txt"), status = ToolStatus.Finished("disk full")),
            ),
        )
        val ready = builder.apply(state, 1) as DiffStoreState.Ready
        assertTrue(ready.diff.fileDiffs.isEmpty())
        assertTrue(ready.diff.summary.isEmpty)
    }

    @Test
    fun `a running call is appended once it finishes`() {
        val builder = DiffBuilder()
        val running = live("t1", "editor", createInput("a.txt", "x"), null, status = ToolStatus.Running)
        assertTrue((builder.apply(seeded(tools = listOf(running)), 1) as DiffStoreState.Ready).diff.fileDiffs.isEmpty())
        val done = builder.apply(seeded(tools = listOf(running.copy(status = ToolStatus.Finished(null), output = createOutput("a.txt")))), 2) as DiffStoreState.Ready
        assertEquals(listOf("a.txt"), done.diff.fileDiffs.map { it.path })
        assertEquals(setOf("a.txt"), done.changedPaths)
    }

    @Test
    fun `loading until seeded, failures surface the kernel text, a cwd change recomputes`() {
        val builder = DiffBuilder()
        assertEquals(DiffStoreState.Loading, builder.apply(SessionState("s", seed = SeedState.Seeding), 1))
        assertEquals(DiffStoreState.Failed("boom"), builder.apply(SessionState("s", seed = SeedState.Failed("boom")), 1))
        assertEquals(DiffStoreState.Failed("Unknown session: s"), DiffBuilder().apply(SessionState("s", seed = SeedState.Missing), 1))

        val history = listOf(historyRow("t1", "editor", createInput("/tmp/qa-ws/a.txt", "x"), createOutput("/tmp/qa-ws/a.txt")))
        val ready = builder.apply(seeded(history), 2) as DiffStoreState.Ready
        assertEquals("a.txt", ready.diff.fileDiffs.single().path)
        assertNull("nothing changed", builder.apply(seeded(history), 3))
        val moved = builder.apply(seeded(history, cwd = "/tmp"), 4) as DiffStoreState.Ready
        assertEquals("qa-ws/a.txt", moved.diff.fileDiffs.single().path)
        // Seeding again later keeps the last Ready on screen.
        assertNull(builder.apply(SessionState("s", seed = SeedState.Seeding), 5))
    }

    @Test
    fun `tool result events follow the CT 1-2 mapping`() {
        val rejected = toolResultEvent(live("t", "editor", null, null, status = ToolStatus.Rejected))
        assertTrue(rejected.toolError!!.contains("rejected by the user"))
        assertEquals("x", toolResultEvent(live("t", "editor", null, null, status = ToolStatus.Finished("x"))).toolError)
        assertNull(toolResultEvent(live("t", "editor", null, null)).toolError)
    }

    // ---- the store over SessionStore + fixtures ---------------------------------------------

    private fun TestScope.newSessionStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
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
            line.event?.let { store.dispatch(it) }
            runCurrent()
        }
    }

    private fun TestScope.newStore(source: SessionStateSource, sessionId: String) =
        SessionDiffStore(sessionId, source, backgroundScope, clock, StandardTestDispatcher(testScheduler))

    @Test
    fun `fixture 04 - a run_commands-only history is Ready with no changes`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
        hub.onMessages = { HubFixtures.messagesReply(f04, session1) }
        val store = newStore(SessionStateSource.of(sessions), session1)
        val ready = store.state.first { it is DiffStoreState.Ready } as DiffStoreState.Ready
        assertTrue(ready.diff.fileDiffs.isEmpty())
        assertTrue("the store seeded the unopened session", "session.messages" in hub.calls)
    }

    @Test
    fun `fixture 03 - the rejected call never produces a diff`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { buildJsonObject { put("sessionId", session2); put("messages", kotlinx.serialization.json.JsonArray(emptyList())) } }
        val store = newStore(SessionStateSource.of(sessions), session2)
        store.state.first { it is DiffStoreState.Ready }
        replay(sessions, hub, f03)
        val ready = store.state.value as DiffStoreState.Ready
        assertTrue(ready.diff.fileDiffs.isEmpty())
    }

    @Test
    fun `live editor events through the session store update the diff once per toolCallId`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onGet = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "s"); put("cwd", "/tmp/qa-ws"); put("workspaceRoot", "/tmp/qa-ws"); put("status", "running") }) } }
        hub.onMessages = { buildJsonObject { put("sessionId", "s"); put("messages", kotlinx.serialization.json.JsonArray(emptyList())) } }
        val store = newStore(SessionStateSource.of(sessions), "s")
        store.state.first { it is DiffStoreState.Ready }

        fun event(name: String, seq: Long, payload: JsonObject) = HubEventEnvelope(event = name, sequence = seq, sessionId = "s", timestamp = 1_000 + seq, payload = payload)
        val started = event("tool.started", 1, buildJsonObject { put("toolCallId", "t1"); put("toolName", "editor"); put("input", createInput("notes.md", "a\nb")) })
        val finished = event("tool.finished", 2, buildJsonObject { put("toolCallId", "t1"); put("toolName", "editor"); put("output", createOutput("notes.md")) })
        sessions.dispatch(started)
        sessions.dispatch(finished)
        // The global + session double delivery carries the same sequence: deduped.
        sessions.dispatch(finished)
        runCurrent()
        val ready = store.state.first { it is DiffStoreState.Ready && it.diff.fileDiffs.isNotEmpty() } as DiffStoreState.Ready
        assertEquals("notes.md", ready.diff.fileDiffs.single().path)
        assertEquals(2, ready.diff.summary.additions)
        assertEquals(setOf("notes.md"), ready.changedPaths)
    }

    @Test
    fun `retry re-seeds through the source`() = runTest {
        val source = FakeSessionStateSource()
        source.set(SessionState("s", seed = SeedState.Failed("boom")))
        val store = newStore(source, "s")
        assertEquals(DiffStoreState.Failed("boom"), store.state.first { it is DiffStoreState.Failed })
        store.retry()
        assertEquals(listOf("s"), source.seeds)
    }

    @Test
    fun `registry hands out one store per session`() = runTest {
        val registry = SessionDiffStore.Registry(FakeSessionStateSource(), backgroundScope, clock, StandardTestDispatcher(testScheduler))
        assertTrue(registry.store("a") === registry.store("a"))
        assertTrue(registry.state("a") === registry.store("a").state)
    }
}
