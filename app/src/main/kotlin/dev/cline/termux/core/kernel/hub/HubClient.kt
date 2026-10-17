package dev.cline.termux.core.kernel.hub

import dev.cline.termux.core.kernel.HubLiveStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the socket to the Hub stands; mirrors the desktop client's transport states. */
sealed interface HubConnectionState {
    /**
     * No socket and no loop: the initial state, or closed on purpose by [HubClient.disconnect]
     * (the coordinator's stop, disconnect, restart and doctor fix). Never published while
     * [HubClient.connect] replaces a reconnect loop that is still running.
     */
    data object Disconnected : HubConnectionState
    data object Connecting : HubConnectionState

    /**
     * Registered and subscribed. [generation] identifies this connection (it grows with every
     * successful registration of the process), so a collector that missed the states in between
     * (a conflated `StateFlow` read by a busy dispatcher) still tells a reconnect from the same
     * connection: a new generation always means "re-register happened, redo the reconnect work".
     */
    data class Connected(val hubUrl: String, val generation: Long = 0L) : HubConnectionState
    data class Reconnecting(val attempt: Int, val lastError: String?) : HubConnectionState

    /** Gave up ([HubClient.maxReconnectAttempts] consecutive failures, or a 401 on a reconnect); the coordinator takes over. */
    data class Unavailable(val reason: String, val unauthorized: Boolean = false) : HubConnectionState
}

/** What the client registers as; `clientId` is stable per install and never rotated (docs/07 §3.1). */
data class HubClientIdentity(
    val clientId: String,
    val displayName: String = "Cline Termux",
    val appVersion: String,
    val workspaceRoot: String? = null,
)

/** A command whose reply is only a correlation id (`session.send_input`, `run.start`); `run.*` events are the truth. */
class HubPendingCommand(val requestId: String, val reply: Deferred<HubReplyEnvelope>)

/**
 * Hub protocol client (docs/07 §3.1): one socket, request/reply correlation with the
 * per-command timeout table, the one global subscription with the persisted cursor and
 * the replay budget (D2), dedupe by `sequence`, and upstream NodeHubClient's reconnect
 * policy (`min(250 ms · 2^attempt, 5 s)` + 50 % jitter, same clientId, re-register,
 * `hub.status`, re-subscribe).
 *
 * The per-session subscribes go out only once the global replay has drained ([replayDrained]):
 * the Hub serves frames concurrently and pages the replay, while a cursor-less per-session
 * subscribe is live at once, so sending both together let newer live events overtake the
 * replayed backlog, whose events the store then dropped as already seen.
 *
 * The WebSocket handshake has its own deadline ([openTimeoutMs], KS §0.4 H5): on loopback the
 * TCP connect succeeds even while the Hub's event loop is frozen, and the upgrade answer would
 * otherwise be awaited forever with the coordinator's lock held.
 *
 * [credentials] is consulted on every (re)connect so a rotated token (the Hub restarted)
 * is picked up without recreating the client. The replacement socket is opened only after
 * the previous one reported its close (fixture 04: a second live socket with the same
 * clientId strands the first one's registration).
 */
