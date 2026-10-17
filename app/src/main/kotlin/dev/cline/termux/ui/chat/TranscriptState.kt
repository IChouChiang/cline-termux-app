package dev.cline.termux.ui.chat

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.data.catalog.ProviderUsageCost
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.isCredentialFailure
import dev.cline.termux.data.chat.isSystemSteeringMessage
import dev.cline.termux.data.chat.resolveCredentialFailureAction
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.session.ChatProjector
import dev.cline.termux.data.session.Liveness
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.TurnRecords
import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.data.session.UsageTotals
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.session.double
import dev.cline.termux.data.sessions.formatCostUsd
import dev.cline.termux.data.sessions.formatTokenCount
import dev.cline.termux.data.tools.buildToolPresentation
import dev.cline.termux.ui.chat.rows.ChatTransportState
import dev.cline.termux.ui.chat.rows.MessageActionsState
import dev.cline.termux.ui.chat.rows.RowFactsCache
import dev.cline.termux.ui.chat.rows.TranscriptGap
import dev.cline.termux.ui.chat.rows.TranscriptModel
import dev.cline.termux.ui.chat.rows.TranscriptRowType
import dev.cline.termux.ui.chat.rows.buildTranscriptModel
import dev.cline.termux.ui.chat.rows.errorBannerShowsText
import dev.cline.termux.ui.chat.rows.shouldShowErrorBanner
import dev.cline.termux.ui.chat.rows.transportLineVisible
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/*
 * The transcript's UI state (home-chat.md §1.2 transcript fields, §3.5 states, §3.8 usage,
 * §3.9 transport line, §3.10 error banner, §4.3 indicator, §3.15 announcements) and the pure
 * controller that derives it from one session's `SessionState`. Independent of the ViewModel:
 * the chat screen feeds [transcriptUiStates] (or calls [TranscriptController.reduce]) and hands
 * the result to `ChatTranscript`.
 */

// ---------------------------------------------------------------- state

/** §3.5 / §3.11: what the transcript is waiting for while a session opens. */
enum class Switching {
    None,

    /** No rows cached: the detail-pane loader and "Loading session...". */
    Loading,

    /** Cached rows shown at once; the loader and "Switching session..." until `session.messages` answers. */
    Switching,
}

/** The "Thinking..." row (screen C, §4.3). [gapAbove] is Tight under working rows or an answer, else Block. */
@Immutable
data class ThinkingIndicator(val gapAbove: TranscriptGap)

/** The banner's button (§3.10): "Retry" for [kind], or the credential action of §3.4.7. */
@Immutable
sealed interface TranscriptBannerAction {
    data class Retry(val kind: SessionError.Kind) : TranscriptBannerAction
    data class Credential(val action: CredentialAction) : TranscriptBannerAction
}

/** The error banner under the last row (§3.10), already past the desktop `shouldShowErrorBanner` rule. */
@Immutable
data class TranscriptBanner(
    val error: SessionError,
    /** "Couldn't resume this session: <message>" etc. */
    val text: String,
    /** False when the newest error row already carries the same text; the banner then holds only its action. */
    val showText: Boolean,
    val action: TranscriptBannerAction?,
)

/** §3.8: no per-message usage row; the composer ring and the details sheet's "Tokens" / "Cost" rows read this. */
@Immutable
data class TranscriptUsage(
    /** `aggregateUsage` first, else `usage` (FX). */
    val totals: UsageTotals,
    /** `formatTokenCount(input, output)`: "950", "1.2k", "12k"; null when the total is 0. */
    val tokens: String?,
    /** `formatCostUsd(metadata.totalCost ?: totals.totalCost)`; null when unknown or 0. */
    val cost: String?,
)

