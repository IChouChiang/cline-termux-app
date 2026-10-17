package dev.cline.termux.ui.chat

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.CreatedWorktree
import dev.cline.termux.core.kernel.termux.GitBranchProbe
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.data.catalog.ProviderUsageCost
import dev.cline.termux.data.chat.AndroidAttachmentIo
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.chat.GlobalSettingsDisabledTools
import dev.cline.termux.data.chat.SendPipeline
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateBuilder
import dev.cline.termux.data.chat.SessionCreateRequest
import dev.cline.termux.data.chat.SlashResolution
import dev.cline.termux.core.kernel.termux.writeAttachment
import dev.cline.termux.data.diff.DiffStoreState
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.model.resolveHomeSelection
import dev.cline.termux.data.model.thinkingConfig
import dev.cline.termux.data.notifications.PendingFirstSend
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionCreateDisconnectedException
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.StallFact
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.SessionsSnapshot
import dev.cline.termux.data.sessions.normalizeTitle
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.normalizeWorkspacePath
import dev.cline.termux.ui.agents.AgentsChipModel
import dev.cline.termux.data.approvals.ApprovalModeRepository
import dev.cline.termux.data.approvals.ApprovalRefresh
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.ui.approval.ApprovalButtonState
import dev.cline.termux.ui.approval.ApprovalController
import dev.cline.termux.ui.approval.PendingInputHost
import dev.cline.termux.ui.chat.rows.MessageActionsState
import dev.cline.termux.ui.chat.rows.ProceedOutcome
import dev.cline.termux.ui.composer.AndroidSpeechEngine
import dev.cline.termux.ui.composer.ComposerAgents
import dev.cline.termux.ui.composer.ComposerController
import dev.cline.termux.ui.composer.ComposerPrefs
import dev.cline.termux.ui.composer.ComposerSessions
import dev.cline.termux.ui.composer.ComposerVariant
import dev.cline.termux.ui.composer.ComposerProjection
import dev.cline.termux.ui.composer.HubSlashSnapshots
import dev.cline.termux.ui.composer.SlashSnapshotSource
import dev.cline.termux.ui.composer.VoiceInputController
import dev.cline.termux.ui.model.ConnectionUpdate
import dev.cline.termux.ui.model.ModelPickerResult
import dev.cline.termux.ui.navigation.ChatSelection
import dev.cline.termux.ui.navigation.ChatSheet
import dev.cline.termux.ui.queue.PromptQueueApi
import dev.cline.termux.ui.queue.PromptQueueController
import dev.cline.termux.ui.question.AskQuestionController
import dev.cline.termux.ui.sessions.SessionsDrawerViewModel
import dev.cline.termux.ui.sessions.SessionsPort
import dev.cline.termux.ui.suggestions.SuggestionContext
import dev.cline.termux.ui.suggestions.SuggestionMenuController
import dev.cline.termux.ui.suggestions.toSuggestionKernel
import dev.cline.termux.data.chat.MentionSearch
import dev.cline.termux.data.chat.SlashCommandSource
import dev.cline.termux.data.chat.TextEdit
import dev.cline.termux.data.chat.TokenEdit
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.sessions.SessionSummary
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Copy the ViewModel needs as data (read once from resources; tests pass plain strings). */
data class ChatStrings(
    /** "Cline Hub is not connected." */
    val hubNotConnected: String,
    /** "The session title could not be updated." */
    val renameFailed: String,
    /** "Could not fork session." */
    val forkFailed: String,
    /** "Cannot fork an empty session." */
    val forkEmpty: String,
    /** "The session could not be removed from local history." */
    val deleteFailed: String,
    /** "Choose a model before sending." */
    val noModel: String,
    /** "Couldn't create a worktree: %1$s" (desktop use-chat-session.ts), git's last line as the argument. */
    val worktreeFailedFormat: String,
    /** "Termux could not start the command." (the worktree's `git` never ran). */
    val termuxUnavailable: String,
) {
    /** [worktreeFailedFormat] with its detail line. */
    fun worktreeFailed(detail: String): String = worktreeFailedFormat.replace("%1\$s", detail)

    companion object {
        fun from(resources: Resources) = ChatStrings(
            hubNotConnected = resources.getString(R.string.hub_not_connected),
            renameFailed = resources.getString(R.string.sessions_rename_failed_body),
            forkFailed = resources.getString(R.string.msg_fork_error),
            forkEmpty = resources.getString(R.string.msg_fork_empty),
            deleteFailed = resources.getString(R.string.sessions_delete_failed_body),
            noModel = resources.getString(R.string.chat_no_model),
            // The raw format: the detail is git's text, filled in by [worktreeFailed].
            worktreeFailedFormat = resources.getString(R.string.worktree_create_failed),
            termuxUnavailable = resources.getString(R.string.components_failure_termux_start),
        )
    }
}

/** The per-session controllers the chat owns while a session is selected (CA §0.1). */
class SessionControllers(
    val sessionId: String,
    val approval: ApprovalController,
    val question: AskQuestionController,
    val queue: PromptQueueController,
    private val job: Job,
) {
    fun close() {
        approval.close()
        question.close()
        job.cancel()
    }
}

/**
 * Everything [ChatViewModel] reads (docs/07 §0 rule 8: stores and controllers, never the
 * socket). [of] wires the app graph; tests build it from fakes and a real [SessionStore] over
 * `FakeSessionHub`.
 */
