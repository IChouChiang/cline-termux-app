package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic prompts ([SendRequest.synthetic], `SessionStore.sendSynthetic`): the G2 guidance
 * steer, whose Hub reply decides the mistake-limit answer (desktop `await manager.send()`), and
 * the act-mode continuation, which must never reach the composer's draft, a snackbar or an
 * error row, whatever happens to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyntheticSendTest {
    private val clock = FakeClock()
    private val sid = "s1"
    private var lifetime: String? = "1:a"

    private fun TestScope.newStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = { lifetime },
    )

    private fun TestScope.collectSignals(store: SessionStore): List<SessionSignal> {
        val signals = mutableListOf<SessionSignal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.signals.collect { signals += it } }
        return signals
    }

    private fun event(name: String, sequence: Long, payload: String, sessionId: String = sid) = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = sequence,
        timestamp = 1_000L + sequence,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    private val resumeConfig = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m")
    private val continuation = SendRequest(sid, ActModeSwitch.CONTINUATION_PROMPT, mode = PromptMode.Act, config = resumeConfig, synthetic = true)

    /** The guidance steer the mistake-limit answer sends, with the store's real reply handling. */
    private suspend fun answerContinue(store: SessionStore, respond: suspend (JsonObject) -> Boolean): Boolean =
        MistakeLimitPrompt.answer(
            MistakeLimitDecision.Continue("The run reached the limit … Do not repeat the same call."),
            steer = { guidance -> MistakeLimitPrompt.steerFailure(store.sendSynthetic(MistakeLimitPrompt.steerRequest(store.state(sid).value, guidance))) },
            respond = respond,
        )

    private fun assertNothingForTheUser(store: SessionStore, signals: List<SessionSignal>) {
        assertTrue("no draft, no snackbar: $signals", signals.none { it is SessionSignal.NotSent || it is SessionSignal.SendFailed })
        assertNull(store.takeReturnedPrompt(sid))
        assertTrue(store.state(sid).value.messages.none { it.role == Role.Error || it.role == Role.User })
    }

    // ---- G2 steer ----------------------------------------------------------------------------

    @Test
    fun `a rejected steer reply answers stop with the Hub's text`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        val results = mutableListOf<JsonObject>()
        val answered = async { answerContinue(store) { results += it; false } }
        runCurrent()
        // The steer frame is written; the decision waits for the Hub's reply to it.
        val steer = hub.sends.single()
        assertEquals("steer", steer.delivery)
        assertTrue("no decision before the reply", results.isEmpty())

        steer.fail(HubErrorCodes.COMMAND_FAILED, "Session s1 is not running")
        runCurrent()
        assertFalse(answered.await())
        assertEquals(
            """{"action":"stop","reason":"Could not send recovery guidance: Session s1 is not running"}""",
            results.single().toString(),
        )
        assertEquals("no recovery resend for a steer", 1, hub.sends.size)
        assertNothingForTheUser(store, signals)
    }

    @Test
    fun `an accepted steer answers continue only after the Hub's reply`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val results = mutableListOf<JsonObject>()
        val answered = async { answerContinue(store) { results += it; false } }
        runCurrent()
        assertTrue(results.isEmpty())
        hub.sends.single().ok()
        runCurrent()
        answered.await()
        assertEquals("""{"action":"continue"}""", results.single().toString())
    }

    @Test
    fun `a steer on a kernel without the prompt queue never falls back to a direct send`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        val result = async { store.sendSynthetic(SendRequest(sid, "guidance", delivery = Delivery.Steer)) }
        runCurrent()
        hub.sends.single().fail(HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE, "no prompt queue")
        runCurrent()
        assertEquals(SyntheticSendResult.Refused(HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE, "no prompt queue"), result.await())
        assertEquals(1, hub.sends.size)
        assertTrue(store.state(sid).value.turns.isEmpty())
        assertNothingForTheUser(store, signals)
    }

    @Test
    fun `a synthetic send while reconnecting is not held and nothing goes out afterwards`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SyntheticSendResult.NotConnected, store.sendSynthetic(SendRequest(sid, "guidance", delivery = Delivery.Steer)))
        assertEquals(SyntheticSendResult.NotConnected, store.sendSynthetic(continuation))

        hub.connection.value = HubConnectionState.Connected("ws://127.0.0.1:25463/hub")
        runCurrent()
        assertTrue("never held, so never flushed", hub.sends.isEmpty())
        hub.connection.value = HubConnectionState.Unavailable("gone")
        runCurrent()
        assertEquals(SyntheticSendResult.NotConnected, store.sendSynthetic(continuation))
        assertNothingForTheUser(store, signals)
    }

    // ---- act-mode continuation ----------------------------------------------------------------

    @Test
    fun `the continuation opens its turn without a bubble`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val result = async { store.sendSynthetic(continuation) }
        runCurrent()
        val sent = hub.sends.single()
        assertEquals(ActModeSwitch.CONTINUATION_PROMPT, sent.prompt)
        assertEquals("act", sent.mode)
        assertNull(sent.delivery)
        sent.ok()
        runCurrent()
        assertEquals(SyntheticSendResult.Accepted(sent.requestId), result.await())
        val state = store.state(sid).value
        assertTrue(state.messages.isEmpty())
        val turn = state.turns.single()
        assertTrue(turn.open && turn.synthetic)
        assertEquals(RunPhase.Starting, state.facts.runPhase)
    }

    @Test
    fun `a refused continuation leaves no error row and no draft`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        val result = async { store.sendSynthetic(continuation) }
        runCurrent()
        hub.sends.single().fail(HubErrorCodes.COMMAND_FAILED, "provider not configured")
        runCurrent()
        assertEquals(SyntheticSendResult.Refused(HubErrorCodes.COMMAND_FAILED, "provider not configured"), result.await())
        val state = store.state(sid).value
        assertTrue("the turn that never ran is dropped", state.turns.isEmpty())
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertNothingForTheUser(store, signals)
    }

    @Test
    fun `a continuation whose session is gone and cannot be resumed never reaches the composer draft`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "internal_error", "history gone") }
        val result = async { store.sendSynthetic(continuation) }
        runCurrent()
        hub.sends.single().fail(HubErrorCodes.SESSION_NOT_FOUND, "session not found: s1")
        runCurrent()
        assertTrue(result.await() is SyntheticSendResult.ResumeFailed)
        assertEquals(1, hub.sends.size)
        assertTrue(store.state(sid).value.turns.isEmpty())
        assertNothingForTheUser(store, signals)
    }

    @Test
    fun `a continuation whose session is gone is resumed and resent like any direct send`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val result = async { store.sendSynthetic(continuation) }
        runCurrent()
        hub.sends.single().fail(HubErrorCodes.SESSION_NOT_FOUND, "session not found: s1")
        runCurrent()
        assertEquals(sid, hub.creates.single().spec.resumeSessionId)
        val resent = hub.sends[1]
        assertEquals(ActModeSwitch.CONTINUATION_PROMPT, resent.prompt)
        resent.ok()
        runCurrent()
        assertEquals(SyntheticSendResult.Accepted(resent.requestId), result.await())
        assertTrue(store.state(sid).value.messages.isEmpty())
    }

    @Test
    fun `a continuation lost with the old socket is dropped on reconnect, not returned to the draft`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        val result = async { store.sendSynthetic(continuation) }
        runCurrent()
        // The frame was written, then the socket dropped before the Hub answered.
        hub.sends.single().fail(HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed")
        runCurrent()
        assertEquals(SyntheticSendResult.Refused(HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed"), result.await())
        assertTrue("the event stream decides, as for any lost reply", store.state(sid).value.turns.single().open)

        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        hub.hubStatus.value = HubLiveStatus(draining = false, activeRpcTurns = 0, pendingRuns = 0, lastSequence = 4, idle = true)
        hub.connection.value = HubConnectionState.Connected("ws://127.0.0.1:25463/hub")
        runCurrent()
        // The replay drains without the turn's run.started: judged undelivered (HC §3.12 step 7).
        store.dispatch(event(HubEvents.RUN_HEARTBEAT, 4, "{}", sessionId = "s0"))
        runCurrent()
        assertTrue(store.state(sid).value.turns.isEmpty())
        assertNothingForTheUser(store, signals)
    }
}