/** Everything `ChatTranscript` renders. */
@Immutable
data class TranscriptUiState(
    /** Null on Home (nothing to render). */
    val sessionId: String?,
    val model: TranscriptModel,
    val status: ChatStatus,
    val kernel: KernelLine,
    /** The transport line to show (§3.9); [ChatTransportState.Connected] = none (also while a banner shows). */
    val transport: ChatTransportState,
    val indicator: ThinkingIndicator?,
    val banner: TranscriptBanner?,
    val switching: Switching,
    /** `session.get` → `session_not_found`: the full-pane "This session no longer exists." state. */
    val missing: Boolean,
    val liveness: Liveness,
    /** Host pending/error maps merged with the derived busy, switching, checkpoint and resend facts. */
    val actions: MessageActionsState,
    val pendingApprovals: Int,
    val pendingQuestions: Int,
    val usage: TranscriptUsage?,
    /** Desktop `AutoScrollOnSend` counts `role == user` rows; growth scrolls to the newest row. */
    val userMessageCount: Int,
    /** The replay began above this session's last known sequence (events may be missing). */
    val replayGap: Boolean,
    /** The newest assistant text (raw), for the completion announcement. */
    val lastAssistantText: String?,
    /** The newest error row's detail (raw), for the failure announcement. */
    val lastErrorDetail: String?,
) {
    val isEmpty: Boolean get() = model.rows.isEmpty()

    companion object {
        val Empty = TranscriptUiState(
            sessionId = null,
            model = TranscriptModel.EMPTY,
            status = ChatStatus.Idle,
            kernel = KernelLine.Connected,
            transport = ChatTransportState.Connected,
            indicator = null,
            banner = null,
            switching = Switching.None,
            missing = false,
            liveness = Liveness.Live,
            actions = MessageActionsState(),
            pendingApprovals = 0,
            pendingQuestions = 0,
            usage = null,
            userMessageCount = 0,
            replayGap = false,
            lastAssistantText = null,
            lastErrorDetail = null,
        )
    }
}

/** The controller's input: one session as the store knows it, the kernel line and the host-owned action state. */
@Immutable
data class TranscriptInput(
    val sessionId: String?,
    /** Null until the store has a state for [sessionId] (shows Loading when a session is selected). */
    val session: SessionState?,
    val kernel: KernelLine = KernelLine.Connected,
    /** Pending ids and errors of edit / restore / resend, and `versioningPending`, owned by the host. */
    val actions: MessageActionsState = MessageActionsState(),
    /** A banner error that has no session state yet (Home's failed first `session.create`). */
    val hostError: SessionError? = null,
    /** A11.9: Settings → Message details; Verbose adds the turn stats (and recovery notices, once a Hub sends them). */
    val details: MessageDetails = MessageDetails.Brief,
    /** A11.9 Verbose: which providers' costs the stats leave out (subscription providers, as the terminal). */
    val usageCost: ProviderUsageCost = ProviderUsageCost.EMPTY,
)

/** The banner and announcement sentences, read once from resources (the controller stays pure). */
@Immutable
data class TranscriptStrings(
    val runError: RunErrorStrings,
    /** "Couldn't start the session: %1$s". */
    val startErrorFormat: String,
    /** "Couldn't resume this session: %1$s". */
    val resumeErrorFormat: String,
    /** "Couldn't load this session: %1$s". */
    val historyErrorFormat: String,
    /** [SessionError.Reason] sentences, used as %1$s when an error carries an app-side cause. */
    val reasonText: Map<SessionError.Reason, String>,
) {
    fun bannerText(error: SessionError): String {
        val format = when (error.kind) {
            SessionError.Kind.Start -> startErrorFormat
            SessionError.Kind.Resume -> resumeErrorFormat
            SessionError.Kind.History -> historyErrorFormat
        }
        return String.format(format, detailOf(error))
    }

    /** The kernel text, or the app-side cause's sentence when there is no kernel text. */
    fun detailOf(error: SessionError): String = error.reason?.let { reasonText[it] } ?: error.message

    companion object {
        fun from(resources: Resources): TranscriptStrings = TranscriptStrings(
            runError = RunErrorStrings.from(resources),
            startErrorFormat = resources.getString(R.string.chat_start_error),
            resumeErrorFormat = resources.getString(R.string.chat_resume_error),
            historyErrorFormat = resources.getString(R.string.chat_history_error),
            reasonText = SessionError.Reason.entries.associateWith { resources.getString(it.text) },
        )
    }
}

// ---------------------------------------------------------------- controller

/**
 * Derives [TranscriptUiState] from [TranscriptInput]. Pure apart from a one-entry memo of the
 * render pipeline (`buildTranscriptModel` is O(messages) and the store emits on every delta),
 * so one instance belongs to one screen; it is not thread-safe.
 */
class TranscriptController(private val strings: TranscriptStrings) {
    private var memoCanonical: List<ChatMessage>? = null
    private var memoLive: List<ChatMessage>? = null
    private var memoStatus: ChatStatus? = null
    private var memoStreaming: String? = null
    private var memoDetails: MessageDetails? = null
    private var memoTurns: List<TurnRecord>? = null
    private var memoUsageCost: ProviderUsageCost? = null
    private var memoProvider: String? = null
    private var memoModel: TranscriptModel = TranscriptModel.EMPTY

