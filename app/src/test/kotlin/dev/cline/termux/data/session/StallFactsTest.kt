package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.RunHeartbeat
import dev.cline.termux.data.chat.RunPhase
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A4: the stall fact from the kernel's `run.heartbeat` (every 30 s while a turn runs,
 * `HUB_RUN_HEARTBEAT_MS`). Event shapes follow fixture 02 (run.started, the session.updated
 * pair and iteration.started within milliseconds, then deltas).
 */
class StallFactsTest {
    private val t0 = 1_791_064_690_015L
    private var seq = 0L

    private fun event(name: String, at: Long, payload: String) = HubEventEnvelope(
        event = name, sessionId = "s1", sequence = ++seq, timestamp = at,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    /** Applies [name] at [at]; [now] is the receive time (a replay arrives long after its timestamp). */
    private fun SessionFacts.on(name: String, at: Long, payload: String = "{}", now: Long = at + 5): SessionFacts =
        SessionFactsReducer.reduce(this, event(name, at, payload), ReduceContext(clientId = "android-t", now = now))

    private fun SessionFacts.beat(atSecond: Int, now: Long? = null): SessionFacts {
        val at = t0 + atSecond * 1_000L
        return on(HubEvents.RUN_HEARTBEAT, at, """{"requestId":"r1","elapsedMs":${at - t0}}""", now ?: (at + 5))
    }

    private fun SessionFacts.delta(atSecond: Int): SessionFacts =
        on(HubEvents.ASSISTANT_DELTA, t0 + atSecond * 1_000L, """{"text":"chunk"}""")

    /** A turn as the Hub opens it: run.started, the session.updated pair, iteration.started. */
    private fun started(): SessionFacts = SessionFacts("s1")
        .on(HubEvents.RUN_STARTED, t0, """{"requestId":"r1","clientId":"android-t"}""")
        .on(HubEvents.SESSION_UPDATED, t0 + 6, """{"sessionId":"s1","snapshot":{"status":"running"}}""")
        .on(HubEvents.SESSION_UPDATED, t0 + 7, """{"session":{"sessionId":"s1","status":"running"}}""")
        .on(HubEvents.ITERATION_STARTED, t0 + 15, """{"iteration":1}""")

    @Test
    fun `the first heartbeat of a silent run is a stall`() {
        val facts = started().beat(30)
        assertEquals(RunPhase.Running, facts.runPhase)
        assertEquals(StallFact(since = t0 + 30_000, requestId = "r1"), facts.stall)
    }

    @Test
    fun `a heartbeat after a chunk is no stall`() {
        assertNull(started().delta(10).beat(30).stall)
    }

    @Test
    fun `a second heartbeat after a chunk and then silence is a stall`() {
        val first = started().delta(10).beat(30)
        assertNull(first.stall)
        assertEquals(StallFact(since = t0 + 60_000, requestId = "r1"), first.beat(60).stall)
    }

    @Test
    fun `later silent heartbeats keep the episode`() {
        val facts = started().beat(30).beat(60).beat(90)
        assertEquals(t0 + 30_000, facts.stall?.since)
    }

    @Test
    fun `a pending approval is no stall`() {
        val facts = started()
            .on(HubEvents.APPROVAL_REQUESTED, t0 + 5_000, """{"approvalId":"a1","toolCallId":"c1","toolName":"run_commands","inputJson":"{}"}""")
            .beat(30)
            .beat(60)
        assertEquals(RunPhase.AwaitingUser, facts.runPhase)
        assertNull(facts.stall)
    }

    @Test
    fun `a pending question for this client is no stall`() {
        val facts = started()
            .on(
                HubEvents.CAPABILITY_REQUESTED, t0 + 5_000,
                """{"requestId":"q1","capabilityName":"tool_executor.askQuestion","targetClientId":"android-t","payload":{"args":["Which?",["a","b"]]}}""",
            )
            .beat(30)
            .beat(60)
        assertNull(facts.stall)
    }

    @Test
    fun `an executing tool call is no stall, a silence after it finished is`() {
        val running = started()
            .on(HubEvents.TOOL_STARTED, t0 + 5_000, """{"toolCallId":"c1","toolName":"run_commands","input":{"commands":["sleep 100"]}}""")
            .beat(30)
            .beat(60)
        assertNull("a long command is the kernel working", running.stall)
        val finished = running.on(HubEvents.TOOL_FINISHED, t0 + 70_000, """{"toolCallId":"c1","toolName":"run_commands","output":[]}""")
        assertNull(finished.beat(90).stall)
        assertNotNull(finished.beat(90).beat(120).stall)
    }

    @Test
    fun `a replayed heartbeat is no stall and opens no window`() {
        val replayed = started().beat(30, now = t0 + 30_000 + SessionFactsReducer.HEARTBEAT_LIVE_MS + 1)
        assertNull(replayed.stall)
        assertNull(replayed.lastHeartbeatAt)
    }

    @Test
    fun `a chunk ends the stall`() {
        val stalled = started().beat(30)
        assertNotNull(stalled.stall)
        val answered = stalled.delta(31)
        assertNull(answered.stall)
        assertEquals(t0 + 31_000, answered.lastActivityAt)
    }

    @Test
    fun `any other event ends the stall too`() {
        assertNull(started().beat(30).on(HubEvents.SESSION_NOTICE, t0 + 31_000, """{"noticeType":"status","reason":"retry"}""").stall)
    }

    @Test
    fun `the run's end ends the stall and its heartbeat window`() {
        for (end in listOf(HubEvents.RUN_COMPLETED, HubEvents.RUN_FAILED, HubEvents.RUN_ABORTED, HubEvents.RUN_INTERRUPTED)) {
            val ended = started().beat(30).on(end, t0 + 40_000, """{"reason":"completed"}""")
            assertNull(end, ended.stall)
            assertNull(end, ended.lastHeartbeatAt)
            assertEquals(end, RunPhase.Idle, ended.runPhase)
        }
    }

    @Test
    fun `Stop ends the stall and a heartbeat while stopping raises none`() {
        val stopping = SessionFactsReducer.onAbortRequested(started().beat(30))
        assertEquals(RunPhase.Stopping, stopping.runPhase)
        assertNull(stopping.stall)
        assertNull(stopping.beat(60).stall)
    }

    @Test
    fun `an interruption or a verified settle ends the stall`() {
        assertNull(SessionFactsReducer.markInterrupted(started().beat(30)).stall)
        assertNull(SessionFactsReducer.onVerifiedSettled(started().beat(30), t0 + 40_000).stall)
    }

    @Test
    fun `a session opened mid-run judges from its second heartbeat`() {
        val seeded = SessionFactsReducer.fromRecord("s1", null, HubJson.parseToJsonElement("""{"status":"running"}""") as JsonObject)
        assertEquals(RunPhase.Running, seeded.runPhase)
        val first = seeded.beat(30)
        assertNull("no known run start: the first heartbeat only opens the window", first.stall)
        assertNotNull(first.beat(60).stall)
    }

    @Test
    fun `a new run starts a new window`() {
        val previous = started().delta(10).beat(30).on(HubEvents.RUN_COMPLETED, t0 + 35_000, """{"reason":"completed"}""")
        val next = previous.on(HubEvents.RUN_STARTED, t0 + 100_000, """{"requestId":"r2","clientId":"android-t"}""")
        assertNull(next.lastHeartbeatAt)
        val beat = next.on(HubEvents.RUN_HEARTBEAT, t0 + 130_000, """{"requestId":"r2","elapsedMs":30000}""")
        assertEquals(StallFact(since = t0 + 130_000, requestId = "r2"), beat.stall)
    }

    @Test
    fun `RunHeartbeat reads the kernel's payload`() {
        val env = HubEventEnvelope(
            event = HubEvents.RUN_HEARTBEAT, sessionId = "s1", sequence = 9, timestamp = t0,
            payload = HubJson.parseToJsonElement("""{"requestId":"r1","elapsedMs":30001,"timeoutMs":600000}""") as JsonObject,
        )
        assertEquals(RunHeartbeat(requestId = "r1", elapsedMs = 30_001, timeoutMs = 600_000, timestamp = t0), RunHeartbeat.of(env))
        assertNull(RunHeartbeat.of(env.copy(event = HubEvents.RUN_STARTED)))
        assertEquals(RunHeartbeat(null, null, null, null), RunHeartbeat.of(HubEventEnvelope(event = HubEvents.RUN_HEARTBEAT)))
    }
}
