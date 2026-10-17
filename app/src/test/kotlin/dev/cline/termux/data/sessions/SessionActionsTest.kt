package dev.cline.termux.data.sessions

import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.data.chat.EmptySessionForkException
import dev.cline.termux.data.chat.ForkTitleStrings
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateRequest
import dev.cline.termux.data.prefs.SessionListCache
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionsHub
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.InMemorySessionsPersistence
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SS §5 F2-F5 data parts and F1 resume over the fakes; fixture 05 supplies the fork source. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock(Instant.parse("2026-10-03T22:00:00Z"))
    private val fixture05 by lazy { HubFixtures.load("05-resume-after-restart.jsonl") }

    private class Env(
        val repo: SessionsRepository,
        val actions: SessionActions,
        val hub: FakeSessionsHub,
        val creates: MutableList<SessionCreateRequest>,
    ) {
        var resumeResult: Result<Unit> = Result.success(Unit)
        var createResult: Result<String> = Result.success("fork-1")

        /** G3: each removed worktree with the Hub calls made before it. */
        val removedWorktrees = mutableListOf<Pair<String, List<String>>>()
    }

    private fun j(text: String): JsonObject = HubJson.parseToJsonElement(text).jsonObject

    private fun record(id: String, updatedAt: Long = 1_000, metadata: String = """"provider":"p","model":"m","title":"Old title"""", cwd: String = "/w") =
        j("""{"sessionId":"$id","workspaceRoot":"$cwd","cwd":"$cwd","createdAt":100,"updatedAt":$updatedAt,"status":"completed","participants":[],"metadata":{$metadata}}""")

    private fun TestScope.env(vararg rows: JsonObject, connected: Boolean = true, compaction: JsonObject? = null): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = FakeSessionsHub("android-test")
        hub.onList = { buildJsonObject { put("sessions", JsonArray(rows.toList())) } }
        val cache = SessionListCache(tmp.root, SessionsCachePayload.serializer(), clock = clock, io = dispatcher)
        val repo = SessionsRepository(hub, InMemorySessionsPersistence(), cache, backgroundScope, dispatcher, clock)
        val creates = mutableListOf<SessionCreateRequest>()
        lateinit var env: Env
        val actions = SessionActions(
            hub = hub,
            repository = repo,
            createSession = { request ->
                creates += request
                env.createResult
            },
            resumeSession = { _, _ -> env.resumeResult },
            autoApproveDefault = { false },
            forkTitleStrings = { ForkTitleStrings("Untitled", " (fork)") },
            clock = clock,
            removeWorktree = { path -> env.removedWorktrees += path to hub.calls.toList() },
            compaction = { compaction },
        )
        env = Env(repo, actions, hub, creates)
        repo.start()
        if (connected) hub.connect()
        settle()
        return env
    }

    private fun JsonObject.meta(): JsonObject = this["metadata"]!!.jsonObject

    // ---- F2 rename ----------------------------------------------------------------------------------

    @Test fun `rename spreads the current metadata and keeps the row's age`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1", metadata = """"provider":"p","model":"m","title":"Old title","pinned":true,"usage":{"inputTokens":5}""")}}""") }
        val gate = CompletableDeferred<Unit>()
        e.hub.onUpdate = { payload ->
            gate.await()
            j("""{"updated":true,"session":${record("s1", updatedAt = 9_000, metadata = """"provider":"p","model":"m","title":"New title","pinned":true""")}}""")
        }
        val result = async { e.actions.rename("s1", "  New title  ") }
        runCurrent()
        assertEquals(SessionVisual.ActionPending(PendingKind.Rename), e.repo.state.value.row("s1")!!.visual)
        gate.complete(Unit)
        assertEquals(ActionOutcome.Done, result.await())
        val payload = e.hub.updates.single()
        assertEquals(JsonPrimitive("New title"), payload["title"])
        assertEquals(JsonPrimitive("New title"), payload.meta()["title"])
        assertEquals("metadata spread", JsonPrimitive(true), payload.meta()["pinned"])
        assertTrue(payload.meta().containsKey("usage"))
        val row = e.repo.state.value.row("s1")!!
        assertEquals("New title", row.summary.title)
        assertEquals("annotation is not activity", 1_000L, row.summary.activityAt)
        assertNull(row.flags.pendingAction)
    }

    @Test fun `blank rename clears the custom title`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        assertEquals(ActionOutcome.Done, e.actions.rename("s1", "   "))
        val payload = e.hub.updates.single()
        assertEquals(JsonPrimitive(""), payload["title"])
        assertFalse(payload.meta().containsKey("title"))
        assertEquals(JsonPrimitive("p"), payload.meta()["provider"])
    }

    @Test fun `unchanged rename sends nothing`() = runTest {
        val e = env(record("s1"), record("s2", metadata = """"provider":"p","model":"m""""))
        assertEquals(ActionOutcome.Unchanged, e.actions.rename("s1", "Old title"))
        assertEquals("blank without a custom title", ActionOutcome.Unchanged, e.actions.rename("s2", ""))
        assertTrue(e.hub.updates.isEmpty())
    }

    @Test fun `rename failure keeps the kernel message and clears pending`() = runTest {
        val e = env(record("s1"))
        e.hub.onUpdate = { throw FakeSessionsHub.error("session.update", "command_failed", "disk full") }
        assertEquals(ActionOutcome.Failed(ActionFailure.Kernel("disk full")), e.actions.rename("s1", "Next"))
        assertTrue(e.repo.state.value.pending.isEmpty())
        e.hub.onUpdate = { j("""{"updated":false}""") }
        assertEquals(ActionOutcome.Failed(ActionFailure.NotApplied), e.actions.rename("s1", "Next"))
    }

    @Test fun `actions need a connection`() = runTest {
        val e = env(record("s1"), connected = false)
        assertEquals(ActionOutcome.Failed(ActionFailure.NotConnected), e.actions.rename("s1", "x"))
        assertEquals(ActionOutcome.Failed(ActionFailure.NotConnected), e.actions.setPinned("s1", true))
        assertEquals(ActionOutcome.Failed(ActionFailure.NotConnected), e.actions.fork("s1"))
        assertEquals(ActionOutcome.Failed(ActionFailure.NotConnected), e.actions.delete("s1"))
        assertTrue(e.hub.calls.none { it.startsWith("session.") })
    }

    // ---- F3 pin -------------------------------------------------------------------------------------

    @Test fun `pin is optimistic and writes the full metadata`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        val gate = CompletableDeferred<Unit>()
        e.hub.onUpdate = {
            gate.await()
            j("""{"updated":true}""")
        }
        val result = async { e.actions.setPinned("s1", true) }
        runCurrent()
        assertTrue("flipped at once", e.repo.state.value.row("s1")!!.summary.pinned)
        assertNull("pin is never pending", e.repo.state.value.row("s1")!!.flags.pendingAction)
        gate.complete(Unit)
        assertEquals(ActionOutcome.Done, result.await())
        assertEquals(JsonPrimitive(true), e.hub.updates.single().meta()["pinned"])
        assertEquals(JsonPrimitive("Old title"), e.hub.updates.single().meta()["title"])
        assertTrue("stays pinned until the next record", e.repo.state.value.row("s1")!!.summary.pinned)
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s1", metadata = """"provider":"p","model":"m","title":"Old title","pinned":true""")))) } }
        val lists = e.hub.listLimits.size
        settle()
        assertEquals("refresh after 50 ms", lists + 1, e.hub.listLimits.size)
        assertTrue(e.repo.state.value.row("s1")!!.summary.pinned)
    }

    @Test fun `unpin removes the key and a failure rolls back`() = runTest {
        val pinned = record("s1", metadata = """"provider":"p","model":"m","pinned":true""")
        val e = env(pinned)
        e.hub.onGet = { j("""{"session":$pinned}""") }
        assertEquals(ActionOutcome.Done, e.actions.setPinned("s1", false))
        assertFalse(e.hub.updates.single().meta().containsKey("pinned"))
        e.hub.onUpdate = { throw HubConnectionClosedException("gone") }
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(pinned))) } }
        assertTrue(e.repo.refresh())
        assertTrue(e.repo.state.value.row("s1")!!.summary.pinned)
        assertEquals(ActionOutcome.Failed(ActionFailure.NotConnected), e.actions.setPinned("s1", false))
        assertTrue("rolled back", e.repo.state.value.row("s1")!!.summary.pinned)
    }

    // ---- F4 fork ------------------------------------------------------------------------------------

    @Test fun `fork builds session create from the source record and messages`() = runTest {
        val id = "1791064689782_kck3p"
        val e = env(record(id))
        val getReply = HubFixtures.replyTo(fixture05, "session-get-011").payload!!
        val source = JsonObject(getReply["session"]!!.jsonObject + mapOf(
            "metadata" to JsonObject(getReply["session"]!!.jsonObject.meta() + mapOf(
                "scheduleId" to JsonPrimitive("sch"), "sessionHistoryOrigin" to j("""{"trigger":"hub-schedule"}"""),
                "totalCost" to JsonPrimitive(1.5), "checkpoint" to j("""{"history":[]}"""),
            )),
        ))
        e.hub.onGet = { JsonObject(getReply + ("session" to source)) }
        e.hub.onMessages = { HubFixtures.messagesReply(fixture05, id) }
        val outcome = e.actions.fork(id)
        assertEquals(ActionOutcome.Forked("fork-1"), outcome)
        val request = e.creates.single()
        val metadata = request.metadata
        assertEquals(JsonPrimitive("Fixture: hello-from-mock (fork)"), metadata["title"])
        assertEquals(JsonPrimitive("android"), metadata["source"])
        assertEquals(JsonPrimitive(true), metadata["interactive"])
        assertEquals(JsonPrimitive(id), metadata["fork"]!!.jsonObject["forkedFromSessionId"])
        assertEquals(JsonPrimitive("2026-10-03T22:00:00Z"), metadata["fork"]!!.jsonObject["forkedAt"])
        for (key in FORK_DROPPED_METADATA_KEYS - "title") assertFalse("drops $key", metadata.containsKey(key))
        assertFalse("docs/07 §14 strips checkpoint", metadata.containsKey("checkpoint"))
        assertEquals("openai-compatible", request.spec.providerId)
        assertEquals("mock-1", request.spec.modelId)
        assertTrue(request.spec.initialMessages!!.isNotEmpty())
        assertTrue(request.payload()["sessionConfig"]!!.jsonObject["checkpoint"]!!.jsonObject["enabled"] == JsonPrimitive(true))
        assertTrue(e.repo.state.value.pending.isEmpty())
    }

    @Test fun `fork of an empty session fails without creating`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        assertEquals(ActionOutcome.Failed(ActionFailure.EmptySession), e.actions.fork("s1"))
        assertTrue(e.creates.isEmpty())
        e.hub.onGet = { j("""{"session":${record("s1", metadata = """"provider":"unknown","model":"m"""")}}""") }
        e.hub.onMessages = { j("""{"messages":[{"role":"user","content":"hi"}]}""") }
        assertEquals(ActionOutcome.Failed(ActionFailure.MissingModel), e.actions.fork("s1"))
    }

    @Test fun `fork create failure surfaces the kernel message`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        e.hub.onMessages = { j("""{"messages":[{"role":"user","content":"hi"}]}""") }
        e.createResult = Result.failure(HubCommandException("session.create", "hub_draining", "Hub is draining"))
        assertEquals(ActionOutcome.Failed(ActionFailure.Kernel("Hub is draining")), e.actions.fork("s1"))
    }

    @Test fun `fork copies the whole history and writes no beforeRunCount (A16)`() = runTest {
        val e = env(record("s1"))
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        e.hub.onMessages = {
            j(
                """{"messages":[
                {"id":"u0","role":"user","content":"first"},{"id":"a0","role":"assistant","content":"one"},
                {"id":"u1","role":"user","content":"second"},{"id":"a1","role":"assistant","content":"two"}]}""",
            )
        }
        assertEquals(ActionOutcome.Forked("fork-1"), e.actions.fork("s1"))
        val request = e.creates.single()
        assertEquals(listOf("u0", "a0", "u1", "a1"), request.spec.initialMessages!!.map { (it.jsonObject["id"] as JsonPrimitive).content })
        val fork = request.metadata["fork"]!!.jsonObject
        assertEquals(JsonPrimitive("s1"), fork["forkedFromSessionId"])
        assertEquals(JsonPrimitive("android"), fork["source"])
        // The terminal's buildForkSessionMetadata and the desktop's handleForkUnlocked write none.
        assertFalse(fork.containsKey("beforeRunCount"))
        assertEquals(JsonPrimitive("Old title (fork)"), request.metadata["title"])
    }

    @Test fun `fork carries the global compaction mode`() = runTest {
        val off = j("""{"enabled":false}""")
        val e = env(record("s1"), compaction = off)
        e.hub.onGet = { j("""{"session":${record("s1")}}""") }
        e.hub.onMessages = { j("""{"messages":[{"role":"user","content":"hi"}]}""") }
        assertEquals(ActionOutcome.Forked("fork-1"), e.actions.fork("s1"))
        val request = e.creates.single()
        assertEquals(off, request.spec.compaction)
        assertEquals(off, (request.payload()["sessionConfig"] as JsonObject)["compaction"])
    }

    // ---- F5 delete ----------------------------------------------------------------------------------

    @Test fun `delete removes the row and refreshes`() = runTest {
        val e = env(record("s1"), record("s2"))
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s2")))) } }
        val lists = e.hub.listLimits.size
        assertEquals(ActionOutcome.Done, e.actions.delete("s1"))
        assertEquals(listOf("s2"), e.repo.state.value.rows.map { it.id })
        assertEquals(lists + 1, e.hub.listLimits.size)
    }

    @Test fun `deleted false is verified with session get`() = runTest {
        val e = env(record("s1"), record("s2"))
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s2")))) } }
        e.hub.onDelete = { j("""{"deleted":false}""") }
        e.hub.onGet = { throw FakeSessionsHub.error("session.get", "session_not_found", "Unknown session: s1") }
        assertEquals(ActionOutcome.Done, e.actions.delete("s1"))
        assertNull(e.repo.state.value.row("s1"))
        e.hub.onGet = { j("""{"session":${record("s2")}}""") }
        assertEquals(ActionOutcome.Failed(ActionFailure.NotApplied), e.actions.delete("s2"))
        assertTrue(e.repo.state.value.row("s2") != null)
    }

    // ---- G3 task worktrees ---------------------------------------------------------------------------

    private val worktree = "${WorktreeCommands.WORKTREES_ROOT}/5e0b3/repo"

    @Test fun `deleting a worktree session removes the worktree after the delete`() = runTest {
        val e = env(record("s1", cwd = worktree), record("s2"))
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s2")))) } }
        assertEquals(ActionOutcome.Done, e.actions.delete("s1"))
        val (path, callsBefore) = e.removedWorktrees.single()
        assertEquals(worktree, path)
        assertTrue("session.delete" in callsBefore)
        assertNull(e.repo.state.value.row("s1"))
    }

    @Test fun `deleting a normal session removes no worktree`() = runTest {
        val e = env(record("s1"), record("s2"))
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s2")))) } }
        assertEquals(ActionOutcome.Done, e.actions.delete("s1"))
        assertTrue(e.removedWorktrees.isEmpty())
    }

    @Test fun `a worktree another session still uses is kept`() = runTest {
        val e = env(record("s1", cwd = worktree), record("s2", cwd = "$worktree/sub"))
        e.hub.onList = { buildJsonObject { put("sessions", JsonArray(listOf(record("s2", cwd = "$worktree/sub")))) } }
        assertEquals(ActionOutcome.Done, e.actions.delete("s1"))
        assertTrue(e.removedWorktrees.isEmpty())
    }

    @Test fun `a delete that did not apply keeps the worktree`() = runTest {
        val e = env(record("s1", cwd = worktree))
        e.hub.onDelete = { j("""{"deleted":false}""") }
        e.hub.onGet = { j("""{"session":${record("s1", cwd = worktree)}}""") }
        assertEquals(ActionOutcome.Failed(ActionFailure.NotApplied), e.actions.delete("s1"))
        assertTrue(e.removedWorktrees.isEmpty())
    }

    @Test fun `an unlisted worktree session is read with session get first`() = runTest {
        val e = env(record("s2"))
        e.hub.onGet = { id -> j("""{"session":${record(id, cwd = worktree)}}""") }
        assertEquals(ActionOutcome.Done, e.actions.delete("s9"))
        assertEquals(listOf("s9"), e.hub.gets)
        assertEquals(worktree, e.removedWorktrees.single().first)
    }

    // ---- F1 resume ----------------------------------------------------------------------------------

    @Test fun `resume maps the store's result`() = runTest {
        val e = env(record("s1"))
        assertEquals(ActionOutcome.Done, e.actions.resume("s1", SessionConfigInput("/w", providerId = "p", modelId = "m")))
        e.resumeResult = Result.failure(HubCommandException("session.create", "command_failed", "no credentials"))
        assertEquals(ActionOutcome.Failed(ActionFailure.Kernel("no credentials")), e.actions.resume("s1"))
        assertFalse(e.repo.state.value.row("s1")!!.flags.starting)
    }

    @Test fun `errors map to the failure taxonomy`() {
        assertEquals(ActionFailure.NotConnected, HubCommandException("x", "hub_connection_closed", "closed").toActionFailure())
        assertEquals(ActionFailure.NotConnected, HubConnectionClosedException("no socket").toActionFailure())
        assertEquals(ActionFailure.Gone, HubCommandException("x", "session_not_found", "Unknown session: a").toActionFailure())
        // The app's own reply deadline is not kernel text: the snackbar falls back to the flow's sentence.
        assertEquals(ActionFailure.TimedOut, HubCommandException("x", dev.cline.termux.core.kernel.hub.HubErrorCodes.CLIENT_TIMEOUT, "no reply within 35000 ms").toActionFailure())
        assertEquals(ActionFailure.EmptySession, EmptySessionForkException().toActionFailure())
        // A create SessionStore could not deliver (no reconnect in time) reads as "not connected", not as its message.
        assertEquals(ActionFailure.NotConnected, dev.cline.termux.data.session.SessionCreateDisconnectedException().toActionFailure())
        assertEquals(ActionFailure.Kernel("boom"), HubCommandException("x", "command_failed", "boom").toActionFailure())
    }

    @Test fun `update payload builders`() {
        val m = j("""{"provider":"p","title":"a","pinned":true}""")
        assertEquals(j("""{"sessionId":"s","title":"b","metadata":{"provider":"p","title":"b","pinned":true}}"""), SessionUpdatePayloads.rename("s", m, "b"))
        assertEquals(j("""{"sessionId":"s","title":"","metadata":{"provider":"p","pinned":true}}"""), SessionUpdatePayloads.rename("s", m, ""))
        assertEquals(j("""{"sessionId":"s","metadata":{"provider":"p","title":"a"}}"""), SessionUpdatePayloads.pin("s", m, false))
        assertEquals(j("""{"sessionId":"s","metadata":{"provider":"p","title":"a","pinned":true}}"""), SessionUpdatePayloads.pin("s", m, true))
    }
}

/** Runs foreground and background work (backgroundScope tasks are skipped by advanceUntilIdle) through 30 s of virtual time. */
private fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(30_000)
    runCurrent()
}