    /** Tool row models and media classifications kept across deltas: only changed rows are rebuilt. */
    private val factsCache = RowFactsCache()

    fun reduce(input: TranscriptInput): TranscriptUiState {
        val session = input.session
        if (input.sessionId == null) return home(input)

        val status = session?.chatStatus ?: ChatStatus.Idle
        val streamingId = session?.streamingMessageId
        val messages = session?.messages.orEmpty()
        val model = modelFor(session, status, streamingId, input.details, input.usageCost)
        val facts = session?.facts

        val missing = session?.seed == SeedState.Missing
        val switching = switchingOf(session, messages.isEmpty(), input.kernel)
        val approvals = facts?.approvals?.size ?: 0
        val questions = facts?.questions?.size ?: 0

        val indicator = if (missing || switching != Switching.None) null else indicatorOf(model, messages, status, streamingId, approvals, questions)
        val banner = bannerOf(input.hostError ?: session?.errorBanner, model.lastErrorMessage)
        val transport = if (transportLineVisible(input.kernel.transport, banner != null)) input.kernel.transport else ChatTransportState.Connected

        val actions = input.actions.copy(
            busy = status.isBusy,
            switching = switching != Switching.None,
            checkpointRuns = facts?.checkpointHistory.orEmpty()
                .associate { it.runCount to CheckpointRef(it.ref, it.createdAt, it.runCount, it.kind) },
            resendableIds = if (session == null) emptySet() else resendableIds(session),
        )

        return TranscriptUiState(
            sessionId = input.sessionId,
            model = model,
            status = status,
            kernel = input.kernel,
            transport = transport,
            indicator = indicator,
            banner = banner,
            switching = switching,
            missing = missing,
            liveness = session?.liveness ?: Liveness.Persisted,
            actions = actions,
            pendingApprovals = approvals,
            pendingQuestions = questions,
            usage = session?.let(::usageOf),
            userMessageCount = messages.count { it.role == Role.User },
            replayGap = session?.replayGap == true,
            lastAssistantText = messages.lastOrNull { it.role == Role.Assistant }?.content,
            lastErrorDetail = model.lastErrorMessage?.content,
        )
    }

    /** Home renders no transcript; only a failed first create can put a banner there. */
    private fun home(input: TranscriptInput): TranscriptUiState = TranscriptUiState.Empty.copy(
        kernel = input.kernel,
        banner = bannerOf(input.hostError, null),
        actions = input.actions,
    )

    /**
     * The render pipeline over the rows [details] shows (A11.9): a change of mode re-projects
     * (`ChatProjector.forDetails`), and Verbose hands each final answer its turn's stats, without
     * the cost for a provider whose cost the kernel hides ([usageCost]).
     */
    private fun modelFor(
        session: SessionState?,
        status: ChatStatus,
        streamingId: String?,
        details: MessageDetails,
        usageCost: ProviderUsageCost,
    ): TranscriptModel {
        if (session == null) return TranscriptModel.EMPTY
        val canonical = session.canonical
        val live = session.live
        val verbose = details == MessageDetails.Verbose
        // Brief needs no turn stats, so a closing turn alone does not rebuild the model there.
        val turns = if (verbose) session.turns else null
        val costs = if (verbose) usageCost else null
        val provider = if (verbose) session.facts.providerId else null
        if (
            canonical === memoCanonical && live === memoLive && status == memoStatus && streamingId == memoStreaming &&
            details == memoDetails && turns === memoTurns && costs == memoUsageCost && provider == memoProvider
        ) return memoModel
        val stats = if (verbose) verboseStats(session, status, provider, usageCost) else emptyMap()
        val model = buildTranscriptModel(ChatProjector.forDetails(session.messages, details), status, streamingId, factsCache, turnStats = stats)
        memoCanonical = canonical
        memoLive = live
        memoStatus = status
        memoStreaming = streamingId
        memoDetails = details
        memoTurns = turns
        memoUsageCost = costs
        memoProvider = provider
        memoModel = model
        return model
    }

