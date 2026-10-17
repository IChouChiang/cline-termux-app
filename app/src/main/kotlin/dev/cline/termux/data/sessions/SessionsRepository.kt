package dev.cline.termux.data.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.data.prefs.JsonFileCache
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.session.epochMillis
import dev.cline.termux.data.session.obj
import dev.cline.termux.data.session.str
import dev.cline.termux.data.tools.asRecord
import dev.cline.termux.data.tools.jsNumber
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where the rows on screen come from (SS §1.7, §1.8). */
enum class ListSource {
    /** Nothing loaded and no cache. */
    None,

    /** `SessionListCache` rows: tappable, every row action disabled. */
    Cache,

    /** A live `session.list` (plus events since). */
    Live,
}

/** SS §1.11 `ListLoad`, per connection (§1.9 item 7). */
sealed interface ListLoad {
    /** Nothing loaded on this connection yet; failures retry every 2 s silently. */
    data object Initial : ListLoad

    data object Loaded : ListLoad

    /** A `session.list` failed **after** a successful load on this connection; [message] is kernel text. */
    data class Failed(val message: String) : ListLoad
}

/** One row with its app-side flags (DS §1.6 inputs). */
@Immutable
data class SessionRow(val summary: SessionSummary, val flags: SessionFlags) {
    val id: String get() = summary.id

    /** The one status mapping (DS §1.6). */
    val visual: SessionVisual get() = summary.visual(flags)
}

/** Everything the three sessions ViewModels read (SS §1.11). */
@Immutable
data class SessionsSnapshot(
    /** `activityAt` descending, ties by id descending (§1.3 rule 3). */
    val rows: List<SessionRow> = emptyList(),
    val source: ListSource = ListSource.None,
    val load: ListLoad = ListLoad.Initial,
    /** The Hub socket is up; actions need it (§1.7). */
    val connected: Boolean = false,
    /** The current `session.list` limit (50, +50 per "load older", doubled by "load all"). */
    val limit: Int = SessionsRepository.INITIAL_LIMIT,
    /** The last reply held as many rows as asked for. */
    val mayHaveMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadingAll: Boolean = false,
    /** Pull-to-refresh in flight (S2). */
    val refreshing: Boolean = false,
    val lastLoadedAt: Long? = null,
    /** The replay budget skipped events on the last (re)connect (§1.2.1 item 2). */
    val replayTruncated: Boolean = false,
    /** Rename / fork / delete in flight, per row (§1.10). */
    val pending: Map<String, PendingKind> = emptyMap(),
    /** Live rows whose usage `session.get` is in flight (S2 shimmer, §1.5). */
    val usageLoading: Set<String> = emptySet(),
) {
    val showingCache: Boolean get() = source == ListSource.Cache

    /**
     * The rows on screen are not live (§1.7 "Showing the last loaded list."): the disk cache, or
     * the last live list kept after the socket dropped (`cline hub stop` while the app is open).
     */
    val showingLastLoaded: Boolean get() = showingCache || (!connected && source == ListSource.Live && rows.isNotEmpty())

    /** Any row unread (rail badge, §1.6). */
    val anyUnread: Boolean get() = rows.any { it.flags.unread }

    fun row(sessionId: String): SessionRow? = rows.firstOrNull { it.id == sessionId }
}

