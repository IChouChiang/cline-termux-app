package dev.cline.termux.ui.chat

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.approvals.ApprovalModeRepository
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.chat.AttachmentIo
import dev.cline.termux.data.chat.AttachmentRef
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.ImageEncodeResult
import dev.cline.termux.data.chat.MentionSearch
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.DisabledToolsSource
import dev.cline.termux.data.chat.SendPipeline
import dev.cline.termux.data.chat.SlashCommandSource
import dev.cline.termux.data.chat.TextReadResult
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.notifications.PendingFirstSend
import dev.cline.termux.data.prefs.ComposerMode
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.InMemoryTurnStore
import dev.cline.termux.ui.approval.PendingInputHost
import dev.cline.termux.ui.composer.ComposerController
import dev.cline.termux.ui.composer.ComposerPrefs
import dev.cline.termux.ui.composer.ComposerVariant
import dev.cline.termux.ui.model.ConnectionUpdate
import dev.cline.termux.ui.model.ModelPickerResult
import dev.cline.termux.ui.navigation.ChatSelection
import dev.cline.termux.ui.navigation.ChatSheet
import dev.cline.termux.ui.queue.PromptQueueApi
import dev.cline.termux.ui.queue.PromptQueueController
import dev.cline.termux.ui.sessions.FakeSessionsPort
import dev.cline.termux.ui.suggestions.SuggestionMenuController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] over a real [SessionStore] fed with the recorded Hub traffic (docs/08
 * fixtures 02–05): Home's first send, streaming, the approval round trip, the resume after a
 * Hub restart and a session the Hub does not know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val main = StandardTestDispatcher()
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val session1 = "1791064689782_kck3p"
    private val prompt = "Run echo hello-from-mock and summarize"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f05 by lazy { HubFixtures.load("05-resume-after-restart.jsonl") }

    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    private val catalog = MutableStateFlow(
        CatalogState(
            source = CatalogSource.Catalog,
            providers = listOf(ProviderListItem("openai-compatible", enabled = true, apiKey = "k", defaultModelId = "mock-1")),
            modelsByProvider = mapOf("openai-compatible" to listOf(ProviderModel("mock-1"), ProviderModel("mock-2"))),
            initialized = true,
        ),
    )
    private val workspace = MutableStateFlow<WorkspaceChoice>(WorkspaceChoice.Folder("/data/data/com.termux/files/home/proj"))
    private val sessionsPort = FakeSessionsPort()
    private val known = mutableListOf<String>()
    private val usedWorkspaces = mutableListOf<String>()
    private val firstSendHolds = mutableListOf<Pair<String, PendingFirstSend?>>()
    private val termuxCalls = mutableListOf<String>()
    private var termuxReply: (TermuxCommand) -> TermuxResult = { ok("") }
    private val termuxCommands = mutableListOf<TermuxCommand>()
    private val workInWorktree = MutableStateFlow(false)

    /** The approval mode's own Termux commands (read and write `global-settings.json`); unavailable by default. */
    private val approvalCalls = mutableListOf<String>()
    private var approvalReply: (TermuxCommand) -> TermuxResult = { throw TermuxUnavailable.NotInstalled() }
    private var beforeSend: suspend (String, dev.cline.termux.data.chat.SessionConfigInput?) -> Result<Unit> = { _, _ -> Result.success(Unit) }
    private val handle = SavedStateHandle()

    /** The global compaction mode the first send carries (G1); null = `{enabled:true}`. */
    private var chatCompaction: JsonObject? = null

    private val transcriptStrings = TranscriptStrings(
        runError = RunErrorStrings(
            runFailedFormat = "The run failed: %1\$s",
            runFailedEmpty = "The run failed before a response was produced.",
            hintLocalCliFormat = "Sign in again with the `%1\$s` CLI in a terminal, then try again.",
            hintCline = "Sign in to Cline again in Settings → Account, then try again.",
            hintUnknownAuth = "Sign in again using your provider's authentication method, then try again.",
            hintProviders = "Check your model connection in Settings → Providers (or sign in with Cline), then try again.",
        ),
        startErrorFormat = "Couldn't start the session: %1\$s",
        resumeErrorFormat = "Couldn't resume this session: %1\$s",
        historyErrorFormat = "Couldn't load this session: %1\$s",
        reasonText = mapOf(SessionError.Reason.MissingResumeConfig to "Choose a model first."),
    )
    private val strings = ChatStrings(
        hubNotConnected = "Cline Hub is not connected.",
        renameFailed = "rename failed",
        forkFailed = "fork failed",
        forkEmpty = "Cannot fork an empty session.",
        deleteFailed = "delete failed",
        noModel = "Choose a model before sending.",
        worktreeFailedFormat = "Couldn't create a worktree: %1\$s",
        termuxUnavailable = "Termux could not start the command.",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ok(stdout: String) = TermuxResult(stdout = stdout, stderr = "", exitCode = 0, err = -1, errmsg = null)

    private fun fail(stderr: String, code: Int = 1) = TermuxResult(stdout = "", stderr = stderr, exitCode = code, err = -1, errmsg = null)

    /** Approvals and questions read the store; decisions are recorded. */
    private class StoreInputHost(private val store: SessionStore, override val gate: StateFlow<KernelGate>) : PendingInputHost {
        val decisions = mutableListOf<Pair<String, Boolean>>()
        override fun state(sessionId: String): StateFlow<SessionState> = store.state(sessionId)
        override val hubLifetime = MutableStateFlow<String?>("175794:a")
        override suspend fun respondToApproval(approvalId: String, approved: Boolean, reason: String?) {
            decisions += approvalId to approved
        }
        override suspend fun answerQuestion(requestId: String, answer: String): Boolean = true
        override suspend fun answerMistakeLimit(sessionId: String, requestId: String, decision: dev.cline.termux.data.session.MistakeLimitDecision): Boolean = true
        override suspend fun abort(sessionId: String): Result<Unit> = store.abort(sessionId)
        override fun recordFailure(command: String, id: String, code: String) = Unit
        override val autoApproveAll = MutableStateFlow(false)
        override suspend fun turnOnAutoApprove(): ApprovalWriteResult = ApprovalWriteResult.Written
    }

    private object NoPrefs : ComposerPrefs {
        override suspend fun draft(key: String): String? = null
        override suspend fun setDraft(key: String, text: String?) = Unit
        override suspend fun mode(sessionId: String?) = ComposerMode.Act
        override suspend fun hasSessionMode(sessionId: String) = false
        override suspend fun setMode(mode: ComposerMode, sessionId: String?) = Unit
    }

    private object NoIo : AttachmentIo {
        override suspend fun encodeImage(ref: AttachmentRef) = ImageEncodeResult.Unreadable
        override suspend fun readText(ref: AttachmentRef) = TextReadResult.Unreadable
        override suspend fun readBytes(file: File) = ByteArray(0)
        override suspend fun delete(file: File) = Unit
    }

    private object EmptyQueue : PromptQueueApi {
        private val empty = buildJsonObject { put("prompts", JsonArray(emptyList())) }
        override suspend fun pendingPrompts(sessionId: String) = empty
        override suspend fun steerFirst(sessionId: String) = empty
        override suspend fun update(sessionId: String, promptId: String, prompt: String?, delivery: String?) = empty
        override suspend fun remove(sessionId: String, promptId: String) = empty
    }

    private class Env(val hub: FakeSessionHub, val store: SessionStore, val host: StoreInputHost, val vm: ChatViewModel, val approvals: ApprovalModeRepository)

    private fun TestScope.env(configure: (FakeSessionHub) -> Unit = {}): Env {
        val hub = FakeSessionHub(clientId)
        configure(hub)
        val store = SessionStore(
            hub = hub,
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = FakeClock(),
            hubLifetimeKey = { "175794:a" },
        )
        val host = StoreInputHost(store, gate)
        val termux = FakeTermuxExecutor()
        val approvals = ApprovalModeRepository(
            run = { command ->
                approvalCalls += command.label
                approvalReply(command)
            },
            scope = backgroundScope,
        )
        val deps = ChatDeps(
            store = store,
            gate = gate,
            socket = hub.connection,
            draining = hub.draining,
            catalog = catalog,
            selection = MutableStateFlow(ModelSelection()),
            reasoning = MutableStateFlow(emptyMap<String, ReasoningEffort>()),
            workspace = workspace,
            workspaceNow = { workspace.value },
            noteWorkspaceUsed = { usedWorkspaces += it },
            approvals = approvals,
            beforeSend = { id, config -> beforeSend(id, config) },
            sessions = sessionsPort,
            diff = { flowOf(null) },
            termux = { command ->
                termuxCalls += command.label
                termuxCommands += command
                termuxReply(command)
            },
            proceed = { _, _ -> buildJsonObject { put("detachedCount", 2) } },
            knownSessionAdded = { known += it },
            knownSessionRemoved = { known -= it },
            pipeline = SendPipeline(DisabledToolsSource.NONE) { Result.failure(IllegalStateException("unread")) },
            slashSnapshots = { null },
            newComposer = { sessionId, scope, sessions, hooks ->
                ComposerController(
                    variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
                    sessionId = sessionId,
                    sessions = sessions,
                    gate = gate,
                    catalog = catalog,
                    selection = MutableStateFlow(ModelSelection()),
                    reasoning = MutableStateFlow(emptyMap<String, ReasoningEffort>()),
                    homeWorkspaceRoot = { "/home" },
                    prefs = NoPrefs,
                    attachmentIo = NoIo,
                    writeText = { dir, name, _ -> Result.success("$dir/$name") },
                    pipeline = SendPipeline(DisabledToolsSource.NONE) { Result.failure(IllegalStateException("unread")) },
                    slashSnapshots = { null },
                    voice = null,
                    scope = scope,
                    hooks = hooks,
                    compute = main,
                )
            },
            pendingInputHost = { host },
            queueFactory = PromptQueueController.Factory(EmptyQueue, SessionStateSource.of(store), gate),
            suggestions = SuggestionMenuController.Factory(SlashCommandSource({ JsonObject(emptyMap()) }, termux), MentionSearch(termux, backgroundScope)),
            transcriptStrings = transcriptStrings,
            strings = strings,
            clock = FakeClock(),
            appScope = backgroundScope,
            compute = main,
            firstSendHold = { owner, send -> firstSendHolds += owner to send },
            workInWorktree = workInWorktree,
            setWorkInWorktree = { workInWorktree.value = it },
            newWorktreeId = { "5e0b3" },
            compaction = { chatCompaction },
        )
        val vm = ChatViewModel(handle, deps)
        backgroundScope.launch { vm.uiState.collect { } }
        backgroundScope.launch { vm.reasoningSelected.collect { } }
        // ChatScreen collects the composer's state while it is shown (WhileSubscribed, docs/07 §8).
        backgroundScope.launch { vm.composer.uiState.collect { } }
        backgroundScope.launch { vm.approvalButton.collect { } }
        return Env(hub, store, host, vm, approvals)
    }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun ChatViewModel.type(text: String) {
        composer.draft.setTextAndPlaceCursorAtEnd(text)
        composer.onDraftEdited()
        Snapshot.sendApplyNotifications()
    }

    private val ChatViewModel.session: ChatUiState.Session get() = uiState.value as ChatUiState.Session

    /** Fixture 02 from Home: create → send → reply, with `session.messages` failing so live rows stay. */
    private fun TestScope.firstSend(): Env {
        val e = env { hub ->
            hub.onCreate = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
            hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
            hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
            hub.nextRequestIds += "session-send_input-002"
        }
        settle()
        e.vm.setVisible(true)
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        runCurrent()
        return e
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    @Test
    fun `fixture 02 - Home's first send creates the session, selects it and sends the prompt`() = runTest(main) {
        val e = env { }
        settle()
        assertTrue(e.vm.uiState.value is ChatUiState.Home)
        val home = (e.vm.uiState.value as ChatUiState.Home).home
        assertEquals(WorkspaceLabel.Name("proj"), home.workspace.label)

        e.hub.onCreate = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
        e.hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
        e.hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
        e.hub.nextRequestIds += "session-send_input-002"
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        runCurrent()

        val spec = e.hub.creates.single().spec
        assertEquals("/data/data/com.termux/files/home/proj", spec.workspaceRoot)
        assertEquals("openai-compatible", spec.providerId)
        assertEquals("mock-1", spec.modelId)
        assertEquals(session1, ChatSelection.selected(handle))
        assertEquals(session1, e.vm.composer.uiState.value.sessionId)
        assertEquals(session1, e.hub.sends.single().sessionId)

        settle()
        val s = e.vm.session
        assertEquals(session1, s.sessionId)
        assertEquals(prompt, s.header.title)
        assertEquals(1, s.transcript.userMessageCount)

        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        settle()
        assertEquals(listOf(session1), known)
        assertEquals(listOf("/data/data/com.termux/files/home/proj"), usedWorkspaces)
        assertEquals(session1, e.vm.controllers.value?.sessionId)
    }

    @Test
    fun `fixture 02 - a first send holds the foreground service until the prompt reached the new session`() = runTest(main) {
        val e = env { hub ->
            hub.onCreate = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
            hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
            hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
            hub.nextRequestIds += "session-send_input-002"
        }
        settle()
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        runCurrent()
        val (owner, held) = firstSendHolds.first()
        assertTrue(owner.startsWith("local:"))
        assertEquals(prompt, held?.title)
        // Released once the prompt went out, when the new session's own `Starting` phase holds.
        assertEquals(listOf(owner to held, owner to null), firstSendHolds)
        assertEquals(RunPhase.Starting, e.store.state(session1).value.facts.runPhase)
    }

    @Test
    fun `a first send whose session cannot be created releases its hold`() = runTest(main) {
        val e = env { hub -> hub.onCreate = { throw FakeSessionHub.error("session.create", "boom") } }
        settle()
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        settle()
        assertEquals(2, firstSendHolds.size)
        assertEquals(firstSendHolds[0].first to null, firstSendHolds[1])
    }

    @Test
    fun `a first send's held title is cut at a grapheme boundary`() = runTest(main) {
        val e = env { hub -> hub.onCreate = { throw FakeSessionHub.error("session.create", "boom") } }
        settle()
        // 78 units, then a ZWJ family emoji (8 UTF-16 units) across the 80-unit limit.
        val head = "a".repeat(78)
        e.vm.type("$head👨‍👩‍👧 trip plan\nsecond line")
        settle()
        e.vm.composer.send()
        settle()
        assertEquals(head, firstSendHolds.first().second?.title)
    }

    @Test
    fun `fixture 02 - streaming deltas reach the transcript and the header shows the run`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        val events = HubFixtures.events(f02)
        val firstDelta = events.indexOfFirst { it.event == "assistant.delta" }
        events.take(firstDelta + 5).forEach { e.store.dispatch(it) }
        settle()
        val s = e.vm.session
        assertTrue(s.header.busy)
        assertTrue(s.transcript.status == ChatStatus.Running || s.transcript.status == ChatStatus.Starting)
        assertTrue(s.transcript.model.rows.size >= 2)
    }

    @Test
    fun `fixture 02 - approval round trip through the session's approval controller`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        val events = HubFixtures.events(f02)
        val requested = events.indexOfFirst { it.event == "approval.requested" }
        events.take(requested + 1).forEach { e.store.dispatch(it) }
        settle()

        val approval = e.vm.controllers.value!!.approval
        val card = approval.state.value.cards.single()
        assertEquals("approval_1791064690209_v5gc4", card.approvalId)
        assertTrue(approval.state.value.sheet.isOpen)
        approval.approve(card.approvalId)
        settle()
        assertEquals(listOf(card.approvalId to true), e.host.decisions)

        events.drop(requested + 1).forEach { e.store.dispatch(it) }
        settle()
        val s = e.vm.session
        assertEquals(ChatStatus.Completed, s.transcript.status)
        assertFalse(s.header.busy)
        assertTrue(approval.state.value.cards.isEmpty())
    }

    @Test
    fun `fixture 05 - a send after the Hub restart resumes with the picker's pending connection`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        HubFixtures.events(f02).forEach { e.store.dispatch(it) }
        settle()

        // Across the restart: shutdown projections only.
        val events05 = HubFixtures.events(f05)
        events05.filter { (it.sequence ?: 0) in 107..114 }.forEach { e.store.dispatch(it) }
        settle()

        e.vm.onModelPickerResult(ModelPickerResult.PendingConnection(session1, ConnectionUpdate("openai-compatible", "mock-2", null, null)))
        e.hub.onMessages = { HubFixtures.messagesReply(f05, session1, nth = 0) }
        e.hub.onCreate = { HubFixtures.replyTo(f05, "session-create-009").payload!! }
        e.hub.nextRequestIds += "session-send_input-006"
        e.hub.nextRequestIds += "session-send_input-010"
        e.vm.type("Are you there?")
        settle()
        e.vm.composer.send()
        runCurrent()
        e.store.dispatch(events05.first { it.sequence == 115L })
        e.hub.sends.single { it.requestId == "session-send_input-006" }.reply.complete(HubFixtures.replyTo(f05, "session-send_input-006"))
        settle()

        val resume = e.hub.creates.drop(1).single()
        val config = resume.payload()["sessionConfig"]!!.jsonObject
        assertEquals(session1, config["sessionId"]!!.jsonPrimitive.content)
        assertEquals("mock-2", resume.spec.modelId)
        assertEquals(listOf("session-send_input-006", "session-send_input-010"), e.hub.sends.drop(1).map { it.requestId })
        assertEquals(session1, e.vm.session.sessionId)
        assertFalse(e.vm.session.header.missing)
    }

    @Test
    fun `a session the Hub does not know shows the missing state and disables the header`() = runTest(main) {
        val e = env { hub -> hub.onGet = { throw FakeSessionHub.error("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Unknown session: gone") } }
        ChatSelection.select(handle, "gone")
        settle()
        val s = e.vm.session
        assertEquals("gone", s.sessionId)
        assertTrue(s.header.missing)
        assertTrue(s.transcript.missing)
        assertFalse(s.header.commandsEnabled)

        e.vm.goHome()
        settle()
        assertNull(ChatSelection.selected(handle))
        assertTrue(e.vm.uiState.value is ChatUiState.Home)
    }

    // ---- first send edge cases -------------------------------------------------------------------------------

    @Test
    fun `without a model the first send fails with the pending bubble and the Start banner`() = runTest(main) {
        // A connected provider with no model to offer: the composer sends, the create cannot.
        catalog.value = CatalogState(
            source = CatalogSource.Catalog,
            providers = listOf(ProviderListItem("openai-compatible", enabled = true, apiKey = "k")),
            initialized = true,
        )
        val e = env { }
        settle()
        e.vm.type("hello")
        settle()
        e.vm.composer.send()
        settle()
        assertTrue(e.hub.creates.isEmpty())
        val s = e.vm.session
        assertNull(s.sessionId)
        assertEquals("hello", s.header.title)
        assertNotNull(s.transcript.banner)
        assertEquals(1, s.transcript.userMessageCount)
        // The bubble and its error row show, so the composer is the conversation variant (desktop `isWelcomeState`).
        assertEquals(ComposerVariant.Conversation, e.vm.composer.uiState.value.variant)
        assertNull(e.vm.composer.uiState.value.sessionId)

        e.vm.onNewSessionRequested()
        settle()
        assertTrue(e.vm.uiState.value is ChatUiState.Home)
        assertEquals(ComposerVariant.Welcome, e.vm.composer.uiState.value.variant)
    }

    @Test
    fun `a deep-linked approval sheet opens once the session's controllers exist`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        val events = HubFixtures.events(f02)
        events.take(events.indexOfFirst { it.event == "approval.requested" } + 1).forEach { e.store.dispatch(it) }
        e.vm.setVisible(false)
        settle()
        val approval = e.vm.controllers.value!!.approval
        approval.collapse()
        settle()
        assertFalse(approval.state.value.sheet.isOpen)

        ChatSelection.select(handle, session1, ChatSheet.Approval)
        settle()
        assertTrue(approval.state.value.sheet.isOpen)
        assertNull(handle.get<String>(ChatSelection.KEY_SHEET))
    }

    // ---- A4: the stall notice ----------------------------------------------------------------------------------

    @Test
    fun `a silent run shows the stall notice and Keep waiting hides it until the next episode`() = runTest(main) {
        val e = env { }
        ChatSelection.select(handle, "s1")
        settle()
        backgroundScope.launch { e.vm.stall.collect { } }
        val t = FakeClock().millis()
        var seq = 100L
        suspend fun event(name: String, at: Long, payload: JsonObject = buildJsonObject { put("requestId", "r1") }) {
            e.store.dispatch(HubEventEnvelope(event = name, sessionId = "s1", sequence = ++seq, timestamp = at, payload = payload))
        }
        event(HubEvents.RUN_STARTED, t - 40_000)
        event(HubEvents.RUN_HEARTBEAT, t - 10_000)
        settle()
        assertEquals(RunPhase.Running, e.store.state("s1").value.facts.runPhase)
        assertEquals(t - 10_000, e.vm.stall.value?.since)

        e.vm.keepWaiting()
        settle()
        assertNull(e.vm.stall.value)
        event(HubEvents.RUN_HEARTBEAT, t + 20_000)
        settle()
        assertNull("still the same silence", e.vm.stall.value)

        event(HubEvents.ASSISTANT_DELTA, t + 25_000, buildJsonObject { put("text", "I'll r") })
        event(HubEvents.RUN_HEARTBEAT, t + 50_000)
        settle()
        assertNull(e.vm.stall.value)
        event(HubEvents.RUN_HEARTBEAT, t + 80_000)
        settle()
        assertEquals("a new episode shows again", t + 80_000, e.vm.stall.value?.since)

        // The kernel banner speaks while the line is down.
        e.hub.connection.value = HubConnectionState.Reconnecting(1, "dropped")
        settle()
        assertNull(e.vm.stall.value)
    }

    // ---- header actions ----------------------------------------------------------------------------------------

    @Test
    fun `rename skips blank and unchanged titles, reverts and reports a failure`() = runTest(main) {
        val e = env { }
        ChatSelection.select(handle, "s1")
        settle()
        e.vm.rename("   ", "Old")
        e.vm.rename("Old", "Old")
        settle()
        assertTrue(sessionsPort.calls.none { it.startsWith("rename") })

        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch { e.vm.eventFlow.collect { events += it } }
        sessionsPort.renameOutcome = ActionOutcome.Failed(ActionFailure.Kernel("nope"))
        e.vm.rename("New title", "Old")
        settle()
        assertEquals("rename:s1:New title", sessionsPort.calls.last { it.startsWith("rename") })
        assertEquals(ChatEvent.RenameFailed("nope"), events.single())
        assertNull(e.vm.session.header.pending)
    }

    @Test
    fun `fork opens the new session and delete goes Home after forgetting the session`() = runTest(main) {
        val e = env { }
        ChatSelection.select(handle, "s1")
        settle()
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch { e.vm.eventFlow.collect { events += it } }
        e.vm.fork()
        settle()
        e.vm.delete()
        settle()
        assertEquals(listOf(ChatEvent.OpenSession("fork-1"), ChatEvent.OpenHome), events)
        assertTrue("the three-dot forks the whole session (A16)", "fork:s1" in sessionsPort.calls)
        assertTrue("delete:s1" in sessionsPort.calls)
    }

    @Test
    fun `Home's first send carries the global compaction mode`() = runTest(main) {
        chatCompaction = buildJsonObject { put("enabled", false) }
        val e = firstSend()
        assertEquals(chatCompaction, e.hub.creates.first().spec.compaction)
    }

    @Test
    fun `export checks shared storage first and saves to Download`() = runTest(main) {
        val e = env { }
        ChatSelection.select(handle, "s1")
        settle()
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch { e.vm.eventFlow.collect { events += it } }

        termuxReply = { cmd -> if (cmd.label == ClineCommands.Labels.HAS_SHARED_STORAGE) fail("No such file") else ok("") }
        e.vm.export()
        settle()
        assertEquals(ChatEvent.ExportNeedsStorage, events.last())

        termuxReply = { ok("") }
        e.vm.export()
        settle()
        assertEquals(ChatEvent.ExportSaved("cline-s1.html"), events.last())
        assertTrue(ClineCommands.Labels.HISTORY_EXPORT in termuxCalls)
    }

    @Test
    fun `the branch chip follows git and a checkout updates it`() = runTest(main) {
        termuxReply = { cmd ->
            when (cmd.label) {
                ClineCommands.Labels.GIT_BRANCH -> ok("main\n")
                ClineCommands.Labels.GIT_BRANCHES -> ok("main\nfeature/x\n")
                else -> ok("")
            }
        }
        val e = env { }
        settle()
        assertEquals(BranchChipState("main", busy = false), (e.vm.uiState.value as ChatUiState.Home).home.branch)

        e.vm.openBranchSheet()
        settle()
        assertEquals(listOf("main", "feature/x"), e.vm.branchSheet.value.branches)
        e.vm.selectBranch("feature/x")
        settle()
        assertEquals("feature/x", (e.vm.uiState.value as ChatUiState.Home).home.branch?.name)

        workspace.value = WorkspaceChoice.Chat
        settle()
        val home = (e.vm.uiState.value as ChatUiState.Home).home
        assertTrue(home.workspace.isDefault)
        assertNull(home.branch)
    }

    @Test
    fun `with no folder chosen the first send starts the session in the Termux home (A22)`() = runTest(main) {
        workspace.value = WorkspaceChoice.Chat
        val e = sendFromHome()
        val spec = e.hub.creates.single().spec
        assertEquals(TERMUX_HOME, spec.workspaceRoot)
        assertEquals(TERMUX_HOME, spec.cwd)
        // Nothing is created or written in the home, and it does not become Home's chosen folder
        // (no `noteWorkspaceUsed`), so the chip keeps reading "Home", not "~".
        assertEquals(emptyList<String>(), termuxCalls)
        assertTrue(usedWorkspaces.isEmpty())
    }

    // ---- G3 worktree --------------------------------------------------------------------------------------

    private val projRoot = "/data/data/com.termux/files/home/proj"
    private val worktreePath = "${WorktreeCommands.WORKTREES_ROOT}/5e0b3/proj"
    private val order = mutableListOf<String>()

    /** A git workspace on `main`; [worktree] answers the worktree commands. */
    private fun gitWorkspace(worktree: (TermuxCommand) -> TermuxResult) {
        termuxReply = { cmd ->
            when (cmd.label) {
                ClineCommands.Labels.GIT_BRANCH -> ok("main\n")
                WorktreeCommands.LABEL_CREATE, WorktreeCommands.LABEL_REMOVE -> {
                    order += cmd.label
                    worktree(cmd)
                }
                else -> ok("")
            }
        }
    }

    private val ChatViewModel.home: HomeState get() = (uiState.value as ChatUiState.Home).home

    private fun TestScope.sendFromHome(createFails: Boolean = false): Env {
        val e = env { hub ->
            hub.onCreate = {
                order += "session.create"
                if (createFails) throw FakeSessionHub.error("session.create", "command_failed", "no credentials")
                HubFixtures.replyTo(f02, "session-create-001").payload!!
            }
            hub.onGet = { HubFixtures.replyTo(f02, "session-create-001").payload!! }
            hub.onMessages = { throw FakeSessionHub.error("session.messages", "unavailable") }
            hub.nextRequestIds += "session-send_input-002"
        }
        settle()
        e.vm.setVisible(true)
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        runCurrent()
        return e
    }

    @Test
    fun `the worktree chip sits beside the branch chip and remembers the toggle`() = runTest(main) {
        gitWorkspace { ok("") }
        val e = env { }
        settle()
        assertEquals(BranchChipState("main", busy = false), e.vm.home.branch)
        assertEquals(false, e.vm.home.worktree)

        e.vm.setWorkInWorktree(true)
        settle()
        assertTrue(workInWorktree.value)
        assertEquals(true, e.vm.home.worktree)

        workspace.value = WorkspaceChoice.Chat
        settle()
        assertNull(e.vm.home.branch)
        assertNull(e.vm.home.worktree)
    }

    @Test
    fun `the worktree chip is hidden for a folder that is not a git checkout`() = runTest(main) {
        workInWorktree.value = true
        termuxReply = { cmd ->
            if (cmd.label == ClineCommands.Labels.GIT_BRANCH) fail("fatal: not a git repository", code = 128) else ok("")
        }
        val probe = env { }
        settle()
        assertNull(probe.vm.home.branch)
        assertNull(probe.vm.home.worktree)
        val e = sendFromHome()
        // The stored toggle is ignored: the session runs in the folder itself.
        assertFalse(WorktreeCommands.LABEL_CREATE in termuxCalls)
        assertEquals(projRoot, e.hub.creates.single().spec.workspaceRoot)
    }

    @Test
    fun `with the toggle on the first send cuts a worktree, then creates the session in it`() = runTest(main) {
        workInWorktree.value = true
        gitWorkspace { cmd -> if (cmd.label == WorktreeCommands.LABEL_CREATE) ok("$projRoot\n$worktreePath\n") else ok("") }
        val e = sendFromHome()

        assertEquals(listOf(WorktreeCommands.LABEL_CREATE, "session.create"), order)
        val create = termuxCommands.single { it.label == WorktreeCommands.LABEL_CREATE }
        assertEquals(listOf("_", projRoot, "5e0b3"), create.args.drop(2))
        val spec = e.hub.creates.single().spec
        assertEquals(worktreePath, spec.workspaceRoot)
        assertEquals(worktreePath, spec.cwd)
        assertEquals(session1, e.hub.sends.single().sessionId)
        settle()
        // Home keeps the repository, never the worktree.
        assertEquals(listOf(projRoot), usedWorkspaces)
        assertFalse(WorktreeCommands.LABEL_REMOVE in termuxCalls)
    }

    @Test
    fun `a failed worktree sends nothing and gives the draft back`() = runTest(main) {
        workInWorktree.value = true
        gitWorkspace { fail("fatal: invalid reference: HEAD", code = 128) }
        val messages = mutableListOf<dev.cline.termux.ui.composer.ComposerMessage>()
        val e = env { hub -> hub.onCreate = { order += "session.create"; HubFixtures.replyTo(f02, "session-create-001").payload!! } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { e.vm.composer.messages.collect { messages += it } }
        settle()
        e.vm.type(prompt)
        settle()
        e.vm.composer.send()
        settle()

        assertEquals(listOf(WorktreeCommands.LABEL_CREATE), order)
        assertTrue(e.hub.creates.isEmpty())
        assertTrue(e.hub.sends.isEmpty())
        assertTrue(e.vm.uiState.value is ChatUiState.Home)
        assertEquals(prompt, e.vm.composer.draft.text.toString())
        assertEquals(
            dev.cline.termux.ui.composer.ComposerMessage.SendFailed("Couldn't create a worktree: fatal: invalid reference: HEAD"),
            messages.last(),
        )
        // The foreground hold of the first send is released again.
        assertNull(firstSendHolds.last().second)
    }

    @Test
    fun `a failed session create removes the worktree it was given`() = runTest(main) {
        workInWorktree.value = true
        gitWorkspace { cmd -> if (cmd.label == WorktreeCommands.LABEL_CREATE) ok("$projRoot\n$worktreePath\n") else ok("") }
        sendFromHome(createFails = true)
        settle()

        assertEquals(listOf(WorktreeCommands.LABEL_CREATE, "session.create", WorktreeCommands.LABEL_REMOVE), order)
        val remove = termuxCommands.single { it.label == WorktreeCommands.LABEL_REMOVE }
        assertEquals(listOf("_", worktreePath), remove.args.drop(2))
    }

    // ---- pure rules ------------------------------------------------------------------------------------------

    @Test
    fun `pending connections merge and fold into the resume config`() {
        val merged = PendingConnections.merge(ConnectionUpdate("p", "m1", true, "high"), ConnectionUpdate(null, "m2", false, null))
        assertEquals(ConnectionUpdate("p", "m2", false, null), merged)
        val facts = dev.cline.termux.data.session.SessionFacts("s", workspaceRoot = "/ws", providerId = "p", modelId = "m0")
        val config = PendingConnections.config(facts, merged, autoApprove = true)!!
        assertEquals("/ws", config.workspaceRoot)
        assertEquals("m2", config.modelId)
        assertEquals(false, config.thinking)
        assertNull(config.reasoningEffort)
        assertTrue(config.autoApprove)
        assertNull(PendingConnections.config(dev.cline.termux.data.session.SessionFacts("s"), merged, autoApprove = true))
    }

    @Test
    fun `a resume takes the global auto-approve, not the session's old policies (decided A1)`() {
        val auto = buildJsonObject { put("autoApproveTools", true) }
        val facts = dev.cline.termux.data.session.SessionFacts("s", workspaceRoot = "/ws", providerId = "p", modelId = "m0", metadata = auto)
        val config = PendingConnections.config(facts, ConnectionUpdate(null, "m1", null, null), autoApprove = false)!!
        assertFalse(config.autoApprove)
    }

    // ---- tool approvals control (decided A1) -------------------------------------------------------------

    @Test
    fun `the header control reads the kernel's mode when its menu opens`() = runTest(main) {
        approvalReply = { ok("{\"toolAutoApprove\":false}") }
        val e = env { }
        settle()
        assertEquals(dev.cline.termux.data.approvals.ApprovalMode.Unknown, e.vm.approvalButton.value.mode)
        assertFalse(e.vm.approvalButton.value.enabled)

        e.vm.onApprovalMenuOpened()
        settle()
        assertEquals(dev.cline.termux.data.approvals.ApprovalMode.Ask, e.vm.approvalButton.value.mode)
        assertTrue(e.vm.approvalButton.value.enabled)
        assertEquals(listOf(ClineCommands.Labels.READ_GLOBAL_SETTINGS), approvalCalls)
    }

    @Test
    fun `Home's first send re-reads the mode and creates the session with it`() = runTest(main) {
        // No `toolAutoApprove` key: the terminal's default, Auto-approve all.
        approvalReply = { ok("{\"telemetryOptOut\":false}") }
        val e = firstSend()
        settle()
        assertTrue(e.hub.creates.single().spec.autoApprove)
        assertTrue(ClineCommands.Labels.READ_GLOBAL_SETTINGS in approvalCalls)
    }

    @Test
    fun `Home's first send with an unreadable mode asks (the safe value)`() = runTest(main) {
        val e = firstSend()
        settle()
        assertFalse(e.hub.creates.single().spec.autoApprove)
    }

    @Test
    fun `a failed pick in the menu rolls back and says why`() = runTest(main) {
        approvalReply = { command ->
            if (command.label == ClineCommands.Labels.READ_GLOBAL_SETTINGS) ok("{\"toolAutoApprove\":false}") else fail("disk full")
        }
        val e = env { }
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch { e.vm.eventFlow.collect { events += it } }
        e.vm.onApprovalMenuOpened()
        settle()

        e.vm.setApprovalMode(true)
        settle()
        assertEquals(dev.cline.termux.data.approvals.ApprovalMode.Ask, e.vm.approvalButton.value.mode)
        val failed = events.filterIsInstance<ChatEvent.ApprovalModeFailed>().single()
        assertTrue(failed.turningOn)
        assertEquals(dev.cline.termux.data.approvals.ApprovalWriteFailure.Failed(1, "disk full"), failed.failure)
    }

    @Test
    fun `a change made in the terminal is announced only while the chat is on screen`() = runTest(main) {
        var stored = "{\"toolAutoApprove\":false}"
        approvalReply = { ok(stored) }
        val e = env { }
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch { e.vm.eventFlow.collect { events += it } }
        e.vm.onApprovalMenuOpened()
        settle()

        stored = "{\"toolAutoApprove\":true}"
        e.approvals.refresh(dev.cline.termux.data.approvals.ApprovalRefresh.Foreground)
        settle()
        assertTrue(events.none { it is ChatEvent.ApprovalModeChangedOutside })

        e.vm.setVisible(true)
        stored = "{\"toolAutoApprove\":false}"
        e.approvals.refresh(dev.cline.termux.data.approvals.ApprovalRefresh.Foreground)
        settle()
        assertEquals(listOf(ChatEvent.ApprovalModeChangedOutside(false)), events.filterIsInstance<ChatEvent.ApprovalModeChangedOutside>())
    }

    @Test
    fun `a failed run-boundary restart before a send keeps the prompt from going out`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        HubFixtures.events(f02).forEach { e.store.dispatch(it) }
        settle()
        val asked = mutableListOf<String>()
        beforeSend = { id, _ ->
            asked += id
            Result.failure(IllegalStateException("restart failed"))
        }

        e.vm.type("second prompt")
        settle()
        e.vm.composer.send()
        settle()
        assertEquals(listOf(session1), asked)
        assertEquals(1, e.hub.sends.size)
    }

    @Test
    fun `an idle session's direct send passes the run-boundary check first`() = runTest(main) {
        val e = firstSend()
        e.hub.sends.single().reply.complete(HubFixtures.replyTo(f02, "session-send_input-002"))
        HubFixtures.events(f02).forEach { e.store.dispatch(it) }
        settle()
        val asked = mutableListOf<String>()
        beforeSend = { id, _ ->
            asked += id
            Result.success(Unit)
        }

        e.vm.type("second prompt")
        settle()
        e.vm.composer.send()
        settle()
        assertEquals(listOf(session1), asked)
        assertEquals(2, e.hub.sends.size)
    }

    @Test
    fun `a leave followed by a show of another session ends with that session visible`() {
        val tracker = VisibleSessionTracker()
        val visible = mutableListOf<String?>()
        val shown = mutableListOf<String>()
        tracker.update("A", visible = true, setVisible = { visible += it }, onShown = { shown += it })
        tracker.update("A", visible = false, setVisible = { visible += it }, onShown = { shown += it })
        tracker.update("B", visible = true, setVisible = { visible += it }, onShown = { shown += it })
        assertEquals(listOf("A", null, "B"), visible)
        assertEquals("B", visible.last())
        assertEquals(listOf("A", "B"), shown)
        // A late "left A" after B is shown (navigation resumes B first) never clears B.
        tracker.update("A", visible = false, setVisible = { visible += it }, onShown = { shown += it })
        assertEquals("B", visible.last())
    }

    @Test
    fun `the first send's pending key and Start error`() {
        val fs = FirstSend("local:1", "hi", 0L, error = "boom")
        assertEquals(SessionError.Kind.Start, fs.hostError?.kind)
        assertEquals(1, fs.syntheticState(FirstSend.PENDING_KEY).messages.size)
        assertEquals(PromptMode.Act.wire, "act")
    }
}