    /**
     * A11.9 Verbose: the stats recorded from this app's `run.start` result win; every other
     * finished turn falls back to the totals the kernel persisted in history
     * (`TurnRecords.historyStatsByAnswer`). While a run is busy and its prompt is not a live row
     * yet (another client started it), the last history turn is still running and gets none.
     */
    private fun verboseStats(session: SessionState, status: ChatStatus, provider: String?, usageCost: ProviderUsageCost): Map<String, TurnStats> {
        val recorded = TurnRecords.statsByAnswer(session.turns, provider, usageCost::showsCost)
        val running = status.isBusy && session.live.none { it.role == Role.User && !isSystemSteeringMessage(it) }
        val fromHistory = TurnRecords.historyStatsByAnswer(session.canonical, provider, usageCost::showsCost, recorded.keys, running)
        return if (fromHistory.isEmpty()) recorded else fromHistory + recorded
    }

    private fun bannerOf(error: SessionError?, lastErrorMessage: ChatMessage?): TranscriptBanner? {
        if (error == null) return null
        val detail = strings.detailOf(error)
        val action: TranscriptBannerAction? = if (isCredentialFailure(detail)) {
            resolveCredentialFailureAction(error.providerId, error.providerAuth)?.let(TranscriptBannerAction::Credential)
        } else {
            TranscriptBannerAction.Retry(error.kind)
        }
        if (!shouldShowErrorBanner(detail, action != null, lastErrorMessage, strings.runError)) return null
        val showText = errorBannerShowsText(detail, lastErrorMessage)
        if (!showText && action == null) return null
        return TranscriptBanner(error, strings.bannerText(error), showText, action)
    }

    companion object {
        /**
         * §3.5: Loading (nothing cached) or Switching (cached rows) while `session.get` +
         * `session.messages` run. A session that cannot seed because the kernel is down shows
         * its cached rows and the transport line instead of an endless loader.
         */
        fun switchingOf(session: SessionState?, noMessages: Boolean, kernel: KernelLine): Switching {
            val waiting = when (session?.seed) {
                null -> kernel == KernelLine.Connected
                SeedState.Seeding -> true
                SeedState.NotSeeded -> kernel == KernelLine.Connected
                is SeedState.Seeded, is SeedState.Failed, SeedState.Missing -> false
            }
            return when {
                !waiting -> Switching.None
                noMessages -> Switching.Loading
                else -> Switching.Switching
            }
        }

        /**
         * §4.3, desktop `isAwaitingFirstOutput`: Starting, or Running with nothing streaming, the
         * last conversation row not an in-progress tool, and no approval or question pending.
         */
        fun indicatorOf(
            model: TranscriptModel,
            messages: List<ChatMessage>,
            status: ChatStatus,
            streamingMessageId: String?,
            pendingApprovals: Int,
            pendingQuestions: Int,
        ): ThinkingIndicator? {
            val awaitingFirstOutput = status == ChatStatus.Running &&
                streamingMessageId == null &&
                !lastToolInProgress(messages) &&
                pendingApprovals == 0 &&
                pendingQuestions == 0
            if (status != ChatStatus.Starting && !awaitingFirstOutput) return null
            return ThinkingIndicator(indicatorGap(model))
        }

        /** Desktop `lastToolInProgress`: the newest non-status row is a tool call without its result. */
        fun lastToolInProgress(messages: List<ChatMessage>): Boolean {
            val last = messages.lastOrNull { it.role != Role.Status } ?: return false
            return last.role == Role.Tool && buildToolPresentation(last).inProgress
        }

        /** Desktop `indicatorFollowsWorkingRows`: 0dp under a tools/run item or an answer, else the block gap. */
        fun indicatorGap(model: TranscriptModel): TranscriptGap {
            val last = model.rows.lastOrNull() ?: return TranscriptGap.None
            return when (last.type) {
                TranscriptRowType.Tools, TranscriptRowType.Run, TranscriptRowType.Assistant -> TranscriptGap.Tight
                else -> TranscriptGap.Block
            }
        }

        /** §3.8 rows: `aggregateUsage` first; cost prefers `metadata.totalCost` (§6.5). */
        fun usageOf(session: SessionState): TranscriptUsage? {
            val totals = session.facts.displayUsage ?: return null
            val metadataCost = session.facts.metadata.double("totalCost")
            return TranscriptUsage(
                totals = totals,
                tokens = formatTokenCount(totals.inputTokens, totals.outputTokens),
                cost = formatCostUsd(metadataCost ?: totals.totalCost),
            )
        }

        /**
         * Interrupted rows whose prompt "Resend" can recover (HC §3.4.7, same rule as
         * `SessionStore.resendPrompt`): no newer user row, and either the turn's own prompt or the
         * last user row before it is non-blank.
         */
        fun resendableIds(session: SessionState): Set<String> {
            val messages = session.messages
            var newerUser = false
            val ids = HashSet<String>()
            for (index in messages.indices.reversed()) {
                val message = messages[index]
                if (message.role == Role.User) {
                    newerUser = true
                    continue
                }
                if (newerUser || message.role != Role.Error || message.meta.reason != REASON_INTERRUPTED) continue
                val turnPrompt = message.meta.requestId?.let { id -> session.turns.firstOrNull { it.requestId == id } }?.prompt
                val recoverable = !turnPrompt.isNullOrBlank() ||
                    messages.subList(0, index).lastOrNull { it.role == Role.User }
                        ?.let { UserInputFormat.formatDisplayUserInput(it.content) }
                        ?.isNotBlank() == true
                if (recoverable) ids += message.id
            }
            return ids
        }
    }
}

