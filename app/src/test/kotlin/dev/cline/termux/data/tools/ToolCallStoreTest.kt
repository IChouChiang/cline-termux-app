package dev.cline.termux.data.tools

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.ChatProjector
import dev.cline.termux.data.session.CommandBuffer
import dev.cline.termux.data.session.OutputChunk
import dev.cline.termux.data.session.OutputStream
import dev.cline.termux.data.session.RunRef
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolCallStoreTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val session1 = "1791064689782_kck3p"
    private val session2 = "1791064691937_rkppp"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val f04 by lazy { HubFixtures.load("04-history-and-replay.jsonl") }

    // ---- helpers ------------------------------------------------------------------------

    private fun TestScope.newSessionStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = FakeClock(),
        hubLifetimeKey = { "175794:a" },
    )

    private suspend fun TestScope.replay(store: SessionStore, hub: FakeSessionHub, lines: List<HubFixtures.Line>, stopAfter: (HubEventEnvelope) -> Boolean = { false }) {
        for (line in lines) {
            line.command?.let { cmd ->
                if (cmd.command == HubCommands.SESSION_SEND_INPUT) {
                    hub.nextRequestIds += cmd.requestId!!
                    store.send(SendRequest(cmd.sessionId!!, cmd.payload!!["prompt"]!!.jsonPrimitive.content))
                }
            }
            line.reply?.let { reply -> hub.sends.firstOrNull { it.requestId == reply.requestId }?.reply?.complete(reply) }
            line.event?.let { event ->
                store.dispatch(event)
                if (stopAfter(event)) return
            }
            runCurrent()
        }
    }

    private fun TestScope.newStore(source: SessionStateSource, sessionId: String) =
        ToolCallStore(sessionId, source, backgroundScope, StandardTestDispatcher(testScheduler))

    private suspend fun ToolCallStore.found(id: String, predicate: (ToolCallView) -> Boolean = { true }): ToolCallView =
        (tool(id).first { it is ToolLookup.Found && predicate(it.view) } as ToolLookup.Found).view

    private fun buffer(vararg chunks: Pair<OutputStream, String>, dropped: Long = 0, gapped: Boolean = false, result: String? = null) = CommandBuffer(
        executionId = "e1",
        commandIndex = 0,
        query = "seq 3",
        chunks = chunks.map { OutputChunk(it.first, it.second) },
        headDroppedChars = dropped,
        gapped = gapped,
        result = result,
    )

    private fun toolRow(id: String, name: String, input: JsonElement?, result: JsonElement?, isError: Boolean? = null, at: Long = 1) = ChatMessage(
        id = ChatProjector.toolId(id),
        sessionId = "s",
        role = Role.Tool,
        content = ChatProjector.toolContent(name, input, result, isError),
        createdAt = at,
        meta = Meta(toolName = name, toolCallId = id),
    )

    private fun liveRecord(id: String, status: ToolStatus, executions: List<CommandBuffer> = emptyList(), output: JsonElement? = null) = ToolRecord(
        toolCallId = id,
        toolName = "run_commands",
        input = buildJsonObject { put("commands", buildJsonArray { add(JsonPrimitive("seq 3")) }) },
        status = status,
        iteration = 1,
        requestId = "r1",
        startedAt = 1_000,
        endedAt = if (status == ToolStatus.Running) null else 3_500,
        output = output,
        executions = executions,
    )

    // ---- fixture replays ----------------------------------------------------------------

    @Test
    fun `fixture 02 - the approved run_commands call streams into its terminal and finishes`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        sessions.open(session1)
        runCurrent()
        val store = newStore(SessionStateSource.of(sessions), session1)

        // Mid-stream: stop right after the first output chunk.
        val cut = f02.indexOfFirst { line ->
            val e = line.event
            e?.event == "tool.updated" && (e.payload?.get("update") as? JsonObject)?.get("chunk")?.jsonPrimitive?.content?.isNotEmpty() == true
        }
        assertTrue(cut > 0)
        replay(sessions, hub, f02.take(cut + 1))
        val streaming = store.found("call_mock_run_1") { it.executions.firstOrNull()?.streamed == true }
        val exec = streaming.executions.single()
        assertEquals("echo hello-from-mock", exec.query)
        assertEquals(listOf("hello-from-mock"), exec.output.lines.map { it.text })
        assertTrue(exec.running)
        assertTrue(streaming.runActive)
        assertEquals(ToolOrigin.Live, streaming.entry.origin)
        assertFalse(exec.gapped)

        replay(sessions, hub, f02.drop(cut + 1))
        val done = store.found("call_mock_run_1") { !it.entry.inProgress }
        val finished = done.executions.single()
        assertEquals(true, finished.success)
        assertEquals("hello-from-mock\n", finished.result)
        assertFalse(finished.running)
        assertFalse(done.runActive)
        assertEquals("hello-from-mock\n", finished.inlineOutput)
        assertFalse("streamed output wins over the result", finished.fromResult)
        assertEquals(listOf("hello-from-mock"), finished.output.lines.map { it.text })
        assertFalse(finished.hasStderr)
        assertEquals("approval_1791064690209_v5gc4", done.entry.record.approvalId)
    }

    @Test
    fun `fixture 03 - the rejected call is Rejected with the kernel text and no executions`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        sessions.open(session2)
        runCurrent()
        val store = newStore(SessionStateSource.of(sessions), session2)
        // The second turn's run.started clears facts.tools; a collecting store keeps the rejected record.
        backgroundScope.launch { store.snapshots.collect {} }
        runCurrent()
        replay(sessions, hub, f03)
        assertTrue(sessions.state(session2).value.facts.tools["call_mock_run_1"] == null)
        val view = store.found("call_mock_run_1") { it.entry.record.status == ToolStatus.Rejected }
        assertTrue(view.entry.isError)
        assertTrue(view.entry.record.error!!.contains("rejected by the user"))
        assertTrue(view.executions.isEmpty())
    }

    @Test
    fun `fixture 04 - history seeds the tool from its tool_use and tool_result pair, split per command`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { HubFixtures.messagesReply(f04, session1) }
        val source = SessionStateSource.of(sessions)
        val store = newStore(source, session1)
        // Nobody opened the session: the store seeds it.
        val view = store.found("call_mock_run_1")
        assertTrue("session.messages" in hub.calls)
        assertEquals(ToolOrigin.History, view.entry.origin)
        assertEquals("run_commands", view.entry.toolName)
        assertTrue((view.entry.record.status as ToolStatus.Finished).ok)
        val exec = view.executions.single()
        assertEquals("history:0", exec.executionId)
        assertEquals("echo hello-from-mock", exec.query)
        assertTrue(exec.fromResult)
        assertFalse(exec.streamed)
        assertEquals(listOf("hello-from-mock"), exec.output.lines.map { it.text })
        assertFalse(view.runActive)
    }

    @Test
    fun `fixture 04 - an id absent from history and the live store is NotFound`() = runTest {
        val hub = FakeSessionHub(clientId)
        val sessions = newSessionStore(hub)
        hub.onMessages = { HubFixtures.messagesReply(f04, session1) }
        val store = newStore(SessionStateSource.of(sessions), session1)
        assertEquals(ToolLookup.NotFound, store.tool("call_nope").first { it != ToolLookup.Loading })
    }

    // ---- lookup states ----------------------------------------------------------------------

    @Test
    fun `lookup is Loading while seeding, HistoryFailed on a failed seed, NotFound for a missing session`() = runTest {
        val source = FakeSessionStateSource()
        source.set(SessionState("s", seed = SeedState.Seeding))
        val store = newStore(source, "s")
        assertEquals(ToolLookup.Loading, store.tool("t1").first())
        source.update("s") { it.copy(seed = SeedState.Failed("boom")) }
        assertEquals(ToolLookup.HistoryFailed("boom"), store.tool("t1").first { it is ToolLookup.HistoryFailed })
        source.update("s") { it.copy(seed = SeedState.Missing) }
        assertEquals(ToolLookup.NotFound, store.tool("t1").first { it == ToolLookup.NotFound })
    }

    @Test
    fun `an unseeded session is seeded, and seeding retries while it stays unseeded`() = runTest {
        val source = FakeSessionStateSource()
        val store = newStore(source, "s")
        val job = backgroundScope.launch { store.snapshots.collect {} }
        testScheduler.advanceTimeBy(1)
        assertEquals(listOf("s"), source.seeds)
        testScheduler.advanceTimeBy(ToolCallStore.SEED_RETRY_MS + 1)
        assertEquals(listOf("s", "s"), source.seeds)
        source.update("s") { it.copy(seed = SeedState.Seeded(0)) }
        testScheduler.advanceTimeBy(ToolCallStore.SEED_RETRY_MS * 3)
        assertEquals(2, source.seeds.size)
        job.cancel()
    }

    @Test
    fun `live records survive the reducer clearing facts tools on the next run`() = runTest {
        val source = FakeSessionStateSource()
        val first = liveRecord("t1", ToolStatus.Finished(null), listOf(buffer(OutputStream.Stdout to "1\n2\n3\n", result = "1\n2\n3\n")))
        source.set(SessionState("s", facts = SessionFacts("s", tools = mapOf("t1" to first)), seed = SeedState.Seeded(0)))
        val store = newStore(source, "s")
        assertNotNull(store.found("t1"))
        source.update("s") { it.copy(facts = it.facts.copy(tools = mapOf("t2" to liveRecord("t2", ToolStatus.Running)), activeRun = RunRef("r2", "run2", 5, clientId, true))) }
        val snapshot = store.snapshots.first { "t2" in it.entries }
        assertEquals(listOf("t1", "t2"), snapshot.entries.keys.toList())
        assertEquals(listOf("1", "2", "3"), store.found("t1").executions.single().output.lines.map { it.text })
    }

    @Test
    fun `history fills the result of a live record that never finished, live chunks still win`() = runTest {
        val source = FakeSessionStateSource()
        val running = liveRecord("t1", ToolStatus.Cancelled, listOf(buffer(OutputStream.Stdout to "partial\n")))
        val result = buildJsonArray { add(buildJsonObject { put("query", "seq 3"); put("result", "full\n"); put("success", true) }) }
        source.set(
            SessionState(
                "s",
                facts = SessionFacts("s", tools = mapOf("t1" to running)),
                canonical = listOf(toolRow("t1", "run_commands", running.input, result)),
                seed = SeedState.Seeded(0),
            ),
        )
        val view = newStore(source, "s").found("t1")
        assertEquals(ToolOrigin.Live, view.entry.origin)
        assertTrue((view.entry.record.status as ToolStatus.Finished).ok)
        assertEquals(result, view.entry.record.output)
        assertEquals(listOf("partial"), view.executions.single().output.lines.map { it.text })
    }

    // ---- trial row 18: the call input's command, not the kernel's capped echo -------------

    private val longCommand = "echo " + "x".repeat(295)
    private val longEcho = longCommand.take(200) + " ... [command truncated: 100 more chars; full command is in the tool call input]"
    private val twoCommands = buildJsonObject { put("commands", buildJsonArray { add(JsonPrimitive("ls")); add(JsonPrimitive(longCommand)) }) }

    @Test
    fun `a live execution prints the input's 300-character command, not the capped echo`() = runTest {
        val source = FakeSessionStateSource()
        val buffers = listOf(
            CommandBuffer(executionId = "x0", commandIndex = 0, query = "ls", chunks = listOf(OutputChunk(OutputStream.Stdout, "a.txt\n"))),
            CommandBuffer(executionId = "x1", commandIndex = 1, query = longEcho, chunks = listOf(OutputChunk(OutputStream.Stdout, "done\n"))),
        )
        val record = liveRecord("t1", ToolStatus.Running, buffers).copy(input = twoCommands)
        source.set(SessionState("s", facts = SessionFacts("s", tools = mapOf("t1" to record)), seed = SeedState.Seeded(0)))
        val view = newStore(source, "s").found("t1")
        assertEquals(listOf("ls", longCommand), view.executions.map { it.query })
    }

    @Test
    fun `a history execution prints the input's 300-character command, the echo only without an input`() = runTest {
        val source = FakeSessionStateSource()
        val result = buildJsonArray {
            add(buildJsonObject { put("query", "ls"); put("result", "a.txt\n"); put("success", true) })
            add(buildJsonObject { put("query", longEcho); put("result", "done\n"); put("success", true) })
        }
        source.set(
            SessionState(
                "s",
                canonical = listOf(toolRow("t1", "run_commands", twoCommands, result), toolRow("t2", "run_commands", null, result)),
                seed = SeedState.Seeded(0),
            ),
        )
        val store = newStore(source, "s")
        val full = store.found("t1")
        assertEquals(ToolOrigin.History, full.entry.origin)
        assertEquals(listOf("ls", longCommand), full.executions.map { it.query })
        // No input to read: the echo, its note replaced by an ellipsis.
        assertEquals(listOf("ls", longCommand.take(200) + "…"), store.found("t2").executions.map { it.query })
    }

    @Test
    fun `no result - in progress while the run is no longer active`() = runTest {
        val source = FakeSessionStateSource()
        source.set(SessionState("s", canonical = listOf(toolRow("t1", "read_files", null, null)), seed = SeedState.Seeded(0)))
        val snapshot = newStore(source, "s").snapshots.first()
        assertTrue(snapshot.entries.getValue("t1").inProgress)
        assertFalse(snapshot.runActive)
        source.update("s") { it.copy(facts = it.facts.copy(status = SessionStatus.Running)) }
        assertTrue(newStore(source, "s").snapshots.first().runActive)
    }

    @Test
    fun `registry hands out one store per session`() = runTest {
        val registry = ToolCallStore.Registry(FakeSessionStateSource(), backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(registry.store("a") === registry.store("a"))
        assertFalse(registry.store("a") === registry.store("b"))
    }

    // ---- history parsing -----------------------------------------------------------------

    @Test
    fun `history record - error results, rejected calls and pending calls`() {
        val error = historyRecord(toolRow("t1", "editor", null, JsonPrimitive("file not found"), isError = true), "t1")!!
        assertEquals(ToolStatus.Finished("file not found"), error.status)
        val rejected = historyRecord(
            toolRow("t2", "run_commands", null, buildJsonObject { put("error", "This tool call was rejected by the user and not executed.") }, isError = true),
            "t2",
        )!!
        assertEquals(ToolStatus.Rejected, rejected.status)
        val pending = historyRecord(toolRow("t3", "read_files", null, null), "t3")!!
        assertEquals(ToolStatus.Running, pending.status)
        assertNull(pending.output)
        assertNull(historyRecord(toolRow("t4", "x", null, null).copy(content = "not json"), "t4"))
    }

    @Test
    fun `history executions skip entries without a query`() {
        val result = HubJson.parseToJsonElement("""[{"query":"a","result":"1","success":true},{"result":"x"},{"query":"b","result":null,"success":false}]""")
        val execs = historyExecutions(result)
        assertEquals(listOf("a", "b"), execs.map { it.query })
        assertEquals(listOf(0, 2), execs.map { it.commandIndex })
        assertEquals("1", execs[0].result)
        assertNull(execs[1].result)
        assertEquals(false, execs[1].success)
        assertTrue(historyExecutions(JsonArray(emptyList())).isEmpty())
        assertTrue(historyExecutions(JsonPrimitive("x")).isEmpty())
    }

    @Test
    fun `result text - strings as is, objects as JSON`() {
        assertEquals("", resultText(null))
        assertEquals("hi", resultText(JsonPrimitive("hi")))
        assertEquals("""{"a":1}""", resultText(HubJson.parseToJsonElement("""{"a":1}""")))
    }

    // ---- terminals -------------------------------------------------------------------------

    @Test
    fun `terminals append only the delta and keep streams apart`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "a\n"))
        t.update(buffer(OutputStream.Stdout to "a\n", OutputStream.Stderr to "oops\n", OutputStream.Stdout to "b\n"))
        val view = t.view(buffer(), running = true, command = "seq 3")
        assertEquals(listOf("a", "oops", "b"), view.output.lines.map { it.text })
        assertEquals(listOf("a", "b"), view.stdout.lines.map { it.text })
        assertEquals(listOf("oops"), view.stderr.lines.map { it.text })
        assertTrue(view.hasStderr)
        assertEquals("a\noops\nb\n", view.inlineOutput)
        // The same snapshot again appends nothing.
        t.update(buffer(OutputStream.Stdout to "a\n", OutputStream.Stderr to "oops\n", OutputStream.Stdout to "b\n"))
        assertEquals(3, t.view(buffer(), running = true, command = "seq 3").output.lines.size)
    }

    @Test
    fun `terminals follow the reducer head cap through headDroppedChars`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "1\n2\n"))
        // The reducer dropped "1\n2\n" and holds only the new text: the delta is the held tail.
        t.update(buffer(OutputStream.Stdout to "3\n", dropped = 4))
        assertEquals(listOf("1", "2", "3"), t.view(buffer(), running = true, command = "seq 3").output.lines.map { it.text })
    }

    @Test
    fun `a delta larger than the held text writes the head marker`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "tail\n", dropped = 100))
        val lines = t.view(buffer(), running = true, command = "seq 3").output.lines
        assertEquals(TerminalNotices.EARLIER_TRUNCATED, lines.first().text)
        assertTrue(lines.first().isNotice)
        assertEquals("tail", lines[1].text)
    }

    @Test
    fun `a gapped buffer shows the head notice, then the kernel result once finished`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "middle\n", gapped = true))
        val streaming = t.view(buffer(gapped = true), running = true, command = "seq 3")
        assertEquals(listOf(TerminalNotices.EARLIER_TRUNCATED, "middle"), streaming.output.lines.map { it.text })
        assertTrue(streaming.gapped)
        val finished = buffer(OutputStream.Stdout to "middle\nend\n", gapped = true, result = "start\n…\nend\n")
        t.update(finished)
        val done = t.view(finished, running = false, command = "seq 3")
        assertTrue(done.fromResult)
        assertEquals(listOf(TerminalNotices.EARLIER_TRUNCATED, "start", "…", "end"), done.output.lines.map { it.text })
    }

    @Test
    fun `result-only kernel notices follow the streamed output as notice rows once`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "line 1\nline 2"))
        val detach = "[Command is still running. Output will continue in /tmp/cline-command-x/output.log]"
        val finished = buffer(OutputStream.Stdout to "line 1\nline 2", result = "$detach\nline 1\nline 2\n")
        t.update(finished)
        t.update(finished)
        val done = t.view(finished, running = false, command = "seq 3")
        assertFalse(done.fromResult)
        assertEquals(listOf("line 1", "line 2", detach), done.output.lines.map { it.text })
        assertEquals(listOf("line 1", "line 2", detach), done.stdout.lines.map { it.text })
        assertTrue(done.output.lines.last().isNotice)
        assertEquals("line 1\nline 2", done.output.copyText())

        val left = ExecTerminals()
        left.update(buffer(OutputStream.Stdout to "done\n"))
        val leftDone = buffer(OutputStream.Stdout to "done\n", result = "done\n\n${TerminalNotices.LEFTOVER_CHILDREN}")
        left.update(leftDone)
        assertEquals(listOf("done", TerminalNotices.LEFTOVER_CHILDREN), left.view(leftDone, running = false, command = "seq 3").output.lines.map { it.text })
    }

    @Test
    fun `a shrinking buffer (replaced execution) resets the terminals`() {
        val t = ExecTerminals()
        t.update(buffer(OutputStream.Stdout to "one\ntwo\n"))
        t.update(buffer(OutputStream.Stdout to "x\n"))
        assertEquals(listOf("x"), t.view(buffer(), running = true, command = "seq 3").output.lines.map { it.text })
    }

    @Test
    fun `the inline tail keeps the desktop 48,000-character cap`() {
        val t = ExecTerminals()
        val big = "x".repeat(CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS) + "\n"
        t.update(buffer(OutputStream.Stdout to big))
        val view = t.view(buffer(), running = true, command = "seq 3")
        assertTrue(view.inlineTruncated)
        assertEquals(CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS, view.inlineOutput.length)
        assertTrue(view.inlineOutput.startsWith(CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER))
    }

    @Test
    fun `history decode keeps HubPayloads messagesOf shape`() {
        // Guard for the fixture helper the seeded test relies on.
        assertTrue(HubPayloads.messagesOf(HubFixtures.messagesReply(f04, session1)).isNotEmpty())
    }
}
