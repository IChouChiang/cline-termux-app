package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.SessionReattachHook
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.HubPendingCommand
import dev.cline.termux.core.kernel.hub.isAuthorityLost
import dev.cline.termux.core.kernel.hub.isDraining
import dev.cline.termux.core.kernel.hub.isLostReply
import dev.cline.termux.core.kernel.hub.isPendingPromptsUnavailable
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.SessionConfigInput
import dev.cline.termux.data.chat.SessionCreateBuilder
import dev.cline.termux.data.chat.SessionCreateRequest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** How a send reaches the Hub (composer-approvals.md C1.7). */
enum class Delivery(val wire: String) { Queue("queue"), Steer("steer") }

/** One prompt for [SessionStore.send]. Prompt text and images are never logged (CA §0.8). */
data class SendRequest(
    val sessionId: String,
    val prompt: String,
    val mode: PromptMode = PromptMode.Act,
    /** null = direct send (opens a turn); otherwise the Hub's prompt queue. */
    val delivery: Delivery? = null,
    /** Shown in the optimistic bubble and kept in memory for Resend. */
    val images: List<ChatImage> = emptyList(),
    /** `attachments.userImages` (data URLs). */
    val imageDataUrls: List<String> = emptyList(),
    /** `attachments.userFiles` (Termux paths the attachment pipeline wrote). */
    val userFiles: List<String> = emptyList(),
    /** The connection the composer shows; used to resume a session that is not live. Null: the session's own. */
    val config: SessionConfigInput? = null,
    /**
     * A prompt the app writes for the model, never one the user typed: the act-mode continuation
     * (A11 row 4) and the mistake-limit guidance steer (G2). Sent with [SessionStore.sendSynthetic],
     * it is never held for a reconnect, never handed back to the composer (no draft, no
     * [SessionSignal.NotSent], no snackbar), never falls back from the queue to a direct send, and a
     * refused one leaves no error row: the caller gets the outcome instead. A steer gets no
     * recovery at all (the run it steers is gone with its session or daemon); a direct send keeps
     * the resume after `session_not_found` and the one resend after a drain. Prompt history
     * (A11.6) must skip it as well.
     */
    val synthetic: Boolean = false,
)

/** How a [SendRequest.synthetic] prompt ended ([SessionStore.sendSynthetic]); text here is kernel text or protocol text. */
sealed interface SyntheticSendResult {
    /** The Hub took the frame (`ok:true`). */
    data class Accepted(val requestId: String) : SyntheticSendResult

    /** Nothing was written: no connection, or the socket is reconnecting (a synthetic prompt is never held). */
    data object NotConnected : SyntheticSendResult

    /** The session had to be resumed first and that failed; [message] is the error banner's text. */
    data class ResumeFailed(val message: String) : SyntheticSendResult

    /** The Hub refused the frame, or its reply never came (`hub_connection_closed`, a timeout): the reply's code and text. */
    data class Refused(val code: String, val message: String) : SyntheticSendResult
}

sealed interface SendOutcome {
    /** Direct send written; `run.*` events are the truth from here on. */
    data class Sent(val requestId: String) : SendOutcome

    /** Written with a `delivery`; the queue strip shows it. */
    data class Queued(val requestId: String) : SendOutcome

    /** The socket is reconnecting: the prompt waits in memory and goes out after re-register (HC §3.12 step 6). */
    data object Held : SendOutcome

    /** A prompt for this session is already waiting for the reconnect (max one per session). */
    data object AlreadyHeld : SendOutcome

    /** The kernel is down: return the prompt to the draft ("Message not sent. Start kernel to continue."). */
    data object NotConnected : SendOutcome

    /** The session must be resumed first and that failed; the error banner says why. */
    data class ResumeFailed(val message: String) : SendOutcome
}

/** One-shot facts for the chat screen and the composer (snackbars, draft restore). */
sealed interface SessionSignal {
    val sessionId: String

    /**
     * A prompt did not reach the Hub; give it back to the composer (HC §3.12 step 6, step 7).
     * [kernelDown]: it was held for a reconnect that ended without a kernel (step 6).
     */
    data class NotSent(
        override val sessionId: String,
        val prompt: String,
        val images: List<ChatImage>,
        val kernelDown: Boolean = false,
    ) : SessionSignal

    /** A turn was marked interrupted (derived or genuine); the chat offers Resend. */
    data class Interrupted(override val sessionId: String, val requestId: String?) : SessionSignal

    /** A send was refused for another reason; [message] is kernel text. */
    data class SendFailed(override val sessionId: String, val code: String, val message: String) : SessionSignal
}

/**
 * A run of a session ended (`run.completed` for any reason, `run.failed`, `run.aborted`, including
 * the user's Stop), closing the turn it had open. [ours]: this client started that run with a
 * direct send. The run-boundary restart of the auto-approve control listens (decided A1).
 * [createTitle]: the title the session had before this run, when the run was its first prompt,
 * sent by this client into a session that already carried a title (trial row 16; the
 * `CreateTitleKeeper` puts it back if the kernel replaced it with the prompt); null otherwise.
 */
data class RunEnded(
    val sessionId: String,
    val requestId: String,
    val ours: Boolean,
    val outcome: TurnOutcome,
    val createTitle: String? = null,
)

/**
 * A first send's `session.create` did not reach the Hub (no connection, or none came back
 * while it waited), or its reply was lost and no session carrying its `clientCreateId` turned
 * up. The composer says "Message not sent. Cline Hub disconnected." and keeps the draft.
 */
class SessionCreateDisconnectedException(cause: Throwable? = null) : Exception("session.create not delivered", cause)

/**
 * The app-scoped session store (docs/07 §4.1, home-chat.md §1.1, §3.6, §3.12): one
 * [SessionState] per session, fed by the only collector of `HubClient.events` (D15) on the
 * single-threaded [dispatcher] (§8), so event order holds without locks.
 *
 * - One global subscription and cursor live in `HubClient`; [open] adds the per-session
 *   subscribe without `sinceSequence` (approval re-issue only) and attaches + seeds.
 * - Every terminal `run.*` reconciles with `session.messages` (§1.3.3).
 * - Turns: [send] opens a `TurnRecord`, persisted while open; interruption is derived after a
 *   reconnect once the replay drained to `hub.status.eventLog.lastSequence` (§1.3.4).
 * - `session_not_found` on send resumes once via `session.create` and resends (§3.12 step 4).
 * - Implements [SessionReattachHook]: the coordinator re-attaches [openSessions] on reconnect.
 *
 * Callers hold [state] flows only between [open] and [close] (or while collecting them);
 * untouched closed states are evicted after 10 minutes, never with an open turn, a busy run
 * phase or a pending approval / question. Long-lived observers (notifier, keep-alive) watch
 * [notifyFacts] instead, which never keeps a state in memory.
 */
