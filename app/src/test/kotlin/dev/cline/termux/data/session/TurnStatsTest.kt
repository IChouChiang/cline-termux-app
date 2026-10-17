package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11.9: a turn's stats come from its terminal `run.*` `result` (`usage`, `iterations`,
 * `durationMs`), stay on the record (persisted with it), and are anchored to the turn's final
 * answer: its live renderKey, then the history id once a reconciliation matched the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnStatsTest {
    private val clock = FakeClock()
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f04 by lazy { HubFixtures.load("04-history-and-replay.jsonl") }
    private val session1 = "1791064689782_kck3p"
    private val requestId = "session-send_input-002"

    private fun obj(raw: String): JsonObject = HubJson.parseToJsonElement(raw).jsonObject

    // ---------------------------------------------------------------- parsing

    @Test
    fun `fromResult reads usage, iterations and duration`() {
        val stats = TurnStats.fromResult(
            obj(
                """{"text":"done","usage":{"inputTokens":1234,"outputTokens":340,"cacheReadTokens":800,"cacheWriteTokens":12,"totalCost":0.0042},
                |"iterations":3,"finishReason":"completed","durationMs":8123}""".trimMargin(),
            ),
        )!!
        assertEquals(TurnStats(1234, 340, 800, 12, 0.0042, 3, 8123), stats)
    }

    @Test
    fun `fromResult without usage or duration is null, partial results keep what they have`() {
        assertNull(TurnStats.fromResult(null))
        assertNull(TurnStats.fromResult(obj("""{"text":"done"}""")))
        assertEquals(TurnStats(durationMs = 407), TurnStats.fromResult(obj("""{"durationMs":407}""")))
        val noCost = TurnStats.fromResult(obj("""{"usage":{"inputTokens":10,"outputTokens":2}}"""))!!
        assertNull(noCost.totalCost)
        assertNull(noCost.durationMs)
        assertEquals(10L, noCost.inputTokens)
    }

    @Test
    fun `stats survive the persisted chat_turns round trip`() {
        val record = TurnRecord(
            requestId = "r1", sessionId = "s1", sentAt = 1L, open = false,
            stats = TurnStats(2500, 120, totalCost = 0.0, iterations = 2, durationMs = 407),
            answerRenderKey = "a:r1:2:0", answerMessageId = "h:m4:0",
        )
        assertEquals(listOf(record), TurnRecords.decode(TurnRecords.encode(listOf(record))))
        // Records written before A11.9 decode with no stats.
        val legacy = TurnRecords.decode("""[{"requestId":"r0","sessionId":"s1","sentAt":1,"open":false}]""").single()
        assertNull(legacy.stats)
        assertNull(legacy.answerRenderKey)
    }

    // ---------------------------------------------------------------- anchoring

    private fun assistant(id: String, requestId: String?, content: String, renderKey: String = id) =
        ChatMessage(id = id, sessionId = "s1", role = Role.Assistant, content = content, createdAt = 1L, renderKey = renderKey, meta = Meta(requestId = requestId))

    @Test
    fun `ended takes the turn's last non-blank assistant row as the answer`() {
        val record = TurnRecord(requestId = "r1", sessionId = "s1", sentAt = 1L)
        val live = listOf(
            assistant("a:r1:1:0", "r1", "Let me look."),
            assistant("a:r1:2:0", "r1", "Here is the answer.", renderKey = "r:r1:2:0"),
            assistant("r:r1:3:0", "r1", ""),
            assistant("a:r0:1:0", "r0", "an older turn"),
        )
        val ended = TurnRecords.ended(record, obj("""{"usage":{"inputTokens":5,"outputTokens":6},"durationMs":10}"""), live)
        assertEquals(TurnStats(5, 6, durationMs = 10), ended.stats)
        assertEquals("r:r1:2:0", ended.answerRenderKey)
        // A terminal without a result (the projector's own run.completed) changes nothing.
        assertSame(record, TurnRecords.ended(record, null, live))
    }

    @Test
    fun `anchorAnswers learns the history id of the row that inherited the answer's renderKey`() {
        val stats = TurnStats(1, 2)
        val turns = listOf(
            TurnRecord(requestId = "r1", sessionId = "s1", sentAt = 1L, stats = stats, answerRenderKey = "a:r1:2:0"),
            TurnRecord(requestId = "r2", sessionId = "s1", sentAt = 2L),
        )
        val canonical = listOf(assistant("h:m1:0", null, "answer", renderKey = "a:r1:2:0"))
        val anchored = TurnRecords.anchorAnswers(turns, canonical)
        assertEquals("h:m1:0", anchored[0].answerMessageId)
        assertNull(anchored[1].answerMessageId)
        // Nothing left to anchor: the same list comes back (no persist).
        assertSame(anchored, TurnRecords.anchorAnswers(anchored, canonical))
        val withoutStats = turns.drop(1)
        assertSame(withoutStats, TurnRecords.anchorAnswers(withoutStats, canonical))
        // The answer row not in history yet: nothing changes.
        assertSame(turns, TurnRecords.anchorAnswers(turns, emptyList()))
        assertEquals(mapOf("a:r1:2:0" to stats, "h:m1:0" to stats), TurnRecords.statsByAnswer(anchored))
    }

    // ---------------------------------------------------------------- fixture replay

    private fun TestScope.newStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = { "175794:a" },
    )

    private suspend fun TestScope.replay(store: SessionStore, hub: FakeSessionHub, lines: List<HubFixtures.Line>, before: (HubEventEnvelope) -> Unit = {}) {
        for (line in lines) {
            line.command?.let { cmd ->
                if (cmd.command == HubCommands.SESSION_SEND_INPUT) {
                    hub.nextRequestIds += cmd.requestId!!
                    store.send(SendRequest(cmd.sessionId!!, cmd.payload!!["prompt"]!!.jsonPrimitive.content))
                }
            }
            line.reply?.let { reply -> hub.sends.firstOrNull { it.requestId == reply.requestId }?.reply?.complete(reply) }
            line.event?.let { event ->
                before(event)
                store.dispatch(event)
            }
            runCurrent()
        }
    }

    @Test
    fun `fixture 02 - run_completed result becomes the closed turn's stats, anchored to the answer row`() = runTest {
        val hub = FakeSessionHub(clientId)
        val createReply = HubFixtures.replyTo(f02, "session-create-001").payload!!
        hub.onGet = { createReply }
        // A failed reconcile keeps the live rows, so the record is checked against them.
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        val store = newStore(hub)
        store.open(session1)
        runCurrent()
        replay(store, hub, f02)
        val state = store.state(session1).value
        val turn = state.turns.single()
        assertEquals(TurnStats(inputTokens = 2500, outputTokens = 120, totalCost = 0.0, iterations = 2, durationMs = 407), turn.stats)
        assertEquals("a:$requestId:2:0", turn.answerRenderKey)
        assertTrue(state.messages.last().content.startsWith("The command ran successfully"))
        assertEquals(mapOf("a:$requestId:2:0" to turn.stats), TurnRecords.statsByAnswer(state.turns))
    }

    @Test
    fun `fixture 02 then 04 - the reconciled answer keeps its stats by renderKey and by history id`() = runTest {
        val hub = FakeSessionHub(clientId)
        val createReply = HubFixtures.replyTo(f02, "session-create-001").payload!!
        hub.onGet = { createReply }
        val store = newStore(hub)
        store.open(session1)
        runCurrent()
        val history = HubPayloads.messagesOf(HubFixtures.messagesReply(f04, session1))
        replay(store, hub, f02) { event -> if (event.event == HubEvents.RUN_COMPLETED) hub.history = history }
        runCurrent()
        val state = store.state(session1).value
        val answer = state.messages.last()
        assertEquals(Role.Assistant, answer.role)
        assertTrue("history row", answer.id.startsWith("h:"))
        val turn = state.turns.single()
        assertNotNull(turn.stats)
        assertEquals(answer.renderKey, turn.answerRenderKey)
        assertEquals(answer.id, turn.answerMessageId)
        val byAnswer = TurnRecords.statsByAnswer(state.turns)
        assertEquals(turn.stats, byAnswer[answer.renderKey])
        assertEquals(turn.stats, byAnswer[answer.id])
    }

    // ---------------------------------------------------------------- history fallback

    /** A turn the terminal drove: a tool-only model call, then the answer; then a second, short turn. */
    private val tuiHistory = """[
        {"id":"u1","role":"user","content":"Read the README","ts":1000},
        {"id":"a1","role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"read_files","input":{"paths":["README.md"]}}],"ts":3000,
         "metrics":{"inputTokens":1000,"outputTokens":40,"cacheReadTokens":500,"cost":0.001},"modelInfo":{"id":"deepseek-chat","provider":"deepseek"}},
        {"id":"t1","role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"# App"}],"ts":3100},
        {"id":"a2","role":"assistant","content":[{"type":"text","text":"It describes the app."}],"ts":9100,
         "metrics":{"inputTokens":1200,"outputTokens":300,"cacheReadTokens":900,"cost":0.003},"modelInfo":{"id":"deepseek-chat","provider":"deepseek"}},
        {"id":"u2","role":"user","content":"Thanks","ts":20000},
        {"id":"a3","role":"assistant","content":"You're welcome.","ts":21500,"metrics":{"inputTokens":1600,"outputTokens":10,"cost":0.0005}}
        ]"""

    private fun rows(json: String): List<ChatMessage> = HistoryMapper.map(session1, HubJson.parseToJsonElement(json) as JsonArray).messages

    @Test
    fun `history totals sum every model call of the turn and time it from prompt to answer`() {
        val stats = TurnRecords.historyStatsByAnswer(rows(tuiHistory))
        assertEquals(setOf("h:a2:0", "h:a3:0"), stats.keys)
        val first = stats.getValue("h:a2:0")
        assertEquals("the tool-only call counts too", 2_200L, first.inputTokens)
        assertEquals(340L, first.outputTokens)
        assertEquals(1_400L, first.cacheReadTokens)
        assertEquals(0.004, first.totalCost!!, 1e-12)
        assertEquals("prompt ts to the answer's ts", 8_100L, first.durationMs)
        assertNull(first.iterations)
        val second = stats.getValue("h:a3:0")
        assertEquals(TurnStats(inputTokens = 1_600, outputTokens = 10, totalCost = 0.0005, durationMs = 1_500), second)
    }

    @Test
    fun `history totals leave recorded, running, failed and folded turns alone`() {
        val history = rows(tuiHistory)
        assertEquals("recorded stats win", setOf("h:a2:0"), TurnRecords.historyStatsByAnswer(history, recordedKeys = setOf("h:a3:0")).keys)
        assertEquals("the last turn is still running", setOf("h:a2:0"), TurnRecords.historyStatsByAnswer(history, lastTurnRunning = true).keys)
        val hidden = TurnRecords.historyStatsByAnswer(history, sessionProviderId = "openai-codex", showsCost = { it == "deepseek" })
        assertEquals("the call's own provider decides", 0.004, hidden.getValue("h:a2:0").totalCost!!, 1e-12)
        assertNull("no model info: the session's provider decides", hidden.getValue("h:a3:0").totalCost)

        val failed = rows(
            """[{"id":"u1","role":"user","content":"go","ts":1000},
            {"id":"a1","role":"assistant","content":"partial","ts":2000,"metrics":{"inputTokens":10,"outputTokens":5}},
            {"id":"e1","role":"assistant","content":"Provider error","ts":2100,"metadata":{"displayRole":"error"}}]""",
        )
        assertTrue("a failed turn prints no totals, as the terminal", TurnRecords.historyStatsByAnswer(failed).isEmpty())

        val folded = rows(
            """[{"id":"c","role":"user","content":"Context summary:\n\nx","ts":1000,"metadata":{"kind":"compaction_summary","displayRole":"system","userRunSpan":2}},
            {"id":"a","role":"assistant","content":"ok","ts":2000,"metrics":{"inputTokens":10,"outputTokens":5}},
            {"id":"m","role":"user","content":"third and fourth","ts":3000,"metadata":{"userRunSpan":2}},
            {"id":"am","role":"assistant","content":"four","ts":4000,"metrics":{"inputTokens":10,"outputTokens":5}},
            {"id":"u5","role":"user","content":"fifth","ts":5000},
            {"id":"a5","role":"assistant","content":"five","ts":6000}]""",
        )
        assertTrue("summaries and merged turns span several runs; a turn without metrics has none", TurnRecords.historyStatsByAnswer(folded).isEmpty())
    }
}