class HubClient(
    private val transport: HubTransport,
    private val identity: HubClientIdentity,
    private val scope: CoroutineScope,
    private val cursors: HubCursorStore,
    private val credentials: suspend () -> HubCredentials?,
    private val workspaceRoot: suspend () -> String? = { identity.workspaceRoot },
    private val random: Random = Random.Default,
    val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val replayBudget: Long = REPLAY_BUDGET,
    private val cursorFlushIntervalMs: Long = CURSOR_FLUSH_INTERVAL_MS,
    private val closeWaitMs: Long = CLOSE_WAIT_MS,
    private val openTimeoutMs: Long = OPEN_TIMEOUT_MS,
    private val replayDrainTimeoutMs: Long = REPLAY_DRAIN_TIMEOUT_MS,
) {
    data class HubCredentials(val url: String, val token: String?)

    sealed interface Notice {
        /**
         * The persisted cursor was missing or more than [REPLAY_BUDGET] behind the Hub's log:
         * events in `(oldCursor, fromSequence]` were skipped. SessionsRepository marks `replayTruncated`.
         */
        data class ReplayTruncated(val fromSequence: Long, val lastSequence: Long) : Notice
    }

    /** What the first attempt of [connect] came to; later reconnects are reported through [state]. */
    sealed interface ConnectOutcome {
        data class Connected(val hubStatus: HubLiveStatus?) : ConnectOutcome
        data class Unauthorized(val httpStatus: Int) : ConnectOutcome
        data class Failed(val reason: String, val code: String?) : ConnectOutcome
    }

    private val _state = MutableStateFlow<HubConnectionState>(HubConnectionState.Disconnected)
    val state: StateFlow<HubConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HubEventEnvelope>(replay = 0, extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)

    /**
     * Every deduplicated event, in arrival order. Exactly one collector (`SessionStore.pump`,
     * docs/07 D15); back-pressure suspends the reader rather than dropping frames.
     */
    val events: SharedFlow<HubEventEnvelope> = _events.asSharedFlow()

    private val _sideEvents = MutableSharedFlow<HubEventEnvelope>(replay = 0, extraBufferCapacity = SIDE_EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The non-session events (`hub.*`, `schedule.*`, `settings.changed`) for the repositories that observe them. */
    val sideEvents: SharedFlow<HubEventEnvelope> = _sideEvents.asSharedFlow()

    private val _notices = MutableSharedFlow<Notice>(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private val _draining = MutableStateFlow(false)

    /**
     * Mirrors `hub.drain_changed {draining}` and the `hub.status` reply of the current
     * connection; reset when a new connection generation registers (G5).
     */
    val draining: StateFlow<Boolean> = _draining.asStateFlow()

    /**
     * `hub.status.eventLog.lastSequence` of the current connection: the status's `draining`
     * already covers every `hub.drain_changed` up to it (G5).
     */
    @Volatile private var drainStatusSequence: Long = -1L

    private val _hubStatus = MutableStateFlow<HubLiveStatus?>(null)

    /** The last `hub.status {}` reply seen by this client. */
    val hubStatus: StateFlow<HubLiveStatus?> = _hubStatus.asStateFlow()

    private val _lastSequence = MutableStateFlow<Long?>(null)

    /** Highest `sequence` seen by this process (the in-memory cursor). */
    val lastSequence: StateFlow<Long?> = _lastSequence.asStateFlow()

    private val _replayDrained = MutableStateFlow(false)

    /**
     * True once the global replay of the current connection has drained: an event with a
     * sequence at or above that connection's `hub.status.lastSequence` arrived (or nothing was
     * to be replayed), or [REPLAY_DRAIN_TIMEOUT_MS] passed. False while disconnected and while
     * a (re)connect replays. The per-session subscribes are sent when it turns true.
     */
    val replayDrained: StateFlow<Boolean> = _replayDrained.asStateFlow()

    val clientId: String get() = identity.clientId
    val isConnected: Boolean get() = _state.value is HubConnectionState.Connected

    private val pending = ConcurrentHashMap<String, CompletableDeferred<HubReplyEnvelope>>()
    private val sessionSubscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val requestCounter = AtomicLong(0)

    /** Source of [HubConnectionState.Connected.generation]. */
    private val generations = AtomicLong(0)

    /** Replay drain of the connection being served; guarded by [drainLock]. */
    private val drainLock = Any()
    private var drainGeneration = 0L
    private var drainTarget = Long.MAX_VALUE
    private var drainTimer: Job? = null
    private val dedupe = LongRingSet(DEDUPE_WINDOW)
    private val lifecycle = Mutex()

    @Volatile private var socket: HubSocket? = null
    @Volatile private var pumpDone: CompletableDeferred<Unit>? = null
    @Volatile private var stopRequested = false

    /** False while [connect] replaces a running loop: the stop must not publish [HubConnectionState.Disconnected]. */
    @Volatile private var publishStop = true
    private var loop: Job? = null

    /**
     * The lowest sequence the first subscribe of this process has to replay from, below the
     * persisted cursor (SessionStore: the start of a turn that was still running when the
     * process died). The cursor marks events as processed, but the rows built from them lived
     * only in the old process, so without this the transcript of that turn lost its first
     * deltas after a force-stop (home-chat §3.16 "no missing deltas").
     */
    @Volatile var replayFloor: (suspend () -> Long?)? = null

    private val cursorLock = Any()
    private var highestSeen: Long? = null
    private var cursorDirty = false
    private var cursorFlush: Job? = null

    /**
     * Opens the socket, registers, subscribes and starts the reconnect loop. Suspends until
     * the first attempt is decided so the coordinator can act on a 401 or a refused handshake
     * (KS §0.4 H5); later drops are handled by the loop and reported through [state].
     */
    suspend fun connect(): ConnectOutcome = lifecycle.withLock {
        val current = loop
        if (current?.isActive == true) {
            if (_state.value is HubConnectionState.Connected) return ConnectOutcome.Connected(_hubStatus.value)
            // Replace the reconnect loop without passing through Disconnected: the state stays
            // Reconnecting until the new attempt settles (Connecting, then Connected or
            // Unavailable), so a prompt held for the reconnect is not returned to the draft
            // (home-chat §3.12 step 6 returns it only on Unavailable).
            stopLoopLocked(publish = false)
        }
        stopRequested = false
        val first = CompletableDeferred<ConnectOutcome>()
        loop = scope.launch { runLoop(first) }
        first.await()
    }

    /**
     * Closes the socket (graceful `close(1000)`, then `cancel()` after [closeWaitMs]) and
     * waits for the transport's close callback before returning; flushes the cursor.
     */
    suspend fun disconnect() = lifecycle.withLock {
        stopLoopLocked()
        flushCursor()
    }

    /**
     * Drops the current socket and lets the loop reconnect with the same clientId, opening
     * the replacement only after the old socket reported its close (fixture 04 rule). Used
     * after `client_authority_mismatch` / `client_not_found` (CR §0.5) and by the
     * coordinator when it wants a fresh registration on the same daemon.
     */
    suspend fun disconnectForReplace() {
        val sock = socket ?: return
        val done = pumpDone ?: return
        sock.close(1000, "re-register")
        if (withTimeoutOrNull(closeWaitMs) { done.await() } == null) {
            sock.cancel()
            withTimeoutOrNull(closeWaitMs) { done.await() }
        }
    }

    /**
     * Per-session subscribe without a cursor: it only makes the Hub re-issue pending
     * approvals and questions for an open session (HC §1.1); re-sent on every reconnect.
     * While the global replay of a (re)connect still drains, the frame is queued and goes out
     * with the others once [replayDrained] turns true (a live per-session stream must never
     * overtake the replayed backlog).
     */
    fun subscribe(sessionId: String) {
        sessionSubscriptions.add(sessionId)
        val sock = synchronized(drainLock) { if (_replayDrained.value) socket else null } ?: return
        sock.send(HubFrame.StreamSubscribe(clientId, sessionId, null))
    }

    fun unsubscribe(sessionId: String) {
        sessionSubscriptions.remove(sessionId)
        socket?.send(HubFrame.StreamUnsubscribe(clientId, sessionId))
    }

    /**
     * Sends a command and awaits its reply. [envelopeTimeoutMs] is the Hub-side timer
     * (`null` disables it, as upstream does for `run.start` / `session.send_input`);
     * [clientTimeoutMs] is this client's own timer and is always longer, so the Hub's
     * `hub_command_timeout` can arrive first (`null` waits for the reply or the close).
     */
    suspend fun command(
        name: String,
        payload: JsonObject = EMPTY_PAYLOAD,
        sessionId: String? = null,
        envelopeTimeoutMs: Long? = defaultEnvelopeTimeout(name, payload),
        clientTimeoutMs: Long? = defaultClientTimeout(name, payload),
    ): JsonObject {
        val sent = enqueue(name, payload, sessionId, envelopeTimeoutMs)
        val reply = try {
            if (clientTimeoutMs == null) sent.reply.await() else withTimeout(clientTimeoutMs) { sent.reply.await() }
        } catch (_: TimeoutCancellationException) {
            throw HubCommandException(name, HubErrorCodes.CLIENT_TIMEOUT, "no reply within $clientTimeoutMs ms")
        } finally {
            pending.remove(sent.requestId)
        }
        if (!reply.ok) {
            val err = reply.error
            // Authority loss already scheduled the re-register in [dispatch].
            throw HubCommandException(name, err?.code ?: "unknown", err?.message ?: "unknown error", err?.details)
        }
        return reply.payload ?: EMPTY_PAYLOAD
    }

    /**
     * Fire-and-forget variant for the long-running commands: no client timer, the reply is
     * delivered through [HubPendingCommand.reply] (it completes with a `hub_connection_closed`
     * error envelope when the socket drops first).
     */
    fun send(name: String, payload: JsonObject, sessionId: String?, envelopeTimeoutMs: Long? = defaultEnvelopeTimeout(name, payload)): HubPendingCommand =
        enqueue(name, payload, sessionId, envelopeTimeoutMs)

    /** True when the Hub stopped draining within [timeoutMs]. */
    suspend fun awaitNotDraining(timeoutMs: Long = DRAIN_WAIT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { _draining.first { !it } } != null

    /** Writes the in-memory cursor through (`ON_STOP`, [disconnect]). */
    suspend fun flushCursor() {
        val value: Long
        synchronized(cursorLock) {
            if (!cursorDirty) return
            cursorDirty = false
            value = highestSeen ?: return
        }
        cursors.put(value)
    }

    // ---- connection loop -------------------------------------------------------------

    private suspend fun runLoop(first: CompletableDeferred<ConnectOutcome>) {
        var attempt = 0
        var lastReason: String? = null
        while (true) {
            _state.value = if (attempt == 0) HubConnectionState.Connecting else HubConnectionState.Reconnecting(attempt, lastReason)
            val result = attemptOnce(first)
            if (stopRequested) {
                if (publishStop) _state.value = HubConnectionState.Disconnected
                return
            }
            when (result) {
                is Attempt.Failed -> {
                    lastReason = result.reason
                    if (!first.isCompleted) {
                        first.complete(result.outcome)
                        _state.value = HubConnectionState.Unavailable(result.reason, result.outcome is ConnectOutcome.Unauthorized)
                        return
                    }
                    if (result.outcome is ConnectOutcome.Unauthorized) {
                        // The token rotated under us (daemon restart): only the coordinator can re-read it.
                        _state.value = HubConnectionState.Unavailable(result.reason, unauthorized = true)
                        return
                    }
                }
                is Attempt.Served -> {
                    lastReason = result.closedReason
                    if (!first.isCompleted) first.complete(ConnectOutcome.Connected(_hubStatus.value))
                    attempt = 0
                }
            }
            attempt += 1
            if (attempt > maxReconnectAttempts) {
                _state.value = HubConnectionState.Unavailable(lastReason ?: "connection lost")
                return
            }
            _state.value = HubConnectionState.Reconnecting(attempt, lastReason)
            delay(backoffMillis(attempt))
        }
    }

    private sealed interface Attempt {
        /** Open or register failed; nothing was served. */
        data class Failed(val reason: String, val outcome: ConnectOutcome) : Attempt

        /** Registered and served until the socket ended. */
        data class Served(val closedReason: String) : Attempt
    }

    private suspend fun attemptOnce(first: CompletableDeferred<ConnectOutcome>): Attempt {
        val creds = credentials() ?: return Attempt.Failed("no hub endpoint", ConnectOutcome.Failed("no hub endpoint", null))
        val sock = try {
            // KS §0.4 H5: 10 s for the handshake. A timeout cancels the open, which cancels the
            // OkHttp call; the attempt then fails like a refused one (reconnect or Unavailable).
            withTimeoutOrNull(openTimeoutMs) { transport.open(creds.url, creds.token) }
                ?: return Attempt.Failed(HANDSHAKE_TIMED_OUT, ConnectOutcome.Failed(HANDSHAKE_TIMED_OUT, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubUpgradeFailed) {
            val status = e.httpStatus
            return if (status == 401 || status == 403) {
                Attempt.Failed("unauthorized ($status)", ConnectOutcome.Unauthorized(status))
            } else {
                Attempt.Failed("upgrade failed: ${e.message}", ConnectOutcome.Failed(e.message ?: "upgrade failed", null))
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            return Attempt.Failed(reason, ConnectOutcome.Failed(reason, null))
        }
        val done = CompletableDeferred<Unit>()
        var closedReason = "stream ended"
        socket = sock
        pumpDone = done
        // The pump lives in the app scope on purpose: cancelling the loop must never leave a
        // socket half-observed. It ends when the transport reports onClosed / onFailure.
        scope.launch {
            try {
                sock.incoming.collect { frame -> dispatch(frame) }
            } catch (e: HubSocketClosed) {
                closedReason = "closed ${e.code}: ${e.reason}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closedReason = e.message ?: e.javaClass.simpleName
            } finally {
                done.complete(Unit)
            }
        }
        var registerFailure: Exception? = null
        try {
            val generation = generations.incrementAndGet()
            registerAndSubscribe(sock, generation)
            _state.value = HubConnectionState.Connected(creds.url, generation)
            // [connect] returns as soon as the first registration is served, not when the socket ends.
            if (!first.isCompleted) first.complete(ConnectOutcome.Connected(_hubStatus.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            registerFailure = e
            sock.close(1000, "register failed")
            if (withTimeoutOrNull(closeWaitMs) { done.await() } == null) sock.cancel()
        }
        done.await()
        endDrain()
        socket = null
        pumpDone = null
        failPending(HubErrorCodes.HUB_CONNECTION_CLOSED)
        runCatching { flushCursor() }
        val failure = registerFailure ?: return Attempt.Served(closedReason)
        val reason = failure.message ?: failure.javaClass.simpleName
        val outcome = when (failure) {
            is HubCommandException -> ConnectOutcome.Failed(reason, failure.code)
            else -> ConnectOutcome.Failed(reason, null)
        }
        return Attempt.Failed(reason, outcome)
    }

    private suspend fun registerAndSubscribe(sock: HubSocket, generation: Long) {
        command(HubCommands.CLIENT_REGISTER, registerPayload(), envelopeTimeoutMs = HubCommands.DEFAULT_TIMEOUT_MS, clientTimeoutMs = REGISTER_CLIENT_TIMEOUT_MS)
        // G5: draining belongs to a connection. A new generation may be a new Hub (the old one
        // announced its drain, "retired by newer install", and was killed), so the old value
        // never survives it: this connection's `hub.status` replaces it, and a status that does
        // not answer drops it (the attempt fails; nothing is known about the new Hub). The value
        // is replaced in one step rather than cleared first, so a send held for a drain that is
        // still going on is not released early.
        val status = try {
            HubLiveStatus.parse(command(HubCommands.HUB_STATUS, EMPTY_PAYLOAD))
        } catch (e: Exception) {
            _draining.value = false
            throw e
        }
        _hubStatus.value = status
        drainStatusSequence = status.lastSequence
        _draining.value = status.draining
        val last = status.lastSequence
        val cursor = cursors.get()
        val since: Long = when {
            cursor != null && cursor > last -> {
                // The Hub's event log restarted (fresh data dir): old sequences are meaningless now.
                synchronized(cursorLock) { highestSeen = null; cursorDirty = false }
                dedupe.clear()
                val from = maxOf(0L, last - replayBudget)
                _notices.tryEmit(Notice.ReplayTruncated(from, last))
                from
            }
            cursor == null || last - cursor > replayBudget -> {
                val from = maxOf(0L, last - replayBudget)
                if (from > (cursor ?: 0L)) _notices.tryEmit(Notice.ReplayTruncated(from, last))
                from
            }
            else -> {
                val fresh = synchronized(cursorLock) { highestSeen == null }
                val floor = if (fresh) replayFloor?.invoke() else null
                if (floor != null && floor < cursor) maxOf(floor, last - replayBudget, 0L) else cursor
            }
        }
        // Only the global frame now (docs/02 step 6, docs/07 §3.1): every event arrives on it, in
        // sequence order. Its subscribe also re-issues every pending approval, so the
        // per-session frames can wait until the replay drained ([markDrained]).
        synchronized(drainLock) {
            drainGeneration = generation
            drainTarget = last
            drainTimer?.cancel()
            drainTimer = null
            _replayDrained.value = false
        }
        if (!sock.send(HubFrame.StreamSubscribe(clientId, null, since))) throw HubConnectionClosedException("socket refused the subscribe frame")
        if (since >= last) {
            // Nothing to replay: `sequence > since` holds for no logged event.
            markDrained(generation)
        } else {
            val timer = scope.launch {
                delay(replayDrainTimeoutMs)
                markDrained(generation)
            }
            synchronized(drainLock) {
                if (drainGeneration == generation && !_replayDrained.value) drainTimer = timer else timer.cancel()
            }
        }
    }

    /**
     * The replay of connection [generation] drained (or its timer fired): sends the queued
     * per-session subscribes on that connection's socket. A stale generation does nothing.
     */
    private fun markDrained(generation: Long) {
        val sock = synchronized(drainLock) {
            if (generation != drainGeneration || _replayDrained.value) return
            _replayDrained.value = true
            drainTimer?.cancel()
            drainTimer = null
            socket
        } ?: return
        for (sessionId in sessionSubscriptions) sock.send(HubFrame.StreamSubscribe(clientId, sessionId, null))
    }

    /** The served socket ended: the next connection drains again, and no stale timer may fire. */
    private fun endDrain() {
        synchronized(drainLock) {
            drainGeneration = 0L
            drainTarget = Long.MAX_VALUE
            drainTimer?.cancel()
            drainTimer = null
            _replayDrained.value = false
        }
    }

    private suspend fun registerPayload(): JsonObject {
        val root = workspaceRoot()?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKSPACE_ROOT
        return buildJsonObject {
            put("clientId", identity.clientId)
            put("clientType", "android")
            put("displayName", identity.displayName)
            put("transport", "native")
            put("actorKind", "client")
            put("capabilities", JsonArray(listOf(
                buildJsonObject { put("name", HubCapabilities.APPROVAL_RESPOND) },
                buildJsonObject { put("name", HubCapabilities.ASK_QUESTION) },
            )))
            putJsonObject("metadata") {
                put("version", identity.appVersion)
                put("platform", "android")
                put("pid", 0)
            }
            putJsonObject("workspaceContext") {
                put("workspaceRoot", root)
                put("cwd", root)
            }
            put("protocolVersion", HUB_PROTOCOL_VERSION)
        }
    }

    private suspend fun stopLoopLocked(publish: Boolean = true) {
        val job = loop ?: return
        publishStop = publish
        stopRequested = true
        val sock = socket
        val done = pumpDone
        if (sock != null && done != null) {
            sock.close(1000, "client closed")
            if (withTimeoutOrNull(closeWaitMs) { done.await() } == null) {
                sock.cancel()
                withTimeoutOrNull(closeWaitMs) { done.await() }
            }
            // Joined after the cancel too, so the old loop can never publish after [publishStop] resets.
            if (withTimeoutOrNull(closeWaitMs) { job.join() } == null) job.cancelAndJoin()
        } else {
            job.cancelAndJoin()
        }
        loop = null
        endDrain()
        socket = null
        pumpDone = null
        failPending(HubErrorCodes.HUB_CONNECTION_CLOSED)
        publishStop = true
        if (publish) _state.value = HubConnectionState.Disconnected
    }

    // ---- frames ----------------------------------------------------------------------

    private fun enqueue(name: String, payload: JsonObject, sessionId: String?, envelopeTimeoutMs: Long?): HubPendingCommand {
        val sock = socket ?: throw HubConnectionClosedException("not connected")
        val requestId = "${identity.clientId}-${requestCounter.incrementAndGet()}"
        val envelope = HubCommandEnvelope(
            command = name,
            requestId = requestId,
            clientId = identity.clientId,
            sessionId = sessionId,
            // Omitted on the wire when null; upstream's default for the long-running pair is null too.
            timeoutMs = envelopeTimeoutMs,
            payload = payload,
        )
        val deferred = CompletableDeferred<HubReplyEnvelope>()
        pending[requestId] = deferred
        if (!sock.send(HubFrame.Command(envelope))) {
            pending.remove(requestId)
            throw HubConnectionClosedException("socket refused the frame")
        }
        return HubPendingCommand(requestId, deferred)
    }

    private suspend fun dispatch(frame: HubFrame) {
        when (frame) {
            is HubFrame.Reply -> {
                val reply = frame.envelope
                // docs/07 §9: the Hub no longer knows this socket's registration. Close it and
                // re-register with the same clientId on a fresh socket, for every command path
                // ([command] and the fire-and-forget [send] alike), before anyone sees the error.
                if (!reply.ok && reply.error?.code in HubErrorCodes.AUTHORITY_LOST) scope.launch { disconnectForReplace() }
                val id = reply.requestId ?: return
                // A reply nobody waits for (unknown or already timed out) is dropped.
                pending.remove(id)?.complete(reply)
            }
            is HubFrame.Event -> {
                val env = frame.envelope
                val sequence = env.sequence
                if (sequence != null) {
                    // Until the drain, only the global subscription is open, so this is its replay.
                    // Checked before the dedupe: a target event seen before still ends the drain.
                        val drainOf = synchronized(drainLock) { if (!_replayDrained.value && sequence >= drainTarget) drainGeneration else null }
                    if (!dedupe.add(sequence)) {
                        drainOf?.let(::markDrained)
                        return
                    }
                    advanceCursor(sequence)
                    deliver(env)
                    // The backlog up to the target is queued ahead of this event by now.
                    drainOf?.let(::markDrained)
                    return
                }
                deliver(env)
            }
            is HubFrame.Command, is HubFrame.StreamSubscribe, is HubFrame.StreamUnsubscribe -> Unit
        }
    }

    private suspend fun deliver(env: HubEventEnvelope) {
        // G5: a replayed drain change (at or below this connection's `hub.status` sequence) is
        // already part of that status; replaying it would revive an old Hub's drain.
        val seq = env.sequence
        if (env.event == HubEvents.HUB_DRAIN_CHANGED && (seq == null || seq > drainStatusSequence)) {
            env.payload?.get("draining")?.jsonPrimitive?.booleanOrNull?.let { _draining.value = it }
        }
        if (env.sessionId == null) _sideEvents.tryEmit(env)
        _events.emit(env)
    }

    private fun advanceCursor(sequence: Long) {
        var schedule = false
        synchronized(cursorLock) {
            val current = highestSeen
            if (current != null && sequence <= current) return
            highestSeen = sequence
            cursorDirty = true
            if (cursorFlush?.isActive != true) schedule = true
        }
        _lastSequence.value = sequence
        if (schedule) {
            // Throttled write-through: at most one DataStore write per interval.
            cursorFlush = scope.launch {
                delay(cursorFlushIntervalMs)
                runCatching { flushCursor() }
            }
        }
    }

    private fun failPending(code: String) {
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { d ->
            d.complete(HubReplyEnvelope(ok = false, error = HubReplyError(code, "connection closed")))
        }
    }

    internal fun backoffMillis(attempt: Int): Long {
        val base = minOf(250L shl (attempt - 1).coerceIn(0, 10), 5_000L)
        val jitter = (base * 0.5 * random.nextDouble()).toLong()
        return base + jitter
    }

    companion object {
        /** D2: how far behind the Hub's log the persisted cursor may be before replay is truncated. */
        const val REPLAY_BUDGET = 2_000L
        const val DEDUPE_WINDOW = 4096
        const val EVENT_BUFFER = 4096
        const val SIDE_EVENT_BUFFER = 256
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
        const val CURSOR_FLUSH_INTERVAL_MS = 1_000L
        const val CLOSE_WAIT_MS = 2_000L
        const val DRAIN_WAIT_MS = 30_000L
        const val REGISTER_CLIENT_TIMEOUT_MS = HubCommands.DEFAULT_TIMEOUT_MS + 5_000L

        /** KS §0.4 H5: the WebSocket handshake (TCP connect + HTTP 101) must finish within this. */
        const val OPEN_TIMEOUT_MS = 10_000L

        /** Longest wait for the replay target before the per-session subscribes go out anyway. */
        const val REPLAY_DRAIN_TIMEOUT_MS = 15_000L

        /** [ConnectOutcome.Failed.reason] of a handshake that never answered. */
        const val HANDSHAKE_TIMED_OUT = "handshake timed out"

        const val SESSION_CREATE_TIMEOUT_MS = 120_000L
        const val SESSION_CREATE_CLIENT_TIMEOUT_MS = 125_000L
        const val SESSION_RESTORE_TIMEOUT_MS = 120_000L
        const val SESSION_RESTORE_CLIENT_TIMEOUT_MS = 130_000L
        const val SETTINGS_LIST_PLUGINS_TIMEOUT_MS = 60_000L
        const val SETTINGS_LIST_PLUGINS_CLIENT_TIMEOUT_MS = 65_000L
        const val DEFAULT_CLIENT_TIMEOUT_MS = 35_000L

        /** `$HOME` in Termux: the registration workspace when the user has none (CR §4.8 needs one). */
        const val DEFAULT_WORKSPACE_ROOT = "/data/data/com.termux/files/home"

        val EMPTY_PAYLOAD = JsonObject(emptyMap())

        /** The Hub-side timer per docs/07 §3.1. */
        fun defaultEnvelopeTimeout(name: String, payload: JsonObject = EMPTY_PAYLOAD): Long? = when {
            name in HubCommands.LONG_RUNNING -> null
            name == HubCommands.SESSION_CREATE || name == HubCommands.SESSION_FORK -> SESSION_CREATE_TIMEOUT_MS
            name == HubCommands.SESSION_RESTORE -> SESSION_RESTORE_TIMEOUT_MS
            name == HubCommands.SETTINGS_LIST && payload.includesPluginTools() -> SETTINGS_LIST_PLUGINS_TIMEOUT_MS
            else -> HubCommands.DEFAULT_TIMEOUT_MS
        }

        /** The client-side timer, always longer than the envelope value; `null` = no timer. */
        fun defaultClientTimeout(name: String, payload: JsonObject = EMPTY_PAYLOAD): Long? = when {
            name in HubCommands.LONG_RUNNING -> null
            name == HubCommands.SESSION_CREATE || name == HubCommands.SESSION_FORK -> SESSION_CREATE_CLIENT_TIMEOUT_MS
            name == HubCommands.SESSION_RESTORE -> SESSION_RESTORE_CLIENT_TIMEOUT_MS
            name == HubCommands.SETTINGS_LIST && payload.includesPluginTools() -> SETTINGS_LIST_PLUGINS_CLIENT_TIMEOUT_MS
            else -> DEFAULT_CLIENT_TIMEOUT_MS
        }

        private fun JsonObject.includesPluginTools(): Boolean =
            (this["includePluginTools"] as? JsonPrimitive)?.booleanOrNull == true
    }
}

/** Fixed-size set of the last [capacity] sequences: O(1) membership, oldest evicted first. */
internal class LongRingSet(private val capacity: Int) {
    private val ring = LongArray(capacity)
    private val members = HashSet<Long>(capacity * 2)
    private var next = 0
    private var size = 0

    /** False when [value] was already present. */
    @Synchronized
    fun add(value: Long): Boolean {
        if (!members.add(value)) return false
        if (size == capacity) members.remove(ring[next]) else size++
        ring[next] = value
        next = (next + 1) % capacity
        return true
    }

    @Synchronized
    fun contains(value: Long): Boolean = value in members

    @Synchronized
    fun clear() {
        members.clear()
        next = 0
        size = 0
    }
}
