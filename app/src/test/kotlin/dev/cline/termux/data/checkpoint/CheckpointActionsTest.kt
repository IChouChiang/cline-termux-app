package dev.cline.termux.data.checkpoint

import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.NewSessionSpec
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.HubFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointActionsTest {

    private class Restore(val source: String, val runCount: Int, val workspace: Boolean, val omit: Boolean, val spec: NewSessionSpec, val metadata: JsonObject)
    private class Create(val spec: NewSessionSpec, val messages: JsonArray, val metadata: JsonObject)

    /** Scripted [CheckpointHub]; every answer is a settable lambda. */
    private class FakeCheckpointHub : CheckpointHub {
        val calls = mutableListOf<String>()
        val restores = mutableListOf<Restore>()
        val creates = mutableListOf<Create>()
        val detached = mutableListOf<String>()
        var onGet: suspend (String) -> JsonObject = { error("no get") }
        var onMessages: suspend (String) -> JsonObject = { buildJsonObject { put("messages", JsonArray(emptyList())) } }
        var onRestore: suspend (Restore) -> JsonObject = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-new") }) } }
        var onWorkspaceOnly: suspend () -> JsonObject = { buildJsonObject { put("checkpoint", buildJsonObject { put("ref", "r") }) } }
        var onCreate: suspend (Create) -> JsonObject = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-created") }) } }
        var onList: suspend () -> JsonObject = { buildJsonObject { put("sessions", JsonArray(emptyList())) } }
        var drainEnds = true
        var connected = true

        override suspend fun get(sessionId: String): JsonObject { calls += "session.get"; return onGet(sessionId) }
        override suspend fun messages(sessionId: String): JsonObject { calls += "session.messages"; return onMessages(sessionId) }
        override suspend fun restore(sourceSessionId: String, checkpointRunCount: Int, restoreWorkspace: Boolean, omitCheckpointMessage: Boolean, spec: NewSessionSpec, metadata: JsonObject): JsonObject {
            calls += "session.restore"
            val r = Restore(sourceSessionId, checkpointRunCount, restoreWorkspace, omitCheckpointMessage, spec, metadata)
            restores += r
            return onRestore(r)
        }
        override suspend fun restoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject {
            calls += "session.restore(workspace)"
            return onWorkspaceOnly()
        }
        override suspend fun createRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject {
            calls += "session.create"
            val c = Create(spec, messages, metadata)
            creates += c
            return onCreate(c)
        }
        override suspend fun detach(sessionId: String): JsonObject {
            calls += "session.detach"
            detached += sessionId
            throw HubCommandException("session.detach", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: $sessionId")
        }
        override suspend fun list(limit: Int): JsonObject { calls += "session.list"; return onList() }
        override suspend fun awaitNotDraining(timeoutMs: Long): Boolean = drainEnds
        override suspend fun awaitConnected(timeoutMs: Long): Boolean = connected
    }

    private val clock = FakeClock()
    private val hub = FakeCheckpointHub()
    private val termux = FakeTermuxExecutor()
    private var release: String? = "3.0.68-termux.2"

    private fun actions(minRestore: String? = null) = CheckpointActions(hub, termux, clock, { release }, minRestore)

    private val history = """{"latest":{"ref":"c2","createdAt":"2000","runCount":2},"history":[{"ref":"c1","createdAt":"1000","runCount":1},{"ref":"c2","createdAt":"2000","runCount":2},{"ref":"c3","createdAt":"3000","runCount":3}]}"""

    private fun record(id: String = "src") = HubJson.parseToJsonElement(
        """{"session":{"sessionId":"$id","workspaceRoot":"/w","cwd":"/w/app","status":"idle",
           "metadata":{"source":"cli","interactive":true,"provider":"anthropic","model":"claude-x","mode":"plan","title":"Fix bug","pinned":true,
                       "checkpoint":$history,"pid":42,"usage":{"inputTokens":1}}},
           "snapshot":{"status":"idle","checkpoint":$history}}""",
    ).jsonObject

    private fun messages() = HubJson.parseToJsonElement(
        """{"messages":[
             {"id":"m1","role":"user","content":"first"},
             {"id":"m2","role":"assistant","content":"ok"},
             {"id":"m3","role":"user","content":"second"},
             {"id":"m4","role":"assistant","content":"done"}]}""",
    ).jsonObject

    private fun request(kind: RestoreKind, runCount: Int = 2, workspace: Boolean = true, hasCheckpoint: Boolean = true) =
        RestoreRequest(kind, "src", runCount, workspace, hasCheckpoint, autoApprove = true)

    private fun failure(code: String, message: String) = HubCommandException("session.restore", code, message)

    // ---- paths ----------------------------------------------------------------------------

    @Test
    fun `path - atomic only when the release satisfies the restore gate, fork without a checkpoint`() {
        assertEquals(RestorePath.Split, actions(minRestore = null).pathFor(RestoreKind.Revert, true))
        assertEquals(RestorePath.Atomic, actions("3.0.68-termux.2").pathFor(RestoreKind.Revert, true))
        assertEquals(RestorePath.Split, actions("3.0.68-termux.3").pathFor(RestoreKind.Edit, true))
        release = null
        assertEquals(RestorePath.Split, actions("3.0.68-termux.2").pathFor(RestoreKind.Edit, true))
        assertEquals(RestorePath.ClientFork, actions("3.0.68-termux.2").pathFor(RestoreKind.Edit, false))
    }

    // ---- atomic ---------------------------------------------------------------------------

    @Test
    fun `atomic revert sends one session restore with the revert metadata, then detaches the source`() = runTest {
        hub.onGet = { record() }
        hub.onRestore = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-new") }); put("messages", JsonArray(listOf(buildJsonObject { put("role", "user") }))) } }
        val progress = mutableListOf<RestoreProgress>()
        val outcome = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)) { progress += it }

        assertEquals(listOf("session.get", "session.restore", "session.detach"), hub.calls)
        val sent = hub.restores.single()
        assertEquals(2, sent.runCount)
        assertTrue(sent.workspace)
        assertFalse(sent.omit)
        assertEquals(HubPayloads.revertMetadata("anthropic", "claude-x"), sent.metadata)
        assertEquals("/w", sent.spec.workspaceRoot)
        assertEquals("/w/app", sent.spec.cwd)
        assertEquals("plan", sent.spec.mode)
        assertTrue(sent.spec.autoApprove)
        assertEquals("android", sent.spec.source)
        assertTrue(sent.spec.contributeAskQuestion)

        val success = outcome as RestoreOutcome.Success
        assertEquals("s-new", success.newSessionId)
        assertTrue(success.hasMessages)
        assertFalse(success.recovered)
        assertEquals(RestorePath.Atomic, success.pending.path)
        val started = progress.single() as RestoreProgress.Started
        assertEquals(started.pending, success.pending)
        assertNull("revert carries no forkedAt", started.pending.forkedAt)
        assertEquals(listOf("src"), hub.detached)
    }

    @Test
    fun `atomic edit omits the checkpoint message and spreads the source metadata with fork`() = runTest {
        hub.onGet = { record() }
        val outcome = actions("3.0.68-termux.2").restore(request(RestoreKind.Edit, workspace = false))
        val sent = hub.restores.single()
        assertTrue(sent.omit)
        assertFalse(sent.workspace)
        val metadata = sent.metadata
        assertEquals("Fix bug", metadata["title"]!!.jsonPrimitive.content)
        assertEquals("android", metadata["source"]!!.jsonPrimitive.content)
        assertNull(metadata["pid"])
        assertNull(metadata["usage"])
        assertNull("the Hub adds restoredFrom on the atomic path", metadata["restoredFromSessionId"])
        val fork = metadata["fork"]!!.jsonObject
        assertEquals("src", fork["forkedFromSessionId"]!!.jsonPrimitive.content)
        assertEquals("cli", fork["source"]!!.jsonPrimitive.content)
        assertEquals(2, fork["beforeRunCount"]!!.jsonPrimitive.int)
        assertEquals("2026-10-03T12:00:00Z", fork["forkedAt"]!!.jsonPrimitive.content)
        assertEquals((outcome as RestoreOutcome.Success).pending.forkedAt, fork["forkedAt"]!!.jsonPrimitive.content)
    }

    // ---- split ----------------------------------------------------------------------------

    @Test
    fun `split edit resets the workspace, trims before the run and creates with restored metadata`() = runTest {
        hub.onGet = { record() }
        hub.onMessages = { messages() }
        val progress = mutableListOf<RestoreProgress>()
        val outcome = actions(null).restore(request(RestoreKind.Edit)) { progress += it }

        assertEquals(listOf("session.get", "session.restore(workspace)", "session.messages", "session.create", "session.detach"), hub.calls)
        val create = hub.creates.single()
        assertEquals(listOf("m1", "m2"), create.messages.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val metadata = create.metadata
        assertEquals("src", metadata["restoredFromSessionId"]!!.jsonPrimitive.content)
        assertEquals(2, metadata["restoredCheckpointRunCount"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 2), metadata["checkpoint"]!!.jsonObject["history"]!!.jsonArray.map { it.jsonObject["runCount"]!!.jsonPrimitive.int })
        assertEquals("Fix bug", metadata["title"]!!.jsonPrimitive.content)
        val success = outcome as RestoreOutcome.Success
        assertEquals("s-created", success.newSessionId)
        assertNull("split replies carry no transcript", success.messages)
        assertTrue(success.pending.workspaceRestored)
        assertTrue(progress[1] is RestoreProgress.WorkspaceRestored)
    }

    @Test
    fun `split revert, chat only, keeps run N's user message and stays untitled`() = runTest {
        hub.onGet = { record() }
        hub.onMessages = { messages() }
        actions(null).restore(request(RestoreKind.Revert, workspace = false))
        assertFalse("session.restore(workspace)" in hub.calls)
        val create = hub.creates.single()
        assertEquals(listOf("m1", "m2", "m3"), create.messages.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertNull(create.metadata["title"])
        assertNull(create.metadata["fork"])
        assertEquals("src", create.metadata["restoredFromSessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `split - the new session failing after the workspace reset says so`() = runTest {
        hub.onGet = { record() }
        hub.onMessages = { messages() }
        hub.onCreate = { throw HubCommandException("session.create", HubErrorCodes.COMMAND_FAILED, "provider exploded") }
        val outcome = actions(null).restore(request(RestoreKind.Revert)) as RestoreOutcome.Failed
        assertEquals(RestoreFailure.Kernel, outcome.reason)
        assertEquals("provider exploded", outcome.message)
        assertTrue(outcome.workspaceRestored)
    }

    @Test
    fun `split - a compacted run fails with the kernel's text and hides the actions`() = runTest {
        hub.onGet = { record() }
        hub.onMessages = {
            HubJson.parseToJsonElement("""{"messages":[{"id":"c","role":"user","content":"summary","metadata":{"kind":"compaction","userRunSpan":2}}]}""").jsonObject
        }
        val outcome = actions(null).restore(request(RestoreKind.Edit, workspace = false)) as RestoreOutcome.Failed
        assertEquals(RestoreFailure.FoldedRun, outcome.reason)
        assertEquals("Cannot fork before run 2 because it is folded into a compacted message", outcome.message)
        assertTrue(hub.creates.isEmpty())
    }

    @Test
    fun `client fork - no checkpoint, chat only, plain create without restoredFrom keys`() = runTest {
        hub.onGet = { record() }
        hub.onMessages = { messages() }
        val outcome = actions("3.0.68-termux.2").restore(request(RestoreKind.Edit, workspace = true, hasCheckpoint = false)) as RestoreOutcome.Success
        assertEquals(RestorePath.ClientFork, outcome.pending.path)
        assertFalse(outcome.pending.restoreWorkspace)
        assertTrue(hub.restores.isEmpty())
        val create = hub.creates.single()
        assertNull(create.metadata["restoredFromSessionId"])
        assertEquals("src", create.metadata["fork"]!!.jsonObject["forkedFromSessionId"]!!.jsonPrimitive.content)
        assertEquals(listOf("m1", "m2"), create.messages.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    // ---- refusals, failures, drain ---------------------------------------------------------

    @Test
    fun `workspace refusals reopen the dialog with the kernel text`() = runTest {
        hub.onGet = { record() }
        val commits = "Cannot restore the workspace: 2 commits were added to the current branch after this checkpoint and would be removed by the restore. Restore the chat only, or move the branch back to abc123 manually (e.g. with git reset) before restoring the workspace."
        hub.onRestore = { throw failure(HubErrorCodes.RESTORE_FAILED, commits) }
        assertEquals(RestoreOutcome.WorkspaceRefused(commits, workspaceOptionAvailable = true), actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)))
        hub.onRestore = { throw failure(HubErrorCodes.RESTORE_FAILED, "/w/app is not a git repository") }
        assertEquals(
            RestoreOutcome.WorkspaceRefused("/w/app is not a git repository", workspaceOptionAvailable = false),
            actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)),
        )
        // `.git` removed: rev-parse exits 128 before the kernel's own check (seen on the dev Hub).
        val removed = "Command failed: git -C /w/app rev-parse --is-inside-work-tree\nfatal: not a git repository (or any parent up to mount point /)\n"
        hub.onRestore = { throw failure(HubErrorCodes.RESTORE_FAILED, removed) }
        assertEquals(
            RestoreOutcome.WorkspaceRefused(removed, workspaceOptionAvailable = false),
            actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)),
        )
    }

    @Test
    fun `a connection lost while reading the source fails as not connected without sending`() = runTest {
        hub.onGet = { throw failure(HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed") }
        val outcome = actions().restore(request(RestoreKind.Revert)) as RestoreOutcome.Failed
        assertEquals(RestoreFailure.NotConnected, outcome.reason)
        assertEquals(null, outcome.message)
    }

    @Test
    fun `a gone source fails before anything is sent`() = runTest {
        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: src") }
        val outcome = actions().restore(request(RestoreKind.Revert)) as RestoreOutcome.Failed
        assertEquals(RestoreFailure.SourceGone, outcome.reason)
        assertEquals("Unknown session: src", outcome.message)
        assertEquals(listOf("session.get"), hub.calls)
    }

    @Test
    fun `no socket fails as not connected, a record without cwd as NoCwd`() = runTest {
        hub.onGet = { throw HubConnectionClosedException("not connected") }
        assertEquals(RestoreOutcome.Failed(RestoreFailure.NotConnected, null), actions().restore(request(RestoreKind.Revert)))
        hub.onGet = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "src") }) } }
        assertEquals(
            RestoreOutcome.Failed(RestoreFailure.NoCwd, CheckpointActions.NO_CWD_MESSAGE),
            actions().restore(request(RestoreKind.Revert)),
        )
        hub.onGet = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "src"); put("cwd", "/w") }) } }
        assertEquals(RestoreFailure.MissingConfig, (actions().restore(request(RestoreKind.Revert)) as RestoreOutcome.Failed).reason)
    }

    @Test
    fun `hub_draining waits for the drain to end and sends exactly once more`() = runTest {
        hub.onGet = { record() }
        var attempts = 0
        hub.onRestore = {
            if (attempts++ == 0) throw failure(HubErrorCodes.HUB_DRAINING, "Hub is draining")
            buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-new") }) }
        }
        val progress = mutableListOf<RestoreProgress>()
        val outcome = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)) { progress += it }
        assertTrue(outcome is RestoreOutcome.Success)
        assertEquals(2, hub.restores.size)
        assertTrue(RestoreProgress.Draining in progress)

        hub.restores.clear()
        hub.drainEnds = false
        attempts = 0
        val timedOut = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert))
        assertEquals(RestoreOutcome.Failed(RestoreFailure.Draining, null), timedOut)
        assertEquals(1, hub.restores.size)
    }

    // ---- lost reply -----------------------------------------------------------------------

    private fun row(id: String, metadata: String, createdAt: Long) =
        HubJson.parseToJsonElement("""{"sessionId":"$id","createdAt":$createdAt,"metadata":$metadata}""").jsonObject

    @Test
    fun `lost reply - the session the restore created is found through session list`() = runTest {
        hub.onGet = { record() }
        hub.onRestore = { throw failure(HubErrorCodes.HUB_COMMAND_TIMEOUT, "Hub command session.restore did not complete within 120000ms.") }
        val sent = clock.millis()
        hub.onList = {
            buildJsonObject {
                put(
                    "sessions",
                    JsonArray(
                        listOf(
                            row("old", """{"restoredFromSessionId":"src","restoredCheckpointRunCount":2}""", sent - 60_000),
                            row("other", """{"restoredFromSessionId":"src","restoredCheckpointRunCount":1}""", sent + 10),
                            row("mine", """{"restoredFromSessionId":"src","restoredCheckpointRunCount":2}""", sent - 1_000),
                        ),
                    ),
                )
            }
        }
        val outcome = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert)) as RestoreOutcome.Success
        assertEquals("mine", outcome.newSessionId)
        assertTrue(outcome.recovered)
        assertEquals("never resent", 1, hub.restores.size)
        assertTrue("session.list" in hub.calls)
    }

    @Test
    fun `lost reply - nothing matching, or no reconnect, is status unknown`() = runTest {
        hub.onGet = { record() }
        hub.onRestore = { throw failure(HubErrorCodes.HUB_CONNECTION_CLOSED, "closed") }
        val unknown = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert))
        assertTrue(unknown is RestoreOutcome.StatusUnknown)
        hub.connected = false
        hub.calls.clear()
        val offline = actions("3.0.68-termux.2").restore(request(RestoreKind.Revert))
        assertTrue(offline is RestoreOutcome.StatusUnknown)
        assertFalse("session.list" in hub.calls)
    }

    @Test
    fun `recover after process death matches a client fork by forkedAt`() = runTest {
        val pending = PendingRestore(RestoreKind.Edit, "src", 3, false, RestorePath.ClientFork, forkedAt = "2026-10-03T12:00:00Z", requestSentAt = 1_000)
        val restored = PendingRestore.decode(pending.encode())!!
        assertEquals(pending, restored)
        hub.onList = {
            buildJsonObject {
                put("sessions", JsonArray(listOf(row("fork", """{"fork":{"forkedFromSessionId":"src","forkedAt":"2026-10-03T12:00:00Z"}}""", 2_000))))
            }
        }
        val outcome = actions().recover(restored) as RestoreOutcome.Success
        assertEquals("fork", outcome.newSessionId)
        assertEquals(listOf("src"), hub.detached)
    }

    @Test
    fun `a second confirm while one is pending is refused`() = runTest {
        hub.onGet = { record() }
        val gate = CompletableDeferred<Unit>()
        hub.onRestore = { gate.await(); buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-new") }) } }
        val a = actions("3.0.68-termux.2")
        val first = async { a.restore(request(RestoreKind.Revert)) }
        runCurrent()
        assertTrue(a.pending)
        assertEquals(RestoreOutcome.AlreadyInProgress, a.restore(request(RestoreKind.Revert)))
        gate.complete(Unit)
        assertTrue(first.await() is RestoreOutcome.Success)
        assertFalse(a.pending)
    }

    // ---- pure parts ---------------------------------------------------------------------------

    @Test
    fun `reply table - CT 1-5-4`() {
        fun map(code: String, message: String) = restoreOutcomeFor(HubCommandException("session.restore", code, message), workspaceRestored = false)
        assertEquals(RestoreFailure.InvalidRequest, (map(HubErrorCodes.INVALID_RESTORE, "session.restore requires a session id") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.InvalidRequest, (map(HubErrorCodes.RESTORE_FAILED, "checkpointRunCount must be a positive integer") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.SourceGone, (map(HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: s") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.EmptyHistory, (map(HubErrorCodes.SESSION_MESSAGES_NOT_FOUND, "No messages found for session s") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.HistoryChanged, (map(HubErrorCodes.RESTORE_FAILED, "No checkpoint found at or before run 3 in session s") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.HistoryChanged, (map(HubErrorCodes.RESTORE_FAILED, "Could not find user message for run 3") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.FoldedRun, (map(HubErrorCodes.RESTORE_FAILED, "Run 3 is folded into a compacted message spanning runs 2-4") as RestoreOutcome.Failed).reason)
        assertEquals(RestoreFailure.NoCwd, (map(HubErrorCodes.RESTORE_FAILED, "cwd or workspaceRoot is required to restore a checkpoint") as RestoreOutcome.Failed).reason)
        val other = map(HubErrorCodes.RESTORE_FAILED, "Checkpoint restore did not start a session") as RestoreOutcome.Failed
        assertEquals(RestoreFailure.Kernel, other.reason)
        assertEquals("Checkpoint restore did not start a session", other.message)
        assertTrue(map(HubErrorCodes.RESTORE_FAILED, "Cannot restore the workspace: the current branch is no longer on the commit") is RestoreOutcome.WorkspaceRefused)
    }

    @Test
    fun `git diagnosis - C3-5`() = runTest {
        assertEquals(GitDiagnosis.NoGit, gitDiagnosis(TermuxResult.undelivered(150, "boom")))
        assertEquals(GitDiagnosis.NoGit, gitDiagnosis(TermuxResult.undelivered(2, "No such file or directory")))
        assertEquals(GitDiagnosis.NotARepository, gitDiagnosis(TermuxResult.failure(128, "fatal: not a git repository")))
        assertEquals(GitDiagnosis.RepositoryOk, gitDiagnosis(TermuxResult.success("true\n")))
        assertEquals(GitDiagnosis.Unknown, gitDiagnosis(TermuxResult.failure(1, "")))
        assertEquals(GitDiagnosis.Unknown, gitDiagnosis(TermuxResult.undelivered(1, "other")))

        termux.on(ClineCommands.Labels.GIT_IS_WORK_TREE, TermuxResult.success("true"))
        assertEquals(GitDiagnosis.RepositoryOk, actions().diagnoseGit("/w"))
        assertEquals(listOf("-C", "/w", "rev-parse", "--is-inside-work-tree"), termux.commands.single().args)

        termux.on(ClineCommands.Labels.GIT_IS_WORK_TREE) { awaitCancellation() }
        assertEquals("10 s timeout", GitDiagnosis.Unknown, actions().diagnoseGit("/w"))

        termux.permitted = false
        termux.commands.clear()
        assertEquals(GitDiagnosis.Unknown, actions().diagnoseGit("/w"))
        assertTrue("never prompts", termux.commands.isEmpty())
    }

    @Test
    fun `busy rule - C3`() {
        val idle = SessionFacts("s", status = SessionStatus.Idle, runPhase = RunPhase.Idle)
        assertFalse(CheckpointActions.isBusy(idle))
        assertTrue(CheckpointActions.isBusy(idle.copy(status = SessionStatus.Stopping)))
        assertTrue(CheckpointActions.isBusy(idle.copy(status = SessionStatus.Pending)))
        assertTrue(CheckpointActions.isBusy(idle.copy(runPhase = RunPhase.Starting)))
        assertTrue(CheckpointActions.isBusy(idle, abortPending = true))
        assertTrue(CheckpointActions.isBusy(idle, switching = true))
        assertTrue(CheckpointActions.isBusy(idle, versioningPending = true))
        assertFalse(CheckpointActions.isBusy(idle.copy(status = SessionStatus.Completed)))
    }

    @Test
    fun `pending restore decode tolerates garbage`() {
        assertNull(PendingRestore.decode(null))
        assertNull(PendingRestore.decode("{nope"))
    }

    @Test
    fun `fixture 04 session get record builds a full spec`() = runTest {
        val f04 = HubFixtures.load("04-history-and-replay.jsonl")
        hub.onGet = { HubFixtures.replyTo(f04, "session-get-002").payload!! }
        actions("3.0.68-termux.2").restore(request(RestoreKind.Revert, runCount = 1))
        val spec = hub.restores.single().spec
        assertEquals("openai-compatible", spec.providerId)
        assertEquals("mock-1", spec.modelId)
        assertEquals("act", spec.mode)
        assertTrue(spec.workspaceRoot.endsWith("/hubfix/workspace"))
    }
}
