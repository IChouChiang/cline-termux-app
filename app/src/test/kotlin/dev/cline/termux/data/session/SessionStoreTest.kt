package dev.cline.termux.data.session

import dev.cline.termux.R
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateBuilder
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionStore paths the fixtures do not reach (HC §3.12, CA §0.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStoreTest {
    private val clock = FakeClock()

    /** The connected daemon's `pid:startedAt`; tests change it to simulate a Hub restart (null: unknown, e.g. `Starting`). */
    private var lifetime: String? = "1:a"

    private fun TestScope.newStore(
        hub: FakeSessionHub,
        turns: InMemoryTurnStore = InMemoryTurnStore(),
        compaction: suspend () -> JsonObject? = { null },
    ) = SessionStore(
        hub = hub,
        turnStore = turns,
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = { lifetime },
        compaction = compaction,
    )

    /** Collects the store's one-shot signals for the whole test. */
    private fun TestScope.collectSignals(store: SessionStore): List<SessionSignal> {
        val signals = mutableListOf<SessionSignal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.signals.collect { signals += it } }
        return signals
    }

    private fun status(lastSequence: Long) = HubLiveStatus(draining = false, activeRpcTurns = 0, pendingRuns = 0, lastSequence = lastSequence, idle = true)

    private val connected = HubConnectionState.Connected("ws://127.0.0.1:25463/hub")

    /** A send whose `run.started` arrived: the session has an open, started turn of ours. */
    private suspend fun TestScope.runningTurn(store: SessionStore, hub: FakeSessionHub, sessionId: String = "s1", sequence: Long = 1): String {
        val requestId = (store.send(SendRequest(sessionId, "work")) as SendOutcome.Sent).requestId
        store.dispatch(event(HubEvents.RUN_STARTED, sequence, """{"requestId":"$requestId","clientId":"${hub.clientId}"}""", sessionId))
        runCurrent()
        return requestId
    }

    private fun assertInterrupted(state: SessionState, requestId: String, signals: List<SessionSignal>) {
        val row = state.messages.single { it.id == "i:$requestId" }
        assertEquals(Role.Error, row.role)
        assertEquals(REASON_INTERRUPTED, row.meta.reason)
        assertEquals(requestId, row.meta.requestId)
        assertFalse(state.live_)
        assertEquals(Liveness.Persisted, state.liveness)
        val turn = state.turns.single { it.requestId == requestId }
        assertFalse(turn.open)
        assertEquals(TurnOutcome.Interrupted, turn.outcome)
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertTrue(signals.contains(SessionSignal.Interrupted(state.sessionId, requestId)))
    }

    private fun event(name: String, sequence: Long, payload: String, sessionId: String = "s1") = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = sequence,
        timestamp = 1_000L + sequence,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    @Test
    fun `a send while reconnecting is held once, and nothing is sent without a connection`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "hi")))
        assertEquals(SendOutcome.AlreadyHeld, store.send(SendRequest("s1", "again")))
        hub.connection.value = HubConnectionState.Unavailable("gone")
        assertEquals(SendOutcome.NotConnected, store.send(SendRequest("s2", "hi")))
        assertTrue(hub.sends.isEmpty())
    }

    @Test
    fun `a direct send shows the optimistic bubble, Starting, and a persisted open turn`() = runTest {
        val hub = FakeSessionHub()
        val turns = InMemoryTurnStore()
        val store = newStore(hub, turns)
        val outcome = store.send(SendRequest("s1", "hello"))
        runCurrent()
        val requestId = (outcome as SendOutcome.Sent).requestId
        val state = store.state("s1").value
        assertEquals(RunPhase.Starting, state.facts.runPhase)
        assertEquals(ChatStatus.Starting, state.chatStatus)
        assertEquals(listOf("local:$requestId"), state.messages.map { it.id })
        assertEquals(Role.User, state.messages.single().role)
        assertTrue(state.turns.single().open)

        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"$requestId","clientId":"${hub.clientId}"}"""))
        runCurrent()
        assertEquals(RunPhase.Running, store.state("s1").value.facts.runPhase)
        assertTrue(store.state("s1").value.turns.single().started)
    }

    @Test
    fun `a queued send falls back to a direct send when the prompt queue is unavailable`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val outcome = store.send(SendRequest("s1", "later", delivery = Delivery.Queue))
        assertTrue(outcome is SendOutcome.Queued)
        assertTrue(store.state("s1").value.messages.isEmpty())
        hub.sends.single().fail(HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE)
        runCurrent()
        assertEquals(listOf("queue", null), hub.sends.map { it.delivery })
        val state = store.state("s1").value
        assertTrue(state.facts.queueUnavailable)
        assertEquals(1, state.messages.count { it.role == Role.User })
    }

    @Test
    fun `stop moves the phase to Stopping until run_aborted`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r1","clientId":"tui"}"""))
        assertTrue(store.abort("s1").isSuccess)
        assertEquals(RunPhase.Stopping, store.state("s1").value.facts.runPhase)
        store.dispatch(event(HubEvents.RUN_ABORTED, 2, """{"requestId":"r1"}"""))
        runCurrent()
        val state = store.state("s1").value
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertEquals(ChatStatus.Cancelled, state.chatStatus)
        assertTrue(state.turns.none { it.open })
    }

    // ---- reconnect and interruption (HC §1.3.4, §3.12 steps 6/7) ---------------------------------

    @Test
    fun `sends written after a reconnect are not judged undelivered when the replay drains`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        // Written before the drop, never started: the frame was lost (positive control).
        val lost = (store.send(SendRequest("s3", "lost on the old socket")) as SendOutcome.Sent).requestId
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "held prompt")))

        hub.hubStatus.value = status(lastSequence = 10)
        hub.connection.value = connected
        runCurrent()
        // The held prompt went out on the new socket; another prompt is typed while the replay drains.
        val held = hub.sends.single { it.prompt == "held prompt" }
        val typed = (store.send(SendRequest("s2", "typed during the drain")) as SendOutcome.Sent).requestId
        runCurrent()
        assertTrue(store.state("s1").value.turns.single().open)

        // The replay drains to hub.status.eventLog.lastSequence.
        store.dispatch(event(HubEvents.RUN_HEARTBEAT, 10, "{}", sessionId = "s0"))
        runCurrent()

        for ((sessionId, requestId) in listOf("s1" to held.requestId, "s2" to typed)) {
            val state = store.state(sessionId).value
            assertTrue(state.turns.single { it.requestId == requestId }.open)
            assertTrue(state.messages.any { it.id == "local:$requestId" })
        }
        assertEquals(listOf(SessionSignal.NotSent("s3", "lost on the old socket", emptyList())), signals.filterIsInstance<SessionSignal.NotSent>())
        assertTrue(store.state("s3").value.turns.none { it.requestId == lost })
        assertTrue(store.state("s3").value.messages.isEmpty())

        // The held prompt's run.started joins its own turn; nothing is "started elsewhere".
        store.dispatch(event(HubEvents.RUN_STARTED, 11, """{"requestId":"${held.requestId}","clientId":"${hub.clientId}"}"""))
        runCurrent()
        val turn = store.state("s1").value.turns.single()
        assertTrue(turn.started && turn.ours && turn.open)
    }

    @Test
    fun `an open turn is interrupted once the replay drained after a Hub restart`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        val requestId = runningTurn(store, hub)
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertFalse(store.state("s1").value.live_)

        lifetime = "2:b"
        hub.hubStatus.value = status(lastSequence = 5)
        hub.connection.value = connected
        runCurrent()
        // Not judged before the replay drained: its own terminal event would win.
        assertTrue(store.state("s1").value.turns.single().open)
        assertTrue(signals.isEmpty())

        store.dispatch(event(HubEvents.RUN_HEARTBEAT, 5, "{}", sessionId = "s0"))
        runCurrent()
        assertInterrupted(store.state("s1").value, requestId, signals)
        assertTrue(signals.none { it is SessionSignal.NotSent })
    }

    @Test
    fun `a Hub restart drops the old daemon's prompt queue with the interrupted turn`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.start()
        runCurrent()
        runningTurn(store, hub)
        store.dispatch(event(HubEvents.SESSION_PENDING_PROMPTS, 3, """{"prompts":[{"id":"p1","prompt":"later","delivery":"queue"}]}"""))
        runCurrent()
        assertEquals(1, store.state("s1").value.facts.pendingPrompts.size)
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()

        lifetime = "2:b"
        hub.hubStatus.value = status(lastSequence = 5)
        hub.connection.value = connected
        runCurrent()
        store.dispatch(event(HubEvents.RUN_HEARTBEAT, 5, "{}", sessionId = "s0"))
        runCurrent()
        assertTrue(store.state("s1").value.facts.pendingPrompts.isEmpty())
    }

    @Test
    fun `run_completed disposed on an open turn is the shutdown projection`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        val requestId = runningTurn(store, hub)
        store.dispatch(event(HubEvents.RUN_COMPLETED, 2, """{"requestId":"$requestId","reason":"disposed"}"""))
        runCurrent()
        assertInterrupted(store.state("s1").value, requestId, signals)
    }

    @Test
    fun `session_updated cancelled with endedAt on an open turn is the shutdown projection`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        val requestId = runningTurn(store, hub)
        store.dispatch(event(HubEvents.SESSION_UPDATED, 2, """{"snapshot":{"status":"cancelled","endedAt":"2026-10-03T12:00:05Z"}}"""))
        runCurrent()
        assertInterrupted(store.state("s1").value, requestId, signals)
    }

    @Test
    fun `hub_draining keeps the bubble and resends once the drain ends`() = runTest {
        val hub = FakeSessionHub()
        hub.draining.value = true
        val store = newStore(hub)
        val first = (store.send(SendRequest("s1", "hello")) as SendOutcome.Sent).requestId
        hub.sends.single().fail(HubErrorCodes.HUB_DRAINING)
        runCurrent()
        assertEquals(1, hub.sends.size)
        assertEquals(listOf("local:$first"), store.state("s1").value.messages.map { it.id })

        hub.draining.value = false
        runCurrent()
        assertEquals(listOf("hello", "hello"), hub.sends.map { it.prompt })
        val second = hub.sends[1].requestId
        val state = store.state("s1").value
        val bubble = state.messages.single()
        assertEquals("local:$second", bubble.id)
        assertEquals("local:$first", bubble.renderKey)
        assertEquals(listOf(second), state.turns.map { it.requestId })
        assertTrue(state.turns.single().open)
    }

    /** The re-register HubClient runs after an authority loss, as the store sees it. */
    private fun TestScope.reregister(hub: FakeSessionHub) {
        hub.connection.value = HubConnectionState.Reconnecting(1, "closed 1000: re-register")
        runCurrent()
        hub.connection.value = connected
        runCurrent()
    }

    @Test
    fun `a send refused with client_not_found is held and resent after the re-register, with no error row`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        val first = (store.send(SendRequest("s1", "hi")) as SendOutcome.Sent).requestId
        runCurrent()
        // HF "Same clientId on a new connection": the stale socket's unregister removed the client.
        hub.sends.single().fail(HubErrorCodes.CLIENT_NOT_FOUND, "Client not found: android-test")
        runCurrent()
        val refused = store.state("s1").value
        assertTrue("the refused turn is gone, nothing ran", refused.turns.isEmpty())
        assertTrue(refused.messages.none { it.role == Role.Error })
        assertTrue(refused.messages.none { it.id == "local:$first" })
        assertEquals(RunPhase.Idle, refused.facts.runPhase)
        assertEquals(1, hub.sends.size)

        reregister(hub)
        assertEquals(listOf("hi", "hi"), hub.sends.map { it.prompt })
        val second = hub.sends[1].requestId
        val state = store.state("s1").value
        assertEquals(listOf("local:$second"), state.messages.map { it.id })
        assertEquals(listOf(second), state.turns.map { it.requestId })
        assertTrue(state.turns.single().open)
        assertTrue(signals.none { it is SessionSignal.SendFailed || it is SessionSignal.NotSent })
    }

    @Test
    fun `a re-register the busy collector only saw as a new Connected generation still resends the held prompt`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        val first = (store.send(SendRequest("s1", "hi")) as SendOutcome.Sent).requestId
        runCurrent()
        hub.sends.single().fail(HubErrorCodes.CLIENT_NOT_FOUND, "Client not found: android-test")
        runCurrent()
        assertEquals(1, hub.sends.size)

        // Reconnecting → Connected was conflated: the StateFlow went from one connection straight to the next.
        hub.connection.value = HubConnectionState.Connected(connected.hubUrl, generation = connected.generation + 1)
        runCurrent()
        assertEquals(listOf("hi", "hi"), hub.sends.map { it.prompt })
        val second = hub.sends[1].requestId
        val state = store.state("s1").value
        assertEquals(listOf("local:$second"), state.messages.map { it.id })
        assertTrue(state.messages.none { it.id == "local:$first" })
        assertTrue(signals.none { it is SessionSignal.SendFailed || it is SessionSignal.NotSent })

        // The same generation again is no reconnect: nothing is sent twice.
        hub.connection.value = HubConnectionState.Connected(connected.hubUrl, generation = connected.generation + 1)
        runCurrent()
        assertEquals(2, hub.sends.size)
    }

    @Test
    fun `an open turn is judged after a reconnect seen only as a new generation`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        val requestId = runningTurn(store, hub)
        hub.hubStatus.value = status(lastSequence = 1)
        // A daemon restart with the drop conflated away: the new lifetime and the new connection at once.
        lifetime = "2:b"
        hub.connection.value = HubConnectionState.Connected(connected.hubUrl, generation = connected.generation + 1)
        runCurrent()
        assertInterrupted(store.state("s1").value, requestId, signals)
    }

    private val resumeConfig = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m")

    @Test
    fun `a resend after a drain that meets session_not_found on the swapped daemon resumes and resends`() = runTest {
        val hub = FakeSessionHub()
        hub.draining.value = true
        val store = newStore(hub)
        val signals = collectSignals(store)
        val first = (store.send(SendRequest("s1", "hello", config = resumeConfig)) as SendOutcome.Sent).requestId
        hub.sends.single().fail(HubErrorCodes.HUB_DRAINING)
        runCurrent()

        // hub.drain_changed {draining:false}: the resend lands on the new daemon, which does not know the session.
        hub.draining.value = false
        runCurrent()
        assertEquals(2, hub.sends.size)
        hub.sends[1].fail(HubErrorCodes.SESSION_NOT_FOUND, "session not found: s1")
        runCurrent()

        // HC §3.12 step 4 still runs after step 5: resume via session.create, then one more send.
        assertEquals("s1", hub.creates.single().spec.resumeSessionId)
        assertEquals(listOf("hello", "hello", "hello"), hub.sends.map { it.prompt })
        val third = hub.sends[2].requestId
        val state = store.state("s1").value
        val bubble = state.messages.single()
        assertEquals("local:$third", bubble.id)
        assertEquals("local:$first", bubble.renderKey)
        assertTrue(state.messages.none { it.role == Role.Error })
        assertEquals(listOf(third), state.turns.map { it.requestId })
        assertTrue(state.turns.single().open)
        assertEquals(Liveness.Live, state.liveness)
        assertTrue(signals.none { it is SessionSignal.SendFailed || it is SessionSignal.NotSent })
    }

    @Test
    fun `a client_authority_mismatch reply that arrives after the re-register is resent at once`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        store.send(SendRequest("s1", "hi"))
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, "closed 1000: re-register")
        runCurrent()
        // The new registration is up before the store read the old socket's reply.
        hub.connection.value = connected
        hub.sends.single().fail(HubErrorCodes.CLIENT_AUTHORITY_MISMATCH)
        runCurrent()
        assertEquals(listOf("hi", "hi"), hub.sends.map { it.prompt })
        val state = store.state("s1").value
        assertTrue(state.messages.none { it.role == Role.Error })
        assertEquals(listOf(hub.sends[1].requestId), state.turns.map { it.requestId })
        assertTrue(signals.none { it is SessionSignal.NotSent || it is SessionSignal.SendFailed })
    }

    @Test
    fun `an authority-loss reply read after the reconnect returned the prompt to the draft sends nothing more`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        store.send(SendRequest("s1", "hi"))
        runCurrent()
        reregister(hub)
        hub.sends.single().fail(HubErrorCodes.CLIENT_NOT_FOUND)
        runCurrent()
        assertEquals(1, hub.sends.size)
        assertEquals(1, signals.count { it is SessionSignal.NotSent })
        assertTrue(store.state("s1").value.turns.isEmpty())
    }

    @Test
    fun `a Hub that keeps refusing the client's authority ends in the error row after two re-registers`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        store.send(SendRequest("s1", "hi"))
        runCurrent()
        repeat(2) {
            hub.sends.last().fail(HubErrorCodes.CLIENT_NOT_FOUND)
            runCurrent()
            reregister(hub)
        }
        assertEquals(3, hub.sends.size)
        hub.sends.last().fail(HubErrorCodes.CLIENT_NOT_FOUND, "Client not found")
        runCurrent()
        assertEquals(3, hub.sends.size)
        val state = store.state("s1").value
        assertEquals(Role.Error, state.messages.last().role)
        assertEquals(TurnOutcome.Failed, state.turns.single().outcome)
        assertTrue(signals.any { it is SessionSignal.SendFailed && it.code == HubErrorCodes.CLIENT_NOT_FOUND })
    }

    @Test
    fun `a second drain refusal or a second session_not_found fails the turn instead of looping`() = runTest {
        val hub = FakeSessionHub()
        hub.draining.value = true
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.send(SendRequest("s1", "hello", config = resumeConfig))
        hub.sends.single().fail(HubErrorCodes.HUB_DRAINING)
        runCurrent()
        hub.draining.value = false
        runCurrent()
        // docs/07 §9: one resend after a drain; the next refusal is final.
        hub.sends[1].fail(HubErrorCodes.HUB_DRAINING, "Hub is draining")
        runCurrent()
        assertEquals(2, hub.sends.size)
        assertEquals(SessionSignal.SendFailed("s1", HubErrorCodes.HUB_DRAINING, "Hub is draining"), signals.single())

        // A resume runs once: session_not_found after it is final too.
        store.send(SendRequest("s2", "again", config = resumeConfig))
        hub.sends[2].fail(HubErrorCodes.SESSION_NOT_FOUND)
        runCurrent()
        assertEquals("s2", hub.creates.single().spec.resumeSessionId)
        hub.sends[3].fail(HubErrorCodes.SESSION_NOT_FOUND, "session not found: s2")
        runCurrent()
        assertEquals(4, hub.sends.size)
        assertEquals(1, hub.creates.size)
        assertEquals(SessionSignal.SendFailed("s2", HubErrorCodes.SESSION_NOT_FOUND, "session not found: s2"), signals.last())
    }

    @Test
    fun `a queued send that fell back to a direct send still resumes on session_not_found`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.send(SendRequest("s1", "later", delivery = Delivery.Queue, config = resumeConfig))
        hub.sends.single().fail(HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE)
        runCurrent()
        assertEquals(listOf("queue", null), hub.sends.map { it.delivery })
        hub.sends[1].fail(HubErrorCodes.SESSION_NOT_FOUND)
        runCurrent()
        assertEquals("s1", hub.creates.single().spec.resumeSessionId)
        assertEquals(listOf("queue", null, null), hub.sends.map { it.delivery })
        val state = store.state("s1").value
        assertEquals(listOf(hub.sends[2].requestId), state.turns.map { it.requestId })
        assertTrue(state.messages.none { it.role == Role.Error })
    }

    @Test
    fun `a held prompt survives a reconnect that the coordinator replaces and goes out on the new socket`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(2, "closed 1006: dropped")
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "hi")))
        // The resume path (onForeground → connect()) swaps the loop: Reconnecting → Connecting → Connected.
        hub.connection.value = HubConnectionState.Connecting
        runCurrent()
        assertTrue("nothing returned to the draft while connecting", signals.isEmpty())
        hub.connection.value = connected
        runCurrent()
        assertEquals(listOf("hi"), hub.sends.map { it.prompt })
        assertTrue(signals.none { it is SessionSignal.NotSent })
    }

    @Test
    fun `a held prompt is returned to the draft when the user disconnects`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "hi")))
        hub.connection.value = HubConnectionState.Disconnected
        runCurrent()
        assertEquals(listOf<SessionSignal>(SessionSignal.NotSent("s1", "hi", emptyList(), kernelDown = true)), signals)
    }

    @Test
    fun `a held prompt is returned to the draft when the kernel becomes unavailable`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "hi")))
        hub.connection.value = HubConnectionState.Unavailable("kernel stopped")
        runCurrent()
        assertEquals(listOf<SessionSignal>(SessionSignal.NotSent("s1", "hi", emptyList(), kernelDown = true)), signals)

        // Returned, not flushed by a later connect.
        hub.connection.value = connected
        runCurrent()
        assertTrue(hub.sends.isEmpty())
    }

    @Test
    fun `a held prompt returned after the user switched sessions waits for that session's composer`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.start()
        runCurrent()
        store.open("s1")
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "hi")))
        // The user switches to another session: nothing on screen listens for s1 any more.
        store.close("s1")
        store.open("s2")
        runCurrent()
        hub.connection.value = HubConnectionState.Unavailable("kernel stopped")
        runCurrent()

        // The signal had no listener for s1; the prompt is still there for s1's composer, once.
        assertNull(store.takeReturnedPrompt("s2"))
        assertEquals(SessionSignal.NotSent("s1", "hi", emptyList(), kernelDown = true), store.takeReturnedPrompt("s1"))
        assertNull(store.takeReturnedPrompt("s1"))
    }

    @Test
    fun `two prompts returned for one session are both kept`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.start()
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "first")))
        hub.connection.value = HubConnectionState.Unavailable("kernel stopped")
        runCurrent()
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        assertEquals(SendOutcome.Held, store.send(SendRequest("s1", "second")))
        hub.connection.value = HubConnectionState.Disconnected
        runCurrent()
        assertEquals(SessionSignal.NotSent("s1", "first\n\nsecond", emptyList(), kernelDown = true), store.takeReturnedPrompt("s1"))
    }

    // ---- open / seed (HC §3.5) -------------------------------------------------------------------------

    @Test
    fun `seeding a session the Hub does not know is Missing, other failures show the History banner`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val defaultGet = hub.onGet

        hub.onGet = { throw FakeSessionHub.error("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: gone") }
        store.open("gone")
        runCurrent()
        val missing = store.state("gone").value
        assertEquals(SeedState.Missing, missing.seed)
        assertEquals(Liveness.Missing, missing.liveness)
        assertNull(missing.errorBanner)

        hub.onGet = { throw FakeSessionHub.error("session.get", "internal_error", "disk unreadable") }
        store.open("broken")
        runCurrent()
        val failed = store.state("broken").value
        assertEquals(SeedState.Failed("disk unreadable"), failed.seed)
        assertEquals(SessionError(SessionError.Kind.History, "disk unreadable"), failed.errorBanner)

        hub.onGet = defaultGet
        hub.onMessages = { throw FakeSessionHub.error("session.messages", "internal_error", "history gone") }
        store.open("no-history")
        runCurrent()
        val noHistory = store.state("no-history").value
        assertEquals(SeedState.Failed("history gone"), noHistory.seed)
        assertEquals(SessionError(SessionError.Kind.History, "history gone"), noHistory.errorBanner)
    }

    @Test
    fun `a persisted session opened while connected carries the hint and resumes before the first send`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.onGet = { id -> buildJsonObject { put("session", buildJsonObject { put("sessionId", id); put("status", "completed") }) } }

        store.open("old")
        runCurrent()
        var state = store.state("old").value
        // FX 05 step 3: attach succeeds on a persisted session without making it live.
        assertTrue(state.live_)
        assertTrue(state.persistedHint)
        assertEquals(Liveness.Persisted, state.liveness)

        val config = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m")
        assertTrue(store.send(SendRequest("old", "continue", config = config)) is SendOutcome.Sent)
        runCurrent()
        assertEquals(listOf("session.create", "session.send_input"), hub.calls.filter { it == "session.create" || it == "session.send_input" })
        assertEquals("old", hub.creates.single().spec.resumeSessionId)
        state = store.state("old").value
        assertFalse(state.persistedHint)
        assertEquals(Liveness.Live, state.liveness)

        // A session that ran a turn in this Hub lifetime is live although session.get says completed.
        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r1","clientId":"tui"}""", sessionId = "fresh"))
        store.dispatch(event(HubEvents.RUN_COMPLETED, 2, """{"requestId":"r1","reason":"completed"}""", sessionId = "fresh"))
        store.open("fresh")
        runCurrent()
        assertFalse(store.state("fresh").value.persistedHint)
        assertEquals(Liveness.Live, store.state("fresh").value.liveness)

        // A new daemon lifetime starts an empty live set.
        lifetime = "2:b"
        store.seed("fresh")
        runCurrent()
        assertTrue(store.state("fresh").value.persistedHint)
    }

    @Test
    fun `an unknown lifetime in between keeps the live set of a reconnect to the same daemon`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.start()
        runCurrent()
        hub.onGet = { id -> buildJsonObject { put("session", buildJsonObject { put("sessionId", id); put("status", "completed") }) } }
        // s1 ran a turn in this lifetime: live although session.get says completed (HC §3.5).
        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r1","clientId":"tui"}"""))
        store.dispatch(event(HubEvents.RUN_COMPLETED, 2, """{"requestId":"r1","reason":"completed"}"""))
        runCurrent()

        // The socket drops; the coordinator passes through Starting (no hubInfo) while the pump drains.
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        lifetime = null
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, 3, """{"requestId":"r2","clientId":"tui"}""", sessionId = "s2"))
        store.dispatch(event(HubEvents.SESSION_CREATED, 4, """{"session":{"sessionId":"s3"}}""", sessionId = "s3"))
        runCurrent()

        // Back on the same daemon (same pid:startedAt).
        lifetime = "1:a"
        hub.connection.value = connected
        runCurrent()
        store.seed("s1")
        runCurrent()
        assertFalse(store.state("s1").value.persistedHint)
        // So the next send writes send_input directly, with no needless resume.
        assertTrue(store.send(SendRequest("s1", "more", config = resumeConfig)) is SendOutcome.Sent)
        runCurrent()
        assertTrue(hub.creates.isEmpty())
        assertEquals(listOf("s1"), hub.sends.map { it.sessionId })
    }

    @Test
    fun `an open idle session re-attached on a new daemon lifetime is not live and resumes before the first send`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val signals = collectSignals(store)
        store.start()
        runCurrent()
        store.open("s1")
        runCurrent()
        assertEquals(Liveness.Live, store.state("s1").value.liveness)

        // Control: a reconnect to the same daemon re-attaches and stays live.
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        hub.connection.value = connected
        runCurrent()
        store.onAttached("s1")
        runCurrent()
        assertEquals(Liveness.Live, store.state("s1").value.liveness)

        // The Hub restarted while s1 was idle: no turn was open, so nothing is marked interrupted.
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        lifetime = "2:b"
        hub.connection.value = connected
        runCurrent()
        // The coordinator's reattach: session.attach succeeds on the persisted session (FX 05 item 3).
        store.onAttached("s1")
        runCurrent()
        var state = store.state("s1").value
        assertFalse(state.live_)
        assertTrue(state.persistedHint)
        assertEquals(Liveness.Persisted, state.liveness)
        assertTrue(signals.none { it is SessionSignal.Interrupted })

        // The first send resumes first: no send_input into session_not_found, no flickering bubble.
        val outcome = store.send(SendRequest("s1", "continue", config = resumeConfig))
        runCurrent()
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf("session.create", "session.send_input"), hub.calls.filter { it == "session.create" || it == "session.send_input" })
        assertEquals("s1", hub.creates.single().spec.resumeSessionId)
        state = store.state("s1").value
        assertEquals(listOf("local:${(outcome as SendOutcome.Sent).requestId}"), state.messages.map { it.id })
        assertEquals(Liveness.Live, state.liveness)
    }

    @Test
    fun `an event log reset rewinds the applied sequence and reseeds open sessions`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.start()
        runCurrent()
        store.open("s1")
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, 50, """{"requestId":"r1","clientId":"tui"}"""))
        store.dispatch(event(HubEvents.RUN_COMPLETED, 51, """{"requestId":"r1","reason":"completed"}"""))
        runCurrent()
        assertEquals(51L, store.appliedSequence.value)
        val getsBefore = hub.calls.count { it == "session.get" }

        // Fresh Hub data dir: the Hub's log ends at 3, below what the store has applied.
        hub.notices.tryEmit(HubClient.Notice.ReplayTruncated(fromSequence = 0, lastSequence = 3))
        runCurrent()
        var state = store.state("s1").value
        assertTrue(state.replayGap)
        assertEquals(0L, state.facts.lastSequence)
        assertEquals(0L, store.appliedSequence.value)
        assertEquals(getsBefore + 1, hub.calls.count { it == "session.get" })

        // New sequences apply again.
        store.dispatch(event(HubEvents.RUN_STARTED, 2, """{"requestId":"r2","clientId":"tui"}"""))
        runCurrent()
        state = store.state("s1").value
        assertEquals(2L, state.facts.lastSequence)
        assertEquals("r2", state.facts.activeRun?.requestId)
        assertEquals(2L, store.appliedSequence.value)
    }

    @Test
    fun `a session held by the chat and a Tool detail stays open until both close`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.open("s1")
        store.open("s1")
        store.close("s1")
        assertTrue(store.isOpen("s1"))
        assertTrue(hub.unsubscribed.isEmpty())
        store.close("s1")
        assertFalse(store.isOpen("s1"))
        assertEquals(listOf("s1"), hub.unsubscribed)
        store.close("s1")
        assertEquals(listOf("s1"), hub.unsubscribed)
    }

    @Test
    fun `session_attached and session_detached for this client set live_`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        fun attached(seq: Long, clientId: String) = event(
            HubEvents.SESSION_ATTACHED, seq,
            """{"session":{"sessionId":"s1","status":"idle","participants":[{"clientId":"$clientId","role":"participant"}]}}""",
        )
        store.dispatch(attached(1, hub.clientId))
        assertTrue(store.state("s1").value.live_)
        store.dispatch(event(HubEvents.SESSION_DETACHED, 2, """{"clientId":"other-client"}"""))
        assertTrue(store.state("s1").value.live_)
        store.dispatch(event(HubEvents.SESSION_DETACHED, 3, """{"clientId":"${hub.clientId}"}"""))
        assertFalse(store.state("s1").value.live_)
        assertEquals(Liveness.Persisted, store.state("s1").value.liveness)
        store.dispatch(attached(4, "other-client"))
        assertFalse(store.state("s1").value.live_)
    }

    @Test
    fun `a resume without workspace or model reports a typed reason, not English text`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        assertTrue(store.resume("s1").isFailure)
        val banner = store.state("s1").value.errorBanner!!
        assertEquals(SessionError.Kind.Resume, banner.kind)
        assertEquals(SessionError.Reason.MissingResumeConfig, banner.reason)
        assertEquals("", banner.message)
        assertEquals(R.string.chat_resume_missing_config, banner.reason!!.text)
        assertTrue(hub.creates.isEmpty())
    }

    // ---- CA §0.2 ------------------------------------------------------------------------------------------

    @Test
    fun `the run_started of a queued send does not open a turn`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val queued = store.send(SendRequest("s1", "later", delivery = Delivery.Queue)) as SendOutcome.Queued
        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"${queued.requestId}","clientId":"${hub.clientId}"}"""))
        runCurrent()
        val state = store.state("s1").value
        assertTrue(state.turns.isEmpty())
        assertNull(state.openTurn)
        assertTrue(state.messages.none { it.role == Role.User })
    }

    private fun history(vararg turns: Pair<String, String>): kotlinx.serialization.json.JsonArray =
        HubJson.parseToJsonElement(
            turns.mapIndexed { index, (prompt, answer) ->
                """{"id":"u$index","role":"user","ts":${900 + index * 100},"content":[{"type":"text","text":"<user_input mode=\"act\">$prompt</user_input>"}]},""" +
                    """{"id":"m$index","role":"assistant","ts":${950 + index * 100},"content":[{"type":"text","text":"$answer"}]}"""
            }.joinToString(",", "[", "]"),
        ) as kotlinx.serialization.json.JsonArray

    /**
     * Recorded on the dev Hub (2026-10-04): the Hub drains a queued prompt before it publishes the
     * previous run's `run.completed`, and the drained prompt gets no `run.*` of its own.
     */
    @Test
    fun `a drained queued prompt keeps its turn after the previous run_completed and ends when the loop goes idle`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.open("s1")
        runCurrent()
        val direct = (store.send(SendRequest("s1", "slow four")) as SendOutcome.Sent).requestId
        val queued = (store.send(SendRequest("s1", "queued alpha", delivery = Delivery.Queue)) as SendOutcome.Queued).requestId
        val running = """{"snapshot":{"status":"running"}}"""
        val idle = """{"snapshot":{"status":"idle"}}"""
        listOf(
            event(HubEvents.RUN_STARTED, 1, """{"requestId":"$direct","clientId":"${hub.clientId}"}"""),
            event(HubEvents.SESSION_UPDATED, 2, running),
            event(HubEvents.ITERATION_STARTED, 3, """{"iteration":1}"""),
            event(HubEvents.RUN_STARTED, 4, """{"requestId":"$queued","clientId":"${hub.clientId}"}"""),
            event(HubEvents.SESSION_PENDING_PROMPTS, 5, """{"prompts":[{"id":"p1","prompt":"queued alpha","delivery":"queue"}]}"""),
            event(HubEvents.ASSISTANT_DELTA, 6, """{"text":"slow chunk"}"""),
            event(HubEvents.ASSISTANT_FINISHED, 7, """{"text":"slow chunk"}"""),
            event(HubEvents.ITERATION_FINISHED, 8, """{"iteration":1}"""),
            event(HubEvents.SESSION_UPDATED, 9, idle),
            event(HubEvents.SESSION_PENDING_PROMPTS, 10, """{"prompts":[]}"""),
            event(HubEvents.SESSION_PENDING_PROMPT_SUBMITTED, 11, """{"prompt":{"id":"p1","prompt":"queued alpha"}}"""),
        ).forEach { store.dispatch(it) }
        hub.history = history("slow four" to "slow chunk")
        store.dispatch(event(HubEvents.RUN_COMPLETED, 12, """{"reason":"completed","snapshot":{"status":"idle"}}"""))
        runCurrent()

        var state = store.state("s1").value
        assertTrue(state.messages.any { it.id == "local:q:p1" && it.role == Role.User && it.content == "queued alpha" })
        assertEquals(TurnOutcome.Completed, state.turns.single { it.requestId == direct }.outcome)
        assertFalse(state.turns.single { it.requestId == direct }.open)
        assertTrue(state.turns.single { it.requestId == "q:p1" }.open)

        listOf(
            event(HubEvents.SESSION_UPDATED, 13, running),
            event(HubEvents.ITERATION_STARTED, 14, """{"iteration":1}"""),
            event(HubEvents.ASSISTANT_DELTA, 15, """{"text":"queued answer"}"""),
            event(HubEvents.ASSISTANT_FINISHED, 16, """{"text":"queued answer"}"""),
            event(HubEvents.ITERATION_FINISHED, 17, """{"iteration":1}"""),
        ).forEach { store.dispatch(it) }
        runCurrent()
        state = store.state("s1").value
        assertEquals(RunPhase.Running, state.facts.runPhase)
        val answer = state.messages.single { it.role == Role.Assistant && it.content == "queued answer" }
        assertEquals("q:p1", answer.meta.requestId)

        hub.history = history("slow four" to "slow chunk", "queued alpha" to "queued answer")
        store.dispatch(event(HubEvents.SESSION_UPDATED, 18, idle))
        runCurrent()
        state = store.state("s1").value
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertNull(state.facts.activeRun)
        assertNull(state.openTurn)
        assertEquals(TurnOutcome.Completed, state.turns.single { it.requestId == "q:p1" }.outcome)
        assertEquals(listOf("slow four", "slow chunk", "queued alpha", "queued answer"), state.messages.map { UserInputFormat.formatDisplayUserInput(it.content) })
    }

    @Test
    fun `stopping a drained queued prompt ends Stopping when the loop goes idle without a run terminal`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.open("s1")
        runCurrent()
        val direct = (store.send(SendRequest("s1", "slow four")) as SendOutcome.Sent).requestId
        val queued = (store.send(SendRequest("s1", "queued alpha", delivery = Delivery.Queue)) as SendOutcome.Queued).requestId
        val running = """{"snapshot":{"status":"running"}}"""
        val idle = """{"snapshot":{"status":"idle"}}"""
        listOf(
            event(HubEvents.RUN_STARTED, 1, """{"requestId":"$direct","clientId":"${hub.clientId}"}"""),
            event(HubEvents.SESSION_UPDATED, 2, running),
            event(HubEvents.RUN_STARTED, 3, """{"requestId":"$queued","clientId":"${hub.clientId}"}"""),
            event(HubEvents.SESSION_PENDING_PROMPTS, 4, """{"prompts":[{"id":"p1","prompt":"queued alpha","delivery":"queue"}]}"""),
            event(HubEvents.SESSION_UPDATED, 5, idle),
            event(HubEvents.SESSION_PENDING_PROMPTS, 6, """{"prompts":[]}"""),
            event(HubEvents.SESSION_PENDING_PROMPT_SUBMITTED, 7, """{"prompt":{"id":"p1","prompt":"queued alpha"}}"""),
            event(HubEvents.RUN_COMPLETED, 8, """{"reason":"completed","snapshot":{"status":"idle"}}"""),
            event(HubEvents.SESSION_UPDATED, 9, running),
            event(HubEvents.ITERATION_STARTED, 10, """{"iteration":1}"""),
        ).forEach { store.dispatch(it) }
        runCurrent()
        assertTrue(store.abort("s1").isSuccess)
        runCurrent()
        assertEquals(RunPhase.Stopping, store.state("s1").value.facts.runPhase)

        listOf(
            event(HubEvents.ITERATION_FINISHED, 11, """{"iteration":1}"""),
            event(HubEvents.AGENT_DONE, 12, """{"reason":"aborted"}"""),
            event(HubEvents.SESSION_UPDATED, 13, idle),
        ).forEach { store.dispatch(it) }
        runCurrent()
        val state = store.state("s1").value
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertNull(state.openTurn)
        assertEquals(TurnOutcome.Aborted, state.turns.single { it.requestId == "q:p1" }.outcome)
    }

    @Test
    fun `a steered prompt joins the running loop and opens no turn of its own`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val requestId = runningTurn(store, hub)
        store.dispatch(event(HubEvents.SESSION_UPDATED, 2, """{"snapshot":{"status":"running"}}"""))
        store.dispatch(event(HubEvents.SESSION_PENDING_PROMPT_SUBMITTED, 3, """{"prompt":{"id":"p9","prompt":"steer now","delivery":"steer"}}"""))
        runCurrent()
        val state = store.state("s1").value
        assertEquals(listOf(requestId), state.turns.filter { it.open }.map { it.requestId })
        assertEquals(requestId, state.messages.single { it.id == "local:q:p9" }.meta.requestId)
    }

    // ---- first-send session.create (KR-3: docs/07 §9, HC §3.12 step 6) ------------------------------

    private val firstSendConfig = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m")

    private fun createdRecord(sessionId: String) = buildJsonObject {
        put("session", buildJsonObject { put("sessionId", sessionId); put("status", "idle") })
    }

    @Test
    fun `a first send's create carries its nonce as metadata clientCreateId`() {
        val request = SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n1")
        assertEquals("n1", request.metadata.str(SessionCreateBuilder.CLIENT_CREATE_ID))
        assertEquals("android", request.metadata.str("source"))
        assertNull(SessionCreateBuilder.firstSend(firstSendConfig).metadata.str(SessionCreateBuilder.CLIENT_CREATE_ID))
    }

    @Test
    fun `a lost create reply adopts the session that session list finds by its nonce and never resends`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.onCreate = { throw FakeSessionHub.error("session.create", HubErrorCodes.CLIENT_TIMEOUT, "no reply within 125000 ms") }
        hub.onList = {
            HubJson.parseToJsonElement(
                """{"sessions":[{"sessionId":"other","metadata":{"clientCreateId":"n0"}},{"sessionId":"s-made","status":"idle","metadata":{"clientCreateId":"n1","source":"android"}}]}""",
            ) as JsonObject
        }
        val result = store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n1"))
        runCurrent()
        assertEquals("s-made", result.getOrNull())
        assertEquals(1, hub.creates.size)
        assertTrue("session.list" in hub.calls)
        val state = store.state("s-made").value
        assertFalse(state.persistedHint)
        assertTrue(state.seed is SeedState.Seeded)
    }

    @Test
    fun `a lost create reply with no matching session fails as disconnected without a resend`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.onCreate = { throw FakeSessionHub.error("session.create", HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed") }
        val result = store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n2"))
        assertTrue(result.exceptionOrNull() is SessionCreateDisconnectedException)
        assertEquals(1, hub.creates.size)
        assertEquals(listOf("session.create", "session.list"), hub.calls)
    }

    @Test
    fun `a lost create reply adopts the session its replayed session created event named`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.onCreate = {
            hub.connection.value = HubConnectionState.Reconnecting(1, "closed")
            throw FakeSessionHub.error("session.create", HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed")
        }
        val pending = async { store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n3")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        store.dispatch(
            event(
                HubEvents.SESSION_CREATED,
                7,
                """{"session":{"sessionId":"s-ev","status":"idle","metadata":{"clientCreateId":"n3","source":"android"}}}""",
                sessionId = "s-ev",
            ),
        )
        hub.connection.value = connected
        runCurrent()
        assertEquals("s-ev", pending.await().getOrNull())
        assertEquals(1, hub.creates.size)
        assertFalse("session.list" in hub.calls)
    }

    @Test
    fun `a first send while reconnecting is held and created after the reconnect`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        val pending = async { store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n4")) }
        runCurrent()
        assertTrue(hub.creates.isEmpty())
        hub.connection.value = connected
        runCurrent()
        assertEquals("s-new", pending.await().getOrNull())
        assertEquals(1, hub.creates.size)
    }

    @Test
    fun `a create the client could not write goes out once more after the reconnect`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        var first = true
        hub.onCreate = {
            if (first) {
                first = false
                hub.connection.value = HubConnectionState.Reconnecting(1, "closed")
                throw HubConnectionClosedException("not connected")
            }
            createdRecord("s-retry")
        }
        val pending = async { store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n5")) }
        runCurrent()
        hub.connection.value = connected
        runCurrent()
        assertEquals("s-retry", pending.await().getOrNull())
        assertEquals(2, hub.creates.size)
    }

    @Test
    fun `a first send without a connection fails as disconnected and sends nothing`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.connection.value = HubConnectionState.Unavailable("gone")
        val result = store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n6"))
        assertTrue(result.exceptionOrNull() is SessionCreateDisconnectedException)
        assertTrue(hub.creates.isEmpty())
    }

    @Test
    fun `a refused create keeps the Hub's error`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        hub.onCreate = { throw FakeSessionHub.error("session.create", HubErrorCodes.COMMAND_FAILED, "provider not configured") }
        val error = store.create(SessionCreateBuilder.firstSend(firstSendConfig, clientCreateId = "n7")).exceptionOrNull()
        assertEquals("provider not configured", (error as dev.cline.termux.core.kernel.hub.HubCommandException).hubMessage)
        assertFalse("session.list" in hub.calls)
    }

    // ---- replay gaps (SS §1.2.1 item 2, docs/07 rule 2) ------------------------------------------------

    private fun record(status: String) = buildJsonObject {
        put("session", buildJsonObject { put("sessionId", "s1"); put("status", status) })
        put("snapshot", buildJsonObject { put("status", status) })
    }

    /** s1 runs our turn and waits on an approval; then the socket drops. Returns the turn's requestId. */
    private suspend fun TestScope.awaitingTurnThenDrop(store: SessionStore, hub: FakeSessionHub): String {
        store.start()
        runCurrent()
        store.open("s1")
        runCurrent()
        val requestId = runningTurn(store, hub)
        store.dispatch(event(HubEvents.APPROVAL_REQUESTED, 2, """{"approvalId":"ap1","toolCallId":"call_1","toolName":"run_commands","inputJson":"{}"}"""))
        runCurrent()
        assertEquals(RunPhase.AwaitingUser, store.state("s1").value.facts.runPhase)
        hub.connection.value = HubConnectionState.Reconnecting(1, null)
        runCurrent()
        return requestId
    }

    /** Reconnects with a replay of only the newest events: s1's terminal (sequence 3) fell outside the budget. */
    private suspend fun TestScope.reconnectWithTruncatedReplay(store: SessionStore, hub: FakeSessionHub) {
        hub.hubStatus.value = status(lastSequence = 2_003)
        hub.connection.value = connected
        hub.notices.tryEmit(HubClient.Notice.ReplayTruncated(fromSequence = 4, lastSequence = 2_003))
        runCurrent()
        // Nothing is judged before the replay drained.
        assertTrue(store.state("s1").value.turns.single().open)
        // The replayed window: another session's long TUI turn, nothing for s1.
        for (seq in 2_000L..2_003L) store.dispatch(event(HubEvents.ASSISTANT_DELTA, seq, """{"requestId":"tui-1","iteration":1,"text":"x"}""", sessionId = "s2"))
        runCurrent()
    }

    @Test
    fun `a truncated replay that skipped the terminal event ends the run from session_get`() = runTest {
        val hub = FakeSessionHub()
        val turns = InMemoryTurnStore()
        val store = newStore(hub, turns)
        val requestId = awaitingTurnThenDrop(store, hub)
        val messagesBefore = hub.calls.count { it == "session.messages" }
        hub.onGet = { record("completed") }

        reconnectWithTruncatedReplay(store, hub)

        val state = store.state("s1").value
        assertTrue(state.replayGap)
        val turn = state.turns.single { it.requestId == requestId }
        assertFalse(turn.open)
        assertEquals(TurnOutcome.Ended, turn.outcome)
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertEquals(SessionStatus.Completed, state.facts.status)
        assertEquals(ChatStatus.Completed, state.chatStatus)
        assertTrue(state.facts.approvals.isEmpty())
        assertNull(state.facts.activeRun)
        assertNull(state.openTurn)
        assertNull(state.streamingMessageId)
        assertTrue(hub.calls.count { it == "session.messages" } > messagesBefore)
        assertFalse(turns.records.single { it.requestId == requestId }.open)
        // Not an interruption: the run ended on the same daemon.
        assertTrue(state.messages.none { it.id == "i:$requestId" })
    }

    @Test
    fun `a truncated replay leaves a run the Hub still reports running alone`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val requestId = awaitingTurnThenDrop(store, hub)
        hub.onGet = { record("pending") }

        reconnectWithTruncatedReplay(store, hub)

        val state = store.state("s1").value
        assertTrue(state.turns.single { it.requestId == requestId }.open)
        assertEquals(RunPhase.AwaitingUser, state.facts.runPhase)
        assertEquals(setOf("ap1"), state.facts.approvals.keys)
    }

    @Test
    fun `a session the Hub no longer knows ends its run as Missing after a truncated replay`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        val requestId = awaitingTurnThenDrop(store, hub)
        hub.onGet = { throw FakeSessionHub.error("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: s1") }

        reconnectWithTruncatedReplay(store, hub)

        val state = store.state("s1").value
        assertEquals(TurnOutcome.Ended, state.turns.single { it.requestId == requestId }.outcome)
        assertEquals(RunPhase.Idle, state.facts.runPhase)
        assertEquals(Liveness.Missing, state.liveness)
    }

    @Test
    fun `restored open turns are verified once connected and expired ones are dropped`() = runTest {
        val now = clock.millis()
        val turns = InMemoryTurnStore(
            listOf(
                TurnRecord(
                    "r-old", "s-old", startedAtSequence = 10, prompt = "ancient", open = true, started = true,
                    hubLifetimeKey = lifetime, sentAt = now - TurnRecords.OPEN_RETENTION_MS - 1,
                ),
                TurnRecord(
                    "r-new", "s1", startedAtSequence = 900, prompt = "recent", open = true, started = true,
                    hubLifetimeKey = lifetime, sentAt = now - 60_000,
                ),
            ),
        )
        val hub = FakeSessionHub()
        hub.connection.value = HubConnectionState.Connecting
        val store = newStore(hub, turns)
        store.start()
        runCurrent()

        // The expired turn neither comes back nor sets the replay floor.
        assertTrue(store.state("s-old").value.turns.isEmpty())
        assertTrue(store.state("s1").value.turns.single().open)
        assertEquals(899L, hub.replayFloor!!.invoke())

        // Connected, the Hub says s1 is idle (its terminal fell outside the replay window): the turn ends.
        hub.connection.value = connected
        runCurrent()
        val state = store.state("s1").value
        assertTrue("session.get" in hub.calls)
        assertEquals(TurnOutcome.Ended, state.turns.single().outcome)
        assertNull(state.openTurn)
        assertEquals(listOf("r-new"), turns.records.map { it.requestId })
        assertFalse(turns.records.single().open)
        assertEquals(null, hub.replayFloor!!.invoke())
    }

    @Test
    fun `a replay that arrives while a slow restore still loads is applied after it, never dropped`() = runTest {
        val now = clock.millis()
        val persisted = listOf(
            TurnRecord(
                "r1", "s1", prompt = "hello", open = true, started = false,
                hubLifetimeKey = lifetime, sentAt = now - 30_000, ours = true,
            ),
        )
        // A cold-start DataStore read that outlasts the replay floor's wait.
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : TurnStore {
            override suspend fun load(): List<TurnRecord> {
                loaded.await()
                return persisted
            }

            override suspend fun save(records: List<TurnRecord>) = Unit
        }
        val hub = FakeSessionHub()
        hub.connection.value = HubConnectionState.Connecting
        val store = SessionStore(
            hub = hub,
            turnStore = slow,
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            hubLifetimeKey = { lifetime },
        )
        store.start()
        runCurrent()

        // HubClient's first subscribe: the floor gives up waiting for the restore.
        val floor = async { hub.replayFloor!!.invoke() }
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(floor.isCompleted)
        assertNull(floor.await())

        // The replay comes in before the persisted turns are in memory: the pump is already subscribed.
        assertTrue(hub.events.subscriptionCount.value > 0)
        assertTrue(hub.events.tryEmit(event(HubEvents.RUN_STARTED, 5, """{"requestId":"r1","clientId":"${hub.clientId}"}""")))
        assertTrue(hub.events.tryEmit(event(HubEvents.ASSISTANT_DELTA, 6, """{"requestId":"r1","iteration":1,"text":"hi"}""")))
        runCurrent()
        assertEquals(0L, store.appliedSequence.value)
        assertTrue(store.state("s1").value.turns.isEmpty())

        // The restore lands; the held events apply on top of it, in order.
        loaded.complete(Unit)
        runCurrent()
        assertEquals(6L, store.appliedSequence.value)
        val state = store.state("s1").value
        val turn = state.turns.single()
        assertEquals("r1", turn.requestId)
        assertTrue(turn.started)
        assertEquals(5L, turn.startedAtSequence)
        assertEquals(RunPhase.Running, state.facts.runPhase)
        assertEquals(6L, state.facts.lastSequence)
        // The local row of our send came back from the persisted prompt (the run.started replay path).
        assertTrue(state.messages.any { it.role == Role.User && it.meta.requestId == "r1" })
    }

    // ---- docs/07 §4.1 eviction, §4.7 notification facts ---------------------------------------------

    @Test
    fun `notifyFacts follows the facts, pins no session, and loses an evicted one`() = runTest {
        val hub = FakeSessionHub()
        val store = SessionStore(
            hub = hub,
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            hubLifetimeKey = { lifetime },
            config = SessionStore.Config(evictAfterMs = 1_000, sweepIntervalMs = 100),
        )
        store.start()
        runCurrent()
        // A process-long observer, as the notifier and the keep-alive are.
        val published = mutableListOf<Map<String, NotifyFacts>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.notifyFacts.collect { published += it } }

        store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r1","clientId":"tui"}"""))
        store.dispatch(event(HubEvents.APPROVAL_REQUESTED, 2, """{"approvalId":"ap1","toolCallId":"call_1","toolName":"run_commands","inputJson":"{}"}""", sessionId = "s2"))
        runCurrent()
        assertEquals(RunPhase.Running, store.notifyFacts.value.getValue("s1").runPhase)
        assertEquals(setOf("ap1"), store.notifyFacts.value.getValue("s2").approvals.keys)

        // A streamed delta changes the transcript, not the facts: nothing is republished.
        val before = published.size
        store.dispatch(event(HubEvents.ASSISTANT_DELTA, 3, """{"requestId":"r1","iteration":1,"text":"hello"}"""))
        runCurrent()
        assertEquals(before, published.size)

        // Idle past the eviction age, but a busy run and a waiting approval are pending: both stay.
        clock.advanceMillis(5_000)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf("s1", "s2"), store.notifyFacts.value.keys)

        store.dispatch(event(HubEvents.RUN_COMPLETED, 4, """{"requestId":"r1","reason":"completed"}"""))
        runCurrent()
        assertEquals(SessionStatus.Completed, store.notifyFacts.value.getValue("s1").status)

        // The ended session goes although notifyFacts is collected the whole time.
        clock.advanceMillis(5_000)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf("s2"), store.notifyFacts.value.keys)
        assertEquals(setOf("s2"), published.last().keys)
    }

    // ---- G1 compaction (batch 1 P4) ---------------------------------------------------------------------

    private val basic = buildJsonObject {
        put("enabled", true)
        put("strategy", "basic")
    }

    @Test
    fun `creates and resumes without a compaction take the global mode, an explicit one wins`() = runTest {
        val hub = FakeSessionHub()
        var reads = 0
        val store = newStore(hub, compaction = {
            reads++
            basic
        })
        val config = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m")
        assertTrue(store.create(SessionCreateBuilder.firstSend(config)).isSuccess)
        assertTrue(store.resume("s1", config).isSuccess)
        val off = buildJsonObject { put("enabled", false) }
        assertTrue(store.resume("s2", config.copy(compaction = off)).isSuccess)
        val sent = hub.creates.map { it.payload()["sessionConfig"]!!.let { c -> (c as JsonObject)["compaction"] } }
        assertEquals(listOf(basic, basic, off), sent)
        assertEquals(2, reads)
    }
}
