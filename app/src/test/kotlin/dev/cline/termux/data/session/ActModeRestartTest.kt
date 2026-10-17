package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateBuilder
import dev.cline.termux.data.chat.SessionCreateRequest
import dev.cline.termux.data.sessions.SessionEventTap
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11 row 4 through the real [SessionStore]: a plan session's runtime has no editor (the Hub
 * builds the tool preset from the create-time `sessionConfig.mode`), so an approved
 * `switch_to_act_mode` rebuilds the session in act mode the way the CLI's `applyMode` does
 * (brief F2: `session.detach`, then `session.create` with the same id and the history) before
 * the continuation's `send_input` goes out. Also the queued case: a plan prompt queued behind
 * a run keeps its mode, and its drained run continues when it settles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActModeRestartTest {
    private val clientId = "android-test"
    private val sid = "s1"
    private var seq = 0L

    private class Rig(val hub: FakeSessionHub, val store: SessionStore, val contributions: ClientContributions, val responds: MutableList<Pair<String, Boolean>>)

    private fun TestScope.rig(): Rig {
        val hub = FakeSessionHub(clientId)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val responds = mutableListOf<Pair<String, Boolean>>()
        lateinit var store: SessionStore
        val contributions = ClientContributions(
            clientId = clientId,
            hub = { requestId, ok, _, _ ->
                responds += requestId to ok
                buildJsonObject { put("requestId", requestId); put("ok", true) }
            },
            sessions = object : ClientContributions.Sessions {
                override fun state(sessionId: String) = store.state(sessionId)
                override suspend fun sendSynthetic(request: SendRequest) = store.sendSynthetic(request)
                override suspend fun restart(sessionId: String, mode: PromptMode) = store.restart(sessionId, mode).isSuccess
            },
            persistMode = { _, _ -> },
            scope = backgroundScope,
            dispatcher = dispatcher,
        )
        // The production wiring: the pump hands each event to the responder before the store applies it.
        store = SessionStore(
            hub = SessionEventTap(hub) { contributions.observe(it) },
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = dispatcher,
            clock = FakeClock(),
            hubLifetimeKey = { "1:a" },
        )
        // The Hub's record: the session id, its workspace and model, and `metadata.mode` from the create's config.
        hub.onCreate = { request -> record(request) }
        store.start()
        runCurrent()
        return Rig(hub, store, contributions, responds)
    }

    private fun record(request: SessionCreateRequest): JsonObject = buildJsonObject {
        putJsonObject("session") {
            put("sessionId", request.spec.resumeSessionId ?: sid)
            put("status", "idle")
            put("workspaceRoot", request.spec.workspaceRoot)
            put("cwd", request.spec.cwd)
            putJsonObject("metadata") {
                put("source", request.spec.source)
                put("provider", request.spec.providerId)
                put("model", request.spec.modelId)
                put("mode", request.spec.mode)
            }
        }
    }

    private fun event(name: String, payload: String) = HubEventEnvelope(
        event = name,
        sessionId = sid,
        sequence = ++seq,
        timestamp = 1_000L + seq,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    private suspend fun Rig.emit(vararg events: HubEventEnvelope) {
        events.forEach { hub.events.emit(it) }
    }

    private fun switchRequested(requestId: String) = event(
        HubEvents.CAPABILITY_REQUESTED,
        """{"requestId":"$requestId","sessionId":"$sid","targetClientId":"$clientId","capabilityName":"${HubCapabilities.SWITCH_TO_ACT_MODE}",
           "payload":{"input":{},"context":{"toolCallId":"call_switch","iteration":1}}}""",
    )

    private val config = SessionConfigInput(workspaceRoot = "/w", providerId = "openai-compatible", modelId = "mock-1", mode = "plan")

    private fun contributionNames(request: SessionCreateRequest): List<String> =
        request.payload()["runtimeOptions"]!!.jsonObject["clientContributions"]!!.jsonArray.map { it.jsonObject["capabilityName"]!!.jsonPrimitive.content }

    private fun JsonElement?.stringAt(key: String): String? = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.content }

    /** A plan session from Home: the first send's create, then the plan prompt as a direct send. */
    private suspend fun TestScope.planSession(r: Rig): String {
        assertTrue(r.store.create(SessionCreateBuilder.firstSend(config, title = "[test] plan")).isSuccess)
        runCurrent()
        assertEquals("plan", r.store.state(sid).value.facts.mode)
        assertEquals("the plan runtime gets the tool", HubCapabilities.SWITCH_TO_ACT_MODE, contributionNames(r.hub.creates.single()).last())
        val requestId = (r.store.send(SendRequest(sid, "make a plan", mode = PromptMode.Plan)) as SendOutcome.Sent).requestId
        runCurrent()
        return requestId
    }

    @Test
    fun `a plan session is rebuilt in act mode before the continuation goes out`() = runTest {
        val r = rig()
        val plan = planSession(r)
        r.emit(
            event(HubEvents.RUN_STARTED, """{"requestId":"$plan","clientId":"$clientId"}"""),
            event(HubEvents.SESSION_UPDATED, """{"snapshot":{"status":"running"}}"""),
            switchRequested("c1"),
        )
        runCurrent()
        assertEquals(listOf("c1" to true), r.responds)
        r.hub.sends.single().ok()
        r.emit(
            event(HubEvents.CAPABILITY_RESOLVED, """{"requestId":"c1","ok":true}"""),
            event(HubEvents.RUN_COMPLETED, """{"reason":"completed","snapshot":{"status":"idle"}}"""),
        )
        runCurrent()

        // session.detach, then session.create with the same id, the history and sessionConfig.mode act ...
        val rebuild = r.hub.creates.last()
        assertEquals(2, r.hub.creates.size)
        assertEquals(sid, rebuild.spec.resumeSessionId)
        assertEquals("act", rebuild.payload()["sessionConfig"].stringAt("mode"))
        assertTrue(rebuild.payload().containsKey("initialMessages"))
        assertEquals("the act runtime drops the tool", listOf(HubCapabilities.ASK_QUESTION, HubCapabilities.MISTAKE_LIMIT), contributionNames(rebuild))
        // ... and only then the continuation, synthetic, with mode act.
        val calls = r.hub.calls
        val detach = calls.indexOf("session.detach")
        val create = calls.lastIndexOf("session.create")
        val continuation = calls.lastIndexOf("session.send_input")
        assertTrue("$calls", detach in 0 until create)
        assertTrue("$calls", create < continuation)
        val sent = r.hub.sends.last()
        assertEquals(ActModeSwitch.CONTINUATION_PROMPT, sent.prompt)
        assertEquals("act", sent.mode)
        assertEquals(null, sent.delivery)
        val state = r.store.state(sid).value
        assertEquals("act", state.facts.mode)
        assertTrue("no bubble for the synthetic prompt", state.messages.none { it.content == ActModeSwitch.CONTINUATION_PROMPT })
        assertFalse(r.contributions.hasPendingSwitch(sid))
    }

    @Test
    fun `a send that arrives during the rebuild waits for the new runtime`() = runTest {
        val r = rig()
        val plan = planSession(r)
        r.hub.sends.single().ok()
        r.emit(
            event(HubEvents.RUN_STARTED, """{"requestId":"$plan","clientId":"$clientId"}"""),
            event(HubEvents.RUN_COMPLETED, """{"reason":"completed","snapshot":{"status":"idle"}}"""),
        )
        runCurrent()
        // The rebuild's create is in flight when the user sends: the prompt waits for its reply.
        val createReply = CompletableDeferred<Unit>()
        r.hub.createGate = createReply
        val rebuild = backgroundScope.async(StandardTestDispatcher(testScheduler)) { r.store.restart(sid, PromptMode.Act) }
        runCurrent()
        val typed = backgroundScope.async(StandardTestDispatcher(testScheduler)) { r.store.send(SendRequest(sid, "next", mode = PromptMode.Act)) }
        runCurrent()
        assertEquals("nothing goes to the old runtime", 1, r.hub.sends.size)
        createReply.complete(Unit)
        runCurrent()
        assertTrue(rebuild.await().isSuccess)
        assertTrue(typed.await() is SendOutcome.Sent)
        assertEquals("next", r.hub.sends.last().prompt)
        val calls = r.hub.calls
        assertTrue("$calls", calls.lastIndexOf("session.create") < calls.lastIndexOf("session.send_input"))
        assertEquals("act", r.store.state(sid).value.facts.mode)
    }

    @Test
    fun `a busy session is not rebuilt`() = runTest {
        val r = rig()
        val plan = planSession(r)
        r.emit(event(HubEvents.RUN_STARTED, """{"requestId":"$plan","clientId":"$clientId"}"""))
        runCurrent()
        val creates = r.hub.creates.size
        assertTrue(r.store.restart(sid, PromptMode.Act).isFailure)
        assertEquals(creates, r.hub.creates.size)
        assertFalse(r.hub.calls.contains("session.detach"))
    }

    @Test
    fun `a plan prompt queued behind a run continues in act when its drained run settles`() = runTest {
        val r = rig()
        val first = planSession(r)
        // "looks good, go ahead" typed while the first reply streamed: queued, in plan mode.
        val queued = (r.store.send(SendRequest(sid, "looks good, go ahead", mode = PromptMode.Plan, delivery = Delivery.Queue)) as SendOutcome.Queued).requestId
        runCurrent()
        r.hub.sends.forEach { it.ok() }
        r.emit(
            event(HubEvents.RUN_STARTED, """{"requestId":"$first","clientId":"$clientId"}"""),
            event(HubEvents.SESSION_UPDATED, """{"snapshot":{"status":"running"}}"""),
            event(HubEvents.RUN_STARTED, """{"requestId":"$queued","clientId":"$clientId"}"""),
            event(HubEvents.SESSION_PENDING_PROMPTS, """{"prompts":[{"id":"p1","prompt":"looks good, go ahead","delivery":"queue"}]}"""),
            event(HubEvents.SESSION_UPDATED, """{"snapshot":{"status":"idle"}}"""),
            event(HubEvents.SESSION_PENDING_PROMPTS, """{"prompts":[]}"""),
            event(HubEvents.SESSION_PENDING_PROMPT_SUBMITTED, """{"prompt":{"id":"p1","prompt":"looks good, go ahead"}}"""),
            // The first run's terminal arrives after the drained prompt took over (docs/08).
            event(HubEvents.RUN_COMPLETED, """{"reason":"completed","snapshot":{"status":"idle"}}"""),
            event(HubEvents.SESSION_UPDATED, """{"snapshot":{"status":"running"}}"""),
        )
        runCurrent()
        val drained = r.store.state(sid).value.turns.single { it.requestId == "q:p1" }
        assertTrue(drained.open)
        assertEquals("the mode the app queued it in", "plan", drained.mode)

        r.emit(switchRequested("c1"))
        runCurrent()
        assertEquals(listOf("c1" to true), r.responds)
        assertTrue(r.contributions.hasPendingSwitch(sid))
        val sendsBefore = r.hub.sends.size
        r.emit(event(HubEvents.SESSION_UPDATED, """{"snapshot":{"status":"idle"}}"""))
        runCurrent()

        val rebuild = r.hub.creates.last()
        assertEquals("act", rebuild.payload()["sessionConfig"].stringAt("mode"))
        assertEquals(sid, rebuild.spec.resumeSessionId)
        assertEquals(sendsBefore + 1, r.hub.sends.size)
        assertEquals(ActModeSwitch.CONTINUATION_PROMPT, r.hub.sends.last().prompt)
        assertEquals("act", r.hub.sends.last().mode)
        val calls = r.hub.calls
        assertTrue("$calls", calls.lastIndexOf("session.create") < calls.lastIndexOf("session.send_input"))
    }

    @Test
    fun `a prompt another client queued runs in the session's mode`() = runTest {
        val r = rig()
        val first = planSession(r)
        r.hub.sends.forEach { it.ok() }
        r.emit(
            event(HubEvents.RUN_STARTED, """{"requestId":"$first","clientId":"$clientId"}"""),
            event(HubEvents.RUN_COMPLETED, """{"reason":"completed","snapshot":{"status":"idle"}}"""),
            event(HubEvents.SESSION_PENDING_PROMPT_SUBMITTED, """{"prompt":{"id":"p7","prompt":"from the terminal"}}"""),
        )
        runCurrent()
        assertEquals("plan", r.store.state(sid).value.turns.single { it.requestId == "q:p7" }.mode)
    }
}