// ---------------------------------------------------------------- announcements

/** What the one polite status text says (§3.15, DS §8.1.4); loading/switching are announced by the UI after 180 ms. */
@Immutable
sealed interface TranscriptAnnouncement {
    /** `run.started`: "Agent is working…". */
    data object Working : TranscriptAnnouncement

    /** `run.completed`: the first 200 characters of the final answer. */
    data class Completed(val excerpt: String) : TranscriptAnnouncement

    /** `run.failed`: "Run failed: <error>". */
    data class Failed(val detail: String) : TranscriptAnnouncement
}

/** How much of the final answer the completion announcement reads (§3.15). */
const val ANNOUNCE_EXCERPT_CHARS = 200

/**
 * Turns the state sequence of one conversation into its announcements (§3.15). Stateful because
 * the Hub's own sequence is not monotonic: a run's `session.updated {status: idle}` arrives
 * before its `run.completed`, so "completed" is announced for any run seen in progress, not only
 * for a busy → completed step. Opening or switching to a session announces nothing but the
 * loader text (the UI's job). One instance per transcript; not thread-safe.
 */
class TranscriptAnnouncer(private val strings: TranscriptStrings) {
    private var previous: TranscriptUiState? = null

    /** A run was seen in progress and has not been announced as finished yet. */
    private var inRun = false

    fun next(state: TranscriptUiState): TranscriptAnnouncement? {
        val before = previous
        previous = state
        if (before == null || before.sessionId != state.sessionId || state.sessionId == null) {
            inRun = state.status.isBusy
            return null
        }
        if (state.switching != Switching.None || before.switching != Switching.None) {
            inRun = inRun || state.status.isBusy
            return null
        }
        val from = before.status
        val to = state.status
        return when {
            to == ChatStatus.Running && from != ChatStatus.Running && from != ChatStatus.Stopping -> {
                inRun = true
                TranscriptAnnouncement.Working
            }
            to == ChatStatus.Completed && from != ChatStatus.Completed && (inRun || from.isBusy) -> {
                inRun = false
                state.lastAssistantText?.trim()?.takeIf { it.isNotEmpty() }?.let { TranscriptAnnouncement.Completed(excerptOf(it)) }
            }
            to == ChatStatus.Failed && from != ChatStatus.Failed -> {
                inRun = false
                TranscriptAnnouncement.Failed(state.lastErrorDetail?.trim()?.takeIf { it.isNotEmpty() } ?: strings.runError.runFailedEmpty)
            }
            else -> {
                if (to.isBusy) inRun = true
                if (to == ChatStatus.Cancelled) inRun = false
                null
            }
        }
    }
}

/**
 * The first [ANNOUNCE_EXCERPT_CHARS] UTF-16 units cut at a grapheme boundary (DS §8.2.7), so
 * TalkBack never reads half a ZWJ emoji, flag, skin-tone sequence or combining mark.
 */
fun excerptOf(text: String, max: Int = ANNOUNCE_EXCERPT_CHARS): String = takeGraphemePrefix(text, max)

// ---------------------------------------------------------------- streaming coalescing

/** DS §6 "Streaming text": deltas reach the transcript at most every 50 ms. */
const val STREAM_COALESCE_MS = 50L

