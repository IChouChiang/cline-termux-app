package dev.cline.termux.ui.question

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.MistakeLimitContext
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.PendingQuestion
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.ui.approval.CardError
import dev.cline.termux.ui.approval.PendingInputHost
import dev.cline.termux.ui.approval.SheetVisibility
import dev.cline.termux.ui.approval.userMessage
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One open `ask_question` request (C4.6), or a mistake-limit decision (G2,
 * [QuestionKind.MistakeLimit]: the sheet builds the desktop's question from [mistakeDetail] and
 * shows the two [MistakeLimitPrompt.OPTIONS] with their string resources). Question, options and
 * answers are never logged (CA §0.8).
 */
@Immutable
data class QuestionItem(
    val requestId: String,
    val question: String,
    /** `args[1]`, distinct, order kept (desktop `[...new Set(item.options)]`); for a mistake limit the answer texts. */
    val options: List<String>,
    /** Always false over the Hub (`AskQuestionInputSchema` has no flag); kept for parity and previews. */
    val multiple: Boolean = false,
    /** Multiple choice only, in the order the user picked them. */
    val selected: Set<String> = emptySet(),
    val customAnswer: String = "",
    /** The option or custom text being sent. */
    val pendingAnswer: String? = null,
    val error: CardError? = null,
    val kind: QuestionKind = QuestionKind.AskQuestion,
    /** Mistake limit only: the request's context (its detail, cut at 600 characters, goes into the question). */
    val mistakeLimit: MistakeLimitContext? = null,
) {
    /** Mistake limit only: the "Latest:" detail, trimmed and cut like the desktop's; null when there is none. */
    val mistakeDetail: String?
        get() = if (kind == QuestionKind.MistakeLimit) MistakeLimitPrompt.truncatedDetail(mistakeLimit?.details).ifEmpty { null } else null

    val sending: Boolean get() = pendingAnswer != null

    /** Desktop `canSubmit`: multiple needs a selection, single needs non-blank custom text. */
    val canSubmit: Boolean get() = if (multiple) selected.isNotEmpty() else customAnswer.isNotBlank()
}

@Immutable
data class QuestionUiState(
    val sessionId: String,
    /** Normally 0 or 1 (the agent waits on one thing at a time). */
    val items: List<QuestionItem> = emptyList(),
    val sheet: SheetVisibility = SheetVisibility.Hidden,
    /** Controls disabled + progress bar until the reconnect settles (C4.4). */
    val reconnecting: Boolean = false,
) {
    val bannerVisible: Boolean get() = sheet == SheetVisibility.Collapsed && items.isNotEmpty()
}

sealed interface QuestionEffect {
    /** "The question was cancelled." *(Android)*: Stop from another client, run timeout, our client disconnected, kernel down. Never for our own Stop. */
    data object Cancelled : QuestionEffect

    /** A deep link to a question that is gone: "This request was already answered.". */
    data object AlreadyAnswered : QuestionEffect

    /** The sheet opened by itself: the polite region says "Follow-up question" (C4.9). */
    data object Opened : QuestionEffect

    /** Asked while this chat is not the visible screen: snackbar "Follow-up question" + "Answer". */
    data object ArrivedWhileAway : QuestionEffect
}

/** Desktop `optionLabel`: A … Z, then AA, AB … (base 26, `agent-ask-question.tsx`). */
fun optionLabel(index: Int): String {
    var value = index + 1
    val label = StringBuilder()
    while (value > 0) {
        value -= 1
        label.insert(0, ('A'.code + value % 26).toChar())
        value /= 26
    }
    return label.toString()
}

/** Answer text for a multiple-choice submit: the selected labels joined with ", " *(Android)* (C4.7). */
fun joinAnswers(selected: Collection<String>): String = selected.joinToString(", ")

/**
 * The questions the user answers for one open session (composer-approvals.md C4, DS #10): the
 * agent's `ask_question` and the run's mistake-limit decision (G2). Plain class owned by
 * `ChatViewModel`; [close] in `onCleared()`.
 *
 * - Items come from `SessionFacts.questions`, which keeps the `tool_executor.askQuestion` and
 *   `mistake_limit.decide` requests whose `targetClientId` is this install (another client
 *   answers its own); a mistake limit the user already continued through opens none.
 * - An `ask_question` answer is `capability.respond {requestId, ok:true, payload:{result}}`; a
 *   mistake-limit answer becomes the decision (`PendingInputHost.answerMistakeLimit`: stop, or
 *   steer the guidance and continue). `ignored:true` closes silently.
 * - A question that disappears without our answer was cancelled by the Hub (Stop, timeout,
 *   our client unregistered), or for a mistake limit stopped by the app's own five-minute
 *   timeout (`ClientContributions`, the desktop's): snackbar, unless the user's own Stop
 *   caused it. Kernel down or a new daemon cancels it too (it cannot survive, C4.4).
 */
