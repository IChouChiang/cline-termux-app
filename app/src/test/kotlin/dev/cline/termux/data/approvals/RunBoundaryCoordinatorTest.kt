package dev.cline.termux.data.approvals

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.session.AUTO_APPROVE_TOOLS_KEY
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.TurnOutcome
import dev.cline.termux.data.session.bool
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run-boundary restart (decided A1; batch 1 brief §7.1, F2): a real [SessionStore] over
 * [FakeSessionHub], with the global mode read through a scripted Termux.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunBoundaryCoordinatorTest {
    private val me = "android-0f1e2d3c"
    private val sessionId = "s1"
    private var stored = """{"toolAutoApprove":false}"""
    private val history: JsonArray = buildJsonArray {
        addJsonObject {
            put("id", "m1")
            put("role", "user")
            put("ts", 1)
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "hi") } }
        }
    }

    /** The Hub's record of [sessionId]: its policies, status and attached clients. */
    private var recordAuto: Boolean? = true
    private var recordStatus = "idle"
    private var participants = listOf(me)

    private fun record(id: String) = buildJsonObject {
        putJsonObject("session") {
            put("sessionId", id)
            put("status", recordStatus)
            put("workspaceRoot", "/w")
            putJsonObject("metadata") {
                recordAuto?.let { put("autoApproveTools", it) }
                put("provider", "deepseek")
                put("model", "deepseek-chat")
                put("source", "android")
            }
            putJsonArray("participants") { participants.forEach { id -> addJsonObject { put("clientId", id) } } }
        }
    }

    private class Env(val hub: FakeSessionHub, val store: SessionStore, val approvals: ApprovalModeRepository, val coordinator: RunBoundaryCoordinator)

    /** The repository's monotonic clock (ms) and how many times it ran the Termux read. */
    private var now = 0L
    private var reads = 0

    private fun TestScope.env(): Env {
        val hub = FakeSessionHub(me)
        hub.history = history
        hub.onGet = { id -> record(id) }
        // The Hub stamps `metadata.autoApproveTools` from the new policies (session-handlers.ts).
        hub.onCreate = { request ->
            recordAuto = request.spec.autoApprove
            record(request.spec.resumeSessionId ?: "s-new")
        }
        val approvals = ApprovalModeRepository(
            run = {
                reads++
                TermuxResult(stdout = stored, stderr = "", exitCode = 0, err = TermuxResult.ERR_NONE, errmsg = null)
            },
            scope = backgroundScope,
            clock = { now },
        )
        val store = SessionStore(
            hub = hub,
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = FakeClock(),
            hubLifetimeKey = { "1:a" },
            autoApproveNow = { approvals.autoApproveForNewSession() },
        )
        val coordinator = RunBoundaryCoordinator(store, approvals, clientId = { me }, scope = backgroundScope)
        return Env(hub, store, approvals, coordinator)
    }

    private suspend fun TestScope.opened(): Env {
        val e = env()
        e.store.open(sessionId)
        e.approvals.refresh(ApprovalRefresh.Foreground)
        runCurrent()
        return e
    }

    private fun event(name: String, sequence: Long, payload: String) = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = sequence,
        timestamp = 1_000L + sequence,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    private fun ended(ours: Boolean = true) = dev.cline.termux.data.session.RunEnded(sessionId, "r1", ours, TurnOutcome.Completed)

    private val Env.restarted: Boolean get() = "session.detach" in hub.calls

    @Test
    fun `the end of our run restarts a session whose policies differ, keeping its messages`() = runTest {
        val e = opened()
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()

        val detach = e.hub.calls.indexOf("session.detach")
        val create = e.hub.calls.lastIndexOf("session.create")
        assertTrue("detach first, then the resume create", detach in 0 until create)
        val request = e.hub.creates.single()
        assertEquals(sessionId, request.spec.resumeSessionId)
        assertFalse(request.spec.autoApprove)
        assertEquals(history, request.payload()["initialMessages"])
        val policies = request.payload()["toolPolicies"]!!.jsonObject
        assertEquals("false", policies["*"]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content)
        assertTrue("Ask keeps the read-only tools", "read_files" in policies)
        assertEquals(false, e.store.state(sessionId).value.facts.metadata.bool(AUTO_APPROVE_TOOLS_KEY))
        assertFalse(e.store.state(sessionId).value.creating)
    }

    @Test
    fun `a session that already has the global policies is left alone`() = runTest {
        recordAuto = false
        val e = opened()
        assertFalse(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertFalse(e.restarted)
        assertTrue(e.hub.creates.isEmpty())
    }

    @Test
    fun `a session another client is attached to is never restarted`() = runTest {
        participants = listOf(me, "cli-terminal")
        val e = opened()
        assertFalse(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertFalse(e.restarted)
    }

    @Test
    fun `the end of another client's run never restarts`() = runTest {
        val e = opened()
        e.coordinator.start()
        runCurrent()
        e.store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"r9","clientId":"tui"}"""))
        e.store.dispatch(event(HubEvents.RUN_COMPLETED, 2, """{"requestId":"r9","reason":"completed"}"""))
        runCurrent()
        assertFalse(e.restarted)
        assertFalse(e.coordinator.onRunEnded(ended(ours = false)))
    }

    @Test
    fun `a closed session is not restarted`() = runTest {
        val e = opened()
        e.store.close(sessionId)
        runCurrent()
        assertFalse(e.coordinator.onRunEnded(ended()))
        assertFalse(e.restarted)
    }

    @Test
    fun `Stop ends the run like any other end and the restart follows`() = runTest {
        val e = opened()
        e.coordinator.start()
        runCurrent()
        val requestId = (e.store.send(SendRequest(sessionId, "work")) as SendOutcome.Sent).requestId
        e.store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"$requestId","clientId":"$me"}"""))
        runCurrent()
        assertFalse("never while the run is going", e.restarted)

        assertTrue(e.store.abort(sessionId).isSuccess)
        e.store.dispatch(event(HubEvents.RUN_ABORTED, 2, """{"requestId":"$requestId"}"""))
        runCurrent()
        assertTrue(e.restarted)
        assertFalse(e.hub.creates.single().spec.autoApprove)
    }

    @Test
    fun `an idle send with different policies restarts before the prompt goes out`() = runTest {
        val e = opened()
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        e.store.send(SendRequest(sessionId, "next"))
        runCurrent()
        val calls = e.hub.calls
        assertTrue(calls.indexOf("session.detach") in 0 until calls.indexOf("session.create"))
        assertTrue(calls.lastIndexOf("session.create") < calls.indexOf("session.send_input"))
        assertFalse(e.hub.creates.single().spec.autoApprove)
    }

    @Test
    fun `a send to a running session restarts nothing`() = runTest {
        recordStatus = "running"
        val e = opened()
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertFalse(e.restarted)
    }

    @Test
    fun `a failed detach fails the send and clears creating`() = runTest {
        val e = opened()
        e.hub.onDetach = { throw FakeSessionHub.error("session.detach", "internal_error", "boom") }
        assertTrue(e.coordinator.beforeSend(sessionId, null).isFailure)
        runCurrent()
        assertTrue(e.hub.creates.isEmpty())
        assertFalse(e.store.state(sessionId).value.creating)
    }

    @Test
    fun `turning Auto-approve all on restarts into the wildcard policy`() = runTest {
        recordAuto = false
        stored = "{}"
        val e = opened()
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        val policies = e.hub.creates.single().payload()["toolPolicies"]!!.jsonObject
        assertEquals(setOf("*"), policies.keys)
    }

    @Test
    fun `pending is shown while an open session's run still auto-approves under Ask`() = runTest {
        val e = opened()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch { e.coordinator.pendingSwitch.collect { values += it } }
        runCurrent()
        val requestId = (e.store.send(SendRequest(sessionId, "work")) as SendOutcome.Sent).requestId
        e.store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"$requestId","clientId":"$me"}"""))
        runCurrent()
        assertTrue(values.last())
    }

    // ---- a pin drops the key from the record (session-handlers.ts stripServerOwnedSessionMetadata) ----

    /** The Hub's `session.updated` after a pin: the record's metadata no longer has `autoApproveTools`. */
    private suspend fun Env.pin(sequence: Long) {
        recordAuto = null
        val session = record(sessionId).getValue("session").jsonObject
        store.dispatch(event(HubEvents.SESSION_UPDATED, sequence, """{"session":$session}"""))
    }

    @Test
    fun `a pinned live session still restarts into Ask at the end of our run`() = runTest {
        // Created under Auto, then the mode turned to Ask; the pin stripped the key from the record.
        val e = opened()
        e.pin(1)
        runCurrent()
        assertEquals(null, e.store.state(sessionId).value.facts.metadata.bool(AUTO_APPROVE_TOOLS_KEY))
        assertEquals(true, e.store.state(sessionId).value.autoApproveTools)

        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertTrue(e.restarted)
        assertFalse(e.hub.creates.single().spec.autoApprove)
        // The resume's record carries the key again.
        assertEquals(false, e.store.state(sessionId).value.autoApproveTools)
    }

    @Test
    fun `a pinned idle session restarts before the next send`() = runTest {
        val e = opened()
        e.pin(1)
        runCurrent()
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertTrue(e.restarted)
        assertFalse(e.hub.creates.single().spec.autoApprove)
    }

    @Test
    fun `a pinned session whose last seen policy matches is left alone`() = runTest {
        recordAuto = false
        val e = opened()
        e.pin(1)
        runCurrent()
        assertFalse(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertFalse(e.restarted)
    }

    @Test
    fun `the pending ring survives a pin while the run still auto-approves`() = runTest {
        val e = opened()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch { e.coordinator.pendingSwitch.collect { values += it } }
        runCurrent()
        val requestId = (e.store.send(SendRequest(sessionId, "work")) as SendOutcome.Sent).requestId
        e.store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"$requestId","clientId":"$me"}"""))
        recordStatus = "running"
        e.pin(2)
        runCurrent()
        assertTrue(values.last())
    }

    @Test
    fun `a session whose policy was never seen restarts once, then carries the key`() = runTest {
        // The app process started after the pin: no record of this process had the key.
        recordAuto = null
        val e = opened()
        assertEquals(null, e.store.state(sessionId).value.autoApproveTools)
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertEquals(1, e.hub.creates.size)
        assertFalse(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertEquals(1, e.hub.creates.size)
    }

    // ---- core resets the record to the creation-time policy at a run's end (V-P1-A1) ---------------

    /** The record the Hub sends with `session.updated` / `run.*` at a run's end: the persisted, creation-time policy. */
    private suspend fun Env.staleRunEnd(sequence: Long, persisted: Boolean) {
        recordAuto = persisted
        val session = record(sessionId).getValue("session").jsonObject
        store.dispatch(event(HubEvents.SESSION_UPDATED, sequence, """{"session":$session}"""))
        store.dispatch(event(HubEvents.RUN_COMPLETED, sequence + 1, """{"requestId":"r1","reason":"completed"}"""))
    }

    @Test
    fun `session created true, then session updated false at run completed, under Ask restarts`() = runTest {
        recordAuto = false
        val e = opened()
        // Any client's create: the runtime runs with auto-approve on.
        val created = buildJsonObject {
            put("sessionId", sessionId)
            putJsonObject("metadata") { put("autoApproveTools", true) }
        }
        e.store.dispatch(event(HubEvents.SESSION_CREATED, 1, """{"session":$created}"""))
        e.staleRunEnd(2, persisted = false)
        runCurrent()
        assertEquals(false, e.store.state(sessionId).value.facts.metadata.bool(AUTO_APPROVE_TOOLS_KEY))
        assertEquals("the create outranks the stale record", true, e.store.state(sessionId).value.autoApproveTools)

        // session.get after the run reads the stale value too; the create still wins.
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertTrue(e.restarted)
        assertFalse(e.hub.creates.single().spec.autoApprove)
    }

    @Test
    fun `a session the app resumed under Auto restarts into Ask at the end of its run despite the stale record`() = runTest {
        // Created under Ask; the global mode turned to Auto, so the run end restarted it into Auto.
        recordAuto = false
        stored = "{}"
        val e = opened()
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertTrue(e.hub.creates.single().spec.autoApprove)
        assertEquals(true, e.store.state(sessionId).value.createdAutoApprove)

        // Switched to Ask mid-run; core's run-end records carry the creation-time false again.
        stored = """{"toolAutoApprove":false}"""
        e.staleRunEnd(1, persisted = false)
        runCurrent()
        assertEquals(true, e.store.state(sessionId).value.autoApproveTools)
        assertTrue("the run end restarts into Ask", e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertEquals(2, e.hub.creates.size)
        assertFalse(e.hub.creates.last().spec.autoApprove)
        assertEquals(false, e.store.state(sessionId).value.autoApproveTools)
    }

    @Test
    fun `a stale record never makes Stop's run end skip the restart`() = runTest {
        recordAuto = false
        stored = "{}"
        val e = opened()
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertTrue(e.hub.creates.single().spec.autoApprove)
        e.coordinator.start()
        runCurrent()

        stored = """{"toolAutoApprove":false}"""
        val requestId = (e.store.send(SendRequest(sessionId, "work")) as SendOutcome.Sent).requestId
        e.store.dispatch(event(HubEvents.RUN_STARTED, 1, """{"requestId":"$requestId","clientId":"$me"}"""))
        recordAuto = false
        val session = record(sessionId).getValue("session").jsonObject
        e.store.dispatch(event(HubEvents.SESSION_UPDATED, 2, """{"session":$session}"""))
        assertTrue(e.store.abort(sessionId).isSuccess)
        e.store.dispatch(event(HubEvents.RUN_ABORTED, 3, """{"requestId":"$requestId"}"""))
        runCurrent()
        assertEquals(2, e.hub.creates.size)
        assertFalse(e.hub.creates.last().spec.autoApprove)
    }

    @Test
    fun `a session restarted into Ask is not restarted again when its record goes back to true`() = runTest {
        // The owner's default: created under Auto, then switched to Ask.
        recordAuto = true
        val e = opened()
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        assertEquals(1, e.hub.creates.size)

        e.staleRunEnd(1, persisted = true)
        runCurrent()
        assertEquals("no pending ring under Ask", false, e.store.state(sessionId).value.autoApproveTools)
        assertFalse(e.coordinator.onRunEnded(ended()))
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertEquals("one restart, not one before every send", 1, e.hub.creates.size)
    }

    // ---- compaction state and the before-send read --------------------------------------------------

    @Test
    fun `a restart passes the runtime's compaction state on, read before the detach`() = runTest {
        val compacted = buildJsonObject {
            put("version", 1)
            put("source_message_count", 1)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "summary") } }
        }
        val e = opened()
        e.hub.onCompaction = { id -> buildJsonObject { put("sessionId", id); put("state", compacted) } }
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        val calls = e.hub.calls
        assertTrue(calls.indexOf("session.compaction.get") in 0 until calls.indexOf("session.detach"))
        assertEquals(compacted, e.hub.creates.single().payload()["initialCompactionState"])
    }

    @Test
    fun `a compaction state the Hub will not hand out leaves it to the Hub's own sidecar`() = runTest {
        val e = opened()
        e.hub.onCompaction = { throw FakeSessionHub.error("session.compaction.get", "session_wrong_client", "owned by tui") }
        assertTrue(e.coordinator.onRunEnded(ended()))
        runCurrent()
        val payload = e.hub.creates.single().payload()
        assertFalse("initialCompactionState" in payload)
        assertEquals(history, payload["initialMessages"])
    }

    @Test
    fun `a send re-reads the setting only when the last read is older than a few seconds`() = runTest {
        val e = opened()
        val afterOpen = reads
        now += RunBoundaryCoordinator.BEFORE_SEND_FRESH_MS
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertEquals("a read that fresh is reused", afterOpen, reads)
        assertFalse("restarted into Ask", e.hub.creates.single().spec.autoApprove)

        now += RunBoundaryCoordinator.BEFORE_SEND_FRESH_MS + 1
        stored = "{}"
        assertTrue(e.coordinator.beforeSend(sessionId, null).isSuccess)
        runCurrent()
        assertEquals(afterOpen + 1, reads)
        // The terminal turned Auto-approve all back on meanwhile: the send restarts into Auto.
        assertTrue(e.hub.creates.last().spec.autoApprove)
    }

    // ---- RestartRule (pure) ------------------------------------------------------------------------

    @Test
    fun `restart rule - unknown policies count as different, the value last seen stands in for a missing key`() {
        recordAuto = null
        assertTrue(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        assertTrue(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me, lastSeen = true))
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me, lastSeen = false))
        participants = listOf(me, "cli-terminal")
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        participants = listOf(me)
        recordStatus = "running"
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        // The record's own key wins over the value last seen.
        recordStatus = "idle"
        recordAuto = false
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me, lastSeen = true))
    }

    @Test
    fun `restart rule - a seen create outranks the record and the value last seen`() {
        recordAuto = false
        assertTrue(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me, lastSeen = false, created = true))
        recordAuto = true
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me, lastSeen = true, created = false))
        participants = listOf(me, "cli-terminal")
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = true, clientId = me, created = false))
    }

    @Test
    fun `restart rule - equal policies, other clients and a running record all say no`() {
        recordAuto = false
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        recordAuto = true
        assertTrue(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        participants = listOf(me, "desktop-1")
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        participants = emptyList()
        assertTrue(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
        recordStatus = "running"
        assertFalse(RunBoundaryCoordinator.RestartRule.shouldRestart(record(sessionId), auto = false, clientId = me))
    }
}
