package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.data.chat.RunPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-1 brief §8: the client-contribution responder (dedupe, unknown capabilities, the
 * mistake-limit dedupe), the G2 decision mapping (desktop `createDesktopMistakeLimitPrompt`),
 * the reducer's mistake-limit question, and the `switch_to_act_mode` handler with its
 * continuation (CLI `sendTurnWithActModeContinuation`). Events go through the rig the way the
 * `SessionStore` pump sends them: the responder observes first, then the reducer applies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientContributionsTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val sid = "s1"
    private var seq = 0L

    private data class Respond(val requestId: String, val ok: Boolean, val result: JsonElement?, val error: String?)

    private inner class Rig(
        val contributions: ClientContributions,
        val responds: MutableList<Respond>,
        val sends: MutableList<SendRequest>,
        val modes: MutableList<Pair<String, PromptMode>>,
        val flips: MutableList<String>,
        val flow: MutableStateFlow<SessionState>,
        /** The rebuilds asked for ([ClientContributions.Sessions.restart]). */
        val restarts: MutableList<PromptMode>,
        /** Rebuilds, synthetic sends and replies in the order they happened. */
        val log: MutableList<String>,
        /** The stop notices handed to the store (A17). */
        val notices: MutableList<StopNotice>,
    ) {
        val facts: SessionFacts get() = flow.value.facts

        /**
         * What the store pump does with one event: observers first (SessionEventTap), then the
         * reducer; a terminal `run.*` closes the open direct turn with its outcome, as the store
         * does (a drained prompt's `q:` turn survives the late terminal of the run before it;
         * the queued tests close it with [settleQueued]).
         */
        fun deliver(event: HubEventEnvelope) {
            contributions.observe(event)
            val s = flow.value
            val own = s.turns.filter { it.open && it.ours }.mapTo(HashSet()) { it.requestId }
            val outcome = when (event.event) {
                HubEvents.RUN_COMPLETED -> if (event.payload?.str("reason") == "completed") TurnOutcome.Completed else TurnOutcome.Ended
                HubEvents.RUN_FAILED -> TurnOutcome.Failed
                HubEvents.RUN_ABORTED -> TurnOutcome.Aborted
                HubEvents.RUN_INTERRUPTED -> TurnOutcome.Interrupted
                else -> null
            }
            flow.value = s.copy(
                facts = SessionFactsReducer.reduce(s.facts, event, ReduceContext(clientId, own, now = 0L)),
                turns = if (outcome == null) {
                    s.turns
                } else {
                    s.turns.map { if (it.open && !SessionFactsReducer.isQueuedRunKey(it.requestId)) it.copy(open = false, outcome = outcome) else it }
                },
            )
        }

        /** The turn this app sent: an open, own `TurnRecord` in [mode]. */
        fun ownTurn(requestId: String, mode: PromptMode) {
            flow.value = flow.value.copy(turns = listOf(TurnRecord(requestId = requestId, sessionId = sid, mode = mode.wire, sentAt = 0L)))
        }

        /** The session's runtime was created in [mode] (`metadata.mode`, the session's own mode). */
        fun runtimeMode(mode: PromptMode) {
            flow.value = flow.value.copy(facts = flow.value.facts.copy(mode = mode.wire))
        }
    }

    private fun TestScope.rig(
        reply: (String) -> JsonObject = { id -> buildJsonObject { put("requestId", id); put("ok", true) } },
        /** The rebuild's outcome; true also moves the session's mode, as the create's record does. */
        rebuild: suspend (PromptMode) -> Boolean = { true },
        /** The Hub's answer to a synthetic send (the guidance steer, the continuation). */
        synthetic: suspend (SendRequest) -> SyntheticSendResult = { SyntheticSendResult.Accepted("continuation") },
        /** The store's `addStopNotice`, after the rig recorded the notice. */
        addNotice: suspend (StopNotice) -> Unit = {},
    ): Rig {
        val responds = mutableListOf<Respond>()
        val notices = mutableListOf<StopNotice>()
        val sends = mutableListOf<SendRequest>()
        val modes = mutableListOf<Pair<String, PromptMode>>()
        val flips = mutableListOf<String>()
        val restarts = mutableListOf<PromptMode>()
        val log = mutableListOf<String>()
        val flow = MutableStateFlow(SessionState(sid))
        val sessions = object : ClientContributions.Sessions {
            override fun state(sessionId: String): StateFlow<SessionState> = flow
            override suspend fun sendSynthetic(request: SendRequest): SyntheticSendResult {
                sends += request
                log += "send:${request.mode.wire}:${request.delivery?.wire ?: "direct"}"
                return synthetic(request)
            }

            override suspend fun restart(sessionId: String, mode: PromptMode): Boolean {
                restarts += mode
                log += "restart:${mode.wire}"
                return rebuild(mode).also { ok ->
                    if (ok) flow.value = flow.value.copy(facts = flow.value.facts.copy(mode = mode.wire))
                }
            }

            override suspend fun addStopNotice(notice: StopNotice) {
                notices += notice
                log += "notice:${notice.kind}"
                addNotice(notice)
            }
        }
        val contributions = ClientContributions(
            clientId = clientId,
            hub = { requestId, ok, result, error ->
                responds += Respond(requestId, ok, result, error)
                log += "respond:$requestId"
                reply(requestId)
            },
            sessions = sessions,
            persistMode = { s, m -> modes += s to m },
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        backgroundScope.launch { contributions.flips.collect { flips += it } }
        runCurrent()
        return Rig(contributions, responds, sends, modes, flips, flow, restarts, log, notices)
    }

    // ---- events ------------------------------------------------------------------------------

    private fun ev(name: String, payload: JsonObject) =
        HubEventEnvelope(event = name, sessionId = sid, sequence = ++seq, timestamp = 1_000L + seq, payload = payload)

    private fun runStarted(requestId: String = "r1", by: String = clientId) = ev(
        HubEvents.RUN_STARTED,
        buildJsonObject { put("requestId", requestId); put("runId", "run-$requestId"); put("clientId", by) },
    )

    private fun iteration(n: Int) = ev(HubEvents.ITERATION_STARTED, buildJsonObject { put("iteration", n) })

    private fun requested(
        requestId: String,
        capability: String,
        target: String = clientId,
        payload: JsonObject = buildJsonObject {},
    ) = ev(
        HubEvents.CAPABILITY_REQUESTED,
        buildJsonObject {
            put("requestId", requestId); put("sessionId", sid); put("targetClientId", target); put("capabilityName", capability)
            put("payload", payload)
        },
    )

    private fun mistake(requestId: String, iteration: Int, details: String? = "Tool no_such_tool does not exist", target: String = clientId) =
        requested(
            requestId, HubCapabilities.MISTAKE_LIMIT, target,
            buildJsonObject {
                putJsonObject("context") {
                    put("iteration", iteration)
                    put("consecutiveMistakes", 6)
                    put("maxConsecutiveMistakes", 6)
                    put("reason", "tool_error")
                    details?.let { put("details", it) }
                }
            },
        )

    private fun switchRequested(requestId: String = "c1", target: String = clientId) = requested(
        requestId, HubCapabilities.SWITCH_TO_ACT_MODE, target,
        payload = buildJsonObject {
            putJsonObject("input") {}
            putJsonObject("context") { put("toolCallId", "call_switch"); put("iteration", 1) }
        },
    )

    private fun resolved(requestId: String, result: JsonObject?, ok: Boolean = true) = ev(
        HubEvents.CAPABILITY_RESOLVED,
        buildJsonObject {
            put("requestId", requestId); put("ok", ok)
            if (result != null) putJsonObject("payload") { put("result", result) }
        },
    )

    private fun runCompleted(requestId: String = "r1", reason: String = "completed") =
        ev(HubEvents.RUN_COMPLETED, buildJsonObject { put("requestId", requestId); put("reason", reason) })

    private fun runAborted(requestId: String = "r1") =
        ev(HubEvents.RUN_ABORTED, buildJsonObject { put("requestId", requestId); put("reason", "User pressed Stop") })

    private fun runFailed(requestId: String = "r1") =
        ev(HubEvents.RUN_FAILED, buildJsonObject { put("requestId", requestId); put("error", "boom") })

    private fun guidanceOf(decision: MistakeLimitDecision): String = (decision as MistakeLimitDecision.Continue).guidance

    // ---- G2 decision mapping (desktop createDesktopMistakeLimitPrompt) ------------------------

    @Test
    fun `every desktop stop spelling stops the run with the desktop reason`() {
        val context = MistakeLimitPrompt.context(null)
        for (answer in listOf("2", "Stop this run", "stop this run", "stop", "STOP", " n ", "N", "no", "No")) {
            assertEquals(answer, MistakeLimitDecision.Stop("stopped after mistake_limit_reached prompt"), MistakeLimitPrompt.decide(context, answer))
        }
        assertEquals(
            """{"action":"stop","reason":"stopped after mistake_limit_reached prompt"}""",
            MistakeLimitPrompt.stopResult(MistakeLimitPrompt.STOP_REASON).toString(),
        )
    }

    @Test
    fun `option 1 continues with the desktop guidance and no user guidance`() {
        val context = MistakeLimitContext(3, 6, 6, "tool_error", "  Tool no_such_tool does not exist  ")
        val expected = "The run reached the limit for repeated mistakes or tool calls. Latest: Tool no_such_tool does not exist " +
            "Do not repeat the same call. Re-check the tool's parameter requirements, fix the call, and try a different approach."
        for (answer in listOf("Try a different approach", "try a different approach", "1", " 1 ")) {
            assertEquals(answer, MistakeLimitDecision.Continue(expected), MistakeLimitPrompt.decide(context, answer))
        }
    }

    @Test
    fun `free text continues with the guidance plus the user's own words`() {
        val context = MistakeLimitContext(3, 6, 6, "tool_error", "bad args")
        assertEquals(
            "The run reached the limit for repeated mistakes or tool calls. Latest: bad args Do not repeat the same call. " +
                "Re-check the tool's parameter requirements, fix the call, and try a different approach. " +
                "User guidance: Use read_files instead",
            guidanceOf(MistakeLimitPrompt.decide(context, "  Use read_files instead  ")),
        )
        // Without a detail the "Latest:" part is left out, not left empty.
        assertEquals(
            "The run reached the limit for repeated mistakes or tool calls. Do not repeat the same call. Re-check the " +
                "tool's parameter requirements, fix the call, and try a different approach.",
            guidanceOf(MistakeLimitPrompt.decide(MistakeLimitContext(3, null, null, null, "   "), "1")),
        )
    }

    @Test
    fun `the detail is trimmed and cut at 600 characters with an ellipsis`() {
        val long = "x".repeat(650)
        assertEquals("x".repeat(600) + "…", MistakeLimitPrompt.truncatedDetail("  $long  "))
        assertEquals("y".repeat(600), MistakeLimitPrompt.truncatedDetail("y".repeat(600)))
        assertEquals("", MistakeLimitPrompt.truncatedDetail(null))
        val guidance = guidanceOf(MistakeLimitPrompt.decide(MistakeLimitContext(1, 6, 6, null, long), "1"))
        assertTrue(guidance.contains("Latest: ${"x".repeat(600)}… Do not repeat"))
        assertFalse(guidance.contains("x".repeat(601)))
    }

    // ---- G2 delivery (desktop: steer the guidance, then a bare continue) ---------------------

    @Test
    fun `continue steers the guidance first, then answers a bare continue`() = runTest {
        val calls = mutableListOf<String>()
        val ignored = MistakeLimitPrompt.answer(
            MistakeLimitDecision.Continue("The run reached the limit … Do not repeat the same call."),
            steer = { guidance -> calls += "steer:$guidance"; null },
            respond = { result -> calls += "respond:$result"; false },
        )
        assertFalse(ignored)
        assertEquals(
            listOf("steer:The run reached the limit … Do not repeat the same call.", """respond:{"action":"continue"}"""),
            calls,
        )
    }

    @Test
    fun `stop answers at once and steers nothing`() = runTest {
        val calls = mutableListOf<String>()
        val ignored = MistakeLimitPrompt.answer(
            MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON),
            steer = { calls += "steer"; null },
            respond = { result -> calls += "respond:$result"; true },
        )
        assertTrue("the reply's ignored flag is passed on", ignored)
        assertEquals(listOf("""respond:{"action":"stop","reason":"stopped after mistake_limit_reached prompt"}"""), calls)
    }

    @Test
    fun `a steer the Hub did not take stops the run with the desktop's reason`() = runTest {
        val results = mutableListOf<JsonObject>()
        MistakeLimitPrompt.answer(
            MistakeLimitDecision.Continue("g"),
            steer = { MistakeLimitPrompt.steerFailure(SyntheticSendResult.NotConnected) },
            respond = { results += it; false },
        )
        assertEquals("""{"action":"stop","reason":"Could not send recovery guidance: not connected to the Hub"}""", results.single().toString())
        assertNull(MistakeLimitPrompt.steerFailure(SyntheticSendResult.Accepted("r")))
        // A refusal carries the Hub's own text, as the desktop's `error.message`.
        assertEquals("Session s1 is not running", MistakeLimitPrompt.steerFailure(SyntheticSendResult.Refused("command_failed", "Session s1 is not running")))
        assertEquals("client_timeout", MistakeLimitPrompt.steerFailure(SyntheticSendResult.Refused("client_timeout", "")))
        assertEquals("gone", MistakeLimitPrompt.steerFailure(SyntheticSendResult.ResumeFailed("gone")))
        assertEquals("the session could not be resumed", MistakeLimitPrompt.steerFailure(SyntheticSendResult.ResumeFailed("")))
    }

    @Test
    fun `the steer goes out in the mode of the turn the model is in`() {
        val plan = SessionState(sid, facts = SessionFacts(sid, activeRun = RunRef("r1", "run-r1", 0L, clientId, ours = true)))
            .copy(turns = listOf(TurnRecord(requestId = "r1", sessionId = sid, mode = PromptMode.Plan.wire, sentAt = 0L)))
        assertEquals(PromptMode.Plan, runMode(plan))
        assertEquals(
            SendRequest(sid, "guidance", mode = PromptMode.Plan, delivery = Delivery.Steer, synthetic = true),
            MistakeLimitPrompt.steerRequest(plan, "guidance"),
        )
        // A run started elsewhere: the session's own mode.
        val elsewhere = SessionState(sid, facts = SessionFacts(sid, mode = PromptMode.Plan.wire, activeRun = RunRef("tui", null, 0L, "cli", ours = false)))
        assertEquals(PromptMode.Plan, runMode(elsewhere))
        assertEquals(PromptMode.Act, runMode(SessionState(sid)))
    }

    @Test
    fun `the request context is read from payload context`() {
        val p = buildJsonObject {
            putJsonObject("context") {
                put("iteration", 4); put("consecutiveMistakes", 6); put("maxConsecutiveMistakes", 6)
                put("reason", "tool_error"); put("details", "d")
            }
        }
        assertEquals(MistakeLimitContext(4, 6, 6, "tool_error", "d"), MistakeLimitPrompt.context(p))
        assertEquals(MistakeLimitContext(null, null, null, null, null), MistakeLimitPrompt.context(buildJsonObject {}))
    }

    // ---- reducer: the mistake-limit question --------------------------------------------------

    @Test
    fun `mistake_limit decide opens a MistakeLimit question with its context`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(iteration(3))
        r.deliver(mistake("m1", 3))
        val q = r.facts.questions.getValue("m1")
        assertEquals(QuestionKind.MistakeLimit, q.kind)
        assertEquals(MistakeLimitPrompt.OPTIONS, q.options)
        assertEquals(listOf("Try a different approach", "Stop this run"), q.options)
        assertEquals(3, q.iteration)
        assertEquals(MistakeLimitContext(3, 6, 6, "tool_error", "Tool no_such_tool does not exist"), q.mistakeLimit)
        assertEquals(RunPhase.AwaitingUser, r.facts.runPhase)
        runCurrent()
        assertTrue("the user answers it, the responder does not", r.responds.isEmpty())
    }

    @Test
    fun `a mistake limit for another client opens nothing and gets no answer`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3, target = "cli-tui"))
        runCurrent()
        assertTrue(r.facts.questions.isEmpty())
        assertFalse("the other client times out its own", r.contributions.hasMistakeTimer("m1"))
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS * 2)
        runCurrent()
        assertTrue(r.responds.isEmpty())
    }

    // ---- G2 timeout (desktop ASK_QUESTION_TIMEOUT_MS, then stop) -------------------------------

    @Test
    fun `an unanswered mistake limit stops the run after the desktop's five minutes`() = runTest {
        assertEquals(5 * 60_000L, MistakeLimitPrompt.TIMEOUT_MS)
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3, details = "  Tool no_such_tool does not exist  "))
        runCurrent()
        assertTrue(r.contributions.hasMistakeTimer("m1"))
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("still waiting for the user", r.responds.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf(Respond("m1", true, MistakeLimitPrompt.stopResult("mistake_limit_reached: Tool no_such_tool does not exist"), null)),
            r.responds,
        )
        assertFalse(r.contributions.hasMistakeTimer("m1"))
        // The Hub resolves the request with that stop: the question closes, no watermark is set.
        r.deliver(resolved("m1", MistakeLimitPrompt.stopResult("mistake_limit_reached: Tool no_such_tool does not exist")))
        assertTrue(r.facts.questions.isEmpty())
        assertNull(r.facts.mistakeContinuedThroughIteration)
    }

    @Test
    fun `the timeout's reason is the untruncated detail, else the context's reason`() {
        assertEquals(
            """{"action":"stop","reason":"mistake_limit_reached: invalid_tool_call"}""",
            MistakeLimitPrompt.timeoutResult(MistakeLimitContext(1, 3, 3, "invalid_tool_call", "   ")).toString(),
        )
        assertEquals(
            "mistake_limit_reached: api_error",
            MistakeLimitPrompt.timeoutResult(MistakeLimitContext(1, 3, 3, "api_error", null))["reason"]!!.jsonPrimitive.content,
        )
        val long = "d".repeat(700)
        assertEquals(
            "mistake_limit_reached: $long",
            MistakeLimitPrompt.timeoutResult(MistakeLimitContext(1, 3, 3, "tool_execution_failed", " $long "))["reason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an answer before the timeout cancels it`() = runTest {
        for (answer in listOf(MistakeLimitPrompt.AUTO_CONTINUE, MistakeLimitPrompt.stopResult(MistakeLimitPrompt.STOP_REASON))) {
            val r = rig()
            r.deliver(runStarted())
            r.deliver(mistake("m1", 3))
            runCurrent()
            advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS - 1_000)
            // The user answered on the phone; the Hub's capability.resolved reaches the pump.
            r.deliver(resolved("m1", answer))
            assertFalse(r.contributions.hasMistakeTimer("m1"))
            advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS * 2)
            runCurrent()
            assertTrue(answer.toString(), r.responds.isEmpty())
        }
    }

    @Test
    fun `the end of the run cancels the timeout`() = runTest {
        // Built inside the loop: the reducer drops an event whose sequence is not newer.
        val ends = listOf({ runAborted() }, { runFailed() }, { runCompleted(reason = "aborted") }, { runCompleted() })
        for ((index, end) in ends.withIndex()) {
            val r = rig()
            r.deliver(runStarted())
            r.deliver(mistake("m$index", 2))
            runCurrent()
            assertTrue(r.contributions.hasMistakeTimer("m$index"))
            val event = end()
            r.deliver(event)
            assertFalse(event.event, r.contributions.hasMistakeTimer("m$index"))
            advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS * 2)
            runCurrent()
            assertTrue(event.event, r.responds.isEmpty())
        }
    }

    @Test
    fun `a request answered continue at once starts no timeout`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(iteration(4))
        r.deliver(mistake("m1", 4))
        r.deliver(resolved("m1", MistakeLimitPrompt.AUTO_CONTINUE))
        r.deliver(mistake("m2", 3))
        runCurrent()
        assertFalse(r.contributions.hasMistakeTimer("m2"))
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(listOf(Respond("m2", true, MistakeLimitPrompt.AUTO_CONTINUE, null)), r.responds)
    }

    @Test
    fun `the question is dropped when the run stops`() = runTest {
        // Built inside the loop: the reducer drops an event whose sequence is not newer.
        val ends = listOf({ runAborted() }, { runFailed() }, { runCompleted(reason = "aborted") })
        for ((index, end) in ends.withIndex()) {
            val r = rig()
            r.deliver(runStarted())
            r.deliver(mistake("m$index", 2))
            assertEquals(1, r.facts.questions.size)
            val event = end()
            r.deliver(event)
            assertTrue(event.event, r.facts.questions.isEmpty())
        }
    }

    @Test
    fun `dedupe by iteration - a request the user already continued through is answered continue`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(iteration(3))
        r.deliver(mistake("m1", 3))
        r.deliver(iteration(4))
        // The user continued; the Hub's capability.resolved carries the decision.
        r.deliver(resolved("m1", MistakeLimitPrompt.AUTO_CONTINUE))
        assertEquals("continuedThroughIteration is the larger of the request's and the run's", 4, r.facts.mistakeContinuedThroughIteration)
        assertTrue(r.facts.questions.isEmpty())

        // An older failure that arrives after the answer: no new question, an automatic continue.
        r.deliver(mistake("m2", 3))
        r.deliver(mistake("m3", 4))
        runCurrent()
        assertTrue(r.facts.questions.isEmpty())
        assertEquals(
            listOf(Respond("m2", true, MistakeLimitPrompt.AUTO_CONTINUE, null), Respond("m3", true, MistakeLimitPrompt.AUTO_CONTINUE, null)),
            r.responds,
        )
        assertEquals("""{"action":"continue"}""", MistakeLimitPrompt.AUTO_CONTINUE.toString())

        // A later iteration asks again.
        r.deliver(mistake("m4", 5))
        runCurrent()
        assertEquals(QuestionKind.MistakeLimit, r.facts.questions.getValue("m4").kind)
        assertEquals(2, r.responds.size)

        // The next run starts clean.
        r.deliver(runCompleted())
        r.deliver(runStarted("r2"))
        assertNull(r.facts.mistakeContinuedThroughIteration)
        r.deliver(mistake("m5", 1))
        assertEquals(QuestionKind.MistakeLimit, r.facts.questions.getValue("m5").kind)
    }

    @Test
    fun `a stop answer or a cancelled request sets no watermark`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        r.deliver(resolved("m1", MistakeLimitPrompt.stopResult(MistakeLimitPrompt.STOP_REASON)))
        assertNull(r.facts.mistakeContinuedThroughIteration)
        r.deliver(mistake("m2", 4))
        r.deliver(ev(HubEvents.CAPABILITY_RESOLVED, buildJsonObject { put("requestId", "m2"); put("ok", false); put("cancelled", true) }))
        assertNull(r.facts.mistakeContinuedThroughIteration)
        assertTrue(r.facts.questions.isEmpty())
    }

    // ---- responder: dedupe, unknown capabilities ----------------------------------------------

    @Test
    fun `an unknown capability for this client is refused once, by requestId`() = runTest {
        val r = rig()
        val request = requested("x1", "checkpoint.create")
        r.deliver(request)
        // A replay or a second subscription delivers the same request again.
        r.contributions.observe(request)
        r.deliver(requested("x1", "checkpoint.create"))
        runCurrent()
        assertEquals(listOf(Respond("x1", false, null, "Unsupported capability: checkpoint.create")), r.responds)
    }

    @Test
    fun `requests for another client and askQuestion get no answer from the responder`() = runTest {
        val r = rig()
        r.deliver(requested("x1", "checkpoint.create", target = "cli-tui"))
        r.deliver(requested("q1", HubCapabilities.ASK_QUESTION))
        r.deliver(switchRequested("c-other", target = "cli-tui"))
        runCurrent()
        assertTrue(r.responds.isEmpty())
    }

    @Test
    fun `a mistake limit already asked is not answered twice on replay`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        r.deliver(resolved("m1", MistakeLimitPrompt.AUTO_CONTINUE))
        val again = mistake("m2", 2)
        r.deliver(again)
        r.contributions.observe(again)
        runCurrent()
        assertEquals(listOf(Respond("m2", true, MistakeLimitPrompt.AUTO_CONTINUE, null)), r.responds)
    }

    // ---- switch_to_act_mode ------------------------------------------------------------------

    @Test
    fun `a plan turn gets the CLI success text and continues in act after completed`() = runTest {
        val r = rig()
        r.runtimeMode(PromptMode.Plan)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf(Respond("c1", true, JsonPrimitive(ActModeSwitch.SUCCESS), null)), r.responds)
        assertEquals(
            "You successfully switched to act mode, proceed with the plan. You now have access to editing files and running " +
                "commands. (The switch_to_act_mode tool is only available in plan mode.)",
            ActModeSwitch.SUCCESS,
        )
        assertTrue(r.contributions.hasPendingSwitch(sid))
        assertTrue("nothing flips before the run ends", r.flips.isEmpty() && r.sends.isEmpty())

        r.deliver(runCompleted("r1"))
        runCurrent()
        assertEquals(listOf(sid to PromptMode.Act), r.modes)
        assertEquals(listOf(sid), r.flips)
        // The plan-mode runtime has no editor: it is rebuilt in act mode, and only then does the plan continue.
        assertEquals(listOf(PromptMode.Act), r.restarts)
        assertEquals(listOf("respond:c1", "restart:act", "send:act:direct"), r.log)
        assertEquals(
            listOf(SendRequest(sid, "The user approved switching to act mode. Continue with the approved plan now.", mode = PromptMode.Act, synthetic = true)),
            r.sends,
        )
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    @Test
    fun `a session whose runtime is already in act mode continues without a rebuild`() = runTest {
        // A session created before the tool became plan-only (or rebuilt in act by another path).
        val r = rig()
        r.runtimeMode(PromptMode.Act)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertTrue(r.restarts.isEmpty())
        assertEquals(listOf("respond:c1", "send:act:direct"), r.log)
    }

    @Test
    fun `a failed rebuild sends no continuation`() = runTest {
        val r = rig(rebuild = { false })
        r.runtimeMode(PromptMode.Plan)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertEquals(listOf(PromptMode.Act), r.restarts)
        assertTrue("the plan never continues on a runtime without the editor", r.sends.isEmpty())
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    @Test
    fun `a user toggle during the rebuild cancels the continuation`() = runTest {
        val rebuilt = CompletableDeferred<Boolean>()
        val r = rig(rebuild = { rebuilt.await() })
        r.runtimeMode(PromptMode.Plan)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertEquals(listOf(PromptMode.Act), r.restarts)
        assertTrue(r.contributions.hasPendingSwitch(sid))
        r.contributions.userToggled(sid)
        rebuilt.complete(true)
        runCurrent()
        assertTrue(r.sends.isEmpty())
    }

    @Test
    fun `a session that is no longer idle when the run ends is neither rebuilt nor continued`() = runTest {
        val waiting = QueuedPrompt("p1", "later", "queue", 0)
        val newTurn = TurnRecord(requestId = "r2", sessionId = sid, sentAt = 1L)
        // A prompt waits in the Hub's queue (it runs next on this runtime, which a rebuild would
        // drop), or the user sent a message right after the run ended.
        for (case in listOf("queued", "sent")) {
            val r = rig()
            r.runtimeMode(PromptMode.Plan)
            r.ownTurn("r1", PromptMode.Plan)
            r.deliver(runStarted("r1"))
            r.deliver(switchRequested("c-$case"))
            r.deliver(runCompleted("r1"))
            val s = r.flow.value
            r.flow.value = if (case == "queued") s.copy(facts = s.facts.copy(pendingPrompts = listOf(waiting))) else s.copy(turns = s.turns + newTurn)
            runCurrent()
            assertTrue(case, r.restarts.isEmpty())
            assertTrue(case, r.sends.isEmpty())
            assertTrue(case, r.flips.isEmpty())
            assertFalse(case, r.contributions.hasPendingSwitch(sid))
        }
    }

    @Test
    fun `a run that does not complete gives no continuation`() = runTest {
        val ends = listOf(
            { runAborted("r1") },
            { runFailed("r1") },
            { runCompleted("r1", reason = "aborted") },
            { ev(HubEvents.RUN_INTERRUPTED, buildJsonObject { put("requestId", "r1") }) },
        )
        for ((index, end) in ends.withIndex()) {
            val r = rig()
            r.ownTurn("r1", PromptMode.Plan)
            r.deliver(runStarted("r1"))
            r.deliver(switchRequested("c$index"))
            runCurrent()
            assertTrue(r.contributions.hasPendingSwitch(sid))
            val event = end()
            r.deliver(event)
            runCurrent()
            assertTrue(event.event, r.sends.isEmpty())
            assertTrue(event.event, r.modes.isEmpty() && r.flips.isEmpty())
            assertFalse(event.event, r.contributions.hasPendingSwitch(sid))
        }
    }

    @Test
    fun `a user toggle between the switch and the end of the run cancels the continuation`() = runTest {
        val r = rig()
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        runCurrent()
        r.contributions.userToggled(sid)
        assertFalse(r.contributions.hasPendingSwitch(sid))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertTrue(r.sends.isEmpty())
        assertTrue(r.flips.isEmpty())
    }

    @Test
    fun `an act turn gets Already in act mode and nothing continues`() = runTest {
        val r = rig()
        r.ownTurn("r1", PromptMode.Act)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf(Respond("c1", false, null, "Already in act mode.")), r.responds)
        assertFalse(r.contributions.hasPendingSwitch(sid))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertTrue(r.sends.isEmpty())
    }

    @Test
    fun `a switch the Hub ignored (already resolved, replayed) never continues`() = runTest {
        val r = rig(reply = { buildJsonObject { put("ignored", true) } })
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertEquals(1, r.responds.size)
        assertTrue(r.sends.isEmpty())
        assertTrue(r.flips.isEmpty())
    }

    @Test
    fun `a switch in a run the app cannot follow is refused, not claimed`() = runTest {
        val r = rig()
        r.runtimeMode(PromptMode.Plan)
        r.deliver(runStarted("tui-1", by = "cli-tui"))
        r.deliver(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf(Respond("c1", false, null, ActModeSwitch.UNAVAILABLE)), r.responds)
        assertFalse(r.contributions.hasPendingSwitch(sid))
        r.deliver(runCompleted("tui-1"))
        runCurrent()
        assertTrue(r.sends.isEmpty())
        assertTrue(r.restarts.isEmpty())
    }

    // ---- switch_to_act_mode in a drained queued prompt (no run.* events) ------------------------

    /** What the store holds once a queued prompt drained: an open `q:<id>` turn in the mode it was queued in. */
    private fun Rig.drained(promptId: String, mode: PromptMode) {
        val key = SessionFactsReducer.queuedRunKey(promptId)
        val s = flow.value
        flow.value = s.copy(
            facts = s.facts.copy(runPhase = RunPhase.Running, status = SessionStatus.Running, activeRun = null),
            turns = s.turns.map { if (it.open) it.copy(open = false, outcome = TurnOutcome.Completed) else it } +
                TurnRecord(requestId = key, sessionId = sid, prompt = "looks good, go ahead", mode = mode.wire, started = true, sentAt = 2L, ours = false),
        )
    }

    /** The store closes the queued turn with [outcome] when the loop settles (`session.updated` idle). */
    private fun Rig.settleQueued(outcome: TurnOutcome) {
        val s = flow.value
        flow.value = s.copy(
            facts = s.facts.copy(runPhase = RunPhase.Idle, status = SessionStatus.Idle, activeRun = null),
            turns = s.turns.map { if (it.open) it.copy(open = false, outcome = outcome) else it },
        )
    }

    @Test
    fun `a plan prompt queued behind a run switches and continues when its queued run completes`() = runTest {
        val r = rig()
        r.runtimeMode(PromptMode.Plan)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        // The user typed "looks good, go ahead" while the plan streamed: it waited in the queue, then drained.
        r.deliver(runCompleted("r1"))
        r.drained("p1", PromptMode.Plan)
        assertEquals(PromptMode.Plan, runMode(r.flow.value))
        r.deliver(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf(Respond("c1", true, JsonPrimitive(ActModeSwitch.SUCCESS), null)), r.responds)
        assertTrue(r.contributions.hasPendingSwitch(sid))
        assertTrue("nothing before the queued run ends", r.sends.isEmpty() && r.restarts.isEmpty())

        r.settleQueued(TurnOutcome.Completed)
        runCurrent()
        assertEquals(listOf("respond:c1", "restart:act", "send:act:direct"), r.log)
        assertEquals(listOf(sid), r.flips)
        assertEquals(ActModeSwitch.CONTINUATION_PROMPT, r.sends.single().prompt)
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    @Test
    fun `a queued run that is stopped or interrupted does not continue`() = runTest {
        for (outcome in listOf(TurnOutcome.Aborted, TurnOutcome.Failed, TurnOutcome.Interrupted)) {
            val r = rig()
            r.runtimeMode(PromptMode.Plan)
            r.drained("p1", PromptMode.Plan)
            r.deliver(switchRequested("c-$outcome"))
            runCurrent()
            assertTrue(r.contributions.hasPendingSwitch(sid))
            r.settleQueued(outcome)
            runCurrent()
            assertTrue("$outcome", r.sends.isEmpty() && r.restarts.isEmpty())
            assertFalse("$outcome", r.contributions.hasPendingSwitch(sid))
        }
    }

    @Test
    fun `the late run_completed of the run before a drained prompt does not end its switch`() = runTest {
        val r = rig()
        r.runtimeMode(PromptMode.Plan)
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.drained("p1", PromptMode.Plan)
        // The drained prompt keeps the direct run's activeRun until that run's late terminal.
        r.flow.value = r.flow.value.copy(facts = r.flow.value.facts.copy(activeRun = RunRef("r1", "run-r1", 0L, clientId, ours = true)))
        r.deliver(switchRequested("c1"))
        runCurrent()
        r.deliver(runCompleted("r1"))
        runCurrent()
        assertTrue("still waiting for the queued run", r.contributions.hasPendingSwitch(sid))
        assertTrue(r.sends.isEmpty())
        r.settleQueued(TurnOutcome.Completed)
        runCurrent()
        assertEquals(listOf("respond:c1", "restart:act", "send:act:direct"), r.log)
    }

    @Test
    fun `a prompt queued in act mode gets Already in act mode`() = runTest {
        val r = rig()
        r.runtimeMode(PromptMode.Plan)
        r.drained("p1", PromptMode.Act)
        r.deliver(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf(Respond("c1", false, null, ActModeSwitch.ALREADY_ACT)), r.responds)
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    @Test
    fun `the end of another run drops a pending switch without continuing`() = runTest {
        val r = rig()
        r.ownTurn("r1", PromptMode.Plan)
        r.deliver(runStarted("r1"))
        r.deliver(switchRequested("c1"))
        runCurrent()
        // The switch's run was lost (Hub restart); a different run completes.
        r.flow.value = r.flow.value.copy(facts = r.flow.value.facts.copy(activeRun = r.facts.activeRun!!.copy(requestId = "r9")))
        r.deliver(runCompleted("r9"))
        runCurrent()
        assertTrue(r.sends.isEmpty())
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    // ---- the user's mistake-limit answer (app scope, timer stopped first) ----------------------

    @Test
    fun `an answer started at 4_59 is not overtaken by the timeout`() = runTest {
        val steered = CompletableDeferred<SyntheticSendResult>()
        val r = rig(synthetic = { steered.await() })
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        runCurrent()
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS - 1_000)
        runCurrent()
        val decision = MistakeLimitPrompt.decide(r.facts.questions.getValue("m1").mistakeLimit, "1")
        val answered = async { r.contributions.answerMistakeLimit(sid, "m1", decision) }
        runCurrent()
        assertFalse("the user's answer stops the timer at once", r.contributions.hasMistakeTimer("m1"))
        assertEquals("the steer is out", Delivery.Steer, r.sends.single().delivery)
        // The steer's round trip outlasts the five minutes: the timer must not stop the run now.
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS)
        runCurrent()
        assertTrue(r.responds.isEmpty())
        steered.complete(SyntheticSendResult.Accepted("steer"))
        runCurrent()
        assertEquals(listOf(Respond("m1", true, MistakeLimitPrompt.AUTO_CONTINUE, null)), r.responds)
        assertFalse(answered.await())
    }

    @Test
    fun `leaving the chat screen after the steer still sends the reply`() = runTest {
        val steered = CompletableDeferred<SyntheticSendResult>()
        val r = rig(synthetic = { steered.await() })
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        runCurrent()
        // The chat screen's controller scope answers, then goes away while the steer is in flight.
        val controller = Job()
        launch(controller) { r.contributions.answerMistakeLimit(sid, "m1", MistakeLimitDecision.Continue("guidance")) }
        runCurrent()
        assertEquals(1, r.sends.size)
        controller.cancel()
        runCurrent()
        steered.complete(SyntheticSendResult.Accepted("steer"))
        runCurrent()
        assertEquals(listOf(Respond("m1", true, MistakeLimitPrompt.AUTO_CONTINUE, null)), r.responds)
        assertEquals(listOf("send:act:steer", "respond:m1"), r.log)
    }

    @Test
    fun `an answer that fails re-arms the timeout while the question is open`() = runTest {
        var failures = 1
        val r = rig(reply = { id ->
            if (failures-- > 0) throw HubCommandException("capability.respond", "client_timeout", "no reply")
            buildJsonObject { put("requestId", id); put("ok", true) }
        })
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3, details = "bad call"))
        runCurrent()
        val failed = runCatching { r.contributions.answerMistakeLimit(sid, "m1", MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON)) }
        assertTrue(failed.exceptionOrNull() is HubCommandException)
        runCurrent()
        assertTrue("still unanswered: the timeout runs again", r.contributions.hasMistakeTimer("m1"))
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS)
        runCurrent()
        assertEquals(MistakeLimitPrompt.timeoutResult(MistakeLimitContext(3, 6, 6, "tool_error", "bad call")), r.responds.last().result)
    }

    // ---- A17: the transcript's stop notice --------------------------------------------------------

    private fun ignoredReply(id: String) = buildJsonObject { put("requestId", id); put("ignored", true) }

    @Test
    fun `a stop the Hub took leaves a notice with the count and the kernel's detail (A17)`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3, details = "  Tool no_such_tool does not exist  "))
        runCurrent()
        advanceTimeBy(42_000)
        runCurrent()
        assertFalse(r.contributions.answerMistakeLimit(sid, "m1", MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON)))
        assertEquals(
            listOf(StopNotice(sid, "m1", StopNoticeKind.Stopped, mistakes = 6, detail = "Tool no_such_tool does not exist", at = 42_000L)),
            r.notices,
        )
        assertEquals("the Hub's reply first, then the notice", listOf("respond:m1", "notice:Stopped"), r.log)
    }

    @Test
    fun `a stop the Hub ignored, an answer that failed, and a continue leave no notice (A17)`() = runTest {
        val ignored = rig(reply = { id -> ignoredReply(id) })
        ignored.deliver(runStarted())
        ignored.deliver(mistake("m1", 3))
        runCurrent()
        assertTrue(ignored.contributions.answerMistakeLimit(sid, "m1", MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON)))
        assertTrue(ignored.notices.isEmpty())

        val failing = rig(reply = { throw HubCommandException("capability.respond", "client_timeout", "no reply") })
        failing.deliver(runStarted())
        failing.deliver(mistake("m2", 3))
        runCurrent()
        val failed = runCatching { failing.contributions.answerMistakeLimit(sid, "m2", MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON)) }
        assertTrue(failed.exceptionOrNull() is HubCommandException)
        assertTrue(failing.notices.isEmpty())

        val continued = rig()
        continued.deliver(runStarted())
        continued.deliver(mistake("m3", 3))
        runCurrent()
        assertFalse(continued.contributions.answerMistakeLimit(sid, "m3", MistakeLimitDecision.Continue("guidance")))
        assertTrue(continued.notices.isEmpty())
    }

    @Test
    fun `the timeout's stop leaves a timed-out notice unless the Hub ignored it (A17)`() = runTest {
        val r = rig()
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        runCurrent()
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS)
        runCurrent()
        assertEquals(
            listOf(StopNotice(sid, "m1", StopNoticeKind.TimedOut, mistakes = 6, detail = "", at = MistakeLimitPrompt.TIMEOUT_MS)),
            r.notices,
        )

        val late = rig(reply = { id -> ignoredReply(id) })
        late.deliver(runStarted())
        late.deliver(mistake("m2", 3))
        runCurrent()
        advanceTimeBy(MistakeLimitPrompt.TIMEOUT_MS)
        runCurrent()
        assertEquals("the timeout answered", 1, late.responds.size)
        assertTrue(late.notices.isEmpty())
    }

    @Test
    fun `a notice the store could not keep never fails the answer (A17)`() = runTest {
        val r = rig(addNotice = { error("disk full") })
        r.deliver(runStarted())
        r.deliver(mistake("m1", 3))
        runCurrent()
        assertFalse(r.contributions.answerMistakeLimit(sid, "m1", MistakeLimitDecision.Stop(MistakeLimitPrompt.STOP_REASON)))
        assertEquals(1, r.notices.size)
        assertFalse("answered: no timeout is left", r.contributions.hasMistakeTimer("m1"))
    }

    @Test
    fun `the synthetic continuation prompt is recognised only verbatim`() {
        assertTrue(ActModeSwitch.isContinuationPrompt(ActModeSwitch.CONTINUATION_PROMPT))
        assertFalse(ActModeSwitch.isContinuationPrompt("Continue with the approved plan now."))
        assertFalse(ActModeSwitch.isContinuationPrompt(ActModeSwitch.CONTINUATION_PROMPT + " please"))
    }
}