class AskQuestionController(
    val sessionId: String,
    private val host: PendingInputHost,
    private val scope: CoroutineScope,
    initiallyVisible: Boolean = false,
    private val deepLinkGraceMs: Long = DEEP_LINK_GRACE_MS,
    /**
     * Multiple choice never arrives over the Hub (`AskQuestionInputSchema` has no flag), so it
     * is false for every request; the component keeps the mode for parity (C4.2).
     */
    private val isMultiple: (PendingQuestion) -> Boolean = { false },
) {
    private val _state = MutableStateFlow(QuestionUiState(sessionId))
    val state: StateFlow<QuestionUiState> = _state.asStateFlow()

    /** True while this session has an unanswered question (KeepAlivePolicy, CA §0.7). */
    val hasPending: StateFlow<Boolean> = _state.map { it.items.isNotEmpty() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _effects = MutableSharedFlow<QuestionEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<QuestionEffect> = _effects.asSharedFlow()

    /** Ids no longer shown: answered (or `ignored`), or cancelled by kernel down / a new daemon. */
    private val dropped = HashSet<String>()
    private var visible = initiallyVisible
    private var autoOpenOnVisible = false
    private var firstInput = true
    private var lifetime: String? = null
    private var lastFacts: SessionFacts? = null
    private var gate: KernelGate = KernelGate.Ready
    private var deepLinkPending = false
    private var deepLinkJob: Job? = null
    private val collector: Job = scope.launch {
        combine(host.state(sessionId), host.gate, host.hubLifetime) { s, g, l -> Triple(s.facts, g, l) }
            .collect { (facts, g, l) -> onInputs(facts, g, l) }
    }

    // ---- intents ----------------------------------------------------------------------------

    /** Single choice: answers at once (desktop). Multiple: toggles the option. */
    fun selectOption(requestId: String, option: String) {
        val s = _state.value
        val item = s.items.firstOrNull { it.requestId == requestId } ?: return
        if (item.sending || s.reconnecting || option !in item.options) return
        if (!item.multiple) {
            answer(requestId, option)
            return
        }
        updateItem(requestId) {
            it.copy(selected = if (option in it.selected) it.selected - option else LinkedHashSet(it.selected).apply { add(option) })
        }
    }

    fun setCustomAnswer(requestId: String, text: String) {
        updateItem(requestId) { if (it.sending) it else it.copy(customAnswer = text) }
    }

    /** Submit button, IME Send, or Enter in the custom field. */
    fun submit(requestId: String) {
        val item = _state.value.items.firstOrNull { it.requestId == requestId } ?: return
        if (!item.canSubmit || item.sending) return
        answer(requestId, if (item.multiple) joinAnswers(item.selected) else item.customAnswer.trim())
    }

    /** Swipe down, back, scrim, Esc: collapse to the banner, no answer sent. */
    fun collapse() {
        autoOpenOnVisible = false
        _state.value = _state.value.let { it.copy(sheet = if (it.items.isEmpty()) SheetVisibility.Hidden else SheetVisibility.Collapsed) }
    }

    /** Banner "Answer". */
    fun reopen() {
        val s = _state.value
        if (s.items.isEmpty()) return
        autoOpenOnVisible = false
        _state.value = s.copy(sheet = SheetVisibility.Expanded)
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        val s = _state.value
        if (visible && autoOpenOnVisible && s.sheet == SheetVisibility.Collapsed && s.items.isNotEmpty()) {
            autoOpenOnVisible = false
            _state.value = s.copy(sheet = SheetVisibility.Expanded)
            _effects.tryEmit(QuestionEffect.Opened)
        }
    }

    /** `chat/{id}?sheet=question` (CA §0.6); see `ApprovalController.openFromDeepLink`. */
    fun openFromDeepLink() {
        val s = _state.value
        if (s.items.isNotEmpty()) {
            autoOpenOnVisible = false
            _state.value = s.copy(sheet = SheetVisibility.Expanded)
            _effects.tryEmit(QuestionEffect.Opened)
            return
        }
        deepLinkPending = true
        deepLinkJob?.cancel()
        deepLinkJob = scope.launch {
            delay(deepLinkGraceMs)
            if (deepLinkPending) {
                deepLinkPending = false
                _effects.tryEmit(QuestionEffect.AlreadyAnswered)
            }
        }
    }

    fun close() {
        collector.cancel()
        deepLinkJob?.cancel()
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun answer(requestId: String, text: String) {
        if (_state.value.reconnecting) return
        val item = _state.value.items.firstOrNull { it.requestId == requestId } ?: return
        updateItem(requestId) { it.copy(pendingAnswer = text, error = null) }
        scope.launch {
            try {
                when (item.kind) {
                    QuestionKind.AskQuestion -> host.answerQuestion(requestId, text)
                    // G2: the answer becomes the decision (stop, or steer the guidance and continue).
                    QuestionKind.MistakeLimit -> host.answerMistakeLimit(sessionId, requestId, MistakeLimitPrompt.decide(item.mistakeLimit, text))
                }
                dropped += requestId
                lastFacts?.let(::reconcile)
            } catch (e: HubCommandException) {
                host.recordFailure("capability.respond", requestId, e.code)
                updateItem(requestId) { it.copy(pendingAnswer = null, error = CardError(e.userMessage())) }
            } catch (_: HubConnectionClosedException) {
                host.recordFailure("capability.respond", requestId, HubErrorCodes.HUB_CONNECTION_CLOSED)
                updateItem(requestId) { it.copy(pendingAnswer = null, error = CardError(null)) }
            }
        }
    }

    private fun onInputs(facts: SessionFacts, gate: KernelGate, life: String?) {
        val previousFacts = lastFacts
        this.gate = gate
        var cancelledByKernel = false
        if (life != null) {
            val old = lifetime
            if (old != null && old != life) {
                cancelledByKernel = dropVisible(previousFacts)
            }
            lifetime = life
        }
        if (gate is KernelGate.Down || gate is KernelGate.Blocked) {
            cancelledByKernel = dropVisible(previousFacts) || cancelledByKernel
            facts.questions.keys.forEach { dropped += it }
        }
        lastFacts = facts
        reconcile(facts, ownStop = previousFacts?.runPhase == RunPhase.Stopping || facts.runPhase == RunPhase.Stopping)
        if (cancelledByKernel) _effects.tryEmit(QuestionEffect.Cancelled)
    }

    /** Drops every shown item (it cannot survive the daemon); true when one was shown and not being answered. */
    private fun dropVisible(previousFacts: SessionFacts?): Boolean {
        val shown = _state.value.items
        shown.forEach { dropped += it.requestId }
        previousFacts?.questions?.keys?.let(dropped::addAll)
        return shown.any { !it.sending }
    }

    private fun reconcile(facts: SessionFacts, ownStop: Boolean = false) {
        val prev = _state.value
        val pending = facts.questions.values
            .filter { it.requestId !in dropped }
            .sortedBy(PendingQuestion::requestedAt)
        val ids = pending.mapTo(HashSet()) { it.requestId }
        // Vanished without our answer and without our drop: the Hub cancelled it (C4.4).
        val cancelled = prev.items.any { it.requestId !in ids && it.requestId !in dropped && !it.sending }
        if (cancelled && !ownStop) _effects.tryEmit(QuestionEffect.Cancelled)
        dropped.retainAll(facts.questions.keys)
        val previous = prev.items.associateBy { it.requestId }
        val items = pending.map { q ->
            previous[q.requestId] ?: QuestionItem(
                q.requestId, q.question, q.options.distinct(),
                multiple = q.kind == QuestionKind.AskQuestion && isMultiple(q),
                kind = q.kind,
                mistakeLimit = q.mistakeLimit,
            )
        }
        val g = gate
        val reconnecting = g is KernelGate.Busy && g.kind != KernelGate.BusyKind.Draining
        var sheet = prev.sheet
        when {
            items.isEmpty() -> {
                sheet = SheetVisibility.Hidden
                autoOpenOnVisible = false
            }
            prev.items.isEmpty() -> {
                if (deepLinkPending || visible) {
                    sheet = SheetVisibility.Expanded
                    _effects.tryEmit(QuestionEffect.Opened)
                } else {
                    sheet = SheetVisibility.Collapsed
                    autoOpenOnVisible = true
                    if (!firstInput) _effects.tryEmit(QuestionEffect.ArrivedWhileAway)
                }
                deepLinkPending = false
                deepLinkJob?.cancel()
            }
            sheet == SheetVisibility.Hidden -> sheet = SheetVisibility.Collapsed
        }
        firstInput = false
        _state.value = QuestionUiState(sessionId, items, sheet, reconnecting)
    }

    private inline fun updateItem(requestId: String, transform: (QuestionItem) -> QuestionItem) {
        val s = _state.value
        if (s.items.none { it.requestId == requestId }) return
        _state.value = s.copy(items = s.items.map { if (it.requestId == requestId) transform(it) else it })
    }

    companion object {
        const val DEEP_LINK_GRACE_MS = 5_000L
    }
}
