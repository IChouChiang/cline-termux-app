package dev.cline.termux.ui.checkpoint

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.NewSessionSpec
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.checkpoint.CheckpointActions
import dev.cline.termux.data.checkpoint.CheckpointHub
import dev.cline.termux.data.checkpoint.GitDiagnosis
import dev.cline.termux.data.checkpoint.PendingRestore
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.data.checkpoint.RestorePath
import dev.cline.termux.data.session.CheckpointEntry
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionStateSource
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointViewModelTest {

    /** Scripted [CheckpointHub] (the data-layer test keeps its own private copy). */
    private class FakeCheckpointHub : CheckpointHub {
        val calls = mutableListOf<String>()
        val restoreWorkspaceFlags = mutableListOf<Boolean>()
        var onGet: suspend (String) -> JsonObject = { error("no get") }
        var onMessages: suspend (String) -> JsonObject = { buildJsonObject { put("messages", JsonArray(emptyList())) } }
        var onWorkspaceOnly: suspend () -> JsonObject = { buildJsonObject { put("checkpoint", buildJsonObject { put("ref", "r") }) } }
        var onCreate: suspend () -> JsonObject = { buildJsonObject { put("session", buildJsonObject { put("sessionId", "s-new") }) } }
        var onList: suspend () -> JsonObject = { buildJsonObject { put("sessions", JsonArray(emptyList())) } }
        var drain: CompletableDeferred<Boolean>? = null
        var connected = true

        override suspend fun get(sessionId: String): JsonObject { calls += "session.get"; return onGet(sessionId) }
        override suspend fun messages(sessionId: String): JsonObject { calls += "session.messages"; return onMessages(sessionId) }
        override suspend fun restore(
            sourceSessionId: String,
            checkpointRunCount: Int,
            restoreWorkspace: Boolean,
            omitCheckpointMessage: Boolean,
            spec: NewSessionSpec,
            metadata: JsonObject,
        ): JsonObject = error("atomic path is off while MIN_PORT_RELEASE_RESTORE is null")

        override suspend fun restoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject {
            calls += "session.restore(workspace)"
            restoreWorkspaceFlags += true
            return onWorkspaceOnly()
        }
        override suspend fun createRestored(spec: NewSessionSpec, messages: JsonArray, metadata: JsonObject): JsonObject {
            calls += "session.create"
            return onCreate()
        }
        override suspend fun detach(sessionId: String): JsonObject { calls += "session.detach"; return JsonObject(emptyMap()) }
        override suspend fun list(limit: Int): JsonObject { calls += "session.list"; return onList() }
        override suspend fun awaitNotDraining(timeoutMs: Long): Boolean = drain?.await() ?: true
        override suspend fun awaitConnected(timeoutMs: Long): Boolean = connected
    }

    private val main = UnconfinedTestDispatcher()
    private val clock = FakeClock()
    private val hub = FakeCheckpointHub()
    private val termux = FakeTermuxExecutor()
    private val sessions = FakeSessionStateSource()
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    private val logs = mutableListOf<String>()

    private val historyJson = """{"latest":{"ref":"c2","createdAt":2000,"runCount":2},"history":[{"ref":"c1","createdAt":1000,"runCount":1},{"ref":"c2","createdAt":2000,"runCount":2}]}"""

    private fun record(history: String = historyJson) = HubJson.parseToJsonElement(
        """{"session":{"sessionId":"src","workspaceRoot":"/w","cwd":"/w/app","status":"idle",
           "metadata":{"source":"android","interactive":true,"provider":"anthropic","model":"claude-x","mode":"act","title":"Fix bug","checkpoint":$history}},
           "snapshot":{"status":"idle","checkpoint":$history}}""",
    ).jsonObject

    private fun messages() = HubJson.parseToJsonElement(
        """{"messages":[
             {"id":"m1","role":"user","content":"first   prompt"},
             {"id":"m2","role":"assistant","content":"ok"},
             {"id":"m3","role":"user","content":"second prompt","metadata":{"mode":"plan"}},
             {"id":"m4","role":"assistant","content":"done"}]}""",
    ).jsonObject

    private val history = listOf(CheckpointEntry("c1", 1000, 1), CheckpointEntry("c2", 2000, 2))

    private fun sessionState(
        status: SessionStatus = SessionStatus.Idle,
        checkpoints: List<CheckpointEntry> = history,
        seeded: Boolean = true,
    ) = SessionState(
        sessionId = "src",
        facts = SessionFacts("src", status = status, checkpointHistory = checkpoints),
        canonical = listOf(
            ChatMessage("m1", "src", Role.User, "first   prompt", createdAt = 1),
            ChatMessage("m3", "src", Role.User, "second prompt", createdAt = 3),
        ),
        seed = if (seeded) SeedState.Seeded(1) else SeedState.NotSeeded,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        hub.onGet = { record() }
        hub.onMessages = { messages() }
        sessions.set(sessionState())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun deps() = CheckpointDeps(
        actions = CheckpointActions(hub, termux, clock, kernelRelease = { "3.0.68-termux.2" }, minRestoreRelease = null),
        hub = hub,
        sessions = sessions,
        gate = gate,
        autoApprove = { false },
        clock = clock,
        log = { logs += it },
    )

    private class Harness(val vm: CheckpointViewModel, val events: MutableList<CheckpointEvent>, val saved: SavedStateHandle)

    private fun TestScope.harness(saved: SavedStateHandle = SavedStateHandle(), bind: Boolean = true): Harness {
        val vm = CheckpointViewModel(saved, deps())
        val events = mutableListOf<CheckpointEvent>()
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.eventFlow.collect { events += it } }
        if (bind) vm.bindSession("src")
        return Harness(vm, events, saved)
    }

    private val Harness.state get() = vm.uiState.value
    private inline fun <reified T> Harness.eventsOf(): List<T> = events.filterIsInstance<T>()

    // ---- dialogs --------------------------------------------------------------------------------

    @Test
    fun `C1 opens with the preview and chat plus workspace selected`() = runTest(main) {
        val h = harness()
        h.vm.openRevert("m1", 1)
        val dialog = h.state.dialog!!
        assertEquals(RestoreKind.Revert, dialog.kind)
        assertEquals("first prompt", dialog.preview)
        assertTrue(dialog.showOptions)
        assertTrue(dialog.workspaceSelected)
        assertTrue(dialog.confirmEnabled)
        assertEquals(R_REVERT_WS, consequenceText(dialog))
        h.vm.selectWorkspace(false)
        assertFalse(h.state.dialog!!.workspaceSelected)
        assertEquals(R_REVERT_CHAT, consequenceText(h.state.dialog!!))
    }

    @Test
    fun `busy session refuses to open and explains, busy while open disables confirm`() = runTest(main) {
        sessions.set(sessionState(status = SessionStatus.Running))
        val h = harness()
        h.vm.openRevert("m1", 1)
        assertNull(h.state.dialog)
        assertEquals(CheckpointSnack.Busy, h.eventsOf<CheckpointEvent.Snackbar>().single().snack)
        assertEquals(CheckpointUnavailable.Busy, h.state.unavailable)

        sessions.set(sessionState(status = SessionStatus.Idle))
        h.vm.openRevert("m1", 1)
        sessions.set(sessionState(status = SessionStatus.Stopping))
        val dialog = h.state.dialog!!
        assertTrue(dialog.busy)
        assertFalse(dialog.confirmEnabled)
        h.vm.confirm()
        assertTrue("nothing sent", hub.calls.isEmpty())
        assertNotNull("dialog stays open", h.state.dialog)
    }

    @Test
    fun `stop pending from the chat counts as busy`() = runTest(main) {
        val h = harness()
        h.vm.setHostFlags(abortPending = true, switching = false)
        assertTrue(h.state.busy)
        h.vm.openEdit("m1", "first prompt", 1)
        assertNull(h.state.dialog)
    }

    @Test
    fun `kernel down and reconnecting disable confirm, connect re-enables`() = runTest(main) {
        val h = harness()
        h.vm.openRevert("m1", 1)
        gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        assertEquals(CheckpointKernelLine.NotConnected, h.state.dialog!!.kernel)
        assertFalse(h.state.dialog!!.confirmEnabled)
        gate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        assertEquals(CheckpointKernelLine.Reconnecting, h.state.dialog!!.kernel)
        gate.value = KernelGate.Busy(KernelGate.BusyKind.Draining, null, slow = false)
        assertTrue("draining still sends; the flow waits", h.state.dialog!!.confirmEnabled)
        gate.value = KernelGate.Ready
        assertTrue(h.state.dialog!!.confirmEnabled)
    }

    @Test
    fun `revert confirm closes the dialog, runs the split path and replaces the session`() = runTest(main) {
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.confirm()
        assertNull(h.state.dialog)
        assertEquals(listOf("session.get", "session.restore(workspace)", "session.messages", "session.create", "session.detach"), hub.calls)
        val replaced = h.eventsOf<CheckpointEvent.SessionReplaced>().single()
        assertEquals("src", replaced.sourceSessionId)
        assertEquals("s-new", replaced.newSessionId)
        assertNull("revert leaves the composer alone", replaced.composerText)
        assertEquals(CheckpointSnack.Restored, h.eventsOf<CheckpointEvent.Snackbar>().single().snack)
        assertNull(h.state.pending)
        assertNull("pending request cleared", h.saved.get<String>(CheckpointViewModel.KEY_PENDING))
    }

    @Test
    fun `edit from the row prefills the composer and shows no snackbar`() = runTest(main) {
        val h = harness()
        h.vm.openEdit("m3", "second prompt", 2)
        assertTrue(h.state.dialog!!.hasCheckpoint)
        assertEquals(R_EDIT_WS, consequenceText(h.state.dialog!!))
        h.vm.confirm()
        val replaced = h.eventsOf<CheckpointEvent.SessionReplaced>().single()
        assertEquals("second prompt", replaced.composerText)
        assertTrue(h.eventsOf<CheckpointEvent.Snackbar>().isEmpty())
    }

    @Test
    fun `edit without any checkpoint shows the no-checkpoint text and forks client side`() = runTest(main) {
        sessions.set(sessionState(checkpoints = emptyList()))
        hub.onGet = { record(history = """{"history":[]}""") }
        val h = harness()
        h.vm.openEdit("m1", "first prompt", 1)
        val dialog = h.state.dialog!!
        assertFalse(dialog.hasCheckpoint)
        assertFalse(dialog.showOptions)
        assertFalse(dialog.workspaceSelected)
        assertEquals(R_EDIT_NONE, consequenceText(dialog))
        h.vm.confirm()
        assertFalse("never touches the workspace", "session.restore(workspace)" in hub.calls)
        assertTrue("session.create" in hub.calls)
        assertEquals("first prompt", h.eventsOf<CheckpointEvent.SessionReplaced>().single().composerText)
    }

    @Test
    fun `a workspace refusal reopens the dialog with the kernel text and chat only`() = runTest(main) {
        val refusal = "Cannot restore the workspace: 1 commit was added to the current branch after this checkpoint."
        hub.onWorkspaceOnly = { throw HubCommandException("session.restore", HubErrorCodes.RESTORE_FAILED, refusal) }
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.confirm()
        val dialog = h.state.dialog!!
        assertEquals(refusal, dialog.refusal)
        assertFalse(dialog.workspaceSelected)
        assertTrue("radio stays editable", dialog.workspaceOptionAvailable)
        assertNull(h.state.pending)

        hub.onWorkspaceOnly = { throw HubCommandException("session.restore", HubErrorCodes.RESTORE_FAILED, "/w/app is not a git repository") }
        h.vm.selectWorkspace(true)
        h.vm.confirm()
        val again = h.state.dialog!!
        assertFalse("workspace option hidden for the dialog's life", again.workspaceOptionAvailable)
        h.vm.selectWorkspace(true)
        assertFalse(h.state.dialog!!.workspaceSelected)
        hub.calls.clear()
        h.vm.confirm()
        assertFalse("retry is chat only", "session.restore(workspace)" in hub.calls)
        assertEquals("s-new", h.eventsOf<CheckpointEvent.SessionReplaced>().single().newSessionId)
    }

    @Test
    fun `split failure after the workspace reset is reported under the row`() = runTest(main) {
        hub.onCreate = { throw HubCommandException("session.create", HubErrorCodes.COMMAND_FAILED, "provider exploded") }
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.confirm()
        val error = h.state.errors.getValue("m3")
        assertEquals(RestoreKind.Revert, error.kind)
        assertTrue(error.workspaceRestored)
        assertEquals("provider exploded", error.message)
        assertTrue(h.eventsOf<CheckpointEvent.SessionReplaced>().isEmpty())
    }

    @Test
    fun `a compacted run hides that message's actions, a gone source disables them`() = runTest(main) {
        hub.onMessages = {
            HubJson.parseToJsonElement(
                """{"messages":[{"id":"s","role":"user","content":"summary","metadata":{"kind":"compaction_summary","userRunSpan":2}}]}""",
            ).jsonObject
        }
        val h = harness()
        h.vm.openEdit("m3", "second prompt", 2)
        h.vm.selectWorkspace(false)
        h.vm.confirm()
        assertTrue(h.state.actionsHidden("m3"))
        assertEquals("Cannot fork before run 2 because it is folded into a compacted message", h.state.errors.getValue("m3").message)

        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: src") }
        h.vm.openRevert("m1", 1)
        h.vm.confirm()
        assertTrue(h.state.sourceGone)
        assertEquals(CheckpointUnavailable.Busy, h.state.unavailable)
        h.vm.bindSession("other")
        assertFalse(h.state.sourceGone)
    }

    @Test
    fun `history changed reseeds the session and logs an unmapped run`() = runTest(main) {
        hub.onMessages = { buildJsonObject { put("messages", JsonArray(emptyList())) } }
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.selectWorkspace(false)
        h.vm.confirm()
        assertEquals("Could not find user message for run 2", h.state.errors.getValue("m3").message)
        assertEquals(listOf("src"), sessions.seeds)
        assertEquals(listOf("restore_run_unmapped"), logs)
    }

    @Test
    fun `lost reply with no match opens restore status unknown and never resends`() = runTest(main) {
        hub.onCreate = { throw HubCommandException("session.create", HubErrorCodes.HUB_CONNECTION_CLOSED, "closed") }
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.selectWorkspace(false)
        h.vm.confirm()
        assertTrue(h.state.lostReply)
        assertEquals(1, hub.calls.count { it == "session.create" })
        assertNull(h.saved.get<String>(CheckpointViewModel.KEY_PENDING))
        h.vm.openSessionsFromLostReply()
        assertFalse(h.state.lostReply)
        assertEquals(CheckpointEvent.OpenSessions, h.events.last())
    }

    @Test
    fun `draining shows the waiting line until the drain ends`() = runTest(main) {
        var first = true
        hub.onWorkspaceOnly = {
            if (first) {
                first = false
                throw HubCommandException("session.restore", HubErrorCodes.HUB_DRAINING, "draining")
            }
            buildJsonObject { put("checkpoint", buildJsonObject { put("ref", "r") }) }
        }
        val drain = CompletableDeferred<Boolean>()
        hub.drain = drain
        val h = harness()
        h.vm.openRevert("m3", 2)
        h.vm.confirm()
        val pending = h.state.pending!!
        assertEquals("m3", pending.messageId)
        assertTrue(pending.draining)
        assertTrue(h.state.versioningPending)
        assertNotNull("saved for process death", PendingRestore.decode(h.saved.get(CheckpointViewModel.KEY_PENDING)))

        h.vm.openEdit("m1", "first prompt", 1)
        assertNull("versioning pending refuses a second dialog", h.state.dialog)
        assertEquals(CheckpointSnack.InProgress, h.eventsOf<CheckpointEvent.Snackbar>().last().snack)

        drain.complete(true)
        assertNull(h.state.pending)
        assertEquals("s-new", h.eventsOf<CheckpointEvent.SessionReplaced>().single().newSessionId)
    }

    @Test
    fun `process death while pending runs the recovery and prefills the edit`() = runTest(main) {
        val sent = clock.millis()
        val pending = PendingRestore(RestoreKind.Edit, "src", 2, restoreWorkspace = true, path = RestorePath.Split, forkedAt = "2026-10-03T12:00:00Z", requestSentAt = sent)
        hub.onList = {
            HubJson.parseToJsonElement(
                """{"sessions":[{"sessionId":"found","createdAt":${sent + 50},"metadata":{"restoredFromSessionId":"src","restoredCheckpointRunCount":2}}]}""",
            ).jsonObject
        }
        val saved = SavedStateHandle(
            mapOf(
                CheckpointViewModel.KEY_SESSION to "src",
                CheckpointViewModel.KEY_PENDING to pending.encode(),
                CheckpointViewModel.KEY_ROW to """{"messageId":"m3","kind":"Edit","pickerMode":false}""",
            ),
        )
        val h = harness(saved, bind = false)
        val replaced = h.eventsOf<CheckpointEvent.SessionReplaced>().single()
        assertEquals("found", replaced.newSessionId)
        assertEquals("second prompt", replaced.composerText)
        assertNull(saved.get<String>(CheckpointViewModel.KEY_PENDING))
    }

    @Test
    fun `an open dialog survives process death, a session switch closes it`() = runTest(main) {
        val first = harness()
        first.vm.openRevert("m3", 2)
        first.vm.selectWorkspace(false)
        val restored = harness(SavedStateHandle(first.saved.keys().associateWith { first.saved.get<Any>(it) }), bind = false)
        val dialog = restored.state.dialog!!
        assertEquals("m3", dialog.messageId)
        assertFalse(dialog.workspaceSelected)
        assertEquals("second prompt", dialog.preview)
        restored.vm.bindSession("other")
        assertNull(restored.state.dialog)
    }

    @Test
    fun `C1 whose checkpoint disappeared closes with the restore error`() = runTest(main) {
        val h = harness()
        h.vm.openRevert("m3", 2)
        sessions.set(sessionState(checkpoints = history.take(1)))
        assertTrue(h.state.dialog!!.checkpointLost)
        assertFalse(h.state.dialog!!.confirmEnabled)
        h.vm.onCheckpointLost()
        assertNull(h.state.dialog)
        assertEquals(CheckpointSnack.RestoreError, h.eventsOf<CheckpointEvent.Snackbar>().single().snack)
    }

    @Test
    fun `C2 switches to the no-checkpoint text when the history loses it`() = runTest(main) {
        val h = harness()
        h.vm.openEdit("m3", "second prompt", 2)
        assertTrue(h.state.dialog!!.hasCheckpoint)
        sessions.set(sessionState(checkpoints = emptyList()))
        assertFalse(h.state.dialog!!.hasCheckpoint)
        assertEquals(R_EDIT_NONE, consequenceText(h.state.dialog!!))
    }

    // ---- picker -----------------------------------------------------------------------------

    @Test
    fun `picker lists restorable messages oldest first with plan mode and caches for 10 s`() = runTest(main) {
        val h = harness()
        h.vm.openPicker()
        val picker = h.state.picker!!
        val rows = (picker.content as PickerContent.Rows).rows
        assertEquals(listOf("m1", "m3"), rows.map { it.messageId })
        assertEquals(listOf(1000L, 2000L), rows.map { it.time })
        assertEquals(listOf(false, true), rows.map { it.planMode })
        assertEquals("first prompt", rows.first().preview)
        assertTrue(picker.rowsEnabled)
        assertFalse(picker.refreshing)

        h.vm.dismissPicker()
        assertNull(h.state.picker)
        hub.calls.clear()
        h.vm.openPicker()
        assertTrue("cached copy younger than 10 s", hub.calls.isEmpty())
        h.vm.dismissPicker()
        clock.advanceMillis(10_001)
        h.vm.openPicker()
        assertEquals(listOf("session.get", "session.messages"), hub.calls)
    }

    @Test
    fun `picker row opens C2 in picker mode, cancel returns, continue restores with status snackbars`() = runTest(main) {
        val h = harness()
        h.vm.openPicker()
        val row = (h.state.picker!!.content as PickerContent.Rows).rows.last()
        h.vm.pickRow(row)
        assertNull("sheet hidden under C2", h.state.picker)
        val dialog = h.state.dialog!!
        assertTrue(dialog.pickerMode)
        assertEquals(RestoreKind.Edit, dialog.kind)
        assertTrue(dialog.hasCheckpoint)
        h.vm.dismissDialog()
        assertNotNull("cancel returns to the sheet", h.state.picker)

        h.vm.pickRow(row)
        h.vm.confirm()
        assertNull(h.state.picker)
        assertNull(h.state.dialog)
        val snacks = h.eventsOf<CheckpointEvent.Snackbar>().map { it.snack }
        assertEquals(listOf(CheckpointSnack.Status(workspace = true), CheckpointSnack.Restored), snacks)
        assertTrue(CheckpointEvent.DismissStatus in h.events)
        assertEquals("second prompt", h.eventsOf<CheckpointEvent.SessionReplaced>().single().composerText)
    }

    @Test
    fun `picker failure is a long snackbar and the row error`() = runTest(main) {
        hub.onCreate = { buildJsonObject { } }
        val h = harness()
        h.vm.openPicker()
        h.vm.pickRow((h.state.picker!!.content as PickerContent.Rows).rows.first())
        h.vm.selectWorkspace(false)
        h.vm.confirm()
        val failed = h.eventsOf<CheckpointEvent.Snackbar>().map { it.snack }.filterIsInstance<CheckpointSnack.Failed>().single()
        assertTrue(failed.error.pickerMode)
        assertTrue(failed.error.noResult)
        assertEquals(failed.error, h.state.errors.getValue("m1"))
    }

    @Test
    fun `picker empty history runs the git diagnosis`() = runTest(main) {
        hub.onGet = { record(history = """{"history":[]}""") }
        termux.on(ClineCommands.Labels.GIT_IS_WORK_TREE, TermuxResult.failure(128, "fatal: not a git repository"))
        val h = harness()
        h.vm.openPicker()
        assertEquals(PickerContent.NoHistory(GitDiagnosis.NotARepository), h.state.picker!!.content)
        assertEquals(listOf("-C", "/w/app", "rev-parse", "--is-inside-work-tree"), termux.commands.single().args)
    }

    @Test
    fun `picker history without restorable rows, load error with retry, busy and kernel down`() = runTest(main) {
        val h = harness()
        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.COMMAND_FAILED, "boom") }
        h.vm.openPicker()
        assertEquals("no cache: load error", PickerContent.LoadError("boom"), h.state.picker!!.content)

        hub.onGet = { record(history = """{"history":[{"ref":"c9","createdAt":9,"runCount":9}]}""") }
        hub.onMessages = { buildJsonObject { put("messages", JsonArray(emptyList())) } }
        h.vm.retryPicker()
        assertEquals(PickerContent.NoRows, h.state.picker!!.content)

        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.COMMAND_FAILED, "boom") }
        h.vm.retryPicker()
        assertEquals("a failed refresh keeps the cached content", PickerContent.NoRows, h.state.picker!!.content)
        assertFalse(h.state.picker!!.refreshing)

        hub.onGet = { record() }
        hub.onMessages = { messages() }
        h.vm.retryPicker()
        assertTrue(h.state.picker!!.content is PickerContent.Rows)

        sessions.set(sessionState(status = SessionStatus.Running))
        assertFalse(h.state.picker!!.rowsEnabled)
        assertTrue(h.state.picker!!.busy)
        sessions.set(sessionState())
        gate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        assertEquals(CheckpointKernelLine.NotConnected, h.state.picker!!.kernel)
        assertTrue("cached rows stay visible", h.state.picker!!.content is PickerContent.Rows)
        assertFalse(h.state.picker!!.rowsEnabled)
    }

    @Test
    fun `picker opened with the kernel down after the cache window shows cached rows disabled`() = runTest(main) {
        val h = harness()
        h.vm.openPicker()
        assertTrue(h.state.picker!!.content is PickerContent.Rows)
        h.vm.dismissPicker()

        clock.advanceMillis(10_001)
        gate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.COMMAND_FAILED, "down") }
        hub.calls.clear()
        h.vm.openPicker()
        val picker = h.state.picker!!
        assertTrue("cached rows stay visible", picker.content is PickerContent.Rows)
        assertFalse(picker.rowsEnabled)
        assertFalse(picker.refreshing)
        assertEquals(CheckpointKernelLine.NotConnected, picker.kernel)
        assertTrue("no refresh while the kernel is down", hub.calls.isEmpty())

        h.vm.retryPicker()
        assertTrue("forced refresh does not replace the cache either", h.state.picker!!.content is PickerContent.Rows)
    }

    @Test
    fun `picker refresh failure after the cache window keeps the cached rows`() = runTest(main) {
        val h = harness()
        h.vm.openPicker()
        h.vm.dismissPicker()
        clock.advanceMillis(10_001)
        hub.onGet = { throw HubCommandException("session.get", HubErrorCodes.COMMAND_FAILED, "boom") }
        hub.calls.clear()
        h.vm.openPicker()
        assertEquals(listOf("session.get"), hub.calls)
        assertTrue(h.state.picker!!.content is PickerContent.Rows)
        assertFalse(h.state.picker!!.refreshing)
    }

    @Test
    fun `kernel line mapping`() {
        assertEquals(CheckpointKernelLine.Connected, CheckpointViewModel.kernelLine(KernelGate.DegradedNoCommands))
        assertEquals(CheckpointKernelLine.NotConnected, CheckpointViewModel.kernelLine(KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Hub)))
        assertEquals(CheckpointKernelLine.Reconnecting, CheckpointViewModel.kernelLine(KernelGate.Busy(KernelGate.BusyKind.Starting, null, false)))
    }

    private companion object {
        val R_REVERT_WS = dev.cline.termux.R.string.checkpoint_revert_text_ws
        val R_REVERT_CHAT = dev.cline.termux.R.string.checkpoint_revert_text_chat
        val R_EDIT_WS = dev.cline.termux.R.string.checkpoint_edit_text_ws
        val R_EDIT_NONE = dev.cline.termux.R.string.checkpoint_edit_text_none
    }
}