/**
 * Throttles [this] to at most one value per [windowMs] (latest wins, the last value always
 * arrives), except that a value [urgent] relative to the last emitted one goes out at once.
 * Upstream is conflated: intermediate states are cumulative, so only the newest matters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Any> Flow<T>.coalesceUnless(windowMs: Long, urgent: (emitted: T, next: T) -> Boolean): Flow<T> = channelFlow {
    val input = this@coalesceUnless.buffer(Channel.CONFLATED).produceIn(this)
    val flush = Channel<Unit>(Channel.CONFLATED)
    var emitted: T? = null
    var pending: T? = null
    var timer: Job? = null

    suspend fun emitNow(value: T) {
        timer?.cancel()
        timer = null
        flush.tryReceive()
        pending = null
        emitted = value
        send(value)
    }

    var done = false
    while (!done) {
        select {
            input.onReceiveCatching { result ->
                val value = result.getOrNull()
                if (value == null) {
                    pending?.let { emitNow(it) }
                    done = true
                } else {
                    val last = emitted
                    if (last == null || urgent(last, value)) {
                        emitNow(value)
                    } else {
                        pending = value
                        if (timer == null) timer = launch { delay(windowMs); flush.send(Unit) }
                    }
                }
            }
            flush.onReceive {
                timer = null
                pending?.let { emitNow(it) }
            }
        }
    }
    timer?.cancel()
}

/**
 * The structure of a session state: anything that changes it goes out at once; a change that
 * leaves it equal (streaming text, live tool output, heartbeats) is coalesced to [STREAM_COALESCE_MS].
 */
private data class TranscriptStructure(
    val status: ChatStatus,
    val seed: SeedState,
    val liveness: Liveness,
    val creating: Boolean,
    val errorBanner: SessionError?,
    val approvals: Set<String>,
    val questions: Set<String>,
    val messageCount: Int,
    val firstKey: String?,
    val lastId: String?,
    val streamingMessageId: String?,
    val replayGap: Boolean,
)

private fun SessionState.structure(): TranscriptStructure {
    val messages = messages
    return TranscriptStructure(
        status = chatStatus,
        seed = seed,
        liveness = liveness,
        creating = creating,
        errorBanner = errorBanner,
        approvals = facts.approvals.keys,
        questions = facts.questions.keys,
        messageCount = messages.size,
        firstKey = messages.firstOrNull()?.renderKey,
        lastId = messages.lastOrNull()?.id,
        streamingMessageId = streamingMessageId,
        replayGap = replayGap,
    )
}

/** Whether [next] must reach the screen without waiting for the coalescing window. */
fun isUrgentTranscriptChange(emitted: SessionState, next: SessionState): Boolean =
    emitted.sessionId != next.sessionId || emitted.structure() != next.structure()

/** The store's per-session flow with streaming deltas coalesced to [STREAM_COALESCE_MS] (DS §6). */
fun Flow<SessionState>.coalesceStreaming(windowMs: Long = STREAM_COALESCE_MS): Flow<SessionState> =
    coalesceUnless(windowMs, ::isUrgentTranscriptChange)

/**
 * The transcript pipeline for one selected session: coalesced store state + kernel line + host
 * action state → [TranscriptUiState]. The ViewModel collects it with `stateIn(viewModelScope,
 * WhileSubscribed(5_000), TranscriptUiState.Empty)` (docs/07 §6.5).
 */
fun transcriptUiStates(
    sessionId: String?,
    session: Flow<SessionState>?,
    kernel: Flow<KernelLine>,
    actions: Flow<MessageActionsState>,
    hostError: Flow<SessionError?>,
    controller: TranscriptController,
    details: Flow<MessageDetails> = flowOf(MessageDetails.Brief),
    usageCost: Flow<ProviderUsageCost> = flowOf(ProviderUsageCost.EMPTY),
): Flow<TranscriptUiState> {
    val sessions: Flow<SessionState?> = session?.coalesceStreaming() ?: flowOf(null)
    val settings = combine(details, usageCost, ::DetailsSettings)
    return combine(sessions, kernel, actions, hostError, settings) { state, line, actionState, error, shown ->
        controller.reduce(TranscriptInput(sessionId, state, line, actionState, error, shown.mode, shown.usageCost))
    }
}

/** The two inputs of A11.9's Verbose stats, combined so [transcriptUiStates] stays at five flows. */
private data class DetailsSettings(val mode: MessageDetails, val usageCost: ProviderUsageCost)