/**
 * The app-scoped sessions list (design/screens/sessions.md §1, docs/07 §4.4).
 *
 * - **Events** arrive through [observe], called by the `SessionStore` pump ([SessionEventTap])
 *   on the shared single-threaded [dispatcher] (docs/07 §8), so every mutation here runs on it
 *   without locks. Status, flags, unread, overrides and the interrupted rule are reduced from
 *   them; the store stays the only collector of the socket (D15).
 * - **Refresh policy** (§1.9, no poller): after every (re)connect, [onResume] / [onDrawerOpened]
 *   when the last list is older than 10 s, [refresh] (pull), 250 ms after an event for an
 *   unknown session, after fork (50 ms), pin (50 ms) and delete, and the 2 s initial-load
 *   retry while nothing has loaded on this connection. Concurrent loads coalesce; a load
 *   that raised the limit is never answered by a smaller one.
 * - **Replay budget** (§1.2.1): the cursor and the 2,000-event budget live in `HubClient`;
 *   here the `ReplayTruncated` notice sets [SessionsSnapshot.replayTruncated], and replayed
 *   events mark unread only when newer than `sessions.lastDisconnectAt`.
 * - **Flags** come from `SessionStore` facts for the session on screen once it is seeded
 *   ([loadedSession]), from events and records otherwise. `interrupted` follows the
 *   client-side rule of §1.3 (`inFlightTurns` → persisted suspects → verdict after the replay).
 * - **Cache** (§1.8): the last good list, overrides, schedule links and suspect hubIds in
 *   `filesDir/sessions/list-cache.json`; cached rows never mix with live rows.
 * - **Reconciliation** (trial row 8): every `session.list` reply is authoritative, so a row it
 *   does not return goes, from the screen and from the cache (only a row that raced the
 *   request survives one reply). Replayed events of sessions deleted since, and late events
 *   of a session this app forgot, never bring a row back; `session_not_found` on any read of
 *   a session ([forget]) drops its row at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsRepository(
    private val hub: SessionsHub,
    private val persistence: SessionsPersistence,
    private val cache: JsonFileCache<SessionsCachePayload>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    /** The `SessionStore` state of the session on screen (flags from facts when loaded). */
    private val loadedSession: (String) -> StateFlow<SessionState>? = { null },
    private val config: Config = Config(),
) {
    data class Config(
        val initialLimit: Int = INITIAL_LIMIT,
        val pageSize: Int = PAGE_SIZE,
        val staleAfterMs: Long = 10_000L,
        val initialRetryMs: Long = 2_000L,
        val unknownSessionRefreshMs: Long = 250L,
        val scheduleEventDebounceMs: Long = 1_000L,
        val scheduleStaleAfterMs: Long = 120_000L,
        val titleHydrationDelayMs: Long = 1_200L,
        val titleHydrationMax: Int = 4,
        val usageMaxRows: Int = 10,
        val usageConcurrency: Int = 4,
        val replayDrainTimeoutMs: Long = 15_000L,
        val cacheRows: Int = 200,
        val maxOverrides: Int = 500,
        val maxLastSeen: Int = 500,
        val maxTombstones: Int = 500,
    )

    /**
     * The part of the on-screen session's `SessionState` that [withLoadedFacts] reads. Streamed
     * deltas change the transcript, not these, so they never rebuild the list (docs/07 §8).
     */
    private data class LoadedFacts(
        val sessionId: String,
        val seeded: Boolean,
        val status: SessionStatus,
        val approvalIds: Set<String>,
        val questionIds: Set<String>,
        val creating: Boolean,
    )

    private data class Suspect(val hubId: String?, val requestId: String?, val evidence: Boolean = false)
    private data class PinOverride(val pinned: Boolean, val settled: Boolean)

    private val _state = MutableStateFlow(SessionsSnapshot(limit = config.initialLimit))
    val state: StateFlow<SessionsSnapshot> = _state.asStateFlow()

    private val _removed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Sessions removed from history (F5 success, or gone on open): notifications cancel, the open chat goes Home. */
    val removed: SharedFlow<String> = _removed.asSharedFlow()

    // ---- state confined to [dispatcher] ----------------------------------------------------------

    private val liveRows = LinkedHashMap<String, SessionSummary>()
    private var cacheRows: List<SessionSummary> = emptyList()
    private var source = ListSource.None
    private var load: ListLoad = ListLoad.Initial
    private var connected = false
    private var loadedThisConnection = false
    private var limit = config.initialLimit
    private var lastReplySize = 0
    private var lastListAt: Long? = null
    private var loadingMore = false
    private var loadingAll = false
    private var refreshing = false
    private var replayTruncated = false

    /**
     * Ids inserted by events or command replies since the last list. A list that does not
     * return one drops it, unless it was inserted while that list was on its way by an event
     * newer than the list's request ([applyList]); the cache never holds one ([writeCache]).
     */
    private val eventOnly = HashSet<String>()

    /** The sequence of the event that inserted an [eventOnly] row (none: a command reply). */
    private val insertedAt = HashMap<String, Long>()

    /**
     * The last applied `session.list` (trial row 8): the ids of every record it returned,
     * listable or not, and [listBound], the highest event sequence known to precede its request.
     * An event at or below the bound for a session that list did not return is history (the
     * replay of a session deleted since) and never inserts its row again. Kept across
     * reconnects to the same event log; a log that restarted resets both ([onConnected]).
     */
    private val listedIds = HashSet<String>()
    private var listBound: Long? = null

    /** The highest event sequence known on this connection: `hub.status`'s, then the events seen. */
    private var connectionSeen = 0L

    /**
     * Sessions known to be gone ([forget]: deleted, or `session_not_found` on a read), with the
     * highest sequence seen and the latest load index at that moment. A late or replayed event
     * never brings one back; a `session.created` newer than the tombstone (the session was
     * created again under its id) or a list requested after it does.
     */
    private data class Tombstone(val sequence: Long, val afterLoad: Long)

    private val tombstones = LinkedHashMap<String, Tombstone>()

    /** Unknown ids a refresh did not resolve (teammates' events): never refreshed for again. */
    private val ignoredUnknown = HashSet<String>()

    /** Sessions this process created (listed before their metadata is filled, §1.3 rule 1). */
    private val createdHere = HashSet<String>()
    private var visibleId: String? = null
    private val visibleFlow = MutableStateFlow<String?>(null)
    private var loaded: LoadedFacts? = null

    /** How many snapshots [publish] has built (tests: streaming must not rebuild the list). */
    internal var publishCount: Long = 0L
        private set

    private val approvals = HashMap<String, MutableSet<String>>()
    private val questions = HashMap<String, MutableSet<String>>()
    private val starting = HashSet<String>()
    private val pending = LinkedHashMap<String, PendingKind>()
    private val unread = HashMap<String, Int>()
    private val lastSeen = HashMap<String, Long>()
    private val highestSeq = HashMap<String, Long>()
    private val overrides = LinkedHashMap<String, AnnotationOverride>()
    private val pinOverrides = HashMap<String, PinOverride>()
    private val links = HashMap<String, ScheduleLink>()
    private var linksAt: Long? = null
    private val hydratedTitles = HashMap<String, String>()
    private val hydrationTried = HashSet<String>()
    private val usageCache = HashMap<String, Pair<Long, RowUsage>>()
    private val usageLoading = LinkedHashSet<String>()

    private val inFlightTurns = HashMap<String, String>()
    private val suspects = HashMap<String, Suspect>()
    private val interrupted = HashSet<String>()

    private var disconnectCap: Long? = null
    private var connectionLastSequence = 0L
    private var highestSeenGlobal = 0L
    private var drained = true
    private var connectedHubId: String? = null

    /** [HubConnectionState.Connected.generation] of the connection [onConnected] ran for. */
    private var connectedGeneration: Long? = null

    private var started = false

    /** Completes once [restore] has read the saved marks; [observe] waits for it (§1.6 lastSeen and cap). */
    private val restored = CompletableDeferred<Unit>()
    private var initialLoadJob: Job? = null
    private var drainJob: Job? = null
    private var unknownRefreshJob: Job? = null
    private var scheduleEventJob: Job? = null
    private var hydrationJob: Job? = null
    private var usageJob: Job? = null
    private var cacheJob: Job? = null
    private var cacheAgain = false
    private var unreadJob: Job? = null
    private var unreadAgain = false

    // ---- list coalescing ---------------------------------------------------------------------------

    private val listMutex = Mutex()
    private var loadsStarted = 0L
    private var lastLoadIndex = 0L
    private var lastLoadLimit = 0
    private var lastLoadOk = false
    private var loadInFlight = false

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Restores the cache and persisted marks, then follows the connection. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch(dispatcher) {
            restore()
            launch { hub.connection.collect { onConnection(it) } }
            launch {
                hub.notices.collect { notice ->
                    if (notice is HubClient.Notice.ReplayTruncated) {
                        replayTruncated = true
                        publish()
                    }
                }
            }
            launch {
                visibleFlow
                    .flatMapLatest { id -> id?.let(loadedSession)?.map(::loadedFacts) ?: flowOf(null) }
                    .distinctUntilChanged()
                    .collect { facts ->
                        loaded = facts
                        publish()
                    }
            }
        }
    }

    private suspend fun restore() {
        try {
            restoreSaved()
        } finally {
            restored.complete(Unit)
        }
    }

    private suspend fun restoreSaved() {
        val cached = runCatching { cache.read() }.getOrNull()?.value
        if (cached != null) {
            cacheRows = cached.rows
            cached.overrides.forEach { (id, o) -> overrides.putIfAbsent(id, o) }
            cached.scheduleLinks.forEach { (id, l) -> links.putIfAbsent(id, l) }
            if (source == ListSource.None && cacheRows.isNotEmpty()) source = ListSource.Cache
        }
        runCatching { persistence.unreadCounts() }.getOrNull()?.forEach { (id, n) -> unread.merge(id, n, Int::plus) }
        runCatching { persistence.lastSeen() }.getOrNull()?.forEach { (id, seq) -> lastSeen.merge(id, seq, ::maxOf) }
        val suspectIds = runCatching { persistence.suspectInterrupted() }.getOrNull().orEmpty()
        suspectIds.forEach { id -> suspects.putIfAbsent(id, Suspect(cached?.suspects?.get(id)?.hubId, cached?.suspects?.get(id)?.requestId)) }
        disconnectCap = runCatching { persistence.lastDisconnectAt() }.getOrNull()
        publish()
    }

    private fun onConnection(state: HubConnectionState) {
        if (state !is HubConnectionState.Connected) {
            if (connected) onDisconnected()
            return
        }
        if (connected) {
            if (state.generation == connectedGeneration) return
            // The drop and the reconnect were conflated away while this collector was busy: do
            // the drop's bookkeeping (in-flight turns become suspects) before the new connection's.
            onDisconnected()
        }
        connectedGeneration = state.generation
        onConnected()
    }

    private fun onConnected() {
        connected = true
        loadedThisConnection = false
        load = ListLoad.Initial
        val status = hub.hubStatus.value
        connectionLastSequence = status?.lastSequence ?: 0L
        connectedHubId = status?.hubId
        connectionSeen = connectionLastSequence
        // The log only grows: a last sequence below the last list's bound is another log (a fresh
        // data dir, another Hub), where that list's ids and bound say nothing.
        if ((listBound ?: 0L) > connectionLastSequence) {
            listBound = null
            listedIds.clear()
        }
        drained = highestSeenGlobal >= connectionLastSequence
        // A new daemon: the suspect's turn cannot have survived (§1.3 interrupted rule, first bullet).
        for ((id, s) in suspects.toMap()) {
            if (s.hubId != null && connectedHubId != null && s.hubId != connectedHubId) resolveSuspect(id, interrupted = true)
        }
        if (drained) finalizeSuspects() else {
            drainJob?.cancel()
            drainJob = scope.launch(dispatcher) {
                delay(config.replayDrainTimeoutMs)
                drained = true
                finalizeSuspects()
                publish()
            }
        }
        initialLoadJob?.cancel()
        initialLoadJob = scope.launch(dispatcher) {
            while (connected && !loadedThisConnection) {
                if (loadList(limit)) break
                delay(config.initialRetryMs)
            }
        }
        scope.launch(dispatcher) { refreshScheduleLinks() }
        publish()
    }

    private fun onDisconnected() {
        connected = false
        val now = clock.millis()
        disconnectCap = now
        scope.launch { runCatching { persistence.setLastDisconnectAt(now) } }
        initialLoadJob?.cancel()
        drainJob?.cancel()
        if (inFlightTurns.isNotEmpty()) {
            for ((id, requestId) in inFlightTurns) suspects[id] = Suspect(connectedHubId, requestId)
            inFlightTurns.clear()
            persistSuspects()
        }
        publish()
    }

    /** `ON_RESUME` (§1.9 item 2): refresh when the last list is older than 10 s; schedule links after 2 min. */
    fun onResume() {
        scope.launch(dispatcher) {
            if (!connected) return@launch
            if (isStale()) loadList(limit)
            val at = linksAt
            if (at == null || clock.millis() - at > config.scheduleStaleAfterMs) refreshScheduleLinks()
        }
    }

    /** `ON_STOP`: the unread cap moves to now while the socket is still live (§1.2.1 item 5). */
    fun onStop() {
        scope.launch(dispatcher) {
            if (!connected) return@launch
            val now = clock.millis()
            runCatching { persistence.setLastDisconnectAt(now) }
        }
    }

    /** The drawer opened (compact/medium, §1.9 item 3). */
    fun onDrawerOpened() {
        scope.launch(dispatcher) { if (connected && isStale()) loadList(limit) }
    }

    private fun isStale(): Boolean {
        val at = lastListAt ?: return true
        return clock.millis() - at > config.staleAfterMs
    }

    // ---- loading ----------------------------------------------------------------------------------

    /** Pull-to-refresh (S2, §1.9 item 4). Returns whether the list loaded. */
    suspend fun refresh(): Boolean = withContext(dispatcher) {
        if (!connected) return@withContext false
        refreshing = true
        publish()
        try {
            loadList(limit)
        } finally {
            refreshing = false
            publish()
        }
    }

    /** "Show more" / "Load older" (+50, desktop `loadOlderSessions`). */
    suspend fun loadOlder(): Boolean = withContext(dispatcher) {
        if (!connected || loadingMore) return@withContext false
        loadingMore = true
        publish()
        try {
            loadList(limit + config.pageSize)
        } finally {
            loadingMore = false
            publish()
        }
    }

    /** The whole history (search, facets, oldest first): doubles the limit until a reply is short (`loadAllSessions`). */
    suspend fun loadAll(): Boolean = withContext(dispatcher) {
        if (!connected) return@withContext false
        if (lastListAt != null && lastReplySize < limit) return@withContext true
        loadingAll = true
        publish()
        try {
            var next = limit * 2
            while (connected) {
                if (!loadList(next)) return@withContext false
                if (lastReplySize < limit) return@withContext true
                next = limit * 2
            }
            false
        } finally {
            loadingAll = false
            publish()
        }
    }

    /** Schedules a list refresh after [delayMs] (after pin / fork, F3 step 3, F4 step 6). */
    internal fun scheduleRefresh(delayMs: Long) {
        scope.launch(dispatcher) {
            delay(delayMs)
            if (connected) loadList(limit)
        }
    }

    /** Runs `session.list` now, coalesced with any load in flight. */
    internal suspend fun refreshNow(): Boolean = withContext(dispatcher) { if (connected) loadList(limit) else false }

    /**
     * One coalesced `session.list` (§1.9 last paragraph): a load that started after this call
     * with at least [requested] rows answers it; otherwise this call loads.
     */
    private suspend fun loadList(requested: Int): Boolean {
        // The load in flight (if any) counts as "started after" this call: it joins it.
        val asked = if (loadInFlight) loadsStarted - 1 else loadsStarted
        return listMutex.withLock {
            if (lastLoadIndex > asked && lastLoadLimit >= requested) return@withLock lastLoadOk
            loadsStarted += 1
            loadInFlight = true
            val index = loadsStarted
            val askLimit = maxOf(requested, config.initialLimit)
            val eventOnlyBefore = eventOnly.toSet()
            val unknownBefore = pendingUnknown.toSet()
            // Every event at or below this sequence was published before the request went out.
            val bound = connectionSeen
            val ok = try {
                val reply = hub.list(askLimit)
                applyList(HubPayloads.sessionsOf(reply), askLimit, ListRequest(index, bound, eventOnlyBefore, unknownBefore))
                true
            } catch (e: HubCommandException) {
                listFailed(e.hubMessage)
                false
            } catch (e: HubConnectionClosedException) {
                listFailed(e.message.orEmpty())
                false
            } finally {
                loadInFlight = false
            }
            lastLoadIndex = index
            lastLoadLimit = askLimit
            lastLoadOk = ok
            ok
        }
    }

    private fun listFailed(message: String) {
        // Before the first success on this connection the 2 s retry stays silent (§1.9 item 7).
        if (loadedThisConnection) load = ListLoad.Failed(message)
        publish()
    }

    /** One `session.list` request: its load index, its [bound] and the state when it went out. */
    private class ListRequest(val index: Long, val bound: Long, val eventOnlyBefore: Set<String>, val unknownBefore: Set<String>)

    /**
     * Replaces the live rows with the reply, which is authoritative (trial row 8; the desktop's
     * list is its `list_discovered_sessions` result): a row it does not return is dropped (deleted
     * here or by another client, or now beyond the page). The one exception is the race with the
     * request: a row inserted while the reply was on its way, by a command reply or by an event
     * newer than the request's bound, may be newer than the reply and stays until the next list.
     * A reply requested before a session was forgotten cannot bring it back ([tombstones]).
     */
    private fun applyList(records: List<JsonObject>, askedLimit: Int, request: ListRequest) {
        val keep = alwaysKeep()
        val returned = HashSet<String>()
        val mapped = LinkedHashMap<String, SessionSummary>()
        for (record in records) {
            val id = record.str("sessionId")?.trim().orEmpty()
            if (id.isEmpty()) continue
            val tombstone = tombstones[id]
            if (tombstone != null) {
                if (request.index <= tombstone.afterLoad) continue
                tombstones.remove(id)
            }
            returned += id
            if (!isListableRecord(record, keep)) continue
            val summary = mapRecord(record, liveRows[id]) ?: continue
            mapped[summary.id] = summary
            noteRecordForSuspect(summary)
        }
        val carried = liveRows.values.filter { row ->
            row.id in eventOnly && row.id !in returned && row.id !in request.eventOnlyBefore &&
                (insertedAt[row.id]?.let { it > request.bound } ?: true)
        }
        // Unknown sessions a refresh did not bring in are teammates or deleted: stop chasing them.
        ignoredUnknown.addAll(request.unknownBefore - mapped.keys)
        pendingUnknown.removeAll(request.unknownBefore)
        liveRows.clear()
        mapped.values.forEach { liveRows[it.id] = it }
        carried.forEach { liveRows[it.id] = it }
        val carriedIds = carried.mapTo(HashSet()) { it.id }
        eventOnly.retainAll(carriedIds)
        insertedAt.keys.retainAll(carriedIds)
        listedIds.clear()
        listedIds.addAll(returned)
        listBound = request.bound
        // Live from here on: the restored cache rows are never shown again.
        cacheRows = emptyList()
        limit = askedLimit
        lastReplySize = records.size
        lastListAt = clock.millis()
        source = ListSource.Live
        load = ListLoad.Loaded
        loadedThisConnection = true
        publish()
        writeCache()
        scheduleTitleHydration()
    }

    private val pendingUnknown = HashSet<String>()

    // ---- events (the pump's side observer) --------------------------------------------------------

    /**
     * One event from the `SessionStore` pump ([SessionEventTap]), before the store applies it.
     * Runs on [dispatcher] after the saved state is restored; never suspends on the network.
     */
    suspend fun observe(event: HubEventEnvelope) = withContext(dispatcher) {
        restored.await()
        observeLocked(event)
    }

    private fun observeLocked(event: HubEventEnvelope) {
        val seq = event.sequence
        if (seq != null && seq > highestSeenGlobal) highestSeenGlobal = seq
        if (seq != null && connected && seq > connectionSeen) connectionSeen = seq
        val name = event.event
        if (name.startsWith("schedule.")) {
            scheduleLinksSoon()
        }
        val sessionId = event.sessionId
        var dirty = false
        if (sessionId != null) {
            val p = event.payload
            val known = sessionId in liveRows || cacheRows.any { it.id == sessionId }
            if (known && seq != null) highestSeq.merge(sessionId, seq, ::maxOf)
            when (name) {
                HubEvents.SESSION_CREATED -> {
                    p?.obj("session")?.let { record ->
                        if (event.clientId == hub.clientId || record.str("createdByClientId") == hub.clientId) createdHere += sessionId
                        dirty = upsertRecord(record, seq, created = true) || dirty
                    }
                }
                HubEvents.SESSION_UPDATED -> {
                    val record = p?.obj("session")
                    if (record != null) {
                        dirty = upsertRecord(record, seq) || dirty
                    } else {
                        val snapshotStatus = p?.obj("snapshot")?.str("status")
                        val row = liveRows[sessionId]
                        if (row != null && snapshotStatus != null) {
                            val status = KernelSessionStatus.parse(snapshotStatus)
                            if (status != row.kernelStatus) {
                                liveRows[sessionId] = row.copy(kernelStatus = status)
                                dirty = true
                            }
                        }
                    }
                }
                HubEvents.RUN_STARTED -> {
                    overrides.remove(sessionId)
                    if (interrupted.remove(sessionId)) dirty = true
                    val requestId = p?.str("requestId")
                    val suspect = suspects[sessionId]
                    // A replay can repeat the suspect's own run.started (cursor flushed ≤ 1 s late); only a new turn resolves it.
                    if (suspect != null && requestId != null && requestId != suspect.requestId) {
                        resolveSuspect(sessionId, interrupted = false)
                        dirty = true
                    }
                    if (requestId != null && p?.str("clientId") == hub.clientId) inFlightTurns[sessionId] = requestId
                }
                HubEvents.APPROVAL_REQUESTED -> p?.str("approvalId")?.let {
                    dirty = approvals.getOrPut(sessionId) { LinkedHashSet() }.add(it) || dirty
                }
                HubEvents.APPROVAL_RESOLVED -> p?.str("approvalId")?.let {
                    dirty = (approvals[sessionId]?.remove(it) == true) || dirty
                }
                HubEvents.CAPABILITY_REQUESTED -> {
                    val requestId = p?.str("requestId")
                    if (requestId != null && p?.str("capabilityName") in QUESTION_CAPABILITIES) {
                        dirty = questions.getOrPut(sessionId) { LinkedHashSet() }.add(requestId) || dirty
                    }
                }
                HubEvents.CAPABILITY_RESOLVED -> p?.str("requestId")?.let {
                    dirty = (questions[sessionId]?.remove(it) == true) || dirty
                }
                HubEvents.USAGE_UPDATED -> dirty = applyUsageEvent(sessionId, p) || dirty
            }
            if (name in TERMINAL_RUN_EVENTS) {
                inFlightTurns.remove(sessionId)
                if (approvals.remove(sessionId) != null) dirty = true
                if (questions.remove(sessionId) != null) dirty = true
            }
            if (name.startsWith("assistant.") || name.startsWith("tool.")) overrides.remove(sessionId)
            // §1.9 item 5: an event for a session the list does not know, carrying no record to insert.
            if (sessionId !in liveRows && p?.obj("session") == null) unknownSession(sessionId)
            dirty = suspectEvidence(sessionId, event) || dirty
            if (countsAsUnread(event, sessionId)) dirty = markUnread(sessionId) || dirty
        }
        if (!drained && seq != null && seq >= connectionLastSequence) {
            drained = true
            drainJob?.cancel()
            finalizeSuspects()
            dirty = true
        }
        if (dirty) publish()
    }

    /**
     * §1.3 rule 2: insert or replace by id; keeps known usage when the new record has none.
     * [sequence] is the event's (null: a command reply); [created] marks `session.created`.
     * Trial row 8: a forgotten session comes back only through a newer `session.created`, and an
     * event the last list already answered for ([listBound]) never inserts a row that list lacked.
     */
    private fun upsertRecord(record: JsonObject, sequence: Long? = null, created: Boolean = false): Boolean {
        val id = record.str("sessionId")?.trim().orEmpty()
        if (id.isEmpty()) return false
        tombstones[id]?.let { tombstone ->
            if (!created || sequence == null || sequence <= tombstone.sequence) return false
            tombstones.remove(id)
        }
        if (id !in liveRows) {
            val bound = listBound
            if (sequence != null && bound != null && sequence <= bound && id !in listedIds) return false
        }
        if (!isListableRecord(record, alwaysKeep())) {
            val metadata = record["metadata"].asRecord()
            val child = record["isSubagent"] == JsonPrimitive(true) || metadata?.get("isSubagent") == JsonPrimitive(true) ||
                !record.str("parentSessionId").isNullOrBlank() || !metadata?.str("parentSessionId").isNullOrBlank()
            if (child) {
                ignoredUnknown += id
                return liveRows.remove(id) != null
            }
            return false
        }
        val summary = mapRecord(record, liveRows[id]) ?: return false
        if (id !in liveRows) {
            eventOnly += id
            if (sequence != null) insertedAt[id] = sequence else insertedAt.remove(id)
        }
        pendingUnknown -= id
        ignoredUnknown -= id
        noteRecordForSuspect(summary)
        val changed = liveRows[id] != summary
        liveRows[id] = summary
        return changed
    }

    /** Maps a record with the app's overlays: annotation override, optimistic pin, hydrated title, usage, schedule link. */
    private fun mapRecord(record: JsonObject, previous: SessionSummary?): SessionSummary? {
        val id = record.str("sessionId")?.trim().orEmpty()
        var summary = SessionSummary.fromRecord(record, links[id]) ?: return null
        if (summary.kernelStatus == KernelSessionStatus.Running) overrides.remove(id)
        overrides[id]?.let { o -> if (summary.activityAt > o.prevActivityAt) summary = summary.copy(activityAt = o.prevActivityAt) }
        pinOverrides[id]?.let { o -> if (o.settled) pinOverrides.remove(id) else summary = summary.copy(pinned = o.pinned) }
        if (!summary.hasCustomTitle && isPlaceholderTitle(summary.title)) hydratedTitles[id]?.let { summary = summary.copy(title = it) }
        if (summary.usage == null) {
            val cached = usageCache[id]?.second
            summary = summary.copy(usage = previous?.usage ?: cached)
        }
        return summary
    }

    private fun applyUsageEvent(sessionId: String, payload: JsonObject?): Boolean {
        val row = liveRows[sessionId] ?: return false
        val totals = payload?.obj("usage") ?: payload?.obj("totals") ?: return false
        val input = totals["inputTokens"].jsNumber()?.toLong()
        val output = totals["outputTokens"].jsNumber()?.toLong()
        if (input == null && output == null) return false
        val usage = RowUsage(input, output, row.usage?.totalCostUsd)
        if (usage == row.usage) return false
        liveRows[sessionId] = row.copy(usage = usage)
        return true
    }

    /** §1.9 item 5: an event for a session the list does not know, without a record to insert. */
    private fun unknownSession(sessionId: String) {
        if (sessionId in ignoredUnknown || !connected || source != ListSource.Live) return
        pendingUnknown += sessionId
        if (unknownRefreshJob?.isActive == true) return
        unknownRefreshJob = scope.launch(dispatcher) {
            delay(config.unknownSessionRefreshMs)
            if (connected) loadList(limit)
        }
    }

    // ---- unread (§1.6) -------------------------------------------------------------------------

    private fun countsAsUnread(event: HubEventEnvelope, sessionId: String): Boolean {
        val counts = when (event.event) {
            HubEvents.ASSISTANT_FINISHED, HubEvents.RUN_FAILED, HubEvents.RUN_ABORTED, HubEvents.APPROVAL_REQUESTED,
            HubEvents.CAPABILITY_REQUESTED, HubEvents.SESSION_NOTICE -> true
            HubEvents.RUN_COMPLETED -> event.payload?.str("reason") == "completed"
            // createdHere already holds sessions this client created (envelope or record says so).
            HubEvents.SESSION_CREATED -> sessionId !in createdHere
            else -> false
        }
        if (!counts || sessionId == visibleId) return false
        if (sessionId !in liveRows && cacheRows.none { it.id == sessionId }) return false
        val seq = event.sequence
        if (seq != null && seq <= (lastSeen[sessionId] ?: -1L)) return false
        // The current connection's hub.status, set before it subscribes: a replay can arrive before onConnected() runs.
        val replayEnd = hub.hubStatus.value?.lastSequence ?: connectionLastSequence
        val replayed = seq != null && seq <= replayEnd
        if (replayed) {
            val cap = disconnectCap ?: return false
            val at = event.timestamp ?: return false
            if (at <= cap) return false
        }
        return true
    }

    private fun markUnread(sessionId: String): Boolean {
        unread.merge(sessionId, 1, Int::plus)
        persistUnread()
        return true
    }

    /** The session is on screen (chat route resumed, app in the foreground); null when none is. */
    fun setVisibleSession(sessionId: String?) {
        scope.launch(dispatcher) {
            visibleId = sessionId
            visibleFlow.value = sessionId
            if (sessionId != null) markSeenLocked(sessionId) else publish()
        }
    }

    /** F1 step 1: clears unread and stores the highest sequence seen for the session. */
    fun markSeen(sessionId: String) {
        scope.launch(dispatcher) { markSeenLocked(sessionId) }
    }

    private fun markSeenLocked(sessionId: String) {
        val removed = unread.remove(sessionId) != null
        val seq = highestSeq[sessionId]
        if (seq != null && seq > (lastSeen[sessionId] ?: -1L)) {
            lastSeen[sessionId] = seq
            scope.launch { runCatching { persistence.setLastSeen(sessionId, seq) } }
            trimLastSeen()
        }
        if (removed) persistUnread()
        publish()
    }

    /** `sessions.lastSeen` keeps at most 500 entries; the oldest (lowest sequence) without unread go first. */
    private fun trimLastSeen() {
        val excess = lastSeen.size - config.maxLastSeen
        if (excess <= 0) return
        val drop = lastSeen.entries.filter { it.key !in unread && it.key !in suspects }.sortedBy { it.value }.take(excess).map { it.key }
        drop.forEach { id ->
            lastSeen.remove(id)
            scope.launch { runCatching { persistence.forget(id) } }
        }
    }

    private fun persistUnread() {
        unreadAgain = true
        if (unreadJob?.isActive == true) return
        unreadJob = scope.launch(dispatcher) {
            while (unreadAgain) {
                unreadAgain = false
                val snapshot = unread.toMap()
                runCatching { persistence.setUnreadCounts(snapshot) }
            }
        }
    }

    // ---- interrupted rule (§1.3) -------------------------------------------------------------------

    private fun suspectEvidence(sessionId: String, event: HubEventEnvelope): Boolean {
        if (sessionId !in suspects) return false
        val p = event.payload
        when (event.event) {
            HubEvents.RUN_COMPLETED -> {
                return if (p?.str("reason") == "completed") resolveSuspect(sessionId, interrupted = false) else indicate(sessionId)
            }
            HubEvents.RUN_FAILED, HubEvents.RUN_ABORTED -> return resolveSuspect(sessionId, interrupted = false)
            HubEvents.SESSION_UPDATED -> {
                val snapshot = p?.obj("snapshot") ?: return false
                val status = snapshot.str("status")?.lowercase()
                val ended = snapshot["endedAt"].epochMillis() != null
                if (ended || status in SUSPECT_END_STATUSES) return indicate(sessionId)
            }
        }
        return false
    }

    /** A list record that is not running is evidence too (§1.3, last bullet). */
    private fun noteRecordForSuspect(summary: SessionSummary) {
        if (summary.id in suspects && summary.kernelStatus != KernelSessionStatus.Running) indicate(summary.id)
    }

    /** Evidence during the replay waits for it to drain (the real terminal event may still follow); after, it decides. */
    private fun indicate(sessionId: String): Boolean {
        val s = suspects[sessionId] ?: return false
        return if (drained) resolveSuspect(sessionId, interrupted = true) else {
            suspects[sessionId] = s.copy(evidence = true)
            false
        }
    }

    private fun finalizeSuspects() {
        for ((id, s) in suspects.toMap()) if (s.evidence) resolveSuspect(id, interrupted = true)
    }

    private fun resolveSuspect(sessionId: String, interrupted: Boolean): Boolean {
        if (suspects.remove(sessionId) == null) return false
        if (interrupted) this.interrupted += sessionId
        persistSuspects()
        return true
    }

    private fun persistSuspects() {
        val ids = suspects.keys.toSet()
        scope.launch { runCatching { persistence.setSuspectInterrupted(ids) } }
        writeCache()
    }

    // ---- schedule links (§1.3 rule 6, customize-and-rest.md §4.8) ------------------------------

    private fun scheduleLinksSoon() {
        scheduleEventJob?.cancel()
        scheduleEventJob = scope.launch(dispatcher) {
            delay(config.scheduleEventDebounceMs)
            refreshScheduleLinks()
        }
    }

    /** Merges executions' `sessionId → scheduleId` with names from `schedule.list`; silent on failure (desktop `.catch(() => null)`). */
    internal suspend fun refreshScheduleLinks() = withContext(dispatcher) {
        if (!connected) return@withContext
        val found = try {
            val names = HashMap<String, String>()
            (hub.scheduleList()["schedules"] as? JsonArray)?.forEach { element ->
                val s = element as? JsonObject ?: return@forEach
                val id = s.str("scheduleId") ?: return@forEach
                s.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { names[id] = it }
            }
            val executions = ArrayList<JsonObject>()
            (hub.scheduleActive()["executions"] as? JsonArray)?.mapNotNullTo(executions) { it as? JsonObject }
            (hub.scheduleExecutions()["executions"] as? JsonArray)?.mapNotNullTo(executions) { it as? JsonObject }
            scheduleLinksFrom(executions, names)
        } catch (_: HubCommandException) {
            return@withContext
        } catch (_: HubConnectionClosedException) {
            return@withContext
        }
        linksAt = clock.millis()
        var changed = false
        for ((id, link) in found) {
            if (links[id] != link) {
                links[id] = link
                changed = true
            }
        }
        if (!changed) return@withContext
        for ((id, row) in liveRows.entries.toList()) {
            val link = links[id] ?: continue
            val schedule = if (row.metadata.isEmpty()) row.schedule ?: scheduleInfo(null, link) else scheduleInfo(row.metadata, link)
            if (schedule != row.schedule) liveRows[id] = row.copy(schedule = schedule)
        }
        publish()
        writeCache()
    }

    // ---- title hydration (§1.4) ----------------------------------------------------------------

    private fun scheduleTitleHydration() {
        hydrationJob?.cancel()
        hydrationJob = scope.launch(dispatcher) {
            delay(config.titleHydrationDelayMs)
            val candidates = liveRows.values.sortedWith(SessionActivityDescending)
                .filter { it.id != visibleId && !it.hasCustomTitle && isPlaceholderTitle(it.title) && it.id !in hydrationTried }
                .take(config.titleHydrationMax)
            for (row in candidates) {
                if (!connected) break
                hydrationTried += row.id
                val title = try {
                    titleFromMessages(messagePairs(HubPayloads.messagesOf(hub.messages(row.id))))
                } catch (e: HubCommandException) {
                    // The Hub has no record of it (trial row 8): the row goes at once.
                    if (e.isSessionGone()) forget(row.id)
                    null
                } catch (_: HubConnectionClosedException) {
                    null
                } ?: continue
                hydratedTitles[row.id] = title
                val current = liveRows[row.id] ?: continue
                if (!current.hasCustomTitle && isPlaceholderTitle(current.title)) {
                    liveRows[row.id] = current.copy(title = title)
                    publish()
                }
            }
        }
    }

    // ---- usage hydration (§1.5) ------------------------------------------------------------------

    /**
     * The rows S2 shows; reads usage for at most 10 **live** rows without `metadata.usage`, 4 at a
     * time, cached by `(id, activityAt)`. An empty list cancels pending reads (leaving S2).
     */
    fun requestUsage(visibleIds: List<String>) {
        scope.launch(dispatcher) {
            usageJob?.cancel()
            usageLoading.clear()
            val wanted = visibleIds.mapNotNull { liveRows[it] }
                .filter { it.isLive && it.usage == null && usageCache[it.id]?.first != it.activityAt }
                .take(config.usageMaxRows)
            if (wanted.isEmpty() || !connected) {
                publish()
                return@launch
            }
            wanted.forEach { usageLoading += it.id }
            publish()
            usageJob = scope.launch(dispatcher) {
                val permits = Semaphore(config.usageConcurrency)
                wanted.map { row ->
                    async {
                        permits.withPermit {
                            val reply = try {
                                hub.get(row.id)
                            } catch (e: HubCommandException) {
                                // `session.get` reads the Hub's records: not there means deleted (trial row 8).
                                if (e.isSessionGone()) forget(row.id)
                                null
                            } catch (_: HubConnectionClosedException) {
                                null
                            }
                            usageLoading -= row.id
                            reply?.let { usageFromGet(it) }?.let { usage ->
                                usageCache[row.id] = row.activityAt to usage
                                liveRows[row.id]?.let { current -> liveRows[row.id] = current.copy(usage = usage) }
                            }
                            publish()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    // ---- hooks for SessionActions --------------------------------------------------------------

    internal val isConnected: Boolean get() = connected

    /** §1.10: false when another action already runs on this row. */
    internal suspend fun beginAction(sessionId: String, kind: PendingKind): Boolean = withContext(dispatcher) {
        if (sessionId in pending) return@withContext false
        pending[sessionId] = kind
        publish()
        true
    }

    internal suspend fun endAction(sessionId: String) = withContext(dispatcher) {
        if (pending.remove(sessionId) != null) publish()
    }

    /** §1.3 rule 5: arm before `session.update` so the reply and later records keep the row's age. */
    internal suspend fun armAnnotationOverride(sessionId: String) = withContext(dispatcher) {
        val row = liveRows[sessionId] ?: return@withContext
        overrides.remove(sessionId)
        overrides[sessionId] = AnnotationOverride(prevActivityAt = row.activityAt, armedAt = clock.millis())
        while (overrides.size > config.maxOverrides) overrides.remove(overrides.keys.first())
        writeCache()
    }

    /** F3 optimistic pin while the write runs; null rolls back to the record's value (failure). */
    internal suspend fun setPinnedOverride(sessionId: String, pinned: Boolean?) = withContext(dispatcher) {
        if (pinned == null) pinOverrides.remove(sessionId) else pinOverrides[sessionId] = PinOverride(pinned, settled = false)
        liveRows[sessionId]?.let { row ->
            val value = pinned ?: metadataPinned(row.metadata)
            if (row.pinned != value) liveRows[sessionId] = row.copy(pinned = value)
        }
        publish()
    }

    /** The write succeeded: the overlay stays until the next record, which then carries the server's value. */
    internal suspend fun settlePinnedOverride(sessionId: String) = withContext(dispatcher) {
        pinOverrides[sessionId]?.let { pinOverrides[sessionId] = it.copy(settled = true) }
    }

    /** A record from a command reply (`session.update`, `session.create`): same rules as the event. */
    internal suspend fun applyRecord(record: JsonObject) = withContext(dispatcher) {
        if (upsertRecord(record)) publish()
    }

    /** Our resume / create is in flight for this row (visual Starting). */
    internal suspend fun setStarting(sessionId: String, value: Boolean) = withContext(dispatcher) {
        val changed = if (value) starting.add(sessionId) else starting.remove(sessionId)
        if (changed) publish()
    }

    /** A session this process created: listed even before its provider/model metadata is filled (§1.3 rule 1). */
    fun noteCreated(sessionId: String) {
        scope.launch(dispatcher) { createdHere += sessionId }
    }

    /**
     * Removes a session everywhere (F5 step 4, or `session_not_found` on open or on a read, F1
     * step 3): row, cached row, unread, last seen, usage cache, flags, overrides, suspect,
     * known-sessions entry. A tombstone keeps late and replayed events from bringing it back.
     */
    suspend fun forget(sessionId: String) = withContext(dispatcher) {
        tombstones.remove(sessionId)
        tombstones[sessionId] = Tombstone(sequence = maxOf(highestSeenGlobal, connectionSeen), afterLoad = loadsStarted)
        while (tombstones.size > config.maxTombstones) tombstones.remove(tombstones.keys.first())
        liveRows.remove(sessionId)
        cacheRows = cacheRows.filterNot { it.id == sessionId }
        eventOnly -= sessionId
        insertedAt.remove(sessionId)
        listedIds -= sessionId
        createdHere -= sessionId
        approvals.remove(sessionId)
        questions.remove(sessionId)
        starting -= sessionId
        unread.remove(sessionId)
        lastSeen.remove(sessionId)
        highestSeq.remove(sessionId)
        overrides.remove(sessionId)
        pinOverrides.remove(sessionId)
        links.remove(sessionId)
        hydratedTitles.remove(sessionId)
        usageCache.remove(sessionId)
        usageLoading -= sessionId
        inFlightTurns.remove(sessionId)
        suspects.remove(sessionId)
        interrupted -= sessionId
        ignoredUnknown += sessionId
        runCatching { persistence.forget(sessionId) }
        runCatching { persistence.removeKnownSession(sessionId) }
        persistUnread()
        persistSuspects()
        publish()
        _removed.tryEmit(sessionId)
    }

    /** The current row model for [sessionId], if listed. */
    fun summary(sessionId: String): SessionSummary? = _state.value.row(sessionId)?.summary

    // ---- publishing and the cache ---------------------------------------------------------------

    private fun alwaysKeep(): Set<String> = buildSet {
        addAll(createdHere)
        visibleId?.let(::add)
    }

    private fun flagsFor(id: String): SessionFlags = SessionFlags(
        pendingAction = pending[id],
        openApprovalIds = approvals[id].orEmpty().toSet(),
        openQuestionIds = questions[id].orEmpty().toSet(),
        starting = id in starting,
        unread = (unread[id] ?: 0) > 0,
        interrupted = id in interrupted,
    )

    private fun loadedFacts(s: SessionState): LoadedFacts = LoadedFacts(
        sessionId = s.sessionId,
        seeded = s.seed is SeedState.Seeded,
        status = s.facts.status,
        approvalIds = s.facts.approvals.keys,
        questionIds = s.facts.questions.keys,
        creating = s.creating,
    )

    /** Facts from `SessionStore` win for the seeded session on screen (docs/07 §4.4). */
    private fun withLoadedFacts(row: SessionRow): SessionRow {
        val s = loaded ?: return row
        if (s.sessionId != row.id || !s.seeded) return row
        val status = KernelSessionStatus.parse(s.status.wire).takeIf { it != KernelSessionStatus.Unknown } ?: row.summary.kernelStatus
        return row.copy(
            summary = row.summary.copy(kernelStatus = status),
            flags = row.flags.copy(
                openApprovalIds = row.flags.openApprovalIds + s.approvalIds,
                openQuestionIds = row.flags.openQuestionIds + s.questionIds,
                starting = row.flags.starting || s.creating,
            ),
        )
    }

    private fun publish() {
        publishCount++
        val base = when (source) {
            ListSource.Live -> liveRows.values.toList()
            ListSource.Cache -> cacheRows
            ListSource.None -> emptyList()
        }
        val rows = base.sortedWith(SessionActivityDescending).map { withLoadedFacts(SessionRow(it, flagsFor(it.id))) }
        _state.value = SessionsSnapshot(
            rows = rows,
            source = source,
            load = load,
            connected = connected,
            limit = limit,
            mayHaveMore = lastListAt != null && lastReplySize >= limit,
            loadingMore = loadingMore,
            loadingAll = loadingAll,
            refreshing = refreshing,
            lastLoadedAt = lastListAt,
            replayTruncated = replayTruncated,
            pending = pending.toMap(),
            usageLoading = usageLoading.toSet(),
        )
    }

    /**
     * The cache holds what the last list returned, with the events since applied to those rows
     * (trial row 8). Rows only events or replies inserted ([eventOnly]) wait for a list to confirm
     * them, so a replayed or since-deleted session never reaches the file.
     */
    private fun writeCache() {
        cacheAgain = true
        if (cacheJob?.isActive == true) return
        cacheJob = scope.launch(dispatcher) {
            while (cacheAgain) {
                cacheAgain = false
                val rows = if (source == ListSource.Live) {
                    liveRows.values.filter { it.id !in eventOnly }
                        .sortedWith(SessionActivityDescending).take(config.cacheRows).map { it.forCache() }
                } else {
                    cacheRows
                }
                val payload = SessionsCachePayload(
                    rows = rows,
                    overrides = overrides.toMap(),
                    scheduleLinks = links.toMap(),
                    suspects = suspects.mapValues { (_, s) -> SuspectRecord(s.hubId, s.requestId) },
                )
                runCatching { cache.write(payload) }
            }
        }
    }

    companion object {
        const val INITIAL_LIMIT = 50
        const val PAGE_SIZE = 50
        const val ASK_QUESTION_CAPABILITY = "tool_executor.askQuestion"

        /** Capabilities that put a question in front of the user; the G2 mistake-limit prompt is asked like one. */
        private val QUESTION_CAPABILITIES = setOf(ASK_QUESTION_CAPABILITY, HubCapabilities.MISTAKE_LIMIT)

        private val TERMINAL_RUN_EVENTS = setOf(HubEvents.RUN_COMPLETED, HubEvents.RUN_FAILED, HubEvents.RUN_ABORTED, HubEvents.RUN_INTERRUPTED)

        /** `session.updated` statuses that end a suspect's turn (the shutdown projection pair, FX 05). */
        private val SUSPECT_END_STATUSES = setOf("completed", "aborted", "cancelled", "canceled", "idle")
    }
}

/** `{sessionId → link}` from schedule executions (`scheduledSessionLinks`); names fill in from `schedule.list`. */
fun scheduleLinksFrom(executions: List<JsonObject>, names: Map<String, String>): Map<String, ScheduleLink> {
    val out = LinkedHashMap<String, ScheduleLink>()
    for (execution in executions) {
        val sessionId = execution.str("sessionId")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val scheduleId = execution.str("scheduleId")?.trim()?.takeIf { it.isNotEmpty() }
        val name = scheduleId?.let(names::get) ?: execution.str("scheduleName")?.trim()?.takeIf { it.isNotEmpty() }
        val previous = out[sessionId]
        out[sessionId] = ScheduleLink(scheduleId ?: previous?.scheduleId, name ?: previous?.scheduleName)
    }
    return out
}

/** `session.messages` → `(role, text)` pairs for [titleFromMessages] (string content or text blocks). */
fun messagePairs(messages: JsonArray): List<Pair<String, String>> = messages.mapNotNull { element ->
    val message = element as? JsonObject ?: return@mapNotNull null
    val role = message.str("role") ?: return@mapNotNull null
    val text = when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "text") obj.str("text") else null
        }.joinToString("\n")
        else -> null
    } ?: return@mapNotNull null
    role to text
}

/** §1.5 step 2: `session.get` top-level `usage` (live memory) and `session.metadata.totalCost`. */
fun usageFromGet(reply: JsonObject): RowUsage? {
    val usage = reply.obj("usage") ?: return null
    val input = usage["inputTokens"].jsNumber()?.toLong()
    val output = usage["outputTokens"].jsNumber()?.toLong()
    if (input == null && output == null) return null
    val cost = reply.obj("session")?.obj("metadata")?.get("totalCost").jsNumber() ?: usage["totalCost"].jsNumber()
    return RowUsage(input, output, cost)
}
