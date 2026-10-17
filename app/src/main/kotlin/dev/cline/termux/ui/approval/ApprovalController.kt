package dev.cline.termux.ui.approval

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.approvals.ApprovalWriteFailure
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.session.PendingApproval
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.StopFailure
import dev.cline.termux.ui.kernel.KernelAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where an approval or question sheet is (composer-approvals.md C3.7 / C4.6). */
enum class SheetVisibility {
    Hidden,
    Expanded,
    PartiallyExpanded,

    /** Dismissed without answering: the banner above the composer shows instead. */
    Collapsed,
    ;

    val isOpen: Boolean get() = this == Expanded || this == PartiallyExpanded
}

/** The decision being sent for one card. */
enum class ApprovalAction { Approving, Rejecting }

/** A failed `respond`; a null [message] shows the fallback copy ("Could not submit decision." / "Could not submit answer."). */
@Immutable
data class CardError(val message: String?)

/** One approval request card (C3.7); arrival order in [ApprovalUiState.cards]. */
@Immutable
data class ApprovalCard(
    val approvalId: String,
    val toolCallId: String?,
    val toolName: String,
    val iteration: Int?,
    /** Envelope timestamp; null shows "Pending now". */
    val requestedAtMs: Long?,
    val preview: ApprovalPreview,
    /** Desktop `formatApprovalInput`: pretty JSON (2 spaces), a string as is, `{}` for null. Never logged (CA §0.8). */
    val rawInput: String,
    val showRaw: Boolean = false,
    val action: ApprovalAction? = null,
    val error: CardError? = null,
) {
    val busy: Boolean get() = action != null

    /** A readable preview exists, so the "Show raw input" toggle is offered. */
    val hasPreview: Boolean get() = preview is ApprovalPreview.Lines
}

@Immutable
data class ApprovalUiState(
    val sessionId: String,
    val cards: List<ApprovalCard> = emptyList(),
    val sheet: SheetVisibility = SheetVisibility.Hidden,
    /** The kernel is (re)connecting: buttons disabled, progress bar under the header; the cards stay (C3.4). */
    val reconnecting: Boolean = false,
    /** The kernel is down: read-only with the progress bar and "Cline Hub is not connected." (C3.4). */
    val kernelDown: Boolean = false,
    /** "Stop agent" was sent; the cards leave with `approval.resolved {cancelled:true}`. */
    val stopping: Boolean = false,
    /**
     * Trial row 11: "The agent did not stop. Try again." stands ([ApprovalEffect.StopFailed]): the
     * run "Stop agent" could not end goes on. False once that run is over, once a later Stop's
     * `run.abort` is accepted, and when the session was deleted; its snackbar goes with it.
     */
    val stopFailed: Boolean = false,
) {
    val readOnly: Boolean get() = reconnecting || kernelDown
    val bannerVisible: Boolean get() = sheet == SheetVisibility.Collapsed && cards.isNotEmpty()
}

/** One-shot results for the host (snackbar, haptic, live region). */
sealed interface ApprovalEffect {
    /** "This request was already answered." *(Android)*: `approval_not_found` after a press, or a deep link to a request that is gone. */
    data object AlreadyAnswered : ApprovalEffect

    /** A decision's reply was ok: haptic confirm (C3.8). */
    data class Decided(val approved: Boolean) : ApprovalEffect

    /** The sheet opened by itself: the polite region says "Tool approval required" (C3.10). */
    data object Opened : ApprovalEffect

    /** A request arrived while this chat is not the visible screen: snackbar "Tool approval required" + "Review" (C3.4). */
    data object ArrivedWhileAway : ApprovalEffect

    /**
     * `run.abort` failed or no terminal event came within [ApprovalController.STOP_TIMEOUT_MS]:
     * "The agent did not stop. Try again." with [action] "Restart kernel" (A4), shown while
     * [ApprovalUiState.stopFailed] holds ([ApprovalController.awaitStopFailureEnd], trial row 11).
     */
    data object StopFailed : ApprovalEffect {
        val action: KernelAction get() = KernelAction.RestartKernel
    }

