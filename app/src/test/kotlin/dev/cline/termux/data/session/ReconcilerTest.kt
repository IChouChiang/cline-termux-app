package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.buildChatRenderItems
import dev.cline.termux.data.chat.groupChatMessages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key inheritance with reasoning (home-chat.md §1.3.3 step 4): live and history shapes differ only in ids. */
class ReconcilerTest {
    private val sessionId = "s1"
    private var sequence = 0L

    private fun event(name: String, payload: String) = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = ++sequence,
        timestamp = 1_000L + sequence,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    private fun project(state: LiveProjection, vararg events: HubEventEnvelope): LiveProjection =
        events.fold(state) { s, e -> ChatProjector.project(s, e, ProjectContext(sessionId = sessionId, now = 5_000L)) }

    /** reasoning, text, tool, reasoning, text: one run with two iterations, as a thinking model streams it. */
    private fun liveTurn(): LiveProjection {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req1", "do it", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req1","clientId":"android-test"}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.REASONING_DELTA, """{"text":"think A"}"""),
            event(HubEvents.REASONING_FINISHED, """{"reasoning":"think A"}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"Let me "}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"look."}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"Let me look."}"""),
            event(HubEvents.TOOL_STARTED, """{"toolCallId":"call1","toolName":"read_files","input":{"paths":["a.kt"]}}"""),
            event(HubEvents.TOOL_FINISHED, """{"toolCallId":"call1","output":"ok"}"""),
            event(HubEvents.ITERATION_FINISHED, """{"iteration":1}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":2}"""),
            event(HubEvents.REASONING_DELTA, """{"text":"think B"}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"Done."}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"Done."}"""),
            event(HubEvents.RUN_COMPLETED, """{"requestId":"req1","reason":"completed"}"""),
        )
        return s
    }

    private val history = HubJson.parseToJsonElement(
        """
        [
          {"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"act\">do it</user_input>"}]},
          {"id":"m1","role":"assistant","ts":1003,"content":[
            {"type":"thinking","thinking":"think A"},
            {"type":"text","text":"Let me look."},
            {"type":"tool_use","id":"call1","name":"read_files","input":{"paths":["a.kt"]}}
          ]},
          {"id":"u2","role":"user","ts":1009,"content":[{"type":"tool_result","tool_use_id":"call1","content":"ok"}]},
          {"id":"m2","role":"assistant","ts":1012,"content":[
            {"type":"thinking","thinking":"think B"},
            {"type":"text","text":"Done."}
          ]}
        ]
        """.trimIndent(),
    ) as JsonArray

    private fun workAndAnswer(messages: List<ChatMessage>): Pair<ChatRenderItem.Work, ChatRenderItem.Message> {
        val items = buildChatRenderItems(messages, ChatStatus.Completed)
        val work = items.filterIsInstance<ChatRenderItem.Work>().single()
        val answer = items.last() as ChatRenderItem.Message
        return work to answer
    }

    @Test
    fun `live text after reasoning continues the reasoning row, like history`() {
        val live = liveTurn().messages
        val assistant = live.filter { it.role == Role.Assistant }
        assertEquals(2, assistant.size)
        assertEquals(listOf("Let me look.", "Done."), assistant.map { it.content })
        assertEquals(listOf("think A", "think B"), assistant.map { it.reasoning })
        assertTrue(assistant.all { it.id.startsWith("a:req1:") })
        assertTrue(assistant.all { it.renderKey.startsWith("r:req1:") })
    }

    @Test
    fun `the item key does not change when text starts after reasoning`() {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req1", "do it", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req1","clientId":"android-test"}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.REASONING_DELTA, """{"text":"think"}"""),
        )
        val thinking = groupChatMessages(s.messages).last() as ChatRenderItem.Message
        s = project(s, event(HubEvents.ASSISTANT_DELTA, """{"text":"Hi"}"""))
        val answering = groupChatMessages(s.messages).last() as ChatRenderItem.Message
        assertEquals(thinking.message.renderKey, answering.message.renderKey)
        assertEquals(thinking.reasoningMessages.first().renderKey, answering.reasoningMessages.first().renderKey)
        assertEquals(s.streamingMessageId, answering.message.id)
        assertEquals("Hi", answering.message.content)
        assertEquals("think", answering.message.reasoning)
    }

    @Test
    fun `reconciliation keeps the Work id and the answer key with reasoning before text`() {
        val live = liveTurn().messages
        val (workBefore, answerBefore) = workAndAnswer(Reconciler.merge(emptyList(), live))

        val result = Reconciler.reconcile(emptyList(), 0, live, HistoryMapper.map(sessionId, history), openRequestIds = emptySet())
        val after = Reconciler.merge(result.canonical, result.live)
        val (workAfter, answerAfter) = workAndAnswer(after)

        assertEquals(workBefore.id, workAfter.id)
        assertEquals(answerBefore.message.renderKey, answerAfter.message.renderKey)
        assertEquals(workBefore.toolCallCount, workAfter.toolCallCount)
        // Every matched live key survives (§1.3.3 unit test b), and nothing is duplicated (a).
        val keysAfter = after.map { it.renderKey }.toSet()
        live.filter { it.role in setOf(Role.User, Role.Assistant, Role.Tool) }.forEach { assertTrue(it.renderKey in keysAfter) }
        assertEquals(after.size, after.map { it.renderKey }.distinct().size)
        assertTrue(result.live.isEmpty())
        assertEquals(listOf(Role.User, Role.Assistant, Role.Tool, Role.Assistant), after.map { it.role })
    }

    private fun stoppedTurn(): List<ChatMessage> {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req2", "stop me", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req2","clientId":"android-test"}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"partial answer"}"""),
            event(HubEvents.RUN_ABORTED, """{"requestId":"req2"}"""),
        )
        return s.messages
    }

    private fun historyOf(json: String) = HistoryMapper.map(sessionId, HubJson.parseToJsonElement(json) as JsonArray)

    private val stoppedHistory = """[{"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"act\">stop me</user_input>"}]}]"""

    @Test
    fun `a stopped turn keeps the partial answer the daemon did not persist`() {
        val live = stoppedTurn()
        val partial = live.single { it.role == Role.Assistant }

        val result = Reconciler.reconcile(emptyList(), 0, live, historyOf(stoppedHistory), openRequestIds = emptySet(), partialRequestIds = setOf("req2"))
        val after = Reconciler.merge(result.canonical, result.live)

        assertEquals(listOf(Role.User, Role.Assistant), after.map { it.role })
        assertEquals("partial answer", after.last().content)
        assertEquals(partial.renderKey, after.last().renderKey)
        assertTrue(result.live.isEmpty())
    }

    @Test
    fun `a completed turn still drops live rows history does not hold`() {
        val result = Reconciler.reconcile(emptyList(), 0, stoppedTurn(), historyOf(stoppedHistory), openRequestIds = emptySet())
        assertEquals(listOf(Role.User), Reconciler.merge(result.canonical, result.live).map { it.role })
    }

    @Test
    fun `the kept partial stays in place when later turns reconcile`() {
        val first = Reconciler.reconcile(emptyList(), 0, stoppedTurn(), historyOf(stoppedHistory), openRequestIds = emptySet(), partialRequestIds = setOf("req2"))
        val later = historyOf(
            """[
              {"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"act\">stop me</user_input>"}]},
              {"id":"u3","role":"user","ts":2000,"content":[{"type":"text","text":"<user_input mode=\"act\">next</user_input>"}]},
              {"id":"m3","role":"assistant","ts":2001,"content":[{"type":"text","text":"answer"}]}
            ]""",
        )
        val second = Reconciler.reconcile(first.canonical, first.canonicalCount, emptyList(), later, openRequestIds = emptySet(), partialRequestIds = setOf("req2"))
        assertEquals(listOf("stop me", "partial answer", "next", "answer"), second.canonical.map { it.content.substringAfter('>').substringBefore('<') })
        assertEquals(second.canonical.size, second.canonical.map { it.renderKey }.distinct().size)
    }

    @Test
    fun `streaming reasoning is the streaming message until it finishes`() {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req3", "think", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req3","clientId":"android-test"}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.REASONING_DELTA, """{"text":"hmm"}"""),
        )
        val reasoningRow = s.messages.last()
        assertEquals("§4.3: no \"Thinking...\" under a streaming reasoning row", reasoningRow.id, s.streamingMessageId)
        s = project(s, event(HubEvents.REASONING_FINISHED, """{"reasoning":"hmm"}"""))
        assertEquals(null, s.streamingMessageId)
    }

    private val failedHistory = """[
      {"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"act\">hello</user_input>"}]},
      {"id":"error_1","role":"assistant","ts":950,"content":[{"type":"text","text":"OpenAI API key is missing. Pass it using the 'apiKey' parameter."}],
       "metadata":{"displayOnly":true,"displayRole":"error"},"modelInfo":{"id":"gpt","provider":"openai"}}
    ]"""

    @Test
    fun `a persisted run failure maps to an error row with its provider`() {
        val rows = historyOf(failedHistory).messages
        assertEquals(listOf(Role.User, Role.Error), rows.map { it.role })
        assertEquals("openai", rows.last().meta.providerId)
        assertEquals(dev.cline.termux.data.chat.REASON_CREDENTIALS, rows.last().meta.reason)
    }

    @Test
    fun `the live error row hands over to the persisted one without a duplicate`() {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req4", "hello", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req4","clientId":"android-test"}"""),
            event(HubEvents.RUN_FAILED, """{"requestId":"req4","error":"OpenAI API key is missing. Pass it using the 'apiKey' parameter."}"""),
        )
        val liveError = s.messages.single { it.role == Role.Error }
        val result = Reconciler.reconcile(emptyList(), 0, s.messages, historyOf(failedHistory), openRequestIds = emptySet())
        val after = Reconciler.merge(result.canonical, result.live)
        assertEquals(listOf(Role.User, Role.Error), after.map { it.role })
        assertEquals(liveError.renderKey, after.last().renderKey)
    }

    @Test
    fun `an unmarked subagent loop inside spawn_agent stays out of the parent transcript`() {
        // Recorded on the dev Hub (mock-agent): the subagent's events arrive in the parent session
        // with no `agent` field, between the parent's own iteration.started and iteration.finished.
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req9", "use a subagent", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req9"}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"I'll ask a subagent."}"""),
            event(HubEvents.TOOL_STARTED, """{"toolCallId":"spawn1","toolName":"spawn_agent","input":{"task":"list"}}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":1}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"sub answer"}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"sub answer"}"""),
            event(HubEvents.ITERATION_FINISHED, """{"iteration":1}"""),
            event(HubEvents.TOOL_FINISHED, """{"toolCallId":"spawn1","output":"sub answer"}"""),
            event(HubEvents.ITERATION_FINISHED, """{"iteration":1}"""),
            event(HubEvents.ITERATION_STARTED, """{"iteration":2}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"Parent summary."}"""),
            event(HubEvents.ITERATION_FINISHED, """{"iteration":2}"""),
            event(HubEvents.RUN_COMPLETED, """{"requestId":"req9"}"""),
        )
        val ids = s.messages.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(
            listOf("I'll ask a subagent.", "Parent summary."),
            s.messages.filter { it.role == Role.Assistant }.map { it.content },
        )
        assertEquals(0, s.nestedDepth)
    }
}