class ChatDeps(
    val store: SessionStore,
    val gate: StateFlow<KernelGate>,
    val socket: StateFlow<HubConnectionState>,
    val draining: StateFlow<Boolean>,
    val catalog: StateFlow<CatalogState>,
    val selection: Flow<ModelSelection>,
    val reasoning: Flow<Map<String, ReasoningEffort>>,
    val workspace: Flow<WorkspaceChoice>,
    val workspaceNow: suspend () -> WorkspaceChoice,
    /** A session was created in [path]: it moves to the front of the recents (§2.7 step 5). */
    val noteWorkspaceUsed: suspend (path: String) -> Unit,
    /** The kernel's global auto-approve (decided A1): new sessions, resumes and the header's control. */
    val approvals: ApprovalModeRepository,
    /** `RunBoundaryCoordinator.beforeSend`: an idle session whose policies differ restarts before a send. */
    val beforeSend: suspend (sessionId: String, config: SessionConfigInput?) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    /** Rename / pin / fork / delete and the sessions list (busy workspaces, parent titles). */
    val sessions: SessionsPort,
    /** The header's diff summary (`SessionDiffStore`, CT §1.3). */
    val diff: (sessionId: String) -> Flow<SessionDiffSummary?>,
    /** RUN_COMMAND through the bridge (git, export); throws `TermuxUnavailable`. */
    val termux: suspend (TermuxCommand) -> TermuxResult,
    /** `run.proceed_while_running {sessionId, toolCallId}` → `{detachedCount}`. */
    val proceed: suspend (sessionId: String, toolCallId: String?) -> JsonObject,
    /** `KnownSessions.add` on create (CR §11.3) and `remove` on delete. */
    val knownSessionAdded: suspend (String) -> Unit,
    val knownSessionRemoved: suspend (String) -> Unit,
    /** C5.7 slash resolution for queue edits (`sendPipeline.resolve(text, mode, snapshot)`). */
    val pipeline: SendPipeline,
    val slashSnapshots: SlashSnapshotSource,
    /** Builds the composer with the chat's [ComposerSessions] wrapper and hooks. */
    val newComposer: (sessionId: String?, scope: CoroutineScope, sessions: ComposerSessions, hooks: ComposerController.Hooks) -> ComposerController,
    val pendingInputHost: (scope: CoroutineScope) -> PendingInputHost,
    val queueFactory: PromptQueueController.Factory,
    /** The slash (C5) and mention (C6) menus of the composer. */
    val suggestions: SuggestionMenuController.Factory,
    val transcriptStrings: TranscriptStrings,
    val strings: ChatStrings,
    val clock: Clock,
    /** Outlives the ViewModel: `session.close` of the last open session in `onCleared()`. */
    val appScope: CoroutineScope,
    /** Where the transcript pipeline computes (`buildTranscriptModel` is O(messages)). */
    val compute: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The selected session is on screen ([visible] = true: chat resumed) or left the screen
     * (SS §1.6 unread, CR §11.4 cancel-on-visible, CR §11.3 KnownSessions on open).
     */
    val sessionVisibility: (sessionId: String, visible: Boolean) -> Unit = { _, _ -> },
    /**
     * The selected session no longer exists on the Hub (`session.get` → `session_not_found`; a
     * notification tap, CR §11.4): its notifications go and its row leaves the list (F1 step 3).
     */
    val sessionGone: (sessionId: String) -> Unit = {},
    /** `KeepAlivePolicy.setFirstSend`: a first send holds the foreground service while its session is created (CR §12.2). */
    val firstSendHold: (owner: String, send: PendingFirstSend?) -> Unit = { _, _ -> },
    /** G3: Home's "Worktree" chip (`home.workInWorktree`): the first send cuts a task worktree. */
    val workInWorktree: Flow<Boolean> = flowOf(false),
    val setWorkInWorktree: suspend (Boolean) -> Unit = {},
    /** A new task worktree id (desktop: five hex characters of a random UUID). */
    val newWorktreeId: () -> String = { WorktreeCommands.newId() },
    /** `sessionConfig.compaction` of a first send (G1, `CompactionModeSource.forCreate`); null = `{enabled:true}`. */
    val compaction: suspend () -> JsonObject? = { null },
    /** A11.9: `transcript.messageDetails` (Settings → Message details). */
    val messageDetails: Flow<MessageDetails> = flowOf(MessageDetails.Brief),
    /** A11.9 Verbose: the providers whose cost the stats leave out (bundle, then the live catalog). */
    val usageCost: Flow<ProviderUsageCost> = flowOf(ProviderUsageCost.EMPTY),
) {
    companion object {
        /** The production wiring; touch only after the graph was warmed off Main (docs/07 §8). */
        fun of(graph: AppGraph, context: Context): ChatDeps {
            val app = context.applicationContext
            val store = graph.sessionStore
            val api = graph.sessionApi
            val termux = graph.termux
            val slashSnapshots = HubSlashSnapshots(api, graph.clock)
            val pipeline = SendPipeline(GlobalSettingsDisabledTools(termux)) { path ->
                runCatching { termux.run(ClineCommands.readTextFile(path)) }
                    .mapCatching { result -> if (result.ok) result.stdout else error("exit ${result.exitCode}") }
            }
            val speech = graph.speech
            val attachmentIo = AndroidAttachmentIo(app)
            val prefs = ComposerPrefs.of(graph.prefs)
            return ChatDeps(
                store = store,
                gate = graph.kernel.gate,
                socket = graph.hubClient.state,
                draining = graph.hubClient.draining,
                catalog = graph.providerCatalog.state,
                selection = graph.modelSelection.selection,
                reasoning = graph.modelSelection.reasoning,
                workspace = graph.workspaceStore.active,
                workspaceNow = { graph.workspaceStore.activeNow() },
                noteWorkspaceUsed = { path -> graph.workspaceStore.adopt(path) },
                approvals = graph.approvalMode,
                beforeSend = { id, config -> graph.runBoundary.beforeSend(id, config) },
                sessions = SessionsDrawerViewModel.graphPort(graph),
                diff = { id ->
                    graph.diffStores.state(id).map { state -> (state as? DiffStoreState.Ready)?.diff?.summary }
                },
                termux = { command -> termux.run(command) },
                proceed = { id, toolCallId -> api.proceedWhileRunning(id, toolCallId) },
                knownSessionAdded = { id -> graph.knownSessions.add(id) },
                knownSessionRemoved = { id -> graph.knownSessions.remove(id) },
                pipeline = pipeline,
                slashSnapshots = slashSnapshots,
                newComposer = { sessionId, scope, sessions, hooks ->
                    ComposerController(
                        variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
                        sessionId = sessionId,
                        sessions = sessions,
                        gate = graph.kernel.gate,
                        catalog = graph.providerCatalog.state,
                        selection = graph.modelSelection.selection,
                        reasoning = graph.modelSelection.reasoning,
                        homeWorkspaceRoot = { graph.workspaceStore.activeNow().pathOrNull ?: TermuxPaths.HOME },
                        prefs = prefs,
                        attachmentIo = attachmentIo,
                        writeText = { dir, name, content -> termux.writeAttachment(dir, name, content) },
                        pipeline = pipeline,
                        slashSnapshots = slashSnapshots,
                        voice = VoiceInputController(AndroidSpeechEngine(app), scope, available = speech.check()),
                        scope = scope,
                        hooks = hooks,
                        // Stop while spawned agents or a team still run (desktop `hasRunningAgents`).
                        runningAgents = { id ->
                            ComposerAgents.running(id, store.state(id)) { parent, expected -> graph.agents.observe(parent, expected) }
                        },
                        // The model's approved switch_to_act_mode flips Plan/Act (A11 row 4).
                        modeSwitches = graph.clientContributions,
                        history = graph.inputHistory,
                    )
                },
                pendingInputHost = { scope -> PendingInputHost.of(store, api, graph.kernel, graph.approvalMode, graph.clientContributions, scope) },
                queueFactory = PromptQueueController.Factory(PromptQueueApi.of(api), SessionStateSource.of(store), graph.kernel.gate),
                suggestions = SuggestionMenuController.Factory(SlashCommandSource.of(api, termux), MentionSearch(termux, graph.appScope)),
                transcriptStrings = TranscriptStrings.from(app.resources),
                strings = ChatStrings.from(app.resources),
                clock = graph.clock,
                appScope = graph.appScope,
                sessionVisibility = { id, visible -> VisibleSession.update(graph, id, visible) },
                sessionGone = { id ->
                    graph.notifier.onSessionGone(id)
                    graph.appScope.launch {
                        runCatching { graph.knownSessions.remove(id) }
                        // F1 step 3, trial row 8: the drawer and the list cache drop the row at once.
                        runCatching { graph.sessionsRepository.forget(id) }
                    }
                },
                firstSendHold = graph.keepAlive::setFirstSend,
                workInWorktree = graph.prefs.workInWorktree,
                setWorkInWorktree = { on -> graph.prefs.setWorkInWorktree(on) },
                compaction = { graph.compactionMode.forCreate() },
                messageDetails = graph.prefs.messageDetails,
                usageCost = flow {
                    val bundled = ProviderUsageCost.load(app)
                    emitAll(graph.providerCatalog.state.map { catalog -> bundled.withLive(catalog.providers) })
                }.distinctUntilChanged(),
            )
        }
    }
}

