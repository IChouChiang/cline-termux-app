package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trial row 16 through the real [SessionStore]: the run end of this client's first prompt into a
 * session that already carries a title hands that title on ([RunEnded.createTitle]) for the
 * `CreateTitleKeeper`; every other run end carries none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunTitleTest {
    private val sid = "s1"
    private var seq = 0L

    private fun TestScope.newStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = FakeClock(),
        hubLifetimeKey = { "1:a" },
    )

    private fun TestScope.collectRunEnds(store: SessionStore): List<RunEnded> {
        val ends = mutableListOf<RunEnded>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.runEnds.collect { ends += it } }
        return ends
    }

    /** The record another client's create left: a title, no stored prompt yet unless [prompt]. */
    private fun record(title: String?, prompt: String? = null): JsonObject = buildJsonObject {
        putJsonObject("session") {
            put("sessionId", sid)
            put("status", "idle")
            putJsonObject("metadata") {
                put("source", "cli")
                title?.let { put("title", it) }
                prompt?.let { put("prompt", it) }
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

    /** A direct send, its `run.started` and its `run.completed`. */
    private suspend fun TestScope.runPrompt(store: SessionStore, hub: FakeSessionHub, prompt: String): String {
        val requestId = (store.send(SendRequest(sid, prompt)) as SendOutcome.Sent).requestId
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, """{"requestId":"$requestId","clientId":"${hub.clientId}"}"""))
        store.dispatch(event(HubEvents.RUN_COMPLETED, """{"requestId":"$requestId","reason":"completed"}"""))
        runCurrent()
        return requestId
    }

    @Test
    fun `the first prompt into a titled session hands its title on, a later one does not`() = runTest {
        val hub = FakeSessionHub()
        hub.onGet = { record("[test] a13") }
        val store = newStore(hub)
        val ends = collectRunEnds(store)
        store.seed(sid)
        runCurrent()

        val first = runPrompt(store, hub, "hello there")
        assertEquals(RunEnded(sid, first, ours = true, outcome = TurnOutcome.Completed, createTitle = "[test] a13"), ends.single())

        val second = runPrompt(store, hub, "and again")
        assertEquals(2, ends.size)
        assertEquals(second, ends[1].requestId)
        assertNull(ends[1].createTitle)
    }

    @Test
    fun `an untitled session, a stored prompt or an earlier user message hands nothing on`() = runTest {
        val untitled = FakeSessionHub().apply { onGet = { record(title = null) } }
        val prompted = FakeSessionHub().apply { onGet = { record("Fix the build", prompt = "<user_input mode=\"act\">Fix the build</user_input>") } }
        val seeded = FakeSessionHub().apply {
            onGet = { record("Notes (fork)") }
            history = HubJson.parseToJsonElement(
                """[{"role":"user","content":[{"type":"text","text":"<user_input mode=\"act\">first</user_input>"}]},
                    {"role":"assistant","content":[{"type":"text","text":"done"}]}]""",
            ) as JsonArray
        }
        for (hub in listOf(untitled, prompted, seeded)) {
            seq = 0
            val store = newStore(hub)
            val ends = collectRunEnds(store)
            store.seed(sid)
            runCurrent()
            runPrompt(store, hub, "next")
            assertNull(ends.single().createTitle)
        }
    }

    @Test
    fun `a run that another client started ends the first-run watch`() = runTest {
        val hub = FakeSessionHub()
        hub.onGet = { record("[test] a13") }
        val store = newStore(hub)
        val ends = collectRunEnds(store)
        store.seed(sid)
        runCurrent()
        val requestId = (store.send(SendRequest(sid, "hello")) as SendOutcome.Sent).requestId
        runCurrent()
        // Our prompt was refused before it ran; another client's run starts and ends instead.
        hub.sends.single { it.requestId == requestId }.fail("internal_error", "boom")
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, """{"requestId":"other-1","clientId":"cli-1"}"""))
        store.dispatch(event(HubEvents.RUN_COMPLETED, """{"requestId":"other-1","reason":"completed"}"""))
        runCurrent()
        val end = ends.single()
        assertEquals("other-1", end.requestId)
        assertNull(end.createTitle)
    }
}