class SessionStore(
    private val hub: SessionHub,
    private val turnStore: TurnStore,
    private val providerAuth: ProviderAuthResolver,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    /** `pid:startedAt` of the connected daemon (`KernelState.hubInfo.lifetimePair`), or null when unknown. */
    private val hubLifetimeKey: () -> String?,
    private val config: Config = Config(),
    /**
     * `sessionConfig.compaction` for a create or resume whose config carries none (G1):
     * `CompactionModeSource.forCreate`. Null (tests) sends `HubPayloads.DEFAULT_COMPACTION`.
     */
    private val compaction: suspend () -> JsonObject? = { null },
    /**
     * The kernel's global auto-approve for a resume without a composer config (decided A1:
     * `ApprovalModeRepository`, re-read on every resume). Every resume takes the global value,
     * as a terminal resume does (auto-approve-control.md §6).
     */
    private val autoApproveNow: suspend () -> Boolean = { false },
    /** A17: where the app's mistake-limit stop notices persist ([StopNotices]). */
    private val stopNoticeStore: StopNoticeStore = StopNoticeStore.None,
) : SessionReattachHook {

    data class Config(
        val evictAfterMs: Long = 10 * 60_000L,
        val sweepIntervalMs: Long = 60_000L,
        /** How long the interruption check waits for the replay to drain before judging anyway. */
        val replayDrainTimeoutMs: Long = 15_000L,
        val drainWaitMs: Long = HubClient.DRAIN_WAIT_MS,
        /** How long a first send waits for a (re)connect in progress before it fails as disconnected. */
        val createConnectWaitMs: Long = 30_000L,
        /** The `session.list` page searched for a lost create's `clientCreateId`. */
        val createRecoveryListLimit: Int = 20,
    )

    private val flows = ConcurrentHashMap<String, MutableStateFlow<SessionState>>()
    private val openIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How many screens hold each open session (the chat and a pushed Tool detail can overlap). */
    private val openCounts = ConcurrentHashMap<String, Int>()
    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 64)

    /** Snackbar-level facts; collect from a ViewModel. */
    val signals: SharedFlow<SessionSignal> = _signals.asSharedFlow()

    private val _runEnds = MutableSharedFlow<RunEnded>(extraBufferCapacity = 64)

    /** Every run end that closed an open turn, in event order (not a Hub restart's interruption). */
    val runEnds: SharedFlow<RunEnded> = _runEnds.asSharedFlow()

    /**
     * Prompts [returnPrompt] handed back that no composer has taken yet (HC §3.12 steps 6
     * and 7). [signals] has no replay, so a prompt returned while its session is not bound
     * to a composer (the user switched sessions or left the chat) waits here until that
     * session's composer takes it with [takeReturnedPrompt]. Text only: images are not kept.
     */
    private val returnedPrompts = ConcurrentHashMap<String, SessionSignal.NotSent>()

    private val _appliedSequence = MutableStateFlow(0L)

    /** Highest event sequence the store has applied (the replay drain check). */
    val appliedSequence: StateFlow<Long> = _appliedSequence.asStateFlow()

    private val _notifyFacts = MutableStateFlow<Map<String, NotifyFacts>>(emptyMap())

    /**
     * [NotifyFacts] of every session in memory, republished only when one of them changes and
     * when a session is evicted (docs/07 §3.1 derived flows, §4.7). Unlike a [state] flow,
     * collecting it pins no session, so the notifier and the keep-alive can hold it for the
     * life of the process.
     */
    val notifyFacts: StateFlow<Map<String, NotifyFacts>> = _notifyFacts.asStateFlow()

    // Touched on [dispatcher] only.
    private val images = HashMap<String, List<ChatImage>>()
    private val held = LinkedHashMap<String, SendRequest>()

    /** Held prompts that already went through an authority-loss re-register, and how often (bounds that loop). */
    private val heldAuthorityRetries = HashMap<String, Int>()

    /** Bumped on every transition into Connected: tells a late send reply whether the re-register already happened. */
    private var connectionEpoch = 0L
    private val needsReconcile = HashSet<String>()

    /** `clientCreateId`s of creates in flight, and the session each one's `session.created` named (lost-reply recovery). */
    private val pendingCreates = HashSet<String>()
    private val createdByNonce = HashMap<String, String>()
    private val reconciling = HashSet<String>()

    /** Sessions with a `session.get` verification in flight (a replay gap, an unresolved open turn). */
    private val verifying = HashSet<String>()

    /** Sessions whose last `session.messages` failed: retried on the next terminal event or reconnect, never in a loop. */
    private val staleHistory = HashSet<String>()

    /** Sessions whose runtime [restart] is rebuilding: a send waits for it, so it reaches the new runtime. */
    private val restarts = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * The Plan/Act mode of this client's prompts waiting in the Hub's queue, oldest first, per
     * session. The queue's snapshots carry no mode, so a drained prompt's turn gets its mode from
     * here, matched by its text (A11 row 4: `switch_to_act_mode` checks the turn's mode).
     */
    private val queuedModes = HashMap<String, ArrayDeque<Pair<String, PromptMode>>>()

    /**
     * Trial row 16: the session's first prompt, sent by this client while the session already had
     * a title, by session id ([createTitleOf]); handed to [RunEnded.createTitle] when that run ends.
     * In memory only: a cold start in between loses it, and nothing is put back then.
     */
    private val firstRunTitles = LinkedHashMap<String, FirstRunTitle>()

    private data class FirstRunTitle(val requestId: String, val title: String)

    /**
     * A17: the stop notices by session, oldest first. Written on [dispatcher]; read by [flowFor]
     * from any thread, which seeds a new state's projection with their rows.
     */
    private val stopNotices = ConcurrentHashMap<String, List<StopNotice>>()
    private var persistNoticesJob: Job? = null
    private var persistNoticesAgain = false

    /** This Hub lifetime's live set (HC §3.5): sessions created, resumed or running a turn since the daemon started. */
    private val liveIds = HashSet<String>()
    private var liveIdsKey: String? = null
    private var started = false

    /**
     * [HubConnectionState.Connected.generation] of the connection whose reconnect work ran, null
     * while not connected. A new generation is a reconnect even when the states in between were
     * conflated away (the collector runs on the busy single-threaded dispatcher).
     */
    private var connectedGeneration: Long? = null
    private var persistJob: Job? = null
    private var persistAgain = false

    // ---- lifecycle ------------------------------------------------------------------------

    /** Starts the pump, the connection watcher, the eviction sweep, and restores persisted turns. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        // The pump subscribes before start() returns, so before HubClient can send its first
        // subscribe. `HubClient.events` has no replay: an event emitted with no collector is
        // gone although its sequence already counts as seen, so a replay that arrives while the
        // persisted turns are still loading (the floor stops waiting after RESTORE_WAIT_MS) must
        // find a collector. It waits in HubClient's buffer, and past that suspends the reader
        // (D15), until the restore is in memory; then events apply in arrival order.
        scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            hub.events.collect { event ->
                restored.await()
                dispatch(event)
            }
        }
        hub.setReplayFloor { withTimeoutOrNull(RESTORE_WAIT_MS) { restored.await() }; replayFloor() }
        scope.launch(dispatcher) {
            restoreTurns()
            restoreStopNotices()
            restored.complete(Unit)
        }
        scope.launch(dispatcher) {
            // The reconnect judges the open turns, so the restored ones must be in memory first.
            withTimeoutOrNull(RESTORE_WAIT_MS) { restored.await() }
            hub.connection.collect { onConnection(it) }
        }
        scope.launch(dispatcher) {
            hub.notices.collect { notice ->
                if (notice is HubClient.Notice.ReplayTruncated) onReplayTruncated(notice)
            }
        }
        scope.launch(dispatcher) {
            while (true) {
                delay(config.sweepIntervalMs)
                evict()
            }
        }
    }

    // ---- reads ----------------------------------------------------------------------------

    /** The session's state; created on first reference. Hold it only while the session is open or collected. */
    fun state(sessionId: String): StateFlow<SessionState> = flowFor(sessionId).asStateFlow()

    fun isOpen(sessionId: String): Boolean = sessionId in openIds

    // ---- open / close / seed ------------------------------------------------------------------

    /** The session is on screen: per-session subscribe (no cursor), attach when connected, seed. */
    suspend fun open(sessionId: String) = withContext(dispatcher) {
        openCounts.merge(sessionId, 1, Int::plus)
        openIds += sessionId
        hub.subscribe(sessionId)
        mutate(sessionId) { it }
        if (hub.connection.value is HubConnectionState.Connected) attach(sessionId)
        seedLocked(sessionId)
    }

    /** One holder leaves; the session closes (unsubscribe) when the last one does. */
    suspend fun close(sessionId: String) = withContext(dispatcher) {
        val left = openCounts.compute(sessionId) { _, n -> if (n == null || n <= 1) null else n - 1 }
        if (left == null && openIds.remove(sessionId)) hub.unsubscribe(sessionId)
        mutate(sessionId) { it }
    }

    /** `session.get {includeSnapshot:true}` + `session.messages` → facts + canonical rows (also after restore). */
    suspend fun seed(sessionId: String) = withContext(dispatcher) { seedLocked(sessionId) }

    /**
     * [verify]: requestIds of turns that were open when a replay gap was found; the record then
     * also ends a run the stream never finished ([applyVerified]).
     */
    private suspend fun seedLocked(sessionId: String, verify: Set<String>? = null) {
        val before = current(sessionId)
        if (before.seed == SeedState.Seeding) return
        val seqBefore = before.facts.lastSequence
        mutate(sessionId) { it.copy(seed = SeedState.Seeding) }
        val record = try {
            hub.get(sessionId)
        } catch (e: HubCommandException) {
            if (e.isSessionGone()) {
                liveSet() -= sessionId
                mutate(sessionId) { it.copy(seed = SeedState.Missing, live_ = false) }
                dropStopNoticesLocked(sessionId)
                if (verify != null) applyVerified(sessionId, null, verify, seqBefore, reconcile = false)
            } else {
                mutate(sessionId) { it.copy(seed = SeedState.Failed(e.hubMessage), errorBanner = SessionError(SessionError.Kind.History, e.hubMessage)) }
            }
            return
        } catch (e: HubConnectionClosedException) {
            mutate(sessionId) { it.copy(seed = if (it.canonical.isEmpty()) SeedState.NotSeeded else it.seed) }
            return
        }
        val inLiveSet = sessionId in liveSet()
        mutate(sessionId) { s ->
            val facts = SessionFactsReducer.fromRecord(sessionId, record.obj("session"), record.obj("snapshot"), s.facts)
            // The not-live hint (HC §3.5): not in this Hub lifetime's live set and completed/aborted per
            // the Hub, or already known not live (a new daemon lifetime, session_not_found).
            val ended = facts.status == SessionStatus.Completed || facts.status == SessionStatus.Cancelled
            s.copy(facts = facts, persistedHint = !inLiveSet && (s.persistedHint || (ended && s.openTurn == null)))
        }
        if (verify != null) applyVerified(sessionId, record, verify, seqBefore, reconcile = false)
        reconcileNow(sessionId, seeding = true)
    }

    private suspend fun attach(sessionId: String) {
        try {
            hub.attach(sessionId)
            onAttachSucceeded(sessionId)
        } catch (e: HubCommandException) {
            if (e.isSessionGone()) markNotLive(sessionId)
        } catch (_: HubConnectionClosedException) {
            // The next connect re-attaches through the coordinator's hook.
        }
    }

    // ---- SessionReattachHook ------------------------------------------------------------------

    override suspend fun openSessions(): Set<String> = openIds.toSet()

    /**
     * The coordinator re-attached [sessionId] after a (re)connect. On a new daemon lifetime the
     * session is not live although the attach succeeded (FX 05 item 3): [liveSet] has marked it
     * with the not-live hint, and only this lifetime's live set makes it live again.
     */
    override suspend fun onAttached(sessionId: String) = withContext(dispatcher) {
        onAttachSucceeded(sessionId)
    }

    override suspend fun onSessionGone(sessionId: String) = withContext(dispatcher) {
        markNotLive(sessionId)
    }

    /** The Hub does not have the session live (authoritative): leave the live set, resume on the next send. */
    private fun markNotLive(sessionId: String) {
        liveSet() -= sessionId
        mutate(sessionId) { it.copy(live_ = false, persistedHint = true) }
    }

    // ---- create / resume ---------------------------------------------------------------------

    /**
     * Sends a built `session.create` (first send, fork) and seeds the new session's state.
     * Returns the new session id. The caller decides navigation.
     */
    suspend fun create(request: SessionCreateRequest): Result<String> {
        val withCompaction = if (request.spec.compaction != null) request else request.copy(spec = request.spec.copy(compaction = compaction()))
        return withContext(dispatcher) { createWith(withCompaction) }
    }

    private suspend fun createWith(request: SessionCreateRequest): Result<String> {
        val nonce = request.metadata.str(SessionCreateBuilder.CLIENT_CREATE_ID)
        if (nonce != null) pendingCreates += nonce
        return try {
            createLocked(request, nonce)
        } finally {
            if (nonce != null) {
                pendingCreates -= nonce
                createdByNonce -= nonce
            }
        }
    }

    /**
     * Held while the socket is (re)connecting (HC §3.12 step 6). A create the client could not
     * write ([HubConnectionClosedException]) is sent once more after the reconnect; a lost reply
     * (the socket dropped or a timer fired after the write) is never resent: the session the
     * Hub may have made is found by its `clientCreateId` (the `session.created` event, then a
     * `session.list` match, docs/07 §9) and adopted, or the send fails as disconnected.
     */
    private suspend fun createLocked(request: SessionCreateRequest, nonce: String?): Result<String> {
        var attempt = 0
        while (true) {
            if (!awaitConnectedForCreate()) return Result.failure(SessionCreateDisconnectedException())
            val reply = try {
                hub.create(request)
            } catch (e: HubConnectionClosedException) {
                if (attempt++ == 0) continue
                return Result.failure(SessionCreateDisconnectedException(e))
            } catch (e: HubCommandException) {
                if (!e.isLostReply()) return Result.failure(e)
                val found = nonce?.let { recoverCreated(it) } ?: return Result.failure(SessionCreateDisconnectedException(e))
                adoptCreated(found.first, found.second, snapshot = null, request = request, live = false)
                return Result.success(found.first)
            }
            val sessionId = HubPayloads.sessionIdOf(reply)
            if (sessionId == null) {
                // A reply that names no session leaves the outcome as unknown as a lost one.
                val found = nonce?.let { recoverCreated(it) } ?: return Result.failure(SessionCreateDisconnectedException())
                adoptCreated(found.first, found.second, snapshot = null, request = request, live = false)
                return Result.success(found.first)
            }
            adoptCreated(sessionId, reply.obj("session"), reply.obj("snapshot"), request, live = true)
            return Result.success(sessionId)
        }
    }

    private suspend fun adoptCreated(sessionId: String, session: JsonObject?, snapshot: JsonObject?, request: SessionCreateRequest, live: Boolean) {
        liveSet() += sessionId
        mutate(sessionId) { s ->
            s.copy(
                facts = if (session != null || snapshot != null) SessionFactsReducer.fromRecord(sessionId, session, snapshot, s.facts) else s.facts,
                createdAutoApprove = request.spec.autoApprove,
                live_ = live || s.live_,
                persistedHint = false,
                errorBanner = null,
            )
        }
        if (request.spec.initialMessages != null) reconcileNow(sessionId, seeding = true)
        else mutate(sessionId) { it.copy(seed = SeedState.Seeded(_appliedSequence.value)) }
    }

    /** Connected now, or once a (re)connect in progress finishes; false when there is no connection to wait for. */
    private suspend fun awaitConnectedForCreate(): Boolean {
        val settled = withTimeoutOrNull(config.createConnectWaitMs) {
            hub.connection.first { it !is HubConnectionState.Reconnecting && it !is HubConnectionState.Connecting }
        }
        return settled is HubConnectionState.Connected
    }

    /** The session a lost `session.create` made, with its list record when it came from `session.list`. */
    private suspend fun recoverCreated(nonce: String): Pair<String, JsonObject?>? {
        createdByNonce[nonce]?.let { return it to null }
        if (!awaitConnectedForCreate()) return createdByNonce[nonce]?.let { it to null }
        createdByNonce[nonce]?.let { return it to null }
        val rows = try {
            HubPayloads.sessionsOf(hub.list(config.createRecoveryListLimit))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return createdByNonce[nonce]?.let { it to null }
        }
        val row = rows.firstOrNull { it.obj("metadata")?.str(SessionCreateBuilder.CLIENT_CREATE_ID) == nonce }
        val id = row?.str("sessionId") ?: return createdByNonce[nonce]?.let { it to null }
        return id to row
    }

    /**
     * Resume (HC §3.6, §3.12 step 3/4, Resend): `session.messages` → `session.create
     * {sessionConfig.sessionId, initialMessages}` built by [SessionCreateBuilder.resume]. A failure
     * sets the "Couldn't resume this session" banner. The config's missing `compaction` is filled
     * from the global mode (G1).
     */
    suspend fun resume(sessionId: String, config: SessionConfigInput? = null): Result<Unit> = withContext(dispatcher) { resumeLocked(sessionId, config) }

    /** [initialCompactionState]: a restart's `session.compaction.get` state, passed on as the terminal does. */
    private suspend fun resumeLocked(sessionId: String, config: SessionConfigInput?, initialCompactionState: JsonObject? = null): Result<Unit> {
        val facts = current(sessionId).facts
        val resolved = config ?: configFromFacts(facts)
        if (resolved == null) {
            mutate(sessionId) { it.copy(errorBanner = SessionError(SessionError.Kind.Resume, "", reason = SessionError.Reason.MissingResumeConfig)) }
            return Result.failure(IllegalStateException("no resume config"))
        }
        mutate(sessionId) { it.copy(creating = true) }
        val cfg = if (resolved.compaction != null) resolved else resolved.copy(compaction = compaction())
        return try {
            val messages = HubPayloads.messagesOf(hub.messages(sessionId))
            val request = SessionCreateBuilder.resume(cfg, sessionId, messages, facts.metadata, initialCompactionState)
            val reply = hub.create(request)
            liveSet() += sessionId
            mutate(sessionId) { s ->
                s.copy(
                    facts = SessionFactsReducer.fromRecord(sessionId, reply.obj("session"), reply.obj("snapshot"), s.facts),
                    createdAutoApprove = request.spec.autoApprove,
                    creating = false,
                    live_ = true,
                    persistedHint = false,
                    errorBanner = null,
                )
            }
            Result.success(Unit)
        } catch (e: HubCommandException) {
            resumeFailed(sessionId, cfg, e.hubMessage)
            Result.failure(e)
        } catch (e: HubConnectionClosedException) {
            resumeFailed(sessionId, cfg, e.message.orEmpty())
            Result.failure(e)
        }
    }

    private fun resumeFailed(sessionId: String, cfg: SessionConfigInput, message: String) {
        mutate(sessionId) {
            it.copy(
                creating = false,
                errorBanner = SessionError(SessionError.Kind.Resume, message, cfg.providerId, providerAuth.resolve(cfg.providerId)),
            )
        }
    }

    /**
     * Rebuilds [sessionId]'s runtime in [mode] with its messages and its own config, keeping the
     * id (brief F2; the CLI's `restartWithCurrentMessages` after `applyMode`): the compaction
     * state (`session.compaction.get`), `session.detach`, then the resume path's
     * `session.messages` + `session.create {sessionConfig.sessionId, initialMessages,
     * initialCompactionState}`, whose contributions follow the new mode. A runtime's tools are
     * fixed when it is created (`runtime-builder.ts` picks the preset from `config.mode`), so a
     * mode change reaches the kernel only this way. The config is [configFromFacts], so the
     * rebuild also takes the kernel's global auto-approve (decided A1): one restart carries both
     * the mode and the approval policies. Refused while the session runs or waits for anything
     * (an open turn, a busy phase, another create); a send that arrives meanwhile waits for the
     * rebuild, and so does another restart. A failed rebuild sets the resume banner.
     */
    suspend fun restart(sessionId: String, mode: PromptMode): Result<Unit> = withContext(dispatcher) {
        awaitRestarts(sessionId)
        val s = current(sessionId)
        if (s.openTurn != null || s.facts.runPhase.isBusy || s.facts.activeRun != null || s.creating) {
            return@withContext Result.failure(IllegalStateException("session is busy"))
        }
        withRestartGate(sessionId) { restartLocked(sessionId, mode) }
    }

    /** Waits until no [restart] of [sessionId] is in flight. */
    private suspend fun awaitRestarts(sessionId: String) {
        while (true) {
            val inFlight = restarts[sessionId] ?: break
            inFlight.await()
        }
    }

    /** Runs [block] as [sessionId]'s restart: sends and other restarts wait for it ([restarts]). */
    private suspend fun <T> withRestartGate(sessionId: String, block: suspend () -> T): T {
        val gate = CompletableDeferred<Unit>()
        restarts[sessionId] = gate
        try {
            return block()
        } finally {
            restarts.remove(sessionId)
            gate.complete(Unit)
        }
    }

    private suspend fun restartLocked(sessionId: String, mode: PromptMode): Result<Unit> {
        val cfg = configFromFacts(current(sessionId).facts)?.copy(mode = mode.wire)
            // resumeLocked sets the missing-config banner.
            ?: return resumeLocked(sessionId, null)
        mutate(sessionId) { it.copy(creating = true) }
        // Read before the detach, which ends this client's right to read it.
        val compactionState = compactionState(sessionId)
        // The runtime this app created goes away: until the resume's create, records decide.
        mutate(sessionId) { it.copy(createdAutoApprove = null) }
        try {
            hub.detach(sessionId)
        } catch (e: HubCommandException) {
            // Not live: there is nothing to leave, and the create brings it back.
            if (!e.isSessionGone()) {
                resumeFailed(sessionId, cfg, e.hubMessage)
                return Result.failure(e)
            }
        } catch (e: HubConnectionClosedException) {
            resumeFailed(sessionId, cfg, e.message.orEmpty())
            return Result.failure(e)
        }
        return resumeLocked(sessionId, cfg, compactionState)
    }

    /**
     * The resume config when the composer passed none: the session's own workspace and model,
     * with the kernel's global auto-approve (decided A1), not the session's old value.
     */
    private suspend fun configFromFacts(facts: SessionFacts): SessionConfigInput? {
        val root = facts.workspaceRoot ?: return null
        val provider = facts.providerId ?: return null
        val model = facts.modelId ?: return null
        return SessionConfigInput(
            workspaceRoot = root,
            cwd = facts.cwd ?: root,
            providerId = provider,
            modelId = model,
            mode = facts.mode,
            autoApprove = runCatching { autoApproveNow() }.getOrElse { if (it is CancellationException) throw it else false },
            thinking = facts.thinking,
            reasoningEffort = facts.reasoningEffort,
        )
    }

    // ---- run-boundary restart (decided A1) -----------------------------------------------------

    /**
     * The Hub's current record of [sessionId] (`session.get`): participants, status and
     * `metadata.autoApproveTools` for the restart decision. Null when it could not be read.
     */
    suspend fun record(sessionId: String): JsonObject? = withContext(dispatcher) {
        try {
            hub.get(sessionId)
        } catch (_: HubCommandException) {
            null
        } catch (_: HubConnectionClosedException) {
            null
        }
    }

    /**
     * Restarts [sessionId] with new tool policies (batch 1 F2, the terminal's
     * `restartWithCurrentMessages`: `stopCurrentSession` + `startFreshSession`): the session's
     * compaction state (`session.compaction.get`), `session.detach`, then the resume path
     * ([resume]: `session.messages` + `session.create {sessionId, initialMessages,
     * initialCompactionState}`) with [config] or the session's own connection and the global
     * auto-approve, so a compacted session keeps its compacted context. `creating` holds a send
     * meanwhile. Not connected: nothing is done (the send path holds or resumes).
     */
    suspend fun restart(sessionId: String, config: SessionConfigInput? = null): Result<Unit> = withContext(dispatcher) {
        // One rebuild at a time: a mode rebuild in flight ([restart] with a mode) finishes first.
        awaitRestarts(sessionId)
        withRestartGate(sessionId) { restartWithPolicies(sessionId, config) }
    }

    private suspend fun restartWithPolicies(sessionId: String, config: SessionConfigInput?): Result<Unit> {
        if (hub.connection.value !is HubConnectionState.Connected) return Result.failure(HubConnectionClosedException("not connected"))
        mutate(sessionId) { it.copy(creating = true) }
        val compactionState = compactionState(sessionId)
        // The runtime this app created goes away: until the resume's create, records decide.
        mutate(sessionId) { it.copy(createdAutoApprove = null) }
        try {
            hub.detach(sessionId)
        } catch (e: HubCommandException) {
            // Not on the Hub any more: the resume below creates it again, as after session_not_found.
            if (!e.isSessionGone()) {
                mutate(sessionId) { it.copy(creating = false) }
                return Result.failure(e)
            }
        } catch (e: HubConnectionClosedException) {
            mutate(sessionId) { it.copy(creating = false) }
            return Result.failure(e)
        }
        return resumeLocked(sessionId, config, compactionState)
    }

    /**
     * The runtime's compaction state (`session.compaction.get` → `state`), or null: none yet,
     * or not readable (the Hub answers only the session's owner, `session_wrong_client`). Without
     * it the Hub's resume falls back to the session's persisted compaction sidecar.
     */
    private suspend fun compactionState(sessionId: String): JsonObject? = try {
        hub.compactionState(sessionId)["state"] as? JsonObject
    } catch (_: HubCommandException) {
        null
    } catch (_: HubConnectionClosedException) {
        null
    }

    fun clearError(sessionId: String) {
        scope.launch(dispatcher) { mutate(sessionId) { it.copy(errorBanner = null) } }
    }

    // ---- returned prompts ---------------------------------------------------------------------

    /**
     * The prompt of [sessionId] that did not reach the Hub and that no composer has taken yet,
     * removed from the store; null when there is none. The composer calls it when it binds a
     * session and when it handles a [SessionSignal.NotSent] for its own session.
     */
    fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent? = returnedPrompts.remove(sessionId)

    /**
     * Gives an undelivered prompt back to the composer (HC §3.12 steps 6 and 7): kept in
     * [returnedPrompts] until a composer takes it (a second one for the same session is
     * appended after a blank line, so neither is lost), and announced on [signals] for the
     * composer that has the session bound and for the snackbar.
     */
    private fun returnPrompt(sessionId: String, prompt: String, images: List<ChatImage>, kernelDown: Boolean = false) {
        if (prompt.isNotBlank()) {
            returnedPrompts.merge(sessionId, SessionSignal.NotSent(sessionId, prompt, emptyList(), kernelDown)) { old, new ->
                old.copy(prompt = "${old.prompt}\n\n${new.prompt}", kernelDown = new.kernelDown)
            }
        }
        _signals.tryEmit(SessionSignal.NotSent(sessionId, prompt, images, kernelDown))
    }

    // ---- send ---------------------------------------------------------------------------------

    /**
     * Sends a prompt (HC §3.12): resumes first when the not-live hint is set, appends the
     * optimistic `local:<requestId>` bubble for a direct send, and recovers from
     * `session_not_found` (resume once, resend), `hub_draining` (resend after the drain) and
     * a lost client authority (held, resent after the re-register: the Hub refused the frame).
     * Otherwise `send_input` is never retried after it was written; the event stream is the truth.
     */
    suspend fun send(request: SendRequest): SendOutcome = withContext(dispatcher) { sendLocked(request, authorityRetries = 0) }

    /**
     * Sends a synthetic prompt ([SendRequest.synthetic], set here) and waits for the Hub's reply
     * to its `session.send_input`, as the desktop awaits `manager.send()` before it answers a
     * mistake-limit decision. Nothing of it reaches the composer, the snackbar or an error row.
     */
    suspend fun sendSynthetic(request: SendRequest): SyntheticSendResult {
        val settled = CompletableDeferred<SyntheticSendResult>()
        withContext(dispatcher) { sendLocked(request.copy(synthetic = true), authorityRetries = 0, settled) }
        return settled.await()
    }

    /**
     * [authorityRetries]: how many times this prompt was already held for an authority-loss
     * re-register. [settled]: a synthetic send's outcome, completed on every path that ends it.
     */
    private suspend fun sendLocked(
        request: SendRequest,
        authorityRetries: Int,
        settled: CompletableDeferred<SyntheticSendResult>? = null,
    ): SendOutcome {
        // A rebuild of the session's runtime is in flight ([restart]): the prompt goes to the new runtime.
        while (true) {
            val rebuild = restarts[request.sessionId] ?: break
            rebuild.await()
        }
        when (hub.connection.value) {
            is HubConnectionState.Connected -> Unit
            is HubConnectionState.Reconnecting, HubConnectionState.Connecting -> {
                if (request.synthetic) {
                    settled?.complete(SyntheticSendResult.NotConnected)
                    return SendOutcome.NotConnected
                }
                if (request.sessionId in held) return SendOutcome.AlreadyHeld
                held[request.sessionId] = request
                if (authorityRetries > 0) heldAuthorityRetries[request.sessionId] = authorityRetries
                return SendOutcome.Held
            }
            else -> {
                settled?.complete(SyntheticSendResult.NotConnected)
                return SendOutcome.NotConnected
            }
        }
        // A daemon lifetime that changed since the last look marks every session not live first.
        liveSet()
        val state = current(request.sessionId)
        // Attach succeeds on a persisted session without making it live (FX 05), so only the hint decides.
        val resumeFirst = request.delivery == null && state.persistedHint
        if (resumeFirst) {
            val resumed = resumeLocked(request.sessionId, request.config)
            if (resumed.isFailure) {
                val message = current(request.sessionId).errorBanner?.message.orEmpty()
                settled?.complete(SyntheticSendResult.ResumeFailed(message))
                return SendOutcome.ResumeFailed(message)
            }
        }
        return sendNow(request, renderKey = null, retries = SendRetries(resumed = resumeFirst, authority = authorityRetries), settled = settled)
    }

    /**
     * The recoveries a send already used (HC §3.12 steps 4 and 5, CA C2.6). Each runs at most
     * once and independently: a resend after a drain can still meet `session_not_found` on the
     * swapped daemon and resume.
     */
    private data class SendRetries(
        /** The session was resumed for this prompt (before the send or after `session_not_found`). */
        val resumed: Boolean = false,
        /** Resent once after `hub_draining`. */
        val drainRetried: Boolean = false,
        /** A queued send fell back to a direct send (`pending_prompts_unavailable`). */
        val queueFallback: Boolean = false,
        /** How many times this prompt was already held for an authority-loss re-register (docs/07 §9). */
        val authority: Int = 0,
    )

    private fun sendNow(
        request: SendRequest,
        renderKey: String?,
        retries: SendRetries,
        settled: CompletableDeferred<SyntheticSendResult>? = null,
    ): SendOutcome {
        val sessionId = request.sessionId
        val pending: HubPendingCommand = try {
            hub.sendInput(sessionId, request.prompt, request.mode.wire, request.delivery?.wire, request.imageDataUrls, request.userFiles)
        } catch (_: HubConnectionClosedException) {
            if (request.synthetic) {
                settled?.complete(SyntheticSendResult.NotConnected)
                return SendOutcome.NotConnected
            }
            if (sessionId in held) return SendOutcome.AlreadyHeld
            held[sessionId] = request
            if (retries.authority > 0) heldAuthorityRetries[sessionId] = retries.authority
            return SendOutcome.Held
        }
        val requestId = pending.requestId
        if (request.delivery != null) {
            if (request.delivery == Delivery.Queue) rememberQueuedMode(request)
            mutate(sessionId) { it.copy(facts = SessionFactsReducer.onQueuedSend(it.facts, requestId)) }
            watchReply(pending, request, renderKey = null, retries = retries, settled = settled)
            return SendOutcome.Queued(requestId)
        }
        val now = clock.millis()
        val providerId = request.config?.providerId ?: current(sessionId).facts.providerId
        val turn = TurnRecord(
            requestId = requestId,
            sessionId = sessionId,
            prompt = request.prompt,
            imageIds = request.images.map { it.id },
            mode = request.mode.wire,
            providerId = providerId,
            providerAuth = providerAuth.resolve(providerId),
            open = true,
            started = false,
            hubLifetimeKey = hubLifetimeKey(),
            sentAt = now,
            ours = true,
            synthetic = request.synthetic,
        )
        if (request.images.isNotEmpty()) images[requestId] = request.images
        createTitleOf(current(sessionId), request)?.let { title ->
            firstRunTitles.remove(sessionId)
            firstRunTitles[sessionId] = FirstRunTitle(requestId, title)
            while (firstRunTitles.size > MAX_FIRST_RUN_TITLES) firstRunTitles.remove(firstRunTitles.keys.first())
        }
        mutate(sessionId) { s ->
            s.copy(
                facts = SessionFactsReducer.onDirectSend(s.facts),
                // A synthetic prompt is for the model: no bubble (the CLI's `isSyntheticUserText`).
                projection = if (request.synthetic) {
                    s.projection
                } else {
                    ChatProjector.appendLocalUser(s.projection, sessionId, requestId, request.prompt, request.images, now, renderKey)
                },
                turns = s.turns + turn,
                errorBanner = null,
            )
        }
        persistTurns()
        watchReply(pending, request, renderKey = renderKey ?: ChatProjector.localId(requestId), retries = retries, settled = settled)
        return SendOutcome.Sent(requestId)
    }

    private fun watchReply(
        pending: HubPendingCommand,
        request: SendRequest,
        renderKey: String?,
        retries: SendRetries,
        settled: CompletableDeferred<SyntheticSendResult>? = null,
    ) {
        // Taken when the frame was written: a reply that arrives after the re-register finished sees a newer epoch.
        val epoch = connectionEpoch
        scope.launch(dispatcher) {
            val reply = runCatching { pending.reply.await() }.getOrNull()
            if (reply == null) {
                settled?.complete(SyntheticSendResult.Refused(HubErrorCodes.HUB_CONNECTION_CLOSED, "no reply from the Hub"))
                return@launch
            }
            if (reply.ok) {
                settled?.complete(SyntheticSendResult.Accepted(pending.requestId))
                return@launch
            }
            val error = HubCommandException(
                command = "session.send_input",
                code = reply.error?.code ?: "unknown",
                message = reply.error?.message ?: "unknown error",
                details = reply.error?.details,
            )
            // Refused: the prompt never joined the queue (a fallback to a direct send opens its own turn).
            if (request.delivery == Delivery.Queue) forgetQueuedMode(request)
            if (request.synthetic) {
                onSyntheticSendError(pending.requestId, request, renderKey, retries, error, settled)
            } else {
                onSendError(pending.requestId, request, renderKey, retries, epoch, error)
            }
        }
    }

    /**
     * A synthetic prompt the Hub refused ([SendRequest.synthetic]): never held, never returned to
     * the composer, no queue fallback and no error row; [settled] gets the outcome. A direct one
     * (the act-mode continuation) keeps the resume after `session_not_found` and the one resend
     * after a drain, like any direct send; a steer gets neither, since the run it steers is gone.
     */
    private suspend fun onSyntheticSendError(
        requestId: String,
        request: SendRequest,
        renderKey: String?,
        retries: SendRetries,
        error: HubCommandException,
        settled: CompletableDeferred<SyntheticSendResult>?,
    ) {
        val sessionId = request.sessionId
        val direct = request.delivery == null
        when {
            // The socket dropped or a timer fired: the event stream decides the turn (HC §3.12 step 7).
            error.isLostReply() -> Unit
            direct && error.isSessionGone() && !retries.resumed -> {
                discardTurn(sessionId, requestId)
                markNotLive(sessionId)
                val resumed = resumeLocked(sessionId, request.config)
                if (resumed.isSuccess) {
                    sendNow(request, renderKey, retries.copy(resumed = true), settled)
                } else {
                    settled?.complete(SyntheticSendResult.ResumeFailed(current(sessionId).errorBanner?.message.orEmpty()))
                }
                return
            }
            direct && error.isDraining() && !retries.drainRetried && hub.awaitNotDraining(config.drainWaitMs) -> {
                discardTurn(sessionId, requestId)
                sendNow(request, renderKey, retries.copy(drainRetried = true), settled)
                return
            }
            // A refused direct prompt ran nothing: drop its turn quietly (it never had a bubble).
            direct -> discardTurn(sessionId, requestId)
            else -> mutate(sessionId) { it.copy(facts = it.facts.copy(queuedRequestIds = it.facts.queuedRequestIds - requestId)) }
        }
        settled?.complete(SyntheticSendResult.Refused(error.code, error.hubMessage))
    }

    private suspend fun onSendError(
        requestId: String,
        request: SendRequest,
        renderKey: String?,
        retries: SendRetries,
        epoch: Long,
        error: HubCommandException,
    ) {
        val sessionId = request.sessionId
        when {
            // The socket dropped or a timer fired: the event stream decides (HC §3.12 step 7).
            error.isLostReply() -> Unit
            request.delivery != null && error.isPendingPromptsUnavailable() && !retries.queueFallback -> {
                // No prompt queue on this kernel build (CA C2.6): direct sends for the life of the connection.
                mutate(sessionId) { it.copy(facts = SessionFactsReducer.onQueueUnavailable(it.facts, true)) }
                sendNow(request.copy(delivery = null), renderKey = null, retries = retries.copy(queueFallback = true))
            }
            error.isAuthorityLost() && retries.authority < MAX_AUTHORITY_RETRIES -> {
                // docs/07 §9: the Hub refused the frame because this socket's registration is gone, so
                // nothing ran. HubClient re-registers with the same clientId on a fresh socket; the
                // prompt is held and goes out once that registration is up (HF "Same clientId on a new connection").
                if (request.delivery != null) {
                    mutate(sessionId) { it.copy(facts = it.facts.copy(queuedRequestIds = it.facts.queuedRequestIds - requestId)) }
                } else if (current(sessionId).turns.none { it.requestId == requestId }) {
                    // A late reply: the reconnect already judged the turn undelivered and returned it to the draft.
                    return
                } else {
                    discardTurn(sessionId, requestId)
                }
                holdForReregister(request, retries.authority + 1, epoch)
            }
            error.isSessionGone() && !retries.resumed -> {
                // Authoritative "not live" (HC §3.12 step 4): resume once, then resend under the same bubble key.
                discardTurn(sessionId, requestId)
                markNotLive(sessionId)
                val resumed = resumeLocked(sessionId, request.config)
                if (resumed.isSuccess) {
                    sendNow(request, renderKey, retries.copy(resumed = true))
                } else {
                    returnPrompt(sessionId, request.prompt, request.images)
                }
            }
            error.isDraining() && !retries.drainRetried -> {
                // Keep the bubble; resend once the Hub stops draining (HC §3.12 step 5, docs/07 §9: one resend).
                if (hub.awaitNotDraining(config.drainWaitMs)) {
                    discardTurn(sessionId, requestId)
                    sendNow(request, renderKey, retries.copy(drainRetried = true))
                } else {
                    failTurn(sessionId, requestId, request, error)
                }
            }
            else -> failTurn(sessionId, requestId, request, error)
        }
    }

    /**
     * Parks a prompt the Hub refused for lost authority until the re-register: [flushHeld]
     * sends it on the next Connected. When that Connected already came (the reply was late, see
     * [watchReply]'s epoch) it goes out now; a session that already holds another prompt
     * returns this one to the draft rather than dropping it.
     */
    private fun holdForReregister(request: SendRequest, authorityRetries: Int, epoch: Long) {
        val sessionId = request.sessionId
        when {
            epoch != connectionEpoch && hub.connection.value is HubConnectionState.Connected ->
                scope.launch(dispatcher) { sendLocked(request, authorityRetries) }
            sessionId in held -> returnPrompt(sessionId, request.prompt, request.images)
            else -> {
                held[sessionId] = request
                heldAuthorityRetries[sessionId] = authorityRetries
            }
        }
    }

    private fun rememberQueuedMode(request: SendRequest) {
        val modes = queuedModes.getOrPut(request.sessionId) { ArrayDeque() }
        modes.addLast(request.prompt to request.mode)
        while (modes.size > MAX_QUEUED_MODES) modes.removeFirst()
    }

    private fun forgetQueuedMode(request: SendRequest) {
        val modes = queuedModes[request.sessionId] ?: return
        val index = modes.indexOfLast { it.first == request.prompt && it.second == request.mode }
        if (index >= 0) modes.removeAt(index)
        if (modes.isEmpty()) queuedModes -= request.sessionId
    }

    /** The mode of the oldest remembered queued prompt with [text], removed; null when the app did not queue it. */
    private fun takeQueuedMode(sessionId: String, text: String): PromptMode? {
        val modes = queuedModes[sessionId] ?: return null
        val index = modes.indexOfFirst { it.first == text }
        if (index < 0) return null
        val mode = modes.removeAt(index).second
        if (modes.isEmpty()) queuedModes -= sessionId
        return mode
    }

    /** Drops a turn whose send never ran (it will be re-sent): record, bubble and phase. */
    private fun discardTurn(sessionId: String, requestId: String) {
        mutate(sessionId) { s ->
            s.copy(
                facts = SessionFactsReducer.onSendRejected(s.facts, requestId),
                projection = ChatProjector.remove(s.projection, ChatProjector.localId(requestId)),
                turns = s.turns.filterNot { it.requestId == requestId },
            )
        }
        images.remove(requestId)
        persistTurns()
    }

    /** A send refused for good: the bubble stays, an error row explains (kernel text). */
    private fun failTurn(sessionId: String, requestId: String, request: SendRequest, error: HubCommandException) {
        if (request.delivery != null) {
            _signals.tryEmit(SessionSignal.SendFailed(sessionId, error.code, error.hubMessage))
            return
        }
        val now = clock.millis()
        mutate(sessionId) { s ->
            val turn = s.turns.firstOrNull { it.requestId == requestId }
            s.copy(
                facts = SessionFactsReducer.onSendRejected(s.facts, requestId),
                projection = ChatProjector.appendError(
                    s.projection, sessionId, requestId, requestId, error.hubMessage,
                    turn?.providerId, turn?.providerAuth, now,
                ),
                turns = s.turns.map { if (it.requestId == requestId) it.copy(open = false, closedAt = now, outcome = TurnOutcome.Failed) else it },
            )
        }
        persistTurns()
        _signals.tryEmit(SessionSignal.SendFailed(sessionId, error.code, error.hubMessage))
    }

    /** Stop (`run.abort`): `Stopping` until the terminal event; a failed abort restores the phase. */
    suspend fun abort(sessionId: String): Result<Unit> = withContext(dispatcher) {
        mutate(sessionId) { it.copy(facts = SessionFactsReducer.onAbortRequested(it.facts)) }
        try {
            hub.abort(sessionId)
            Result.success(Unit)
        } catch (e: HubCommandException) {
            mutate(sessionId) { it.copy(facts = SessionFactsReducer.onAbortFailed(it.facts)) }
            Result.failure(e)
        } catch (e: HubConnectionClosedException) {
            mutate(sessionId) { it.copy(facts = SessionFactsReducer.onAbortFailed(it.facts)) }
            Result.failure(e)
        }
    }

    /**
     * The prompt "Resend" would send for the interrupted row [errorRowId] (HC §1.3.4): the turn's
     * own prompt, else the last user row at or after the turn's slice; null hides the button.
     * Also null once a newer user message exists.
     */
    fun resendPrompt(sessionId: String, errorRowId: String): String? {
        val s = flows[sessionId]?.value ?: return null
        val messages = s.messages
        val rowIndex = messages.indexOfFirst { it.id == errorRowId }
        if (rowIndex < 0) return null
        if (messages.drop(rowIndex + 1).any { it.role == Role.User }) return null
        val row = messages[rowIndex]
        val turn = row.meta.requestId?.let { id -> s.turns.firstOrNull { it.requestId == id } }
        turn?.prompt?.takeIf { it.isNotBlank() }?.let { return it }
        return messages.subList(0, rowIndex).lastOrNull { it.role == Role.User }
            ?.let { UserInputFormat.formatDisplayUserInput(it.content) }
            ?.takeIf { it.isNotBlank() }
    }

    /** Resend after an interruption (HC §3.4.7): resume (a restarted Hub never has it live), then send. */
    suspend fun resend(sessionId: String, errorRowId: String, config: SessionConfigInput? = null): SendOutcome = withContext(dispatcher) {
        val prompt = resendPrompt(sessionId, errorRowId) ?: return@withContext SendOutcome.ResumeFailed("")
        val requestId = current(sessionId).messages.firstOrNull { it.id == errorRowId }?.meta?.requestId
        val turn = requestId?.let { id -> current(sessionId).turns.firstOrNull { it.requestId == id } }
        val resumed = resumeLocked(sessionId, config)
        if (resumed.isFailure) return@withContext SendOutcome.ResumeFailed(current(sessionId).errorBanner?.message.orEmpty())
        val mode = PromptMode.parse(turn?.mode)
        sendNow(
            SendRequest(sessionId, prompt, mode, images = requestId?.let { images[it] }.orEmpty(), config = config, synthetic = turn?.synthetic == true),
            renderKey = null,
            retries = SendRetries(resumed = true),
        )
    }

    // ---- interruption ---------------------------------------------------------------------------

    /** Marks the session's open turn interrupted (`InterruptionDetector`, docs/07 §4.1). */
    suspend fun markInterrupted(sessionId: String, requestId: String? = null) = withContext(dispatcher) { markInterruptedLocked(sessionId, requestId) }

    private fun markInterruptedLocked(sessionId: String, requestId: String?) {
        val s = current(sessionId)
        val turn = s.turns.lastOrNull { it.open && (requestId == null || it.requestId == requestId) } ?: return
        val now = clock.millis()
        val provider = turn.providerId ?: s.facts.providerId
        liveSet() -= sessionId
        mutate(sessionId) { st ->
            st.copy(
                facts = SessionFactsReducer.markInterrupted(st.facts),
                projection = ChatProjector.appendInterrupted(
                    st.projection, sessionId, turn.requestId, turn.requestId, "",
                    provider, turn.providerAuth ?: providerAuth.resolve(provider), now,
                ),
                turns = st.turns.map { if (it.requestId == turn.requestId) it.copy(open = false, closedAt = now, outcome = TurnOutcome.Interrupted) else it },
                // A restarted Hub does not have the session live (FX 05).
                live_ = false,
                persistedHint = true,
            )
        }
        persistTurns()
        _signals.tryEmit(SessionSignal.Interrupted(sessionId, turn.requestId))
        requestReconcile(sessionId)
    }

    // ---- the pump ---------------------------------------------------------------------------------

    /** Applies one event (internal: called by the pump; public for fixture replay in tests). */
    suspend fun dispatch(event: HubEventEnvelope) = withContext(dispatcher) { dispatchLocked(event) }

    private fun dispatchLocked(event: HubEventEnvelope) {
        event.sequence?.let { if (it > _appliedSequence.value) _appliedSequence.value = it }
        val sessionId = event.sessionId ?: return
        val before = current(sessionId)
        val seq = event.sequence
        if (seq != null && seq <= before.facts.lastSequence) return
        val now = clock.millis()
        val ownRequestIds = before.turns.filter { it.open && it.ours }.mapTo(HashSet()) { it.requestId }
        val facts = SessionFactsReducer.reduce(before.facts, event, ReduceContext(hub.clientId, ownRequestIds, now))
        var projection = ChatProjector.project(
            before.projection,
            event,
            ProjectContext(
                sessionId = sessionId,
                now = now,
                queuedRequestIds = before.facts.queuedRequestIds,
                openTurn = before.openTurn,
                sessionProviderId = before.facts.providerId,
                authFor = providerAuth::resolve,
                previousRunSettled = before.facts.status in SETTLED_STATUSES,
            ),
        )
        var turns = before.turns
        var persistedHint = before.persistedHint
        var createdAutoApprove = before.createdAutoApprove
        var turnsChanged = false
        val p = event.payload
        val at = event.timestamp ?: now
        when (event.event) {
            HubEvents.RUN_STARTED -> {
                val requestId = p?.str("requestId")
                if (requestId != null) liveSet() += sessionId
                if (requestId != null && requestId !in before.facts.queuedRequestIds) {
                    persistedHint = false
                    val existing = turns.firstOrNull { it.requestId == requestId }
                    // Replayed after a cold start (HubClient.replayFloor): our send's local row died
                    // with the old process, so it comes back from the persisted prompt.
                    if (existing != null && existing.ours && existing.prompt.isNotBlank() &&
                        projection.messages.none { it.role == Role.User && it.meta.requestId == requestId }
                    ) {
                        projection = ChatProjector.appendLocalUser(projection, sessionId, requestId, existing.prompt, emptyList(), existing.sentAt)
                    }
                    turns = if (existing != null) {
                        turns.map { if (it.requestId == requestId) it.copy(started = true, startedAtSequence = seq ?: it.startedAtSequence) else it }
                    } else if (turns.none { it.open }) {
                        // Started elsewhere (the TUI): prompt unknown until reconciliation.
                        turns + TurnRecord(
                            requestId = requestId, sessionId = sessionId, startedAtSequence = seq, open = true, started = true,
                            hubLifetimeKey = hubLifetimeKey(), sentAt = at, ours = false, providerId = before.facts.providerId,
                        )
                    } else turns
                    turnsChanged = true
                }
            }
            HubEvents.SESSION_PENDING_PROMPT_SUBMITTED -> {
                val prompt = p?.obj("prompt")
                val promptId = prompt?.str("id")
                // A drained prompt is a turn of its own (CA C2.6): the settled direct run before it is
                // closed here, its `run.completed` follows and only refines the outcome. A steered
                // prompt joins the running loop and opens nothing.
                val settled = before.facts.status in SETTLED_STATUSES
                val queuedKey = promptId?.let(SessionFactsReducer::queuedRunKey)
                val text = prompt?.str("prompt").orEmpty()
                // The mode the app queued it in; a prompt another client queued runs in the session's mode.
                val mode = takeQueuedMode(sessionId, text) ?: PromptMode.parse(before.facts.mode)
                if (prompt != null && queuedKey != null && turns.none { it.requestId == queuedKey } && (settled || turns.none { it.open })) {
                    turns = turns.map { if (it.open) it.copy(open = false, closedAt = at, outcome = TurnOutcome.Completed) else it }
                    turns = turns + TurnRecord(
                        requestId = SessionFactsReducer.queuedRunKey(promptId), sessionId = sessionId, startedAtSequence = seq,
                        prompt = text, mode = mode.wire, open = true, started = true, hubLifetimeKey = hubLifetimeKey(),
                        sentAt = at, ours = false, providerId = before.facts.providerId,
                    )
                    turnsChanged = true
                }
            }
            HubEvents.SESSION_CREATED -> {
                liveSet() += sessionId
                persistedHint = false
                // Any client's create fixes the new runtime's policy (decided A1, SessionState.createdAutoApprove).
                p?.obj("session")?.obj("metadata")?.bool(AUTO_APPROVE_TOOLS_KEY)?.let { createdAutoApprove = it }
                p?.obj("session")?.obj("metadata")?.str(SessionCreateBuilder.CLIENT_CREATE_ID)
                    ?.takeIf { it in pendingCreates }
                    ?.let { createdByNonce[it] = sessionId }
            }
        }
        // The terminal of the direct run a drained prompt already followed (see SESSION_PENDING_PROMPT_SUBMITTED).
        val endingPrevious = before.projection.endingRequestId?.takeIf { event.event in TERMINAL_EVENTS }
        val shutdown = InterruptionDetector.isShutdownProjection(event)
        val interruptedByShutdown = shutdown && endingPrevious == null && turns.any { it.open }
        val terminal = event.event in TERMINAL_EVENTS
        var ended: RunEnded? = null
        if (terminal && !interruptedByShutdown) {
            val outcome = when (event.event) {
                HubEvents.RUN_COMPLETED -> if (p?.str("reason") == "completed") TurnOutcome.Completed else TurnOutcome.Ended
                HubEvents.RUN_FAILED -> TurnOutcome.Failed
                HubEvents.RUN_ABORTED -> TurnOutcome.Aborted
                else -> TurnOutcome.Interrupted
            }
            // A11.9: the result-bearing terminal carries the turn's totals for Verbose.
            val result = p?.obj("result")
            if (endingPrevious != null) {
                turns = turns.map { if (it.requestId == endingPrevious) TurnRecords.ended(it.copy(outcome = outcome), result, projection.messages) else it }
                turnsChanged = true
            } else if (turns.any { it.open }) {
                val closing = turns.last { it.open }
                // Whatever closed, the session's first run is over (trial row 16).
                val createTitle = firstRunTitles.remove(sessionId)?.takeIf { it.requestId == closing.requestId }?.title
                if (outcome != TurnOutcome.Interrupted) ended = RunEnded(sessionId, closing.requestId, closing.ours, outcome, createTitle)
                turns = turns.map { if (it.open) TurnRecords.ended(it.copy(open = false, closedAt = at, outcome = outcome), result, projection.messages) else it }
                turnsChanged = true
            }
        }
        // A drained queued turn has no `run.*`: it ends when the loop goes idle again (CA C2.6).
        val queuedEnded = event.event == HubEvents.SESSION_UPDATED && before.facts.status.isRunning &&
            facts.status in SETTLED_STATUSES &&
            turns.any { it.open && SessionFactsReducer.isQueuedRunKey(it.requestId) && (seq == null || (it.startedAtSequence ?: 0L) < seq) }
        if (queuedEnded) {
            // The Hub reports a stopped loop as plain "idle"; our own Stop is what makes it aborted.
            val outcome = when {
                before.facts.runPhase == RunPhase.Stopping -> TurnOutcome.Aborted
                facts.status == SessionStatus.Failed || facts.status == SessionStatus.Error -> TurnOutcome.Failed
                facts.status == SessionStatus.Cancelled -> TurnOutcome.Aborted
                else -> TurnOutcome.Completed
            }
            turns = turns.map { if (it.open && SessionFactsReducer.isQueuedRunKey(it.requestId)) it.copy(open = false, closedAt = at, outcome = outcome) else it }
            projection = ChatProjector.endQueuedRun(projection)
            turnsChanged = true
        }
        write(flowFor(sessionId), before.copy(
            facts = facts,
            projection = projection,
            turns = turns,
            persistedHint = persistedHint,
            createdAutoApprove = createdAutoApprove,
            live_ = when (event.event) {
                HubEvents.RUN_INTERRUPTED -> false
                // docs/07 §4.2: this client's attach / detach (the attached record lists us as a participant).
                HubEvents.SESSION_ATTACHED -> if (namesThisClient(p?.obj("session"))) true else before.live_
                HubEvents.SESSION_DETACHED -> if (p?.str("clientId") == hub.clientId) false else before.live_
                else -> before.live_
            },
            touchedAt = now,
        ))
        if (turnsChanged) persistTurns()
        ended?.let { _runEnds.tryEmit(it) }
        when {
            interruptedByShutdown -> markInterruptedLocked(sessionId, null)
            terminal -> {
                if (event.event == HubEvents.RUN_INTERRUPTED) _signals.tryEmit(SessionSignal.Interrupted(sessionId, before.openTurn?.requestId))
                requestReconcile(sessionId)
            }
            queuedEnded -> requestReconcile(sessionId)
        }
    }

    /**
     * Trial row 16: the title [state]'s session already carries when [request] is its first prompt
     * (no prompt in the record, no user message, no turn yet), else null. Core 0.0.91 persists a
     * brand-new session only at its first prompt and titles it after that prompt there, over the
     * create's `metadata.title` (`createRootSessionWithArtifacts` → `resolveMetadataWithTitle`);
     * a session seeded with messages (a fork) is persisted at its create and keeps its title.
     */
    private fun createTitleOf(state: SessionState, request: SendRequest): String? {
        if (request.synthetic) return null
        val title = state.facts.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (state.facts.metadata.containsKey(PROMPT_METADATA_KEY) || state.turns.isNotEmpty()) return null
        if (state.messages.any { it.role == Role.User }) return null
        return title
    }

    private fun namesThisClient(session: JsonObject?): Boolean =
        session?.arr("participants")?.any { (it as? JsonObject)?.str("clientId") == hub.clientId } == true

    // ---- reconciliation -------------------------------------------------------------------------

    /** After a terminal event: `session.messages` + key-inheriting replacement (open or seeded sessions only). */
    private fun requestReconcile(sessionId: String) {
        val s = current(sessionId)
        val seeded = s.seed is SeedState.Seeded
        if (sessionId !in openIds && !seeded) {
            // Not on screen and never loaded: drop the closed turns' live rows; the next open seeds.
            val openRequestIds = openRequestIds(s)
            mutate(sessionId) { st ->
                st.copy(projection = st.projection.copy(messages = st.projection.messages.filter { it.role !in TRANSCRIPT_ROLES || it.meta.requestId in openRequestIds }))
            }
            return
        }
        if (sessionId in reconciling) {
            needsReconcile += sessionId
            return
        }
        scope.launch(dispatcher) { reconcileNow(sessionId, seeding = false) }
    }

    private suspend fun reconcileNow(sessionId: String, seeding: Boolean) {
        if (!reconciling.add(sessionId)) {
            needsReconcile += sessionId
            return
        }
        try {
            val reply = try {
                hub.messages(sessionId)
            } catch (e: HubCommandException) {
                // A failed reply leaves live untouched and retries on the next terminal event or reconnect.
                staleHistory += sessionId
                if (seeding) {
                    mutate(sessionId) { it.copy(seed = SeedState.Failed(e.hubMessage), errorBanner = SessionError(SessionError.Kind.History, e.hubMessage)) }
                }
                return
            } catch (_: HubConnectionClosedException) {
                staleHistory += sessionId
                if (seeding) mutate(sessionId) { it.copy(seed = if (it.canonical.isEmpty()) SeedState.NotSeeded else SeedState.Seeded(_appliedSequence.value)) }
                return
            }
            val mapping = HistoryMapper.map(sessionId, HubPayloads.messagesOf(reply))
            var answersAnchored = false
            mutate(sessionId) { s ->
                val result = Reconciler.reconcile(s.canonical, s.canonicalCount, s.projection.messages, mapping, openRequestIds(s), partialRequestIds(s))
                val prompts = backfillPrompts(s.turns, result.canonical, s.canonicalCount, mapping)
                // A11.9: a finished turn's answer is a history row now; its id keeps the stats across a cold start.
                val anchored = TurnRecords.anchorAnswers(prompts, result.canonical)
                answersAnchored = anchored !== prompts
                s.copy(
                    canonical = result.canonical,
                    canonicalCount = result.canonicalCount,
                    projection = s.projection.copy(messages = result.live),
                    seed = SeedState.Seeded(_appliedSequence.value),
                    turns = anchored,
                    errorBanner = s.errorBanner?.takeIf { it.kind != SessionError.Kind.History },
                )
            }
            if (answersAnchored) persistTurns()
            staleHistory -= sessionId
        } finally {
            reconciling.remove(sessionId)
            if (needsReconcile.remove(sessionId) && hub.connection.value is HubConnectionState.Connected) {
                scope.launch(dispatcher) { reconcileNow(sessionId, seeding = false) }
            }
        }
    }

    /** Turns started elsewhere learn their prompt from the slice (for Resend after a cold start). */
    private fun backfillPrompts(turns: List<TurnRecord>, canonical: List<ChatMessage>, previousCount: Int, mapping: HistoryMapping): List<TurnRecord> {
        if (turns.none { !it.ours && it.prompt.isEmpty() }) return turns
        val sliceUser = canonical.indices
            .filter { mapping.rawIndex.getOrNull(it)?.let { raw -> raw >= previousCount } == true }
            .map { canonical[it] }
            .lastOrNull { it.role == Role.User }
            ?: return turns
        val text = UserInputFormat.formatDisplayUserInput(sliceUser.content)
        val target = turns.lastOrNull { !it.ours && it.prompt.isEmpty() } ?: return turns
        return turns.map { if (it === target) it.copy(prompt = text) else it }
    }

    /** Turns the user stopped or a Hub restart cut short: the daemon kept only part of their output. */
    private fun partialRequestIds(s: SessionState): Set<String> =
        s.turns.filter { !it.open && (it.outcome == TurnOutcome.Aborted || it.outcome == TurnOutcome.Interrupted) }.mapTo(HashSet()) { it.requestId }

    private fun openRequestIds(s: SessionState): Set<String> =
        s.turns.filter { it.open }.mapTo(HashSet()) { it.requestId }.apply { s.facts.activeRun?.requestId?.let { add(it) } }

    // ---- connection --------------------------------------------------------------------------

    private suspend fun onConnection(state: HubConnectionState) {
        if (state !is HubConnectionState.Connected) {
            if (connectedGeneration != null) for (id in flows.keys) mutate(id) { it.copy(live_ = false) }
            connectedGeneration = null
            // Step 6: a held prompt goes back to the draft only when the reconnect ended without a
            // kernel: the client gave up, or the coordinator closed it on purpose (HubClient
            // publishes Disconnected only from disconnect(), never while it replaces a reconnect
            // loop). Reconnecting and Connecting keep it held.
            if (state is HubConnectionState.Unavailable || state is HubConnectionState.Disconnected) returnHeld()
            return
        }
        val previous = connectedGeneration
        if (previous == state.generation) return
        // A drop and the reconnect after it both happened while this collector was busy: the
        // drop's part (sessions not live) first, then the reconnect work below as usual.
        if (previous != null) for (id in flows.keys) mutate(id) { it.copy(live_ = false) }
        connectedGeneration = state.generation
        connectionEpoch += 1
        // A new daemon lifetime marks every session not live before anything is re-attached or sent.
        liveSet()
        for (id in flows.keys) mutate(id) { it.copy(facts = SessionFactsReducer.onReconnected(it.facts)) }
        // Open sessions re-attach through the coordinator's hook; their seeds catch up here.
        for (id in openIds) if (current(id).seed !is SeedState.Seeded || id in staleHistory) scope.launch(dispatcher) { reconcileNow(id, seeding = current(id).canonical.isEmpty()) }
        // Only turns opened before this connection are judged: held prompts flushed now and
        // sends made during the drain wait were written on this socket and will run.
        val candidates = HashMap<String, Set<String>>()
        for ((id, flow) in flows) {
            val open = flow.value.turns.filter { it.open }.mapTo(HashSet()) { it.requestId }
            if (open.isNotEmpty()) candidates[id] = open
        }
        scope.launch(dispatcher) { flushHeld() }
        scope.launch(dispatcher) { detectInterruptions(candidates) }
    }

    /**
     * HC §1.3.4 (a) after the replay drained, plus undelivered sends (§3.12 step 7), for the
     * [candidates] (session id → requestIds of the turns open when the connection came up).
     */
    private suspend fun detectInterruptions(candidates: Map<String, Set<String>>) {
        if (candidates.isEmpty()) return
        // The replay may still deliver the turn's real terminal event, which wins (§1.3.4 a).
        awaitReplayDrained()
        val key = hubLifetimeKey()
        val unresolved = HashMap<String, Set<String>>()
        for ((id, requestIds) in candidates) {
            fun judged() = current(id).turns.filter { it.open && it.requestId in requestIds }
            val open = judged()
            if (open.isEmpty()) continue
            InterruptionDetector.interruptedByRestart(open, key).forEach { markInterruptedLocked(id, it.requestId) }
            InterruptionDetector.notDelivered(judged(), key).forEach { turn ->
                val prompt = turn.prompt
                val turnImages = images[turn.requestId].orEmpty()
                discardTurn(id, turn.requestId)
                // A synthetic prompt (the act-mode continuation) is the app's own: never the user's draft.
                if (!turn.synthetic) returnPrompt(id, prompt, turnImages)
            }
            judged().takeIf { it.isNotEmpty() }?.let { left -> unresolved[id] = left.mapTo(HashSet()) { it.requestId } }
        }
        // Still open after the drain without a terminal event: its `run.*` may lie before the
        // replay window (a truncated replay, a cold start). The Hub's record decides (docs/07 rule 2).
        for ((id, requestIds) in unresolved) verifyWithHub(id, requestIds)
    }

    /** Until the store applied the replay up to `hub.status.eventLog.lastSequence`, or the drain timeout. */
    private suspend fun awaitReplayDrained() {
        val target = hub.hubStatus.value?.lastSequence ?: 0L
        withTimeoutOrNull(config.replayDrainTimeoutMs) { _appliedSequence.first { it >= target } }
    }

    /**
     * Re-reads a session whose terminal `run.*` may lie outside what the stream delivered
     * (SS §1.2.1 item 2: statuses come from the Hub's record after a truncated replay; docs/07
     * rule 2: a missing event never leaves the UI stuck). [judged] are the requestIds of the
     * turns open when the gap was found. One verification per session at a time.
     */
    private suspend fun verifyWithHub(sessionId: String, judged: Set<String>) {
        if (!verifying.add(sessionId)) return
        try {
            val seqBefore = current(sessionId).facts.lastSequence
            val record = try {
                hub.get(sessionId)
            } catch (e: HubCommandException) {
                if (e.isSessionGone()) applyVerified(sessionId, null, judged, seqBefore, reconcile = false)
                return
            } catch (_: HubConnectionClosedException) {
                // The next connect judges the still-open turns again (detectInterruptions).
                return
            }
            applyVerified(sessionId, record, judged, seqBefore, reconcile = true)
        } finally {
            verifying.remove(sessionId)
        }
    }

    /**
     * Applies a verification record ([record] null: the Hub does not know the session). When
     * the record says the loop settled, the run ends: the [judged] turns close as
     * [TurnOutcome.Ended] (except those the reconnect detector judges), approvals and questions
     * go, and [reconcile] pulls the transcript. Events that arrived while the reply was on its
     * way are newer than the record, so the record is then ignored.
     */
    private fun applyVerified(sessionId: String, record: JsonObject?, judged: Set<String>, seqBefore: Long, reconcile: Boolean) {
        val s = current(sessionId)
        if (s.facts.lastSequence != seqBefore) return
        val facts = if (record == null) s.facts else SessionFactsReducer.fromRecord(sessionId, record.obj("session"), record.obj("snapshot"), s.facts)
        val key = hubLifetimeKey()
        val closing = s.turns
            .filter { it.open && it.requestId in judged && !InterruptionDetector.judgedOnReconnect(it, key) }
            .mapTo(HashSet()) { it.requestId }
        val runLeft = s.facts.runPhase.isBusy || s.facts.activeRun != null || s.facts.hasPendingInput
        val settled = record == null || facts.status in SETTLED_STATUSES
        if (!settled || (closing.isEmpty() && !runLeft)) {
            if (facts != s.facts) mutate(sessionId) { it.copy(facts = facts) }
            return
        }
        val now = clock.millis()
        if (record == null) liveSet() -= sessionId
        val inLiveSet = sessionId in liveSet()
        mutate(sessionId) { st ->
            val turns = st.turns.map { if (it.requestId in closing) it.copy(open = false, closedAt = now, outcome = TurnOutcome.Ended) else it }
            val nextFacts = SessionFactsReducer.onVerifiedSettled(facts, now)
            val ended = nextFacts.status == SessionStatus.Completed || nextFacts.status == SessionStatus.Cancelled
            st.copy(
                facts = nextFacts,
                projection = ChatProjector.endVerifiedRun(st.projection),
                turns = turns,
                seed = if (record == null) SeedState.Missing else st.seed,
                live_ = if (record == null) false else st.live_,
                persistedHint = st.persistedHint || (!inLiveSet && ended && turns.none { it.open }),
            )
        }
        if (closing.isNotEmpty()) persistTurns()
        if (reconcile && record != null) requestReconcile(sessionId)
    }

    private fun flushHeld() {
        val pending = held.values.toList()
        val retries = HashMap(heldAuthorityRetries)
        held.clear()
        heldAuthorityRetries.clear()
        pending.forEach { request -> scope.launch(dispatcher) { sendLocked(request, retries[request.sessionId] ?: 0) } }
    }

    private fun returnHeld() {
        val pending = held.values.toList()
        held.clear()
        heldAuthorityRetries.clear()
        pending.forEach { returnPrompt(it.sessionId, it.prompt, it.images, kernelDown = true) }
    }

    /**
     * The replay skipped events. When a session's last sequence is above the Hub's
     * `lastSequence`, the event log restarted (fresh Hub data dir, `HubClient` reset its
     * cursor): sequences start over, so that session's and the store's applied sequence reset
     * (or every new event would be dropped as already seen) and open sessions reseed.
     *
     * Either way a session's terminal `run.*` may be among the skipped events (one long TUI
     * turn alone outnumbers the 2000-event replay budget), so every session in memory with a
     * gap, a busy run phase or an open turn is re-read with `session.get` once the replay
     * drained (SS §1.2.1 item 2); a reseed does that with its own `session.get`.
     */
    private fun onReplayTruncated(notice: HubClient.Notice.ReplayTruncated) {
        var logRestarted = false
        for ((id, flow) in flows) {
            val s = flow.value
            when {
                s.facts.lastSequence > notice.lastSequence -> {
                    logRestarted = true
                    mutate(id) { it.copy(facts = it.facts.copy(lastSequence = 0), replayGap = true) }
                }
                s.facts.lastSequence in 1 until notice.fromSequence -> mutate(id) { it.copy(replayGap = true) }
            }
        }
        if (_appliedSequence.value > notice.lastSequence) {
            logRestarted = true
            _appliedSequence.value = notice.fromSequence
        }
        val suspects = HashMap<String, Set<String>>()
        for ((id, flow) in flows) {
            val s = flow.value
            if (s.replayGap || s.facts.runPhase.isBusy || s.openTurn != null || s.facts.hasPendingInput) {
                suspects[id] = s.turns.filter { it.open }.mapTo(HashSet()) { it.requestId }
            }
        }
        val reseeded = if (logRestarted) openIds.toSet() else emptySet()
        for (id in reseeded) scope.launch(dispatcher) { seedLocked(id, verify = suspects[id]) }
        val toVerify = suspects.filterKeys { it !in reseeded }
        if (toVerify.isNotEmpty()) {
            scope.launch(dispatcher) {
                awaitReplayDrained()
                for ((id, requestIds) in toVerify) verifyWithHub(id, requestIds)
            }
        }
    }

    // ---- persistence and eviction --------------------------------------------------------------

    private val restored = CompletableDeferred<Unit>()

    /**
     * Replay from just before the earliest turn that was open when the last process died.
     * An expired open turn (`TurnRecords.isExpired`) never sets the floor: its terminal is long
     * gone from the replay window, and `session.get` verifies it after connect instead.
     */
    private suspend fun replayFloor(): Long? = withContext(dispatcher) {
        val now = clock.millis()
        flows.values.flatMap { it.value.turns }
            .filter { it.open && !TurnRecords.isExpired(it, now) }
            .mapNotNull { it.startedAtSequence }
            .minOrNull()?.let { it - 1 }
    }

    // ---- stop notices (A17) ---------------------------------------------------------------------------

    /**
     * Shows and keeps [notice]: the app stopped a run at the mistake limit ([ClientContributions]).
     * One per mistake-limit request; the row joins the session's live rows, where reconciliation
     * keeps status rows, and every state built later for the session starts with it ([flowFor]).
     */
    suspend fun addStopNotice(notice: StopNotice) = withContext(dispatcher) {
        val current = stopNotices[notice.sessionId].orEmpty()
        if (current.any { it.requestId == notice.requestId }) return@withContext
        stopNotices[notice.sessionId] = StopNotices.prune(current + notice)
        pruneStopNotices()
        val row = StopNotices.row(notice)
        mutate(notice.sessionId) { s ->
            if (s.projection.messages.any { it.id == row.id }) s else s.copy(projection = s.projection.copy(messages = s.projection.messages + row))
        }
        persistStopNotices()
    }

    /** The session was deleted (F5) or the Hub no longer has it: its stop notices go. */
    suspend fun forgetStopNotices(sessionId: String) = withContext(dispatcher) { dropStopNoticesLocked(sessionId) }

    private fun dropStopNoticesLocked(sessionId: String) {
        if (stopNotices.remove(sessionId) == null) return
        flows[sessionId]?.let { flow ->
            val s = flow.value
            if (s.projection.messages.any(StopNotices::isStopNotice)) {
                write(flow, s.copy(projection = s.projection.copy(messages = s.projection.messages.filterNot(StopNotices::isStopNotice))))
            }
        }
        persistStopNotices()
    }

    /** Keeps the newest [StopNotices.MAX_NOTICES] across all sessions. */
    private fun pruneStopNotices() {
        val all = stopNotices.values.flatten()
        if (all.size <= StopNotices.MAX_NOTICES) return
        val kept = StopNotices.prune(all).mapTo(HashSet()) { it.requestId }
        for ((id, list) in stopNotices.entries.toList()) {
            val left = list.filter { it.requestId in kept }
            if (left.isEmpty()) stopNotices.remove(id) else stopNotices[id] = left
        }
    }

    private suspend fun restoreStopNotices() {
        val restored = runCatching { stopNoticeStore.load() }.getOrDefault(emptyList())
        StopNotices.prune(restored).groupBy { it.sessionId }.forEach { (sessionId, notices) ->
            val merged = StopNotices.prune(notices + stopNotices[sessionId].orEmpty())
            stopNotices[sessionId] = merged
            // A state built before the restore finished gets the rows now.
            flows[sessionId]?.let { flow ->
                val s = flow.value
                val missing = merged.map(StopNotices::row).filter { row -> s.projection.messages.none { it.id == row.id } }
                if (missing.isNotEmpty()) write(flow, s.copy(projection = s.projection.copy(messages = s.projection.messages + missing)))
            }
        }
    }

    private fun persistStopNotices() {
        persistNoticesAgain = true
        if (persistNoticesJob?.isActive == true) return
        persistNoticesJob = scope.launch(dispatcher) {
            while (persistNoticesAgain) {
                persistNoticesAgain = false
                runCatching { stopNoticeStore.save(StopNotices.prune(stopNotices.values.flatten())) }
            }
        }
    }

    private suspend fun restoreTurns() {
        val restored = runCatching { turnStore.load() }.getOrDefault(emptyList())
        val now = clock.millis()
        TurnRecords.prune(restored, now).groupBy { it.sessionId }.forEach { (sessionId, records) ->
            mutate(sessionId) { s -> s.copy(turns = (records + s.turns).distinctBy { it.requestId }) }
        }
    }

    private fun persistTurns() {
        persistAgain = true
        if (persistJob?.isActive == true) return
        persistJob = scope.launch(dispatcher) {
            while (persistAgain) {
                persistAgain = false
                val all = flows.values.flatMap { it.value.turns }
                runCatching { turnStore.save(TurnRecords.prune(all, clock.millis())) }
            }
        }
    }

    /**
     * Drops states that are not open, not pending and untouched for [Config.evictAfterMs]
     * (docs/07 §4.1). "Pending" covers an open turn, a held send, a busy run phase or an
     * active run, and an approval or question waiting for the user: the keep-alive and the
     * notifier read those from [notifyFacts], which loses an evicted session.
     */
    private fun evict() {
        val now = clock.millis()
        val evicted = ArrayList<String>()
        for ((id, flow) in flows.entries.toList()) {
            val s = flow.value
            val idle = now - s.touchedAt >= config.evictAfterMs
            val pending = s.openTurn != null || id in held || s.facts.runPhase.isBusy || s.facts.activeRun != null || s.facts.hasPendingInput
            if (id !in openIds && !pending && idle && flow.subscriptionCount.value == 0) {
                flows.remove(id)
                s.turns.forEach { images.remove(it.requestId) }
                evicted += id
            }
        }
        if (evicted.isNotEmpty()) _notifyFacts.update { it - evicted.toSet() }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * The live set of the connected daemon (HC §3.5). Only a change to a *different* known
     * `hubLifetimeKey` starts an empty one: while the key is unknown (`Starting`, `HubDown`, a
     * reconnect in progress) the set is kept, so events still drained in that window and a
     * reconnect to the same daemon leave this lifetime's live set intact. The first key a
     * process sees adopts the set without judging anything (cold start: the seed decides).
     */
    private fun liveSet(): MutableSet<String> {
        val key = hubLifetimeKey() ?: return liveIds
        if (key != liveIdsKey) {
            val previous = liveIdsKey
            liveIdsKey = key
            if (previous != null) {
                liveIds.clear()
                onNewLifetime()
            }
        }
        return liveIds
    }

    /**
     * A different daemon lifetime is up: nothing is live on it until it is created, resumed or
     * run there (HC §3.5), and `session.attach` succeeding does not change that (FX 05 item 3).
     * Every session in memory gets the not-live hint, so the next send resumes first instead of
     * writing `send_input` into `session_not_found` (docs/07 §14 "Liveness before sending").
     * Events of this lifetime (`session.created`, `run.started`) and a resume clear it again.
     * The runtimes of the old lifetime are gone, and with them their created tool policies.
     * `touchedAt` is left alone: a lifetime change is not a use of the session.
     */
    private fun onNewLifetime() {
        for (flow in flows.values) {
            val s = flow.value
            if (s.live_ || !s.persistedHint || s.createdAutoApprove != null) {
                flow.value = s.copy(live_ = false, persistedHint = true, createdAutoApprove = null)
            }
        }
    }

    /** `session.attach` succeeded: live unless the session carries the not-live hint outside this lifetime's live set. */
    private fun onAttachSucceeded(sessionId: String) {
        val live = sessionId in liveSet() || !current(sessionId).persistedHint
        mutate(sessionId) { it.copy(live_ = live) }
    }

    private fun flowFor(sessionId: String): MutableStateFlow<SessionState> =
        flows.computeIfAbsent(sessionId) {
            val notices = stopNotices[sessionId].orEmpty().map(StopNotices::row)
            MutableStateFlow(SessionState(sessionId, projection = LiveProjection(messages = notices), touchedAt = clock.millis()))
        }

    private fun current(sessionId: String): SessionState = flowFor(sessionId).value

    private inline fun mutate(sessionId: String, transform: (SessionState) -> SessionState) {
        val flow = flowFor(sessionId)
        write(flow, transform(flow.value).copy(touchedAt = clock.millis()))
    }

    /**
     * Every state write goes through here, so [notifyFacts] follows it; the map is copied only when
     * the facts changed. [SessionState.runtimeAutoApprove] follows the runtime's created policy
     * ([SessionState.createdAutoApprove]) while one is known, else a record that carries
     * `metadata.autoApproveTools`; it outlives a later record without it (decided A1). A record
     * never overrides a created policy: core stamps a resume's policy only in memory, and the
     * run-end records carry the creation-time value again.
     */
    private fun write(flow: MutableStateFlow<SessionState>, next: SessionState) {
        val stamped = next.createdAutoApprove ?: next.facts.metadata.bool(AUTO_APPROVE_TOOLS_KEY)
        val value = if (stamped != null && stamped != next.runtimeAutoApprove) next.copy(runtimeAutoApprove = stamped) else next
        flow.value = value
        val facts = NotifyFacts.of(value)
        _notifyFacts.update { map -> if (map[value.sessionId] == facts) map else map + (value.sessionId to facts) }
    }

    private companion object {
        /** How long the first subscribe waits for the persisted turns (replay floor). */
        const val RESTORE_WAIT_MS = 2_000L

        /** A prompt is held for at most this many authority-loss re-registers before the error row shows. */
        const val MAX_AUTHORITY_RETRIES = 2

        /** Queued prompts whose mode is remembered per session (an edited or removed one is never drained). */
        const val MAX_QUEUED_MODES = 32

        /** First prompts into titled sessions remembered until their run ends ([firstRunTitles]). */
        const val MAX_FIRST_RUN_TITLES = 32
        val TERMINAL_EVENTS = setOf(HubEvents.RUN_COMPLETED, HubEvents.RUN_FAILED, HubEvents.RUN_ABORTED, HubEvents.RUN_INTERRUPTED)
        val TRANSCRIPT_ROLES = setOf(Role.User, Role.Assistant, Role.Tool)

        /** The agent loop is not running: a drained prompt starts a turn, an open queued turn ends. */
        val SETTLED_STATUSES = setOf(SessionStatus.Idle, SessionStatus.Completed, SessionStatus.Cancelled, SessionStatus.Failed, SessionStatus.Error)
    }
}