/**
 * The one session on screen across chat entries (the root and pushed subagent chats): a late
 * "left" from the entry below never clears the entry on top (navigation pauses and resumes
 * entries in either order).
 */
internal object VisibleSession {
    private val tracker = VisibleSessionTracker()

    fun update(graph: AppGraph, sessionId: String, visible: Boolean) = tracker.update(
        sessionId = sessionId,
        visible = visible,
        setVisible = graph.sessionsRepository::setVisibleSession,
        onShown = { id ->
            graph.notifier.onSessionVisible(id)
            graph.appScope.launch { runCatching { graph.knownSessions.add(id) } }
        },
    )
}

/**
 * [VisibleSession]'s ordering rule: [setVisible] runs synchronously inside [update], so a
 * "left A" followed by a "shown B" reaches the repository in that order (two parallel launches
 * could land the late `null` after `B`, leaving nothing visible while B is on screen).
 */
internal class VisibleSessionTracker {
    private val current = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun update(sessionId: String, visible: Boolean, setVisible: (String?) -> Unit, onShown: (String) -> Unit) {
        if (visible) {
            current.set(sessionId)
            setVisible(sessionId)
            onShown(sessionId)
        } else if (current.compareAndSet(sessionId, null)) {
            setVisible(null)
        }
    }
}

/** Not-live sessions keep the picker's choice until the next send resumes them (SP S8.3). */
object PendingConnections {
    fun merge(old: ConnectionUpdate?, new: ConnectionUpdate): ConnectionUpdate = ConnectionUpdate(
        providerId = new.providerId ?: old?.providerId,
        modelId = new.modelId ?: old?.modelId,
        thinking = new.thinking ?: old?.thinking,
        reasoningEffort = if (new.thinking == false) null else new.reasoningEffort ?: old?.reasoningEffort,
    )

    /**
     * The resume `session.create` config (HC §3.12 step 3): the session's own workspace, model
     * and flags with the pending [update] folded in. Null when the session has no workspace or
     * model to resume with (the store then reports MissingResumeConfig). [autoApprove] is the
     * kernel's global mode now: every resume takes it, whatever the session had (decided A1).
     */
    fun config(facts: SessionFacts, update: ConnectionUpdate, autoApprove: Boolean): SessionConfigInput? {
        val root = facts.workspaceRoot ?: return null
        val provider = update.providerId ?: facts.providerId ?: return null
        val model = update.modelId ?: facts.modelId.takeIf { update.providerId == null || update.providerId == facts.providerId } ?: return null
        val thinking = update.thinking ?: facts.thinking
        return SessionConfigInput(
            workspaceRoot = root,
            cwd = facts.cwd ?: root,
            providerId = provider,
            modelId = model,
            mode = facts.mode,
            autoApprove = autoApprove,
            thinking = thinking,
            reasoningEffort = if (thinking == false) null else update.reasoningEffort ?: facts.reasoningEffort,
        )
    }
}