    /**
     * "Approve and auto-approve from now on": the approval stood, the write of
     * `toolAutoApprove: true` did not (snackbar "Couldn't turn on Auto-approve all: <reason>").
     */
    data class AutoApproveFailed(val failure: ApprovalWriteFailure) : ApprovalEffect
}

/**
 * Tool approvals of one open session (composer-approvals.md C3, DS #9). Plain class owned by
 * `ChatViewModel` (docs/07 §6.5): created in `init`, [close]d in `onCleared()`.
 *
 * - Cards come from `SessionFacts.approvals` (the reducer dedupes the re-issue on every
 *   subscribe by `approvalId`); only this session's approvals reach it (rule 1).
 * - Collapse never rejects; Reject never stops the turn; "Stop agent" is `run.abort`.
 * - A new daemon (`pid:startedAt` changed) drops every card held for the old one (CA §0.5);
 *   the same daemon keeps them (rule 4).
 * - `inputJson` is never logged; failures record the `approvalId` and code only (rule 6).
 *
 * Call the intents from the thread [scope] is confined to (the ViewModel's main scope).
 */
class ApprovalController(
    val sessionId: String,
    private val host: PendingInputHost,
    private val scope: CoroutineScope,
    initiallyVisible: Boolean = false,
    private val rejectReason: String = REJECT_REASON,
    private val deepLinkGraceMs: Long = DEEP_LINK_GRACE_MS,
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow(ApprovalUiState(sessionId))
    val state: StateFlow<ApprovalUiState> = _state.asStateFlow()

    /** True while this session has an unanswered approval (KeepAlivePolicy, CA §0.7). */
    val hasPending: StateFlow<Boolean> = _state.map { it.cards.isNotEmpty() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _effects = MutableSharedFlow<ApprovalEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<ApprovalEffect> = _effects.asSharedFlow()

    /** Ids the UI no longer shows: answered by our reply, gone (`approval_not_found`) or held for an old daemon. */
    private val dropped = HashSet<String>()

    /**
     * Every approval id this controller has seen. Only an id first seen while Auto-approve all
     * is on, in a run this client started, is answered by the app itself (decided A1): requests
     * already on screen stay for the user, as in the terminal.
     */
    private val seen = HashSet<String>()
    private var autoApprove = false
    private var visible = initiallyVisible
    private var autoOpenOnVisible = false
    private var firstInput = true
    private var lifetime: String? = null
    private var lastFacts: SessionFacts? = null
    private var gate: KernelGate = KernelGate.Ready
    private var deepLinkPending = false
    private var deepLinkJob: Job? = null
    private var stopJob: Job? = null

    /** Trial row 11: the Stop that did not take, while the run it was about goes on. */
    private var stopFailure: StopFailure? = null

    private data class Inputs(val facts: SessionFacts, val deleted: Boolean, val gate: KernelGate, val lifetime: String?, val autoApprove: Boolean)

    private val collector: Job = scope.launch {
        combine(host.state(sessionId), host.gate, host.hubLifetime, host.autoApproveAll) { s, g, l, a -> Inputs(s.facts, s.seed == SeedState.Missing, g, l, a) }
            .collect { onInputs(it.facts, it.deleted, it.gate, it.lifetime, it.autoApprove) }
    }

    // ---- intents ----------------------------------------------------------------------------

    fun approve(approvalId: String) = decide(approvalId, approved = true)

    /**
     * The sheet's "Approve and auto-approve from now on" (auto-approve-control.md §3): approves
     * this request first, then turns on Auto-approve all. A failed write leaves the approval
     * standing and says why ([ApprovalEffect.AutoApproveFailed]); other cards stay on screen.
     */
    fun approveAndAutoApprove(approvalId: String) = decide(approvalId, approved = true) {
        val result = host.turnOnAutoApprove()
        if (result is ApprovalWriteResult.Failed) _effects.tryEmit(ApprovalEffect.AutoApproveFailed(result.failure))
    }

    /** Sends the FM rejection reason (C3.2); the turn continues with it as the tool result (FX `03`). */
    fun reject(approvalId: String) = decide(approvalId, approved = false)

    fun toggleRaw(approvalId: String) {
        updateCard(approvalId) { if (it.hasPreview) it.copy(showRaw = !it.showRaw) else it }
    }

    /**
     * "Stop agent": `run.abort`; the sheet stays until the cancelled resolutions remove the cards.
     * A later Stop the Hub accepts ends an earlier failure's notice: a new attempt is under way.
     */
    fun stop() {
        val s = _state.value
        if (s.stopping || s.readOnly || s.cards.isEmpty()) return
        _state.value = s.copy(stopping = true)
        stopJob?.cancel()
        stopJob = scope.launch {
            val result = host.abort(sessionId)
            result.exceptionOrNull()?.let { e ->
                host.recordFailure("run.abort", sessionId, (e as? HubCommandException)?.code ?: HubErrorCodes.HUB_CONNECTION_CLOSED)
                stopFailed()
                return@launch
            }
            if (stopFailure != null) {
                stopFailure = null
                _state.value = _state.value.copy(stopFailed = false)
            }
            delay(stopTimeoutMs)
            if (_state.value.stopping && _state.value.cards.isNotEmpty()) stopFailed()
        }
    }

    /**
     * Returns once "The agent did not stop" no longer stands (trial row 11): the snackbar's end. The
     * state is set before [ApprovalEffect.StopFailed] goes out, so this never returns before the
     * failure it belongs to.
     */
    suspend fun awaitStopFailureEnd() {
        _state.first { !it.stopFailed }
    }

    /** Swipe down, scrim, back, Esc: collapse to the banner, never reject (DS §5.8.2). */
    fun collapse() {
        autoOpenOnVisible = false
        _state.value = _state.value.let { it.copy(sheet = if (it.cards.isEmpty()) SheetVisibility.Hidden else SheetVisibility.Collapsed) }
    }

    /** Banner "Review" or a tap on the banner. */
    fun reopen() {
        val s = _state.value
        if (s.cards.isEmpty()) return
        autoOpenOnVisible = false
        _state.value = s.copy(sheet = openFor(s.cards.size))
    }

    /** The chat for this session is visible and resumed (true) or not (false). */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        val s = _state.value
        if (visible && autoOpenOnVisible && s.sheet == SheetVisibility.Collapsed && s.cards.isNotEmpty()) {
            autoOpenOnVisible = false
            _state.value = s.copy(sheet = openFor(s.cards.size))
            _effects.tryEmit(ApprovalEffect.Opened)
        }
    }

    /**
     * `chat/{id}?sheet=approval` (CA §0.6). Opens the sheet when a request is there; the Hub
     * re-issues pending approvals on subscribe, so one may still be on its way: if none
     * arrives within [deepLinkGraceMs], "This request was already answered.".
     */
    fun openFromDeepLink() {
        val s = _state.value
        if (s.cards.isNotEmpty()) {
            autoOpenOnVisible = false
            _state.value = s.copy(sheet = openFor(s.cards.size))
            _effects.tryEmit(ApprovalEffect.Opened)
            return
        }
        deepLinkPending = true
        deepLinkJob?.cancel()
        deepLinkJob = scope.launch {
            delay(deepLinkGraceMs)
            if (deepLinkPending) {
                deepLinkPending = false
                _effects.tryEmit(ApprovalEffect.AlreadyAnswered)
            }
        }
    }

    fun close() {
        collector.cancel()
        deepLinkJob?.cancel()
        stopJob?.cancel()
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun decide(approvalId: String, approved: Boolean, afterDecided: (suspend () -> Unit)? = null) {
        val s = _state.value
        val card = s.cards.firstOrNull { it.approvalId == approvalId } ?: return
        if (card.busy || s.readOnly) return
        updateCard(approvalId) { it.copy(action = if (approved) ApprovalAction.Approving else ApprovalAction.Rejecting, error = null) }
        scope.launch {
            try {
                host.respondToApproval(approvalId, approved, if (approved) null else rejectReason)
                dropped += approvalId
                _effects.tryEmit(ApprovalEffect.Decided(approved))
                lastFacts?.let(::reconcile)
                afterDecided?.invoke()
            } catch (e: HubCommandException) {
                host.recordFailure("approval.respond", approvalId, e.code)
                if (e.code == HubErrorCodes.APPROVAL_NOT_FOUND) {
                    dropped += approvalId
                    _effects.tryEmit(ApprovalEffect.AlreadyAnswered)
                    lastFacts?.let(::reconcile)
                } else {
                    updateCard(approvalId) { it.copy(action = null, error = CardError(e.userMessage())) }
                }
            } catch (_: HubConnectionClosedException) {
                host.recordFailure("approval.respond", approvalId, HubErrorCodes.HUB_CONNECTION_CLOSED)
                updateCard(approvalId) { it.copy(action = null, error = CardError(null)) }
            }
        }
    }

    /** The failure is about the run going now ([lastFacts]; none seen yet: a run this client never saw start). */
    private fun stopFailed() {
        stopFailure = lastFacts?.let(StopFailure::of) ?: StopFailure(run = null)
        _state.value = _state.value.copy(stopping = false, stopFailed = true)
        _effects.tryEmit(ApprovalEffect.StopFailed)
    }

    private fun onInputs(facts: SessionFacts, deleted: Boolean, gate: KernelGate, life: String?, autoApprove: Boolean) {
        this.gate = gate
        this.autoApprove = autoApprove
        // Trial row 11: the failure ends with its run, and with the session.
        stopFailure?.let { failure -> if (deleted || failure.isOver(facts.runPhase, facts.activeRun)) stopFailure = null }
        if (life != null) {
            val old = lifetime
            if (old != null && old != life) {
                // A new daemon resolved every open approval as rejected on shutdown without an event (CA §0.5).
                _state.value.cards.forEach { dropped += it.approvalId }
                lastFacts?.approvals?.keys?.let(dropped::addAll)
            }
            lifetime = life
        }
        lastFacts = facts
        autoAnswerNew(facts)
        reconcile(facts)
    }

    /**
     * Auto-approve all is on (decided A1): a request that first appears now, in a run this
     * client started, is approved by the app itself and never becomes a card. The first input
     * only records what is already there (on screen when the chat opened: it stays).
     */
    private fun autoAnswerNew(facts: SessionFacts) {
        val fresh = facts.approvals.keys.filter { it !in seen }
        seen.retainAll(facts.approvals.keys)
        seen.addAll(fresh)
        val g = gate
        val connected = !(g is KernelGate.Busy && g.kind != KernelGate.BusyKind.Draining) && g !is KernelGate.Down && g !is KernelGate.Blocked
        if (firstInput || !autoApprove || facts.activeRun?.ours != true || !connected) return
        for (approvalId in fresh) {
            if (approvalId in dropped) continue
            dropped += approvalId
            scope.launch {
                try {
                    host.respondToApproval(approvalId, true, null)
                } catch (e: HubCommandException) {
                    host.recordFailure("approval.respond", approvalId, e.code)
                    // Gone already: nothing to show. Otherwise the user decides after all.
                    if (e.code != HubErrorCodes.APPROVAL_NOT_FOUND) showAfterAutoFailure(approvalId)
                } catch (_: HubConnectionClosedException) {
                    host.recordFailure("approval.respond", approvalId, HubErrorCodes.HUB_CONNECTION_CLOSED)
                    showAfterAutoFailure(approvalId)
                }
            }
        }
    }

    private fun showAfterAutoFailure(approvalId: String) {
        dropped -= approvalId
        lastFacts?.let(::reconcile)
    }

    private fun reconcile(facts: SessionFacts) {
        val prev = _state.value
        dropped.retainAll(facts.approvals.keys)
        val pending = facts.approvals.values
            .filter { it.approvalId !in dropped }
            .sortedWith(compareBy<PendingApproval>({ it.sequence ?: Long.MAX_VALUE }, { it.requestedAt }))
        val previous = prev.cards.associateBy { it.approvalId }
        val cards = pending.map { previous[it.approvalId] ?: newCard(it) }
        val g = gate
        val reconnecting = g is KernelGate.Busy && g.kind != KernelGate.BusyKind.Draining
        val kernelDown = g is KernelGate.Down || g is KernelGate.Blocked
        var sheet = prev.sheet
        var stopping = prev.stopping
        when {
            cards.isEmpty() -> {
                sheet = SheetVisibility.Hidden
                stopping = false
                autoOpenOnVisible = false
            }
            prev.cards.isEmpty() -> {
                // A new wave of requests: auto-open when the chat is in front (DS §5.5).
                when {
                    deepLinkPending || visible -> {
                        sheet = openFor(cards.size)
                        _effects.tryEmit(ApprovalEffect.Opened)
                    }
                    else -> {
                        sheet = SheetVisibility.Collapsed
                        autoOpenOnVisible = true
                        // Pending when the chat was opened: no snackbar, the sheet opens on resume.
                        if (!firstInput) _effects.tryEmit(ApprovalEffect.ArrivedWhileAway)
                    }
                }
                deepLinkPending = false
                deepLinkJob?.cancel()
            }
            sheet == SheetVisibility.Hidden -> sheet = SheetVisibility.Collapsed
        }
        firstInput = false
        _state.value = ApprovalUiState(sessionId, cards, sheet, reconnecting, kernelDown, stopping, stopFailed = stopFailure != null)
    }

    private fun newCard(a: PendingApproval): ApprovalCard = ApprovalCard(
        approvalId = a.approvalId,
        toolCallId = a.toolCallId,
        toolName = a.toolName,
        iteration = a.iteration,
        requestedAtMs = a.requestedAt.takeIf { it > 0 },
        preview = ApprovalPreviews.build(a.toolName, a.input),
        rawInput = formatApprovalInput(a.input),
    )

    private inline fun updateCard(approvalId: String, transform: (ApprovalCard) -> ApprovalCard) {
        val s = _state.value
        if (s.cards.none { it.approvalId == approvalId }) return
        _state.value = s.copy(cards = s.cards.map { if (it.approvalId == approvalId) transform(it) else it })
    }

    private fun openFor(count: Int): SheetVisibility =
        if (count <= 1) SheetVisibility.Expanded else SheetVisibility.PartiallyExpanded

    companion object {
        /** The FM rejection reason the model reads (C3.2, recorded in FX `03`). Wire text, never shown, so a code constant rather than a string resource. */
        const val REJECT_REASON = "This tool call was rejected by the user and not executed."

        /** How long a deep link waits for the re-issued approval before saying it was already answered. */
        const val DEEP_LINK_GRACE_MS = 5_000L

        /**
         * Same window as the composer's Stop (C1.7): no terminal event within it means the stop
         * did not take. The Hub's command timeout (A4), not an app figure.
         */
        const val STOP_TIMEOUT_MS = HubCommands.DEFAULT_TIMEOUT_MS
    }
}

/** The reply's `error.message`, or null (fallback copy) for the client-side codes whose text is not for people. */
internal fun HubCommandException.userMessage(): String? = when (code) {
    HubErrorCodes.HUB_CONNECTION_CLOSED, HubErrorCodes.CLIENT_TIMEOUT -> null
    else -> hubMessage.takeIf { it.isNotBlank() }
}
