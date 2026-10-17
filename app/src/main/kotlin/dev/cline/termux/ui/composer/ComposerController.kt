package dev.cline.termux.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.data.chat.ActiveMention
import dev.cline.termux.data.chat.ActiveSlash
import dev.cline.termux.data.chat.ActiveToken
import dev.cline.termux.data.chat.AttachmentIo
import dev.cline.termux.data.chat.AttachmentRef
import dev.cline.termux.data.chat.Attachments
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ImageEncodeResult
import dev.cline.termux.data.chat.ImageLimit
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.SendBlock
import dev.cline.termux.data.chat.SendCheckInput
import dev.cline.termux.data.chat.GlobalSettingsDisabledTools
import dev.cline.termux.data.chat.SendPipeline
import dev.cline.termux.data.chat.SendResult
import dev.cline.termux.data.chat.SlashRefusal
import dev.cline.termux.data.chat.SlashResolution
import dev.cline.termux.data.chat.TextReadResult
import dev.cline.termux.core.kernel.termux.writeAttachment
import dev.cline.termux.data.composer.InputHistory
import dev.cline.termux.data.composer.InputHistorySource
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.model.resolveHomeSelection
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.Delivery
import dev.cline.termux.data.session.ModeSwitchEvents
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.StopFailure
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionCreateDisconnectedException
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.ui.agents.AgentsRosterSource
import dev.cline.termux.ui.kernel.ComposerGate
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's brain (design/screens/composer-approvals.md C1, C5.7, C7; docs/07 §6.5): a
 * plain class the `ChatViewModel` owns per chat entry. It holds the draft ([draft], a
 * `TextFieldState` that survives configuration changes here), the attachments, Plan/Act, the
 * send pipeline, Stop, the held/resume states and the voice wiring, and derives one
 * [ComposerUiState] from the session store, the kernel gate and the catalog.
 *
 * Prompt text, attachment contents and names are never logged (CA §0.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerController(
    variant: ComposerVariant,
    sessionId: String?,
    private val sessions: ComposerSessions,
    private val gate: StateFlow<KernelGate>,
    private val catalog: StateFlow<CatalogState>,
    private val selection: Flow<ModelSelection>,
    private val reasoning: Flow<Map<String, ReasoningEffort>>,
    /** Home's workspace (`workspace.active`, else the Termux home) for slash resolution. */
    private val homeWorkspaceRoot: suspend () -> String,
    private val prefs: ComposerPrefs,
    private val attachmentIo: AttachmentIo,
    /** Materialises a text attachment in Termux (stdin chunks, C1.9). */
    private val writeText: suspend (dir: String, name: String, content: String) -> Result<String>,
    private val pipeline: SendPipeline,
    private val slashSnapshots: SlashSnapshotSource,
    /** Null when the host has no recogniser wiring (previews, tests). */
    val voice: VoiceInputController?,
    private val scope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
    private val config: Config = Config(),
    /** Where session projection and image encoding run (docs/07 §8: off Main); tests pass their dispatcher. */
    private val compute: CoroutineContext = Dispatchers.Default,
    /**
     * The running child agents of a session (desktop `agentActivity.running`, the merged roster
     * and tool tally of [ComposerAgents]): Stop stays offered while any runs (`hasRunningAgents`).
     * Collected only while [uiState] has subscribers.
     */
    private val runningAgents: (sessionId: String) -> Flow<Int> = { flowOf(0) },
    /**
     * The model's approved `switch_to_act_mode` (A11 row 4, `ClientContributions`): a flip to Act
     * for the bound session, and the user's own Plan/Act changes, which cancel a pending one.
     */
    private val modeSwitches: ModeSwitchEvents = ModeSwitchEvents.None,
    /**
     * The recent prompts shared with the terminal (A11.6): the fling, the chip and the attach
     * sheet's list read it, and every direct send the Hub accepts is recorded. Null = no history
     * (previews, hosts without Termux).
     */
    private val history: InputHistorySource? = null,
) {
    /** Calls the composer makes into the chat screen's ViewModel. */
    data class Hooks(
        /** Home's first send: `session.create` from the shared `NewSessionSpec` (HC); returns the new id. */
        val createSession: (suspend (PromptMode) -> Result<String>)? = null,
        /** Empty Enter while busy: `session.steer_first_pending_prompt` (C2 §2.7). */
        val steerFirstQueued: (() -> Unit)? = null,
    )

    data class Config(
        val draftDebounceMs: Long = 500,
        /** A4: the protocol's command timeout, not an app figure. */
        val stopTimeoutMs: Long = HubCommands.DEFAULT_TIMEOUT_MS,
    )

    /** The field (state-based `BasicTextField`). Only the composable and this class edit it. */
    val draft = TextFieldState()

    private data class PendingSend(val sessionId: String?, val text: String, val attachments: List<DraftAttachment>)

    private data class Local(
        val variant: ComposerVariant,
        val sessionId: String?,
        val text: String = "",
        /** Cursor (selection end), so cursor-only moves update the active tokens. */
        val cursor: Int = 0,
        val attachments: List<DraftAttachment> = emptyList(),
        val mode: PromptMode = PromptMode.Act,
        val modeExplicit: Boolean = false,
        val hold: HoldReason? = null,
        val creating: Boolean = false,
        /**
         * Home's transcript shows the first send (the chat's optimistic bubble, kept with its
         * error row when the create failed): the host's fact, kept across [bind].
         */
        val firstSendShown: Boolean = false,
        val hardwareKeyboard: Boolean = false,
        val stopTimedOut: Boolean = false,
        /** Trial row 11: the Stop that did not take, while the run it was about goes on ([settleStopFailure]). */
        val stopFailure: StopFailure? = null,
        /** A11.6: the recalled prompt in the field; current only while the field still shows [Browse.shown]. */
        val browse: Browse? = null,
    )

    /**
     * A11.6 browsing: [entries] is the list as it was when browsing began (newest first), so the
     * steps stay stable while the file is re-read; [index] is 1-based; [saved] is what the field
     * held before (empty after a fling), restored when browsing is left and kept as the draft.
     */
    private data class Browse(val entries: List<String>, val index: Int, val shown: String, val saved: String)

    private val local = MutableStateFlow(Local(variant, sessionId))

    private val _messages = MutableSharedFlow<ComposerMessage>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Snackbar requests (one at a time, DS #17). */
    val messages: SharedFlow<ComposerMessage> = _messages.asSharedFlow()

    private val _effects = MutableSharedFlow<ComposerEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<ComposerEffect> = _effects.asSharedFlow()

    /**
     * The bound session reduced to [ComposerSessionInputs] on [compute] (P4): every Hub event of the
     * session (deltas, tool chunks, heartbeats) is projected off Main, and only a changed slice
     * reaches [derive]. Collected only while [uiState] has subscribers.
     */
    private val sessionInputs: Flow<ComposerSessionInputs?> = local
        .map { it.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else sessions.state(id).map(ComposerProjection::inputs).conflate().flowOn(compute)
        }
        .distinctUntilChanged()
        .onEach(::onSessionInputs)

    private val modelInputs = combine(catalog, selection, reasoning) { c, s, r -> Triple(c, s, r) }

    private val voiceState: Flow<VoiceState> = voice?.state ?: flowOf(VoiceState.Unavailable)

    /**
     * The bound session's running agents, tagged with its id so a count never outlives a
     * [bind]. Shared, so [current] reuses the running roster instead of loading it again.
     */
    private val agentsRunning: StateFlow<Pair<String, Int>?> = local
        .map { it.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else runningAgents(id).map { id to it }.conflate().flowOn(compute)
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val historyState: Flow<InputHistory> = history?.state ?: flowOf(InputHistory())

    private data class LocalInputs(val local: Local, val agents: Int, val history: InputHistory)

    private val localAndAgents: Flow<LocalInputs> = combine(local, agentsRunning, historyState) { l, a, h ->
        LocalInputs(l, a?.takeIf { it.first == l.sessionId }?.second ?: 0, h)
    }

    private val liveState: Flow<ComposerUiState> =
        combine(localAndAgents, sessionInputs, gate, modelInputs, voiceState) { (raw, agents, h), s, g, (c, sel, r), v ->
            val l = settleStopFailure(raw, agents, s)
            derive(l, agents, s, g, c, sel, r, v).withHistory(l, h)
        }

    val uiState: StateFlow<ComposerUiState> =
        liveState.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ComposerUiState.initial(variant, sessionId))

    /** The slash token at the cursor (C5 panel input). */
    val activeSlash: StateFlow<ActiveSlash?> = local
        .map { ActiveToken.slash(it.text, it.cursor) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The mention token at the cursor (C6 panel input). */
    val activeMention: StateFlow<ActiveMention?> = local
        .map { ActiveToken.mention(it.text, it.cursor) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The state as of now, also when nothing collects [uiState] (send paths, attachment checks). */
    private suspend fun current(): ComposerUiState = liveState.first()

    private val insertion = VoiceInsertion()
    private var lastSent: PendingSend? = null
    private var lastDirect: Pair<String, String>? = null
    private var retired: List<DraftAttachment.Image> = emptyList()
    private var draftJob: Job? = null
    private var holdJob: Job? = null
    private var stopJob: Job? = null
    private var focusJob: Job? = null
    private var historyJob: Job? = null
    private var sending = false

    init {
        scope.launch { loadFor(local.value.sessionId) }
        scope.launch { sessions.signals.collect(::onSignal) }
        scope.launch { modeSwitches.flips.collect(::onSwitchedToAct) }
        voice?.let { v -> scope.launch { v.events.collect(::onVoiceEvent) } }
    }

    /**
     * The model's switch to Act was approved and the plan continues (CLI `tuiModeChanged`): the
     * Plan/Act control shows Act. `ClientContributions` already stored it as the session's mode.
     */
    private fun onSwitchedToAct(sessionId: String) {
        local.update { if (it.sessionId == sessionId) it.copy(mode = PromptMode.Act, modeExplicit = true) else it }
    }

    // ---- derivation -------------------------------------------------------------------------

    /** Side effects of a new session slice; replayed with the latest slice whenever collection resumes. */
    private fun onSessionInputs(s: ComposerSessionInputs?) {
        if (s == null) return
        if (s.runPhase != RunPhase.Stopping) local.update { if (it.stopTimedOut) it.copy(stopTimedOut = false) else it }
        // A session opened from history starts in the mode of its last user message (C1.7).
        val l = local.value
        if (!l.modeExplicit && s.seed is SeedState.Seeded && l.sessionId == s.sessionId) {
            s.lastUserMode?.let { mode -> local.update { it.copy(mode = mode, modeExplicit = true) } }
        }
    }

    /**
     * Trial row 11: a Stop failure ends for good once the run it was about is over, or the session
     * was deleted. The latch keeps a later run of the session, one this client never saw start,
     * from bringing back the failure of an earlier one.
     */
    private fun settleStopFailure(l: Local, agentsRunning: Int, s: ComposerSessionInputs?): Local {
        val failure = l.stopFailure ?: return l
        if (s == null || s.sessionId != l.sessionId) return l
        if (s.seed != SeedState.Missing && !failure.isOver(s.runPhase, s.activeRun, agentsRunning)) return l
        local.update { if (it.sessionId == l.sessionId && it.stopFailure == failure) it.copy(stopFailure = null) else it }
        return l.copy(stopFailure = null)
    }

    /** A11.6: the chip, the fling and the TalkBack actions from the browse state and the known list. */
    private fun ComposerUiState.withHistory(l: Local, h: InputHistory): ComposerUiState {
        if (this@ComposerController.history == null) return this
        val browse = l.browse?.takeIf { it.shown == l.text }
        val chip = browse?.let { HistoryBrowse(it.index, it.entries.size) }
        val gestures = !readOnly && !loading && !sessionDeleted && holdReason == null && (l.text.isEmpty() || browse != null)
        val older = gestures && (chip?.hasOlder ?: (h.entries.isNotEmpty() || !h.loaded))
        return copy(history = chip, historyGestures = gestures, historyOlderAvailable = older)
    }

    private fun derive(
        l: Local,
        agentsRunning: Int,
        s: ComposerSessionInputs?,
        g: KernelGate,
        c: CatalogState,
        sel: ModelSelection,
        r: Map<String, ReasoningEffort>,
        v: VoiceState,
    ): ComposerUiState {
        val storePhase = when {
            l.sessionId == null -> if (l.creating) RunPhase.Starting else RunPhase.NoSession
            l.creating -> RunPhase.Starting
            s == null -> RunPhase.Idle
            else -> s.runPhase
        }
        val phase = if (l.stopTimedOut && storePhase == RunPhase.Stopping) RunPhase.Running else storePhase
        val busy = phase.isBusy
        val home = resolveHomeSelection(sel, c)
        val choice = if (s == null) home else ComposerProjection.choice(s.providerId, s.modelId, home)
        val model = ComposerProjection.modelChip(choice, c, busy)
        val explicit = ComposerProjection.explicitEffort(s?.thinking, s?.reasoningEffort, choice?.providerId, r)
        val kernel = ComposerGate.of(g, port = null)
        val hasDraft = l.text.isNotBlank() || l.attachments.isNotEmpty()
        val writing = l.attachments.any { it is DraftAttachment.TextFile && it.state == FileAttachState.Writing }
        val loading = s != null && s.seed == SeedState.Seeding && s.empty
        val deleted = s?.seed == SeedState.Missing
        // Right after bind() `s` can still be the previous session's state, or the new one before seeding starts.
        val settled = l.sessionId == null || (s?.sessionId == l.sessionId && s.seed != SeedState.NotSeeded && !loading)
        val queue = s?.pendingPrompts.orEmpty()
        // Desktop `isWelcomeState`: welcome only while the transcript shows nothing. The first
        // send's bubble appears before `session.create` replies and stays when it fails.
        val variant = if (l.creating || l.firstSendShown) ComposerVariant.Conversation else l.variant
        return ComposerUiState(
            variant = variant,
            sessionId = l.sessionId,
            phase = phase,
            kernel = kernel,
            hasDraft = hasDraft,
            attachments = l.attachments,
            imagesUnsupported = model.supportsImages == false && l.attachments.any { it is DraftAttachment.Image },
            mode = l.mode,
            model = model,
            reasoning = ComposerProjection.reasoningChip(explicit, model.supportsReasoning),
            usage = if (variant == ComposerVariant.Conversation) ComposerProjection.usage(s?.latestUsage, s?.sessionTotalCost, model.contextWindow) else null,
            queueSize = queue.size,
            firstQueuedSteering = queue.firstOrNull()?.steer == true,
            voice = v,
            // A restart for the new policies (decided A1) reads as the resume it is.
            holdReason = if (l.hold == HoldReason.PreparingSend && s?.creating == true) HoldReason.ResumingSession else l.hold,
            canSend = hasDraft && kernel.kernelAllowsSend && !v.active && l.hold == null && !writing && !loading && !deleted,
            isBusy = busy,
            canAbort = phase.canAbort(agentsRunning),
            hardwareKeyboard = l.hardwareKeyboard,
            loading = loading,
            sessionDeleted = deleted,
            providerConfigured = ComposerProjection.providerConfigured(c),
            stopTimedOut = l.stopTimedOut,
            stopFailed = l.stopFailure != null,
            settled = settled,
        )
    }

    // ---- draft --------------------------------------------------------------------------------

    private val draftKey: String get() = local.value.sessionId ?: HOME_KEY

    /** The field changed (the composable reports every edit; programmatic edits call it too). */
    fun onDraftEdited() {
        val text = draft.text.toString()
        val cursor = draft.selection.end
        val l = local.value
        if (text == l.text) {
            // Cursor-only moves still change the active tokens.
            local.update { it.copy(cursor = cursor) }
            return
        }
        if (l.hold == HoldReason.HubDraining && text.isNotEmpty()) {
            // Editing cancels the hold: the held text comes back in front of what was typed (C1.7).
            val held = lastSent?.text.orEmpty()
            holdJob?.cancel()
            local.update { it.copy(hold = null) }
            replaceDraft(held + text)
            restoreAttachments()
            return
        }
        // Typing or clearing ends browsing a recalled prompt (A11.6); the text stays.
        local.update { it.copy(text = text, cursor = cursor, browse = it.browse?.takeIf { b -> b.shown == text }) }
        scheduleDraftSave(text)
    }

    /** Replaces the whole field and places the cursor at [cursor] (end by default); ends any history browsing. */
    fun replaceDraft(text: String, cursor: Int = text.length) {
        draft.edit {
            replace(0, length, text)
            selection = TextRange(cursor.coerceIn(0, text.length))
        }
        local.update { it.copy(text = text, cursor = cursor.coerceIn(0, text.length), browse = null) }
        scheduleDraftSave(text)
    }

    private fun scheduleDraftSave(text: String) {
        val key = draftKey
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(config.draftDebounceMs)
            prefs.setDraft(key, text)
        }
    }

    private suspend fun loadFor(sessionId: String?) {
        val saved = prefs.draft(sessionId ?: HOME_KEY)
        if (!saved.isNullOrEmpty() && draft.text.isEmpty()) {
            draft.setTextAndPlaceCursorAtEnd(saved)
            local.update { it.copy(text = saved, cursor = saved.length) }
        }
        // Home: the default. A session: its stored mode, else (until seeded) the default, which
        // the last user message's mode replaces once history arrives (C1.7).
        val explicit = sessionId == null || prefs.hasSessionMode(sessionId)
        val mode = prefs.mode(sessionId).toPromptMode()
        local.update {
            when {
                it.sessionId != sessionId -> it
                explicit -> it.copy(mode = mode, modeExplicit = true)
                !it.modeExplicit -> it.copy(mode = mode)
                else -> it
            }
        }
        if (sessionId != null && local.value.sessionId == sessionId) {
            sessions.takeReturnedPrompt(sessionId)?.let(::giveBackReturned)
        }
    }

    /**
     * A prompt the store handed back while this session was not bound here (HC §3.12 steps 6
     * and 7: the reconnect gave up, or the send was judged not delivered, after the user left
     * the session). It goes into the field, after a blank line when the field already holds a
     * newer draft so neither is lost, is saved as the session's draft, and the snackbar says why.
     */
    private fun giveBackReturned(returned: SessionSignal.NotSent) {
        val current = draft.text.toString()
        val text = if (current.isBlank()) returned.prompt else "$current\n\n${returned.prompt}"
        replaceDraft(text)
        // Saved at once, not after the debounce: the store no longer holds it.
        draftJob?.cancel()
        val key = draftKey
        scope.launch { prefs.setDraft(key, text) }
        SendPipeline.resultOf(returned)?.let(::messageFor)?.let(::emit)
    }

    /**
     * Switches the composer to another session (C1.6 "Loading"): voice stops, the current
     * draft is saved at once, and the target's draft and mode are restored.
     */
    fun bind(sessionId: String?) {
        if (sessionId == local.value.sessionId) return
        focusJob?.cancel()
        historyJob?.cancel()
        voice?.cancel()
        insertion.end()
        val oldKey = draftKey
        // A recalled prompt is not the draft: what the field held before browsing is (A11.6).
        val oldText = currentBrowse()?.saved ?: draft.text.toString()
        draftJob?.cancel()
        scope.launch { prefs.setDraft(oldKey, oldText) }
        discardAttachments()
        holdJob?.cancel()
        local.update {
            Local(
                variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
                sessionId = sessionId,
                firstSendShown = it.firstSendShown,
                hardwareKeyboard = it.hardwareKeyboard,
            )
        }
        draft.edit { replace(0, length, "") }
        scope.launch { loadFor(sessionId) }
    }

    /** Home's composer adopted the session its first send created; the draft stays as is. */
    private fun adopt(sessionId: String) {
        local.update { it.copy(sessionId = sessionId, variant = ComposerVariant.Conversation) }
    }

    fun setHardwareKeyboard(attached: Boolean) = local.update { it.copy(hardwareKeyboard = attached) }

    /**
     * The chat's transcript shows (true) or dropped (false) Home's first send. While it shows,
     * the composer is the conversation variant even before a session id exists (desktop
     * `isWelcomeState = displayedMessages.length === 0 && !displayedError`).
     */
    fun setFirstSendShown(shown: Boolean) = local.update { if (it.firstSendShown == shown) it else it.copy(firstSendShown = shown) }

    /**
     * Home "New session" while Home is shown (HC §2.9), and the edit-and-restart session (C.6).
     * Waits until the bound session has loaded (the field is disabled until then); a later
     * [bind] drops the request.
     */
    fun requestFocus() {
        focusJob?.cancel()
        val target = local.value.sessionId
        focusJob = scope.launch {
            uiState.first { it.sessionId == target && it.settled }
            _effects.emit(ComposerEffect.RequestFocus)
        }
    }

    // ---- Plan / Act -----------------------------------------------------------------------------

    /**
     * Sets the mode of the next send; per session in DataStore, Home's is the default (C1.7).
     * A user change cancels the continuation of a model's pending switch to Act (CLI: only a
     * tool-initiated switch continues the plan); re-selecting the mode already shown changes
     * nothing, so it cancels nothing (the CLI reports source "ui" only for a real toggle).
     */
    fun setMode(mode: PromptMode) {
        if (mode == PromptMode.Yolo) return
        val sid = local.value.sessionId
        val changed = local.value.mode != mode
        local.update { it.copy(mode = mode, modeExplicit = true) }
        if (changed) sid?.let(modeSwitches::userToggled)
        scope.launch { prefs.setMode(mode.toComposerMode(), sid) }
    }

    // ---- send ------------------------------------------------------------------------------------

    /** Send (touch, hardware Enter, Retry, Resend). */
    fun send() {
        if (sending) return
        sending = true
        scope.launch {
            try {
                sendNow()
            } finally {
                sending = false
            }
        }
    }

    /**
     * Long-press Send while a run is active (A11 row 5): the typed prompt goes to the running
     * agent now (`session.send_input {delivery:"steer"}`) instead of the queue. Idle: nothing
     * (the long-press shows Send's tooltip there).
     */
    fun steer() {
        if (sending) return
        sending = true
        scope.launch {
            try {
                if (current().isBusy) sendNow(steer = true)
            } finally {
                sending = false
            }
        }
    }

    private suspend fun sendNow(steer: Boolean = false) {
        val ui = current()
        val text = draft.text.toString()
        val block = SendPipeline.precheck(
            SendCheckInput(
                canSend = ui.canSend,
                text = text,
                attachmentCount = ui.attachments.size,
                imagesUnsupported = ui.imagesUnsupported,
                providerConfigured = ui.providerConfigured,
            ),
        )
        when (block) {
            SendBlock.NotAllowed -> return
            SendBlock.ImagesUnsupported -> return emit(ComposerMessage.ImagesUnsupported)
            SendBlock.NeedsText -> return emit(ComposerMessage.NeedsText)
            SendBlock.ConnectModel -> return emit(ComposerMessage.ConnectModel)
            null -> Unit
        }
        val trimmed = text.trim()
        var prompt = trimmed
        if (trimmed.startsWith("/")) {
            local.update { it.copy(hold = HoldReason.ReadingCommand) }
            val resolution = try {
                val snapshot = slashSnapshots.snapshot(workspaceRoot(ui))
                pipeline.resolve(trimmed, ui.mode, snapshot)
            } finally {
                local.update { if (it.hold == HoldReason.ReadingCommand) it.copy(hold = null) else it }
            }
            when (resolution) {
                is SlashResolution.Refused -> return emit(refusalMessage(resolution.reason))
                is SlashResolution.Send -> prompt = resolution.prompt
            }
        }
        dispatch(prompt, ui.mode, text, steer)
    }

    private suspend fun workspaceRoot(ui: ComposerUiState): String =
        ui.sessionId?.let { sessions.state(it).value.facts.workspaceRoot } ?: if (ui.sessionId == null) homeWorkspaceRoot() else TermuxPaths.HOME

    private suspend fun dispatch(prompt: String, mode: PromptMode, typed: String, steer: Boolean = false) {
        val pending = PendingSend(local.value.sessionId, typed, local.value.attachments)
        lastSent = pending
        // The field and chips clear at once (desktop `setPromptInput("")`); a failure restores them.
        draftJob?.cancel()
        draft.edit { replace(0, length, "") }
        local.update { it.copy(text = "", cursor = 0, attachments = emptyList(), browse = null) }
        voice?.cancel()

        if (gate.value.isDraining()) {
            local.update { it.copy(hold = HoldReason.HubDraining) }
            val job = scope.launch { gate.first { !it.isDraining() } }
            holdJob = job
            job.join()
            if (job.isCancelled || local.value.hold != HoldReason.HubDraining) return
            local.update { it.copy(hold = null) }
        }

        var sessionId = pending.sessionId
        if (sessionId == null) {
            val create = hooks.createSession ?: return restore(pending, ComposerMessage.SendFailedDisconnected)
            local.update { it.copy(creating = true) }
            val created = create(mode)
            local.update { it.copy(creating = false) }
            sessionId = created.getOrElse { error ->
                // A create that never reached the Hub is the disconnected copy, never the client's own text.
                val message = if (error is SessionCreateDisconnectedException) {
                    ComposerMessage.SendFailedDisconnected
                } else {
                    ComposerMessage.SendFailed(error.message.orEmpty())
                }
                return restore(pending, message)
            }
            adopt(sessionId)
            lastSent = pending.copy(sessionId = sessionId)
        }

        val state = sessions.state(sessionId).value
        val busy = state.facts.runPhase.isBusy
        // A11 row 5: a steer reaches the running turn; a busy tap queues; idle sends directly.
        val delivery = when {
            busy && steer -> Delivery.Steer
            busy && !state.facts.queueUnavailable -> Delivery.Queue
            else -> null
        }
        if (delivery == null && state.persistedHint) local.update { it.copy(hold = HoldReason.ResumingSession) }

        val images = pending.attachments.filterIsInstance<DraftAttachment.Image>()
        // Read and Base64-encode each image once, off Main (docs/07 §8). The bytes go out of scope
        // as soon as they are encoded, and the data URL reuses the same string (P8).
        val encoded = withContext(compute) {
            images.map { image -> image to java.util.Base64.getEncoder().encodeToString(attachmentIo.readBytes(image.cacheFile)) }
        }
        val request = SendRequest(
            sessionId = sessionId,
            prompt = prompt,
            mode = mode,
            delivery = delivery,
            images = if (delivery == null) encoded.map { (image, base64) -> ChatImage(image.id, image.mime, base64) } else emptyList(),
            // `data:<mime>;base64,<…>`, the Attachments.dataUrl format, without encoding again.
            imageDataUrls = encoded.map { (image, base64) -> "data:${image.mime};base64,$base64" },
            userFiles = pending.attachments.filterIsInstance<DraftAttachment.TextFile>().mapNotNull { it.termuxPath },
        )
        // A direct send may first re-read the auto-approve setting and restart the session (decided A1).
        if (delivery == null) local.update { if (it.hold == null) it.copy(hold = HoldReason.PreparingSend) else it }
        val outcome = try {
            sessions.send(request)
        } finally {
            local.update { if (it.hold == HoldReason.ResumingSession || it.hold == HoldReason.PreparingSend) it.copy(hold = null) else it }
        }
        when (val result = SendPipeline.resultOf(outcome)) {
            is SendResult.Accepted -> {
                retire(images)
                scope.launch { prefs.setDraft(sessionId, null) }
                if (pending.sessionId == null) scope.launch { prefs.setDraft(HOME_KEY, null) }
                if (result.direct) lastDirect = sessionId to typed
                // The terminal records a submit that is not queued (`if (!delivery)`), as typed (A11.6).
                if (result.direct) history?.record(typed)
                // The keyboard stays open after an idle send and closes after a busy one (DS §5.7.4).
                if (busy) _effects.tryEmit(ComposerEffect.HideKeyboard)
                if (delivery == Delivery.Steer) emit(ComposerMessage.SteerSent)
            }
            else -> restore(pending, messageFor(result))
        }
    }

    private fun Collection<DraftAttachment.Image>.files() = map { it.cacheFile }

    private fun retire(images: List<DraftAttachment.Image>) {
        val old = retired
        retired = images
        if (old.isNotEmpty()) scope.launch { old.files().forEach { attachmentIo.delete(it) } }
    }

    private fun messageFor(result: SendResult): ComposerMessage? = when (result) {
        is SendResult.Accepted -> null
        SendResult.Disconnected -> ComposerMessage.SendFailedDisconnected
        SendResult.KernelDown -> ComposerMessage.SendFailedKernelDown
        SendResult.ResumeFailed -> ComposerMessage.ResumeFailed
        is SendResult.Failed -> ComposerMessage.SendFailed(result.message)
        SendResult.RestoreOnly -> null
    }

    /** Puts a failed send back (only into an empty field) and says why. */
    private fun restore(pending: PendingSend, message: ComposerMessage?) {
        local.update { it.copy(hold = null, creating = false) }
        if (draft.text.isBlank()) replaceDraft(pending.text)
        if (local.value.attachments.isEmpty()) {
            val alive = pending.attachments.filter { it !is DraftAttachment.Image || it.cacheFile.exists() }
            local.update { it.copy(attachments = alive) }
            if (retired.any { r -> alive.any { it.id == r.id } }) retired = emptyList()
        }
        message?.let(::emit)
    }

    private fun restoreAttachments() {
        val pending = lastSent ?: return
        if (local.value.attachments.isEmpty()) local.update { it.copy(attachments = pending.attachments) }
    }

    private fun onSignal(signal: SessionSignal) {
        val sid = local.value.sessionId ?: return
        if (signal.sessionId != sid) return
        when (signal) {
            is SessionSignal.Interrupted -> {
                // Prompt back into an empty field, cursor at the end (C1.7 "Interrupted turn").
                val text = lastDirect?.takeIf { it.first == sid }?.second
                if (text != null && draft.text.isBlank()) replaceDraft(text)
                lastDirect = null
                emit(ComposerMessage.RunInterrupted)
            }
            is SessionSignal.NotSent, is SessionSignal.SendFailed -> {
                // This composer handles the returned prompt now, so the store need not keep it for a later bind.
                if (signal is SessionSignal.NotSent) sessions.takeReturnedPrompt(sid)
                val result = SendPipeline.resultOf(signal) ?: return
                val pending = lastSent?.takeIf { it.sessionId == sid }
                    ?: PendingSend(sid, (signal as? SessionSignal.NotSent)?.prompt.orEmpty(), emptyList())
                restore(pending, messageFor(result))
            }
        }
    }

    // ---- stop ----------------------------------------------------------------------------------------

    /**
     * Stop (C1.7): `run.abort`. When the request fails, or no terminal event comes within the
     * Hub's command timeout (30 s, A4), Stop is offered again and the snackbar says "The agent did
     * not stop. Try again." with Restart kernel, as the approval sheet's Stop does. That message
     * stands while the run goes on (trial row 11): it goes once the run is over, once a later
     * Stop's request is accepted (a new attempt is under way) and when the composer moves to
     * another session. An outcome that arrives after such a move is not shown for the new session.
     */
    fun stop() {
        val sid = local.value.sessionId ?: return
        if (!uiState.value.canAbort) return
        local.update { it.copy(stopTimedOut = false) }
        stopJob?.cancel()
        stopJob = scope.launch {
            if (sessions.abort(sid).isFailure) {
                stopFailed(sid)
                return@launch
            }
            local.update { if (it.sessionId == sid && it.stopFailure != null) it.copy(stopFailure = null) else it }
            delay(config.stopTimeoutMs)
            if (sessions.state(sid).value.facts.runPhase == RunPhase.Stopping) {
                local.update { if (it.sessionId == sid) it.copy(stopTimedOut = true) else it }
                stopFailed(sid)
            }
        }
    }

    /**
     * Returns once "The agent did not stop" no longer stands (trial row 11): the snackbar's end. It
     * reads the controller's own state, which is set before [ComposerMessage.StopFailed] goes out,
     * so it never returns before the failure it belongs to.
     */
    suspend fun awaitStopFailureEnd() {
        local.first { it.stopFailure == null }
    }

    /** The Stop of [sid] did not take: the failure is about the run going now. */
    private fun stopFailed(sid: String) {
        if (local.value.sessionId != sid) return
        val failure = StopFailure.of(sessions.state(sid).value.facts)
        local.update { if (it.sessionId == sid) it.copy(stopFailure = failure) else it }
        emit(ComposerMessage.StopFailed)
    }

    // ---- hardware keyboard (C1.8) --------------------------------------------------------------

    /** Enter (no Shift): send, or steer the first queued prompt on an empty busy composer. Consumed. */
    fun onHardwareEnter(): Boolean {
        val ui = uiState.value
        when {
            ui.canSend -> send()
            !ui.hasDraft && ui.isBusy && ui.queueSize > 0 && !ui.firstQueuedSteering -> hooks.steerFirstQueued?.invoke()
            else -> Unit
        }
        return true
    }

    /** Esc with no panel open: Stop when the agent can be stopped. */
    fun onHardwareEscape(): Boolean {
        if (!uiState.value.canAbort) return false
        stop()
        return true
    }

    // ---- attachments (C1.9) ------------------------------------------------------------------

    /** Every ingress (Photos, Camera, Files, paste, drop): images take the image path, the rest the text path. */
    fun addAttachments(refs: List<AttachmentRef>) {
        if (refs.isEmpty()) return
        val (images, files) = refs.partition(Attachments::isImage)
        scope.launch {
            if (images.isNotEmpty()) addImagesNow(images)
            files.forEach { addTextFile(it) }
        }
    }

    private suspend fun addImagesNow(images: List<AttachmentRef>) {
        // Step 0: a model without image input never gets a chip.
        if (current().model.supportsImages == false) {
            emit(ComposerMessage.ImagesUnsupported)
            return
        }
        val current = local.value.attachments.count { it is DraftAttachment.Image }
        val (accept, tooMany) = Attachments.acceptCount(current, images.size)
        if (tooMany) emit(ComposerMessage.TooManyImages)
        for (ref in images.take(accept)) {
            when (val result = attachmentIo.encodeImage(ref)) {
                ImageEncodeResult.Unreadable -> emit(ComposerMessage.ImageUnreadable)
                ImageEncodeResult.UnsupportedType -> emit(ComposerMessage.UnsupportedImageType)
                is ImageEncodeResult.Ok -> {
                    val existing = local.value.attachments.filterIsInstance<DraftAttachment.Image>()
                    if (existing.size >= Attachments.MAX_IMAGES) {
                        attachmentIo.delete(result.image.file)
                        emit(ComposerMessage.TooManyImages)
                        continue
                    }
                    when (Attachments.sizeError(result.image.base64Size, existing.sumOf { it.base64Bytes })) {
                        ImageLimit.TooLarge -> {
                            attachmentIo.delete(result.image.file)
                            emit(ComposerMessage.ImageTooLarge)
                        }
                        ImageLimit.TotalTooLarge -> {
                            attachmentIo.delete(result.image.file)
                            emit(ComposerMessage.TotalTooLarge)
                        }
                        ImageLimit.TooMany, null -> {
                            val chip = DraftAttachment.Image(
                                id = UUID.randomUUID().toString(),
                                displayName = ref.displayName,
                                cacheFile = result.image.file,
                                mime = result.image.mime,
                                base64Bytes = result.image.base64Size,
                                thumbnail = result.image.thumbnail?.let { runCatching { it.asImageBitmap() }.getOrNull() },
                            )
                            local.update { it.copy(attachments = it.attachments + chip) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun addTextFile(ref: AttachmentRef) {
        val id = UUID.randomUUID().toString()
        val name = ref.displayName
        local.update { it.copy(attachments = it.attachments + DraftAttachment.TextFile(id, name, null, FileAttachState.Writing)) }
        fun drop(message: ComposerMessage) {
            local.update { l -> l.copy(attachments = l.attachments.filterNot { it.id == id }) }
            emit(message)
        }
        val text = when (val read = attachmentIo.readText(ref)) {
            is TextReadResult.Ok -> read.text
            TextReadResult.TooLarge -> return drop(ComposerMessage.FileTooLarge(name))
            TextReadResult.NotText -> return drop(ComposerMessage.FileNotText(name))
            TextReadResult.Unreadable -> return drop(ComposerMessage.FileFailed(name))
        }
        val dir = Attachments.termuxDir(local.value.sessionId)
        writeText(dir, name, text)
            .onSuccess { path ->
                local.update { l ->
                    l.copy(attachments = l.attachments.map { if (it.id == id) DraftAttachment.TextFile(id, name, path, FileAttachState.Ready) else it })
                }
            }
            .onFailure { drop(ComposerMessage.FileFailed(name)) }
    }

    /** The chip's close button: the image's cache file goes with it. */
    fun removeAttachment(id: String) {
        val removed = local.value.attachments.firstOrNull { it.id == id } ?: return
        local.update { l -> l.copy(attachments = l.attachments.filterNot { it.id == id }) }
        if (removed is DraftAttachment.Image) scope.launch { attachmentIo.delete(removed.cacheFile) }
    }

    private fun discardAttachments() {
        val images = local.value.attachments.filterIsInstance<DraftAttachment.Image>() + retired
        retired = emptyList()
        local.update { it.copy(attachments = emptyList()) }
        if (images.isNotEmpty()) scope.launch { images.files().forEach { attachmentIo.delete(it) } }
    }

    // ---- prompt history (A11.6) ----------------------------------------------------------------

    /** The recent prompts for the attach sheet's "Recent prompts" list, newest first. */
    val recentPrompts: StateFlow<InputHistory> = history?.state ?: MutableStateFlow(InputHistory()).asStateFlow()

    /** The host wired the prompt history (the attach sheet shows "Recent prompts"). */
    val hasHistory: Boolean get() = history != null

    /** Field focus, the attach sheet, the list sheet and a fling's first touch: re-read the file (at most once per 30 s). */
    fun refreshHistory() {
        history?.refresh()
    }

    /**
     * A fling in the field or a TalkBack action: [HistoryStep.Older] from an empty field shows the
     * newest prompt, then each older one; [HistoryStep.Newer] goes back, and from the newest it
     * leaves browsing and restores the field (empty after a fling). Ignored while the field holds
     * anything but an unchanged recalled prompt. Before the file was ever read, the first step
     * waits for that read.
     */
    fun historyStep(step: HistoryStep) {
        val source = history ?: return
        if (!historyEditable() || !fieldBrowsable()) return
        val browse = currentBrowse()
        when {
            browse != null -> stepFrom(browse, step)
            step == HistoryStep.Newer -> Unit
            source.state.value.loaded -> startBrowsing(source.state.value.entries)
            else -> {
                historyJob?.cancel()
                historyJob = scope.launch {
                    source.ensureLoaded()
                    if (historyEditable() && draft.text.isEmpty() && currentBrowse() == null) startBrowsing(source.state.value.entries)
                }
            }
        }
    }

    /** The browsing chip's ×: leaves browsing and clears the field (restores what it held before). */
    fun stopHistoryBrowsing() {
        val browse = currentBrowse() ?: return
        leaveBrowsing(browse, announce = false)
    }

    /**
     * A row of the "Recent prompts" sheet: [entries] as the sheet listed them, [index] 0-based.
     * The field shows that prompt in the browsing state; text typed before comes back when
     * browsing is left.
     */
    fun pickRecentPrompt(entries: List<String>, index: Int) {
        if (history == null || !historyEditable()) return
        val prompt = entries.getOrNull(index) ?: return
        val saved = currentBrowse()?.saved ?: draft.text.toString()
        showEntry(Browse(entries, index + 1, prompt, saved), announce = false)
    }

    private fun currentBrowse(): Browse? = local.value.browse?.takeIf { it.shown == draft.text.toString() }

    /** The field is editable and nothing holds it (kernel, resume, Hub hold, voice, loading). */
    private fun historyEditable(): Boolean {
        val l = local.value
        if (l.hold != null || l.creating) return false
        if (ComposerGate.of(gate.value, port = null).readOnly) return false
        if (voice?.state?.value?.active == true) return false
        val ui = uiState.value
        return !ui.loading && !ui.sessionDeleted
    }

    /** Empty, or showing a recalled prompt unchanged. */
    private fun fieldBrowsable(): Boolean = draft.text.isEmpty() || currentBrowse() != null

    private fun startBrowsing(entries: List<String>) {
        val first = entries.firstOrNull() ?: return
        showEntry(Browse(entries, 1, first, saved = draft.text.toString()), announce = true)
    }

    private fun stepFrom(browse: Browse, step: HistoryStep) {
        when (step) {
            HistoryStep.Older -> if (browse.index < browse.entries.size) {
                showEntry(browse.copy(index = browse.index + 1, shown = browse.entries[browse.index]), announce = true)
            }
            HistoryStep.Newer -> if (browse.index > 1) {
                showEntry(browse.copy(index = browse.index - 1, shown = browse.entries[browse.index - 2]), announce = true)
            } else {
                leaveBrowsing(browse, announce = true)
            }
        }
    }

    /** Shows [browse] in the field, cursor at the end; the text held before stays the saved draft. */
    private fun showEntry(browse: Browse, announce: Boolean) {
        val text = browse.shown
        // Local first: the field's edit listener then sees its own text and keeps browsing.
        local.update { it.copy(browse = browse, text = text, cursor = text.length) }
        draft.edit {
            replace(0, length, text)
            selection = TextRange(text.length)
        }
        scheduleDraftSave(browse.saved)
        if (announce) _effects.tryEmit(ComposerEffect.HistoryStepped(HistoryBrowse(browse.index, browse.entries.size)))
    }

    private fun leaveBrowsing(browse: Browse, announce: Boolean) {
        replaceDraft(browse.saved)
        if (announce) _effects.tryEmit(ComposerEffect.HistoryStepped(null))
    }

    // ---- voice (C7) ------------------------------------------------------------------------------

    fun startVoice() = voice?.start()

    /** The `RECORD_AUDIO` request was denied; [permanently] adds the "Settings" action (C7.4). */
    fun onVoicePermissionDenied(permanently: Boolean) =
        emit(ComposerMessage.Voice(if (permanently) VoiceError.PermissionPermanentlyDenied else VoiceError.Permission))

    /** Host-side checks that end in a composer snackbar (attach sheet rows, C1.9). */
    fun report(message: ComposerMessage) = emit(message)

    /** Applies a suggestion insertion (`/name ` or `@path `, C5.8 / C6.7) to the field. */
    fun applyTokenEdit(edit: dev.cline.termux.data.chat.TokenEdit) = replaceDraft(edit.text, edit.cursor)

    fun stopVoice() = voice?.stop()

    private fun onVoiceEvent(event: VoiceEvent) {
        when (event) {
            VoiceEvent.UtteranceStarted -> insertion.begin(draft.text.toString(), draft.selection.min, draft.selection.max)
            is VoiceEvent.Transcript -> insertion.apply(draft.text.toString(), event.text)?.let { (text, cursor) -> replaceDraft(text, cursor) }
            VoiceEvent.UtteranceEnded -> insertion.end()
            is VoiceEvent.Failed -> emit(ComposerMessage.Voice(event.error))
            is VoiceEvent.Active -> Unit
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** `onCleared()`: voice released, the draft saved, cache files of this draft deleted. */
    fun close() {
        voice?.cancel()
        draftJob?.cancel()
        historyJob?.cancel()
        val key = draftKey
        val text = currentBrowse()?.saved ?: draft.text.toString()
        scope.launch { prefs.setDraft(key, text) }
        discardAttachments()
    }

    private fun emit(message: ComposerMessage) {
        _messages.tryEmit(message)
    }

    private fun refusalMessage(reason: SlashRefusal): ComposerMessage = when (reason) {
        SlashRefusal.TeamDisabled -> ComposerMessage.TeamDisabled
        SlashRefusal.TeamUsage -> ComposerMessage.TeamUsage
        SlashRefusal.TeamPlanMode -> ComposerMessage.TeamPlanMode
        is SlashRefusal.WorkflowReadFailed -> ComposerMessage.WorkflowReadFailed(reason.name)
        is SlashRefusal.SkillReadFailed -> ComposerMessage.SkillReadFailed(reason.name)
    }

    companion object {
        /** `composer.draft.home`. */
        const val HOME_KEY = "home"

        private fun KernelGate.isDraining(): Boolean = this is KernelGate.Busy && kind == KernelGate.BusyKind.Draining

        /**
         * The app wiring (docs/07 §6.5): one controller per chat entry, built from the graph's
         * singletons. [sessionId] null = Home's welcome composer.
         */
        fun create(
            sessionId: String?,
            sessionStore: SessionStore,
            sessionApi: HubSessionApi,
            modelSelection: ModelSelectionStore,
            workspaceStore: WorkspaceStore,
            gate: StateFlow<KernelGate>,
            scope: CoroutineScope,
            catalog: StateFlow<CatalogState>,
            prefs: ComposerPrefs,
            termux: TermuxExecutor,
            attachmentIo: AttachmentIo,
            clock: java.time.Clock,
            voice: VoiceInputController?,
            hooks: Hooks = Hooks(),
            slashSnapshots: SlashSnapshotSource = HubSlashSnapshots(sessionApi, clock),
            /** The agents roster (`AppGraph.agents.observe`) behind Stop's running-agents rule. */
            agentRoster: AgentsRosterSource? = null,
            /** `AppGraph.clientContributions`: the model's approved switch to Act. */
            modeSwitches: ModeSwitchEvents = ModeSwitchEvents.None,
            /** The recent prompts (`AppGraph.inputHistory`, A11.6). */
            history: InputHistorySource? = null,
        ): ComposerController = ComposerController(
            variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
            sessionId = sessionId,
            sessions = sessionStore.asComposerSessions(),
            gate = gate,
            catalog = catalog,
            selection = modelSelection.selection,
            reasoning = modelSelection.reasoning,
            homeWorkspaceRoot = { workspaceStore.activeNow().pathOrNull ?: TermuxPaths.HOME },
            prefs = prefs,
            attachmentIo = attachmentIo,
            writeText = { dir, name, content -> termux.writeAttachment(dir, name, content) },
            pipeline = SendPipeline(GlobalSettingsDisabledTools(termux)) { path ->
                runCatching { termux.run(dev.cline.termux.core.kernel.termux.ClineCommands.readTextFile(path)) }
                    .mapCatching { result -> if (result.ok) result.stdout else error("exit ${result.exitCode}") }
            },
            slashSnapshots = slashSnapshots,
            voice = voice,
            scope = scope,
            hooks = hooks,
            runningAgents = { id -> agentRoster?.let { ComposerAgents.running(id, sessionStore.state(id), it) } ?: flowOf(0) },
            modeSwitches = modeSwitches,
            history = history,
        )
    }
}
