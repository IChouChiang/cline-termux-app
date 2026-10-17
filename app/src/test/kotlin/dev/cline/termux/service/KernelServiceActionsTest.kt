package dev.cline.termux.service

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The notification's Stop agent action goes through [SessionStore.abort] (docs/07 rule 8). */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelServiceActionsTest {
    private val clock = FakeClock()

    private fun TestScope.newStore(hub: FakeSessionHub) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = { "1:a" },
    )

    private fun runStarted(sessionId: String = "s1") = HubEventEnvelope(
        event = HubEvents.RUN_STARTED,
        sessionId = sessionId,
        sequence = 1L,
        timestamp = 1_001L,
        payload = HubJson.parseToJsonElement("""{"requestId":"r1","clientId":"tui"}""") as JsonObject,
    )

    @Test
    fun `stop agent moves the session to Stopping and confirms the keep-alive stop`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.dispatch(runStarted())
        runCurrent()
        assertEquals(RunPhase.Running, store.state("s1").value.facts.runPhase)
        val confirmed = mutableListOf<String>()

        val ok = KernelServiceActions.runStopAgent("s1", KernelGate.Ready, store::abort) { confirmed += it }

        assertTrue(ok)
        assertEquals(listOf("run.abort"), hub.calls.filter { it == "run.abort" })
        assertEquals(RunPhase.Stopping, store.state("s1").value.facts.runPhase)
        assertEquals(listOf("s1"), confirmed)
    }

    @Test
    fun `a refused abort restores the phase and keeps the notification variant`() = runTest {
        val hub = FakeSessionHub()
        hub.onAbort = { throw HubCommandException("run.abort", "session_not_found", "Session not found") }
        val store = newStore(hub)
        store.dispatch(runStarted())
        runCurrent()
        val confirmed = mutableListOf<String>()

        val ok = KernelServiceActions.runStopAgent("s1", KernelGate.Ready, store::abort) { confirmed += it }

        assertFalse(ok)
        assertEquals(RunPhase.Running, store.state("s1").value.facts.runPhase)
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `a tap after the gate left Ready is dropped`() = runTest {
        val hub = FakeSessionHub()
        val store = newStore(hub)
        store.dispatch(runStarted())
        runCurrent()
        val confirmed = mutableListOf<String>()

        val ok = KernelServiceActions.runStopAgent(
            "s1",
            KernelGate.Down(KernelGate.DownVariant.Disconnected, null),
            store::abort,
        ) { confirmed += it }

        assertFalse(ok)
        assertTrue(hub.calls.none { it == "run.abort" })
        assertEquals(RunPhase.Running, store.state("s1").value.facts.runPhase)
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `an unmapped abort error is swallowed without confirming`() = runTest {
        val confirmed = mutableListOf<String>()
        val ok = KernelServiceActions.runStopAgent("s1", KernelGate.Ready, { throw IllegalStateException("protocol") }) { confirmed += it }
        assertFalse(ok)
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `cancellation propagates`() = runTest {
        try {
            KernelServiceActions.runStopAgent("s1", KernelGate.Ready, { throw CancellationException("scope gone") }) { fail("confirmed") }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