/**
 * The chat destination's ViewModel (home-chat.md §1.1, §2.7, §2.9; composer-approvals.md §0.1;
 * docs/07 §6.5): one per `Chat` back-stack entry (the root and each pushed subagent entry).
 *
 * - The selection is `SavedStateHandle["sessionId"]` of the entry ([ChatSelection]); selecting
 *   rebinds the one [composer] (it keeps its IME and focus), opens/closes the store session and
 *   rebuilds the per-session approval, question and queue controllers.
 * - Home's first send ([createSession]) shows the optimistic bubble at once, creates the session
 *   with `buildSessionCreate`, writes the new id into the selection and hands the send back to
 *   the composer.
 * - A `?sheet=` deep link opens the approval / question / queue sheet of the selected session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val savedState: SavedStateHandle,
    private val deps: ChatDeps,
    /** A pushed subagent entry (`ChildChat`): its id is fixed and back pops it. */
    val isChild: Boolean = false,
) : ViewModel() {
    private val store = deps.store
    private val selected: StateFlow<String?> = ChatSelection.selectedFlow(savedState)
    private val firstSend = MutableStateFlow<FirstSend?>(null)
    private val headerPending = MutableStateFlow<HeaderAction?>(null)
    private val titleOverride = MutableStateFlow<Pair<String, String>?>(null)
    private val hostActions = MutableStateFlow(MessageActionsState())
    private val checkpointActions = MutableStateFlow(MessageActionsState())
    private val branchName = MutableStateFlow<String?>(null)
    private val branchRefresh = MutableStateFlow(0)
    private val _branchSheet = MutableStateFlow(BranchSheetState())
    private val events = Channel<ChatEvent>(Channel.BUFFERED)
    private val pendingConnections = HashMap<String, ConnectionUpdate>()
    private val transcriptController = TranscriptController(deps.transcriptStrings)
    private val _controllers = MutableStateFlow<SessionControllers?>(null)
    private var openId: String? = null
    private var visible = false
    private val visibleFlow = MutableStateFlow(false)
    private var lastDraftText = ""
    private var pendingPrefill: Pair<String, String>? = null
    private val sheetTakes = MutableStateFlow(0)

    /** The approval, question and queue controllers of the selected session (null on Home). */
    val controllers: StateFlow<SessionControllers?> = _controllers.asStateFlow()

    /** One-shot navigation, snackbars and dialogs. */
    val eventFlow: Flow<ChatEvent> = events.receiveAsFlow()

    /** The Branch sheet's content (§2.5). */
    val branchSheet: StateFlow<BranchSheetState> = _branchSheet.asStateFlow()

    private val pendingHost: PendingInputHost = deps.pendingInputHost(viewModelScope)

    /** The composer's slash / mention menus (C5, C6); the screen routes hardware keys to it. */
    val suggestions: SuggestionMenuController = deps.suggestions.create(viewModelScope)

    /** Rename, pin, fork, delete and the details sheet's rows. */
    val sessionsPort: SessionsPort get() = deps.sessions

    private val composerSessions = object : ComposerSessions {
        override fun state(sessionId: String): StateFlow<SessionState> = store.state(sessionId)
        override val signals: Flow<SessionSignal> get() = store.signals
        override suspend fun send(request: SendRequest): SendOutcome = sendThroughStore(request)
        override suspend fun abort(sessionId: String): Result<Unit> = store.abort(sessionId)
        override fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent? = store.takeReturnedPrompt(sessionId)
    }

    /** The persistent composer (Home's welcome variant, then the conversation variant). */
    val composer: ComposerController = deps.newComposer(
        selected.value,
        viewModelScope,
        composerSessions,
        ComposerController.Hooks(
            createSession = { mode -> createSession(mode) },
            steerFirstQueued = { _controllers.value?.queue?.steerFirst() },
        ),
    )

    /** HC §1.6 from the gate, the socket and `draining`. */
    val kernelLine: StateFlow<KernelLine> = combine(deps.gate, deps.socket, deps.draining) { g, s, d -> KernelLine.of(g, s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KernelLine.of(deps.gate.value, deps.socket.value, deps.draining.value))

    /** The stall episode "Keep waiting" hid (A4); a new episode shows the notice again. */
    private val stallDismissed = MutableStateFlow<StallEpisode?>(null)

    /** A4: the stall notice of the selected session, null while hidden ([StallBannerModel]). */
    val stall: StateFlow<StallFact?> = combine(
        selected.flatMapLatest { id ->
            if (id == null) flowOf(null) else store.state(id).map { Triple(id, it.facts.stall, it.facts.runPhase) }.distinctUntilChanged()
        },
        kernelLine,
        stallDismissed,
    ) { session, line, dismissed ->
        session?.let { (id, stall, phase) -> StallBannerModel.shown(id, stall, phase, line, dismissed) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Keep waiting" on the stall notice: hides this episode only (A4). */
    fun keepWaiting() {
        val id = selected.value ?: return
        val shown = stall.value ?: return
        stallDismissed.value = StallBannerModel.dismiss(id, shown)
    }

    private val workspaceChip: Flow<WorkspaceChipState> = deps.workspace.map(WorkspaceChipState::of).distinctUntilChanged()

    /** Workspaces with a running or pending session (the branch sheet's busy rule). */
    private val busyWorkspaces: Flow<Set<String>> = deps.sessions.snapshot.map(::busyWorkspacesOf).distinctUntilChanged()

    private val branchChip: Flow<BranchChipState?> = combine(workspaceChip, branchName, busyWorkspaces) { chip, name, busy ->
        if (chip.isDefault || name == null) null else BranchChipState(name, normalizeWorkspacePath(chip.path.orEmpty()) in busy)
    }

    /** The explicit thinking level the ReasoningMenu marks (SP S8.4). */
    val reasoningSelected: StateFlow<ReasoningEffort?> = combine(selected, deps.catalog, deps.selection, deps.reasoning) { id, catalog, sel, remembered ->
        Triple(id, resolveHomeSelection(sel, catalog)?.providerId, remembered)
    }.flatMapLatest { (id, homeProvider, remembered) ->
        if (id == null) flowOf(homeProvider?.let(remembered::get))
        else store.state(id).map { s -> ComposerProjection.explicitEffort(s.facts, s.facts.providerId, remembered) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The header's tool approvals control (decided A1/A′): the kernel's global mode, with the
     * open session's pending and desktop notes. Home and a first send's hand-off pass no session.
     */
    val approvalButton: StateFlow<ApprovalButtonState> = selected.flatMapLatest { id ->
        val session: Flow<SessionState?> = if (id == null) flowOf(null) else store.state(id)
        combine(deps.approvals.mode, deps.approvals.writing, deps.gate, session) { mode, writing, gate, s ->
            ApprovalButtonState.of(mode, writing, gate, s?.facts, s?.autoApproveTools)
        }
    }.distinctUntilChanged().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ApprovalButtonState.of(deps.approvals.mode.value, deps.approvals.writing.value, deps.gate.value, null, null),
    )

    /** The control's menu opened: re-read the setting (auto-approve-control.md §6). */
    fun onApprovalMenuOpened() {
        viewModelScope.launch { deps.approvals.refresh(ApprovalRefresh.MenuOpen) }
    }

    /** A pick in the control's menu: writes `toolAutoApprove`; a failure rolls back and shows a snackbar. */
    fun setApprovalMode(auto: Boolean) {
        viewModelScope.launch {
            val result = deps.approvals.set(auto)
            if (result is ApprovalWriteResult.Failed) events.trySend(ChatEvent.ApprovalModeFailed(turningOn = auto, failure = result.failure))
        }
    }

    private sealed interface Target {
        data object Home : Target
        data class Pending(val firstSend: FirstSend) : Target
        data class Real(val sessionId: String) : Target
    }

    private val target: Flow<Target> = combine(selected, firstSend) { id, fs ->
        when {
            fs != null && (fs.sessionId == null || fs.sessionId == id) -> Target.Pending(fs)
            id != null -> Target.Real(id)
            else -> Target.Home
        }
    }.distinctUntilChanged()

    private val actionsFlow: Flow<MessageActionsState> = combine(hostActions, checkpointActions) { host, cp ->
        host.copy(
            versioningPending = host.versioningPending || cp.versioningPending,
            editPendingId = cp.editPendingId,
            editErrors = cp.editErrors,
            restorePendingId = cp.restorePendingId,
            restoreErrors = cp.restoreErrors,
            drainingMessageId = cp.drainingMessageId,
        )
    }

    /** docs/07 §6.5 `ChatUiState`. */
    val uiState: StateFlow<ChatUiState> = target.flatMapLatest { t ->
        when (t) {
            Target.Home -> homeState()
            is Target.Pending -> pendingState(t.firstSend)
            is Target.Real -> sessionState(t.sessionId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState.Initial)

    init {
        viewModelScope.launch { selected.collect(::onSelected) }
        // The first send's bubble (pending or failed) puts the composer in the conversation variant (desktop `isWelcomeState`).
        viewModelScope.launch { firstSend.map { it != null }.distinctUntilChanged().collect(composer::setFirstSendShown) }
        viewModelScope.launch {
            selected.flatMapLatest { id ->
                if (id == null) {
                    emptyFlow()
                } else {
                    store.state(id).map { it.seed == dev.cline.termux.data.session.SeedState.Missing }.distinctUntilChanged().map { gone -> id.takeIf { gone } }
                }
            }.collect { gone -> gone?.let(deps.sessionGone) }
        }
        // The session on screen: the drawer's unread, the notifier's cancel rule and KnownSessions follow it.
        viewModelScope.launch {
            var shown: String? = null
            combine(selected, visibleFlow) { id, v -> if (v) id else null }.distinctUntilChanged().collect { id ->
                shown?.let { deps.sessionVisibility(it, false) }
                shown = id
                id?.let { deps.sessionVisibility(it, true) }
            }
        }
        // §6: a re-read found the terminal's change; only the chat on screen says so.
        viewModelScope.launch {
            deps.approvals.externalChanges.collect { on -> if (visibleFlow.value) events.trySend(ChatEvent.ApprovalModeChangedOutside(on)) }
        }
        // `takeSheet` removes the key, which detaches its StateFlow: re-subscribe after each take.
        viewModelScope.launch {
            sheetTakes.flatMapLatest { savedState.getStateFlow<String?>(ChatSelection.KEY_SHEET, null) }
                .collect { sheet -> if (sheet != null) applySheetRequest() }
        }
        // The typed text of the first send (§2.7 step 2): the last non-empty draft before the composer cleared it.
        viewModelScope.launch {
            snapshotFlow { composer.draft.text.toString() }.collect { text -> if (text.isNotEmpty()) lastDraftText = text }
        }
        viewModelScope.launch {
            combine(workspaceChip, branchRefresh) { chip, _ -> chip }.collectLatest { chip -> probeBranch(chip) }
        }
        viewModelScope.launch {
            snapshotFlow { composer.draft.text.toString() to composer.draft.selection.max }
                .collect { (text, cursor) -> suggestions.update(text, cursor) }
        }
        viewModelScope.launch {
            val root: Flow<String?> = selected.flatMapLatest { id ->
                if (id == null) deps.workspace.map { it.pathOrNull } else store.state(id).map { it.facts.workspaceRoot }
            }
            combine(root.distinctUntilChanged(), deps.gate) { r, g -> SuggestionContext(r, g.toSuggestionKernel()) }
                .distinctUntilChanged()
                .collect(suggestions::setContext)
        }
    }

    // ---- state builders ---------------------------------------------------------------------------

    private fun homeState(): Flow<ChatUiState> =
        combine(kernelLine, workspaceChip, branchChip, deps.catalog, deps.workInWorktree) { line, chip, branch, catalog, worktree ->
            val notice = line.homeNotice?.let { KernelNoticeState(it, line) }
            val shownBranch = if (chip.isDefault) null else branch
            ChatUiState.Home(
                kernel = line,
                home = HomeState(
                    workspace = chip,
                    branch = shownBranch,
                    kernelNotice = notice,
                    setupNotice = HomeState.setupNoticeVisible(ComposerProjection.providerConfigured(catalog), line),
                    // G3: only a git workspace (the branch chip is there) offers a worktree.
                    worktree = if (shownBranch != null) worktree else null,
                ),
            )
        }

    /** G3: Home's "Worktree" chip (`home.workInWorktree`). */
    fun setWorkInWorktree(enabled: Boolean) {
        viewModelScope.launch { runCatching { deps.setWorkInWorktree(enabled) } }
    }

    private fun pendingState(fs: FirstSend): Flow<ChatUiState> {
        val key = fs.sessionId ?: FirstSend.PENDING_KEY
        val session: Flow<SessionState> = if (fs.sessionId == null) {
            flowOf(fs.syntheticState(key))
        } else {
            store.state(fs.sessionId).map { real -> if (FirstSend.showsSynthetic(fs, real)) fs.syntheticState(key) else real }
        }
        val transcript = transcriptUiStates(key, session, kernelLine, actionsFlow, flowOf(fs.hostError), transcriptController, deps.messageDetails, deps.usageCost)
            .flowOn(deps.compute)
        return combine(transcript, kernelLine, headerPending) { tr, line, pending ->
            val visual = if (fs.error != null) {
                HeaderState.visualOf(dev.cline.termux.data.chat.ChatStatus.Failed, null, creating = false, pending = pending)
            } else {
                HeaderState.visualOf(dev.cline.termux.data.chat.ChatStatus.Starting, null, creating = true, pending = pending)
            }
            ChatUiState.Session(
                kernel = line,
                sessionId = fs.sessionId,
                header = HeaderState(
                    sessionId = null,
                    title = HeaderState.titleOfPrompt(fs.text),
                    visual = visual,
                    agents = AgentsChipModel.Hidden,
                    diff = null,
                    parent = null,
                    pinned = false,
                    busy = fs.error == null,
                    pending = pending,
                    kernelReady = line.isReady,
                    missing = false,
                ),
                transcript = tr,
                isChild = isChild,
            )
        }
    }

    private data class HeaderInputs(
        val pending: HeaderAction?,
        val override: Pair<String, String>?,
        val diff: SessionDiffSummary?,
        val parent: ParentLink?,
    )

    private fun sessionState(sessionId: String): Flow<ChatUiState> {
        val stateFlow = store.state(sessionId)
        val transcript = transcriptUiStates(sessionId, stateFlow, kernelLine, actionsFlow, flowOf(null), transcriptController, deps.messageDetails, deps.usageCost)
            .flowOn(deps.compute)
        val parent: Flow<ParentLink?> = stateFlow.map { it.facts.parentSessionId }.distinctUntilChanged().flatMapLatest { parentId ->
            if (parentId == null) flowOf(null) else deps.sessions.snapshot.map { snap -> ParentLink.of(parentId, snap.row(parentId)?.summary) }
        }.distinctUntilChanged()
        val inputs = combine(headerPending, titleOverride, deps.diff(sessionId), parent) { p, o, d, par -> HeaderInputs(p, o, d, par) }
        val header = combine(stateFlow.coalesceStreaming(), inputs, kernelLine) { s, i, line ->
            val override = i.override?.takeIf { it.first == sessionId }?.second
            HeaderState(
                sessionId = sessionId,
                title = override ?: HeaderState.titleOf(s),
                visual = HeaderState.visualOf(s, i.pending),
                agents = AgentsChipModel.of(s),
                diff = i.diff,
                parent = i.parent,
                pinned = s.facts.pinned,
                busy = s.chatStatus.isBusy,
                pending = i.pending,
                kernelReady = line.isReady,
                missing = s.seed == dev.cline.termux.data.session.SeedState.Missing,
            )
        }.flowOn(deps.compute)
        return combine(transcript, header, kernelLine) { tr, h, line ->
            ChatUiState.Session(kernel = line, sessionId = sessionId, header = h, transcript = tr, isChild = isChild)
        }
    }

    // ---- selection ----------------------------------------------------------------------------------

    private fun onSelected(id: String?) {
        val fs = firstSend.value
        if (fs != null && (id == null || fs.sessionId != id)) {
            // Leaving the hand-off (another session, or Home after the create finished): drop it.
            if (id != null || fs.sessionId != null) firstSend.value = null
        }
        if (titleOverride.value?.first != id) titleOverride.value = null
        hostActions.value = MessageActionsState()
        composer.bind(id)
        if (openId != id) {
            openId?.let { old -> deps.appScope.launch { store.close(old) } }
            id?.let { new -> viewModelScope.launch { store.open(new) } }
            // auto-approve-control.md §6: the terminal may have changed the mode since the last read.
            if (id != null) viewModelScope.launch { deps.approvals.refresh(ApprovalRefresh.SessionOpen) }
            openId = id
        }
        rebuildControllers(id)
        pendingPrefill?.takeIf { it.first == id }?.let { (_, text) ->
            pendingPrefill = null
            composer.replaceDraft(text)
            composer.requestFocus()
        }
        applySheetRequest()
        if (id == null) branchRefresh.update { it + 1 }
    }

    private fun rebuildControllers(id: String?) {
        val current = _controllers.value
        if (current?.sessionId == id) return
        current?.close()
        _controllers.value = id?.let { sessionId ->
            val job = SupervisorJob(viewModelScope.coroutineContext[Job])
            val scope = CoroutineScope(viewModelScope.coroutineContext + job)
            SessionControllers(
                sessionId = sessionId,
                approval = ApprovalController(sessionId, pendingHost, scope, initiallyVisible = visible),
                question = AskQuestionController(sessionId, pendingHost, scope, initiallyVisible = visible),
                queue = deps.queueFactory.create(sessionId, scope) { text -> resolveQueueEdit(sessionId, text) },
                job = job,
            )
        }
    }

    /** CA §0.6: open the requested sheet of the selected session once its controllers exist. */
    private fun applySheetRequest() {
        val controllers = _controllers.value ?: return
        if (controllers.sessionId != selected.value) return
        val sheet = ChatSelection.takeSheet(savedState)
        sheetTakes.update { it + 1 }
        when (sheet) {
            ChatSheet.Approval -> controllers.approval.openFromDeepLink()
            ChatSheet.Question -> controllers.question.openFromDeepLink()
            ChatSheet.Queue -> controllers.queue.openSheet()
            null -> Unit
        }
    }

    /** Opens a top-level session in this entry (drawer and notifications go through the navigator). */
    fun select(sessionId: String?) {
        if (sessionId == null) firstSend.value = null
        ChatSelection.select(savedState, sessionId)
    }

    /** Back from a selected session or a pending hand-off: Home (HC §2.9). */
    fun goHome() {
        firstSend.value = null
        if (selected.value != null) ChatSelection.select(savedState, null)
    }

    /** "New session" while the Home entry still holds a failed first send: drop it and focus the composer. */
    fun onNewSessionRequested() {
        firstSend.value = null
        composer.requestFocus()
    }

    /** The approval / question sheets auto-open only while the chat is the visible screen (C3.4). */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        visibleFlow.value = visible
        _controllers.value?.let {
            it.approval.setVisible(visible)
            it.question.setVisible(visible)
        }
        if (visible) branchRefresh.update { it + 1 }
    }

    // ---- first send (§2.7) ----------------------------------------------------------------------------

    private suspend fun createSession(mode: PromptMode): Result<String> {
        val nonce = UUID.randomUUID().toString()
        val localId = "local:$nonce"
        val createdAt = deps.clock.millis()
        firstSend.value = FirstSend(localId = localId, text = lastDraftText, createdAt = createdAt)
        releaseFirstSendHold()
        deps.firstSendHold(localId, PendingFirstSend(firstLineOf(lastDraftText), createdAt))
        firstSendHoldOwner = localId
        val config = homeConfig(mode)
        if (config == null) {
            releaseFirstSendHold()
            firstSend.update { it?.copy(error = deps.strings.noModel) }
            return Result.failure(IllegalStateException(deps.strings.noModel))
        }
        // G3: the worktree is cut before the create (desktop use-chat-session.ts); a failure sends nothing.
        val worktree = if (worktreeChosen()) {
            cutWorktree(config.workspaceRoot).getOrElse { error ->
                releaseFirstSendHold()
                firstSend.update { it?.takeUnless { fs -> fs.localId == localId } }
                return Result.failure(error)
            }
        } else {
            null
        }
        val spec = if (worktree == null) config else config.copy(workspaceRoot = worktree.path, cwd = worktree.path)
        // The local id's uuid is the create's nonce: a lost reply is recovered, never resent (docs/07 §9).
        val created = store.create(SessionCreateBuilder.firstSend(spec, clientCreateId = nonce))
        if (worktree != null && created.isFailure) {
            // No session owns the worktree yet: drop it rather than leave an orphan directory and branch.
            deps.appScope.launch { runCatching { deps.termux(WorktreeCommands.remove(worktree.path)) } }
        }
        // Client-side failure text never reaches the user; kernel text is shown as data.
        val result = created.recoverCatching { error ->
            throw when (error) {
                is SessionCreateDisconnectedException -> error
                is HubCommandException -> IllegalStateException(error.hubMessage.ifBlank { deps.strings.hubNotConnected }, error)
                else -> SessionCreateDisconnectedException(error)
            }
        }
        result.onSuccess { id ->
            firstSend.update { it?.takeIf { fs -> fs.localId == localId }?.copy(sessionId = id) }
            ChatSelection.select(savedState, id)
            viewModelScope.launch {
                runCatching { deps.knownSessionAdded(id) }
                val folder = deps.workspaceNow().pathOrNull
                if (folder != null) runCatching { deps.noteWorkspaceUsed(folder) }
            }
        }.onFailure { error ->
            releaseFirstSendHold()
            val message = if (error is SessionCreateDisconnectedException) deps.strings.hubNotConnected else error.message.orEmpty().ifBlank { deps.strings.hubNotConnected }
            firstSend.update { it?.takeIf { fs -> fs.localId == localId }?.copy(error = message) }
        }
        return result
    }

    /** The local id whose first send holds the foreground service ([ChatDeps.firstSendHold]), if any. */
    private var firstSendHoldOwner: String? = null

    private fun releaseFirstSendHold() {
        val owner = firstSendHoldOwner ?: return
        firstSendHoldOwner = null
        deps.firstSendHold(owner, null)
    }

    /** G3: the "Worktree" chip is on and shown (a git workspace: the branch chip has a name). */
    private suspend fun worktreeChosen(): Boolean {
        if (WorkspaceChipState.of(deps.workspaceNow()).isDefault || branchName.value == null) return false
        return deps.workInWorktree.first()
    }

    /**
     * `git worktree add` through Termux for the repository containing [cwd] (desktop
     * `create_git_worktree`). The failure carries "Couldn't create a worktree: <git's last line>",
     * which the composer shows as the not-sent snackbar.
     */
    private suspend fun cutWorktree(cwd: String): Result<CreatedWorktree> {
        val id = deps.newWorktreeId()
        val result = try {
            deps.termux(WorktreeCommands.create(cwd, id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // `TermuxUnavailable` (the bridge or Termux itself); the client-side text never reaches the user.
            return Result.failure(IllegalStateException(deps.strings.worktreeFailed(deps.strings.termuxUnavailable), e))
        }
        val created = WorktreeCommands.parseCreated(result, id)
            ?: return Result.failure(IllegalStateException(deps.strings.worktreeFailed(WorktreeCommands.failureLine(result))))
        return Result.success(created)
    }

    /** The notification's stand-in for the title the Hub derives from the prompt. */
    private fun firstLineOf(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { takeGraphemePrefix(it, FIRST_SEND_TITLE_MAX) }

    /**
     * The `NewSessionSpec` of a first send: Home's workspace, model, thinking level and the kernel's
     * auto-approve, re-read. With no folder chosen the session starts in the Termux home (decided
     * A22), which every Termux has; nothing is created or written there.
     */
    private suspend fun homeConfig(mode: PromptMode): SessionConfigInput? {
        val choice = resolveHomeSelection(deps.selection.first(), deps.catalog.value) ?: return null
        val model = choice.modelId?.takeIf { it.isNotBlank() } ?: return null
        val effort = deps.reasoning.first()[choice.providerId]
        val thinking = effort?.let(::thinkingConfig)
        val root = deps.workspaceNow().pathOrNull ?: TERMUX_HOME
        return SessionConfigInput(
            workspaceRoot = root,
            cwd = root,
            providerId = choice.providerId,
            modelId = model,
            mode = mode.wire,
            autoApprove = deps.approvals.autoApproveAfterRefresh(ApprovalRefresh.NewSession),
            thinking = thinking?.thinking,
            reasoningEffort = thinking?.reasoningEffort,
            compaction = deps.compaction(),
        )
    }

    /**
     * Every composer send passes here: pending connections fold into the resume config; the
     * hand-off ends. A direct send first lets [ChatDeps.beforeSend] restart an idle session whose
     * tool policies differ from the global mode (decided A1, (b)); a failed restart is a failed resume.
     */
    private suspend fun sendThroughStore(request: SendRequest): SendOutcome {
        val pending = pendingConnections[request.sessionId]
        val withConfig = if (pending != null && request.config == null) request.copy(config = resumeConfig(request.sessionId)) else request
        // The hand-off's send goes to the session just created with a fresh read (homeConfig).
        val handOff = firstSend.value?.sessionId == withConfig.sessionId
        if (withConfig.delivery == null && !handOff) {
            val restarted = deps.beforeSend(withConfig.sessionId, withConfig.config)
            if (restarted.isFailure) {
                releaseFirstSendHold()
                return SendOutcome.ResumeFailed(store.state(withConfig.sessionId).value.errorBanner?.message.orEmpty())
            }
        }
        val outcome = store.send(withConfig)
        // The new session's own run phase holds the service from here on (or the send failed).
        releaseFirstSendHold()
        if (outcome is SendOutcome.Sent || outcome is SendOutcome.Queued) pendingConnections.remove(request.sessionId)
        if (firstSend.value?.sessionId == request.sessionId) firstSend.value = null
        return outcome
    }

    private suspend fun resumeConfig(sessionId: String): SessionConfigInput? {
        val update = pendingConnections[sessionId] ?: return null
        return PendingConnections.config(store.state(sessionId).value.facts, update, deps.approvals.autoApproveForNewSession())
    }

    /** SP S8.3: a not-live session keeps the picked connection for its resume. */
    fun onModelPickerResult(result: ModelPickerResult) {
        if (result is ModelPickerResult.PendingConnection) {
            pendingConnections[result.sessionId] = PendingConnections.merge(pendingConnections[result.sessionId], result.update)
        }
    }

    // ---- transcript actions ------------------------------------------------------------------------------

    /** The banner's button (§3.10). */
    fun onBannerAction(action: TranscriptBannerAction) {
        when (action) {
            is TranscriptBannerAction.Credential -> events.trySend(ChatEvent.Credential(action.action))
            is TranscriptBannerAction.Retry -> when (action.kind) {
                SessionError.Kind.Start -> composer.send()
                SessionError.Kind.Resume -> selected.value?.let { id ->
                    viewModelScope.launch { store.resume(id, resumeConfig(id)) }
                }
                SessionError.Kind.History -> selected.value?.let { id ->
                    store.clearError(id)
                    viewModelScope.launch { store.seed(id) }
                }
            }
        }
    }

    fun onCredentialAction(action: CredentialAction) {
        events.trySend(ChatEvent.Credential(action))
    }

    /** "Resend" on an interrupted row (§3.4.7). */
    fun resend(messageId: String) {
        val id = selected.value ?: return
        if (hostActions.value.resendPendingId != null) return
        hostActions.update { it.copy(resendPendingId = messageId) }
        viewModelScope.launch {
            try {
                store.resend(id, messageId, resumeConfig(id))
            } finally {
                hostActions.update { it.copy(resendPendingId = null) }
            }
        }
    }

    /** "Proceed while running" (§3.4.4). */
    suspend fun proceed(sessionId: String, toolCallId: String?): ProceedOutcome = try {
        val reply = deps.proceed(sessionId, toolCallId)
        val count = (reply["detachedCount"] as? JsonPrimitive)?.intOrNull ?: 0
        if (count > 0) ProceedOutcome.Detached(count) else ProceedOutcome.NothingDetached
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ProceedOutcome.Failed(e.message)
    }

    /** The checkpoint ViewModel's row state, merged into the transcript's action state. */
    fun setCheckpointActions(state: MessageActionsState) {
        checkpointActions.value = state
    }

    /** C.6 step 7: the edit-and-restart session opens with the edited text in the composer. */
    fun onSessionReplaced(newSessionId: String, composerText: String?) {
        if (!composerText.isNullOrEmpty()) pendingPrefill = newSessionId to composerText
        events.trySend(ChatEvent.OpenSession(newSessionId))
    }

    // ---- header actions (§6.6) ----------------------------------------------------------------------------

    /** Commit of the inline editor: blank or unchanged text exits without a command (§6.7, Q7). */
    fun rename(input: String, currentTitle: String?) {
        val id = selected.value ?: return
        val title = normalizeTitle(input.replace('\n', ' ')).trim()
        if (title.isEmpty() || title == currentTitle) return
        if (headerPending.value != null) return
        headerPending.value = HeaderAction.Rename
        titleOverride.value = id to title
        viewModelScope.launch {
            val outcome = deps.sessions.rename(id, title)
            headerPending.value = null
            when (outcome) {
                is ActionOutcome.Failed -> {
                    titleOverride.value = null
                    events.trySend(ChatEvent.RenameFailed(failureText(outcome.failure, deps.strings.renameFailed)))
                }
                else -> Unit
            }
        }
    }

    fun togglePin(currentlyPinned: Boolean) {
        val id = selected.value ?: return
        if (headerPending.value != null) return
        headerPending.value = HeaderAction.Pin
        viewModelScope.launch {
            val outcome = deps.sessions.setPinned(id, !currentlyPinned)
            headerPending.value = null
            if (outcome is ActionOutcome.Failed) events.trySend(ChatEvent.PinFailed(!currentlyPinned))
        }
    }

    /** Overflow "Fork": the fork replaces the selection (back → Home, §6.9). */
    fun fork() {
        val id = selected.value ?: return
        if (headerPending.value != null) return
        headerPending.value = HeaderAction.Fork
        viewModelScope.launch {
            val outcome = deps.sessions.fork(id)
            headerPending.value = null
            when (outcome) {
                is ActionOutcome.Forked -> events.trySend(ChatEvent.OpenSession(outcome.sessionId))
                is ActionOutcome.Failed -> events.trySend(ChatEvent.ForkFailed(failureText(outcome.failure, deps.strings.forkFailed)))
                ActionOutcome.Done, ActionOutcome.Unchanged -> Unit
            }
        }
    }

    /** Delete (§6.7): a busy session is stopped first; `deleted:false` is verified by the action. */
    fun delete() {
        val id = selected.value ?: return
        if (headerPending.value != null) return
        headerPending.value = HeaderAction.Delete
        viewModelScope.launch {
            if (store.state(id).value.chatStatus.isBusy) store.abort(id)
            val outcome = deps.sessions.delete(id)
            headerPending.value = null
            when (outcome) {
                ActionOutcome.Done -> {
                    runCatching { deps.knownSessionRemoved(id) }
                    events.trySend(ChatEvent.OpenHome)
                }
                is ActionOutcome.Failed -> events.trySend(ChatEvent.DeleteFailed(failureText(outcome.failure, deps.strings.deleteFailed)))
                else -> events.trySend(ChatEvent.DeleteFailed(deps.strings.deleteFailed))
            }
        }
    }

    /** Export (Android, FM "Session export"): `cline history export <id> -o ~/storage/downloads/cline-<id>.html`. */
    fun export() {
        val id = selected.value ?: return
        if (headerPending.value != null) return
        headerPending.value = HeaderAction.Export
        viewModelScope.launch {
            try {
                val storage = runCatching { deps.termux(ClineCommands.hasSharedStorage()) }.getOrNull()
                if (storage == null || !storage.ok) {
                    events.trySend(ChatEvent.ExportNeedsStorage)
                    return@launch
                }
                val result = runCatching { deps.termux(ClineCommands.historyExport(id, ExportPaths.outPath(id))) }
                    .getOrElse { error ->
                        events.trySend(ChatEvent.ExportFailed(error.message.orEmpty()))
                        return@launch
                    }
                when {
                    result.ok -> events.trySend(ChatEvent.ExportSaved(ExportPaths.fileName(id)))
                    BranchRules.failureLine(result).contains(TermuxPaths.STORAGE) -> events.trySend(ChatEvent.ExportNeedsStorage)
                    else -> events.trySend(ChatEvent.ExportFailed(BranchRules.failureLine(result)))
                }
            } finally {
                headerPending.value = null
            }
        }
    }

    /** The subagent back chip (§6.4). */
    fun openParent(parentId: String) {
        events.trySend(ChatEvent.OpenParent(parentId))
    }

    // ---- branch (§2.5) ------------------------------------------------------------------------------------

    private suspend fun probeBranch(chip: WorkspaceChipState) {
        val path = chip.path
        if (chip.isDefault || path == null) {
            branchName.value = null
            return
        }
        val probe = runCatching { GitBranchProbe.from(deps.termux(ClineCommands.gitBranch(path))) }.getOrNull()
        branchName.value = when (val p = probe?.let(BranchRules::probe)) {
            is BranchRules.Probe.Named -> p.name
            BranchRules.Probe.Detached -> runCatching { deps.termux(ClineCommands.gitShortHead(path)) }.getOrNull()
                ?.takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /** The sheet opened: load the branches each time (desktop) and mark the busy rule. */
    fun openBranchSheet() {
        viewModelScope.launch {
            val chip = workspaceChip.first()
            val path = chip.path ?: return@launch
            val busy = normalizeWorkspacePath(path) in busyWorkspaces.first()
            _branchSheet.value = BranchSheetState(loading = true, current = branchName.value, busy = busy)
            val result = runCatching { deps.termux(ClineCommands.gitBranches(path)) }.getOrNull()
            val branches = result?.takeIf { it.ok }?.let(BranchRules::parse).orEmpty()
            _branchSheet.update { it.copy(loading = false, branches = branches, capped = branches.size >= BranchRules.CAP) }
        }
    }

    fun setBranchQuery(query: String) = _branchSheet.update { it.copy(query = query) }

    /** A row tap: the current branch closes the sheet; another one runs `git checkout`. */
    fun selectBranch(branch: String) {
        val sheet = _branchSheet.value
        if (branch == sheet.current) {
            events.trySend(ChatEvent.BranchSwitched)
            return
        }
        if (!sheet.rowsEnabled) return
        _branchSheet.update { it.copy(switchingTo = branch) }
        viewModelScope.launch {
            val path = workspaceChip.first().path
            val result = path?.let { runCatching { deps.termux(ClineCommands.gitCheckout(it, branch)) }.getOrNull() }
            if (result != null && result.ok) {
                branchName.value = branch
                _branchSheet.update { it.copy(switchingTo = null, current = branch) }
                events.trySend(ChatEvent.BranchSwitched)
            } else {
                _branchSheet.update { it.copy(switchingTo = null) }
                events.trySend(ChatEvent.BranchSwitchFailed(branch, result?.let(BranchRules::failureLine).orEmpty()))
            }
        }
    }

    /** A suggestion row was picked (tap, Tab or Enter): the field takes the new text and cursor. */
    fun applySuggestion(edit: TextEdit?) {
        if (edit != null) composer.applyTokenEdit(TokenEdit(edit.text, edit.cursor))
    }

    /** The delete dialog's row: the sessions list's summary, or one built from the session's facts. */
    fun summary(sessionId: String): Flow<SessionSummary> = combine(deps.sessions.snapshot, store.state(sessionId)) { snap, s ->
        snap.row(sessionId)?.summary ?: fallbackSummary(sessionId, s)
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private suspend fun resolveQueueEdit(sessionId: String, text: String): SlashResolution {
        val root = store.state(sessionId).value.facts.workspaceRoot ?: TermuxPaths.HOME
        return deps.pipeline.resolve(text, composer.uiState.value.mode, deps.slashSnapshots.snapshot(root))
    }

    private fun failureText(failure: ActionFailure, fallback: String): String = when (failure) {
        is ActionFailure.Kernel -> failure.message.ifBlank { fallback }
        ActionFailure.NotConnected -> deps.strings.hubNotConnected
        ActionFailure.EmptySession -> deps.strings.forkEmpty
        else -> fallback
    }

    override fun onCleared() {
        if (visibleFlow.value) selected.value?.let { deps.sessionVisibility(it, false) }
        _controllers.value?.close()
        _controllers.value = null
        composer.close()
        releaseFirstSendHold()
        openId?.let { id -> deps.appScope.launch { store.close(id) } }
        viewModelScope.cancel()
    }

    companion object {
        /** The longest prompt line the "Cline is working..." notification shows before the Hub titles the session. */
        private const val FIRST_SEND_TITLE_MAX = 80

        /** A summary for a session the sessions list has not loaded (a deep link, a fresh create). */
        fun fallbackSummary(sessionId: String, state: SessionState): SessionSummary {
            val facts = state.facts
            val root = facts.workspaceRoot ?: facts.cwd.orEmpty()
            return SessionSummary(
                id = sessionId,
                title = HeaderState.titleOf(state).orEmpty(),
                hasCustomTitle = false,
                workspaceRoot = root,
                cwd = facts.cwd ?: root,
                kernelStatus = if (state.chatStatus.isBusy) KernelSessionStatus.Running else KernelSessionStatus.Idle,
                provider = facts.providerId.orEmpty(),
                model = facts.modelId.orEmpty(),
                source = facts.source.orEmpty(),
                pinned = facts.pinned,
                createdAt = 0L,
                activityAt = state.touchedAt,
            )
        }

        /** Busy rule of the branch sheet: workspaces of running or pending sessions. */
        fun busyWorkspacesOf(snapshot: SessionsSnapshot): Set<String> = snapshot.rows
            .filter { it.summary.kernelStatus == KernelSessionStatus.Running || it.summary.kernelStatus == KernelSessionStatus.Pending }
            .mapNotNull { normalizeWorkspacePath(it.summary.workspaceRoot).takeIf(String::isNotEmpty) }
            .toSet()

        /**
         * The chat entry's ViewModel. [handle] is the entry's own `SavedStateHandle` (the one
         * `ShellNavigator.openSession` writes); call after the graph was warmed off Main.
         */
        fun factory(graph: AppGraph, context: Context, handle: SavedStateHandle, isChild: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(handle, ChatDeps.of(graph, context), isChild) }
        }
    }
}

/** Sends need a connected (or draining) Hub; anything else disables the header's commands (§6.8). */
val KernelLine.isReady: Boolean get() = this == KernelLine.Connected || this == KernelLine.Draining
