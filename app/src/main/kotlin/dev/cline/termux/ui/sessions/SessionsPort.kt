package dev.cline.termux.ui.sessions

import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.sessions.RowUsage
import dev.cline.termux.data.sessions.SessionActions
import dev.cline.termux.data.sessions.SessionsRepository
import dev.cline.termux.data.sessions.SessionsSnapshot
import dev.cline.termux.data.sessions.usageFromGet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * What the three sessions ViewModels use (design/screens/sessions.md §1.11: "they never call
 * the Hub directly except through SessionsRepository and SessionActions"). One seam so the
 * ViewModels are tested over a fake (docs/07 rule 6) and so the graph is resolved off the
 * main thread (docs/07 §8: the first touch of `hubClient` must not block a composable).
 */
interface SessionsPort {
    /** `SessionsRepository.state`. */
    val snapshot: Flow<SessionsSnapshot>

    /** §1.9 item 3. */
    suspend fun onDrawerOpened()

    /** Pull-to-refresh (§1.9 item 4). */
    suspend fun refresh(): Boolean

    /** "Show more" (+50, `loadOlderSessions`). */
    suspend fun loadOlder(): Boolean

    /** The whole history (`loadAllSessions`). */
    suspend fun loadAll(): Boolean

    /** §1.5 step 2: the S2 rows on screen; empty cancels the reads. */
    suspend fun requestUsage(visibleIds: List<String>)

    /** F1 step 1. */
    suspend fun markSeen(sessionId: String)

    suspend fun rename(sessionId: String, input: String): ActionOutcome

    suspend fun setPinned(sessionId: String, pinned: Boolean): ActionOutcome

    /** F4, the whole session (`SessionActions.fork`). */
    suspend fun fork(sessionId: String): ActionOutcome

    suspend fun delete(sessionId: String): ActionOutcome

    /** F6: `session.get` for a live row without usage; null when the kernel has none. */
    suspend fun liveUsage(sessionId: String): RowUsage?

    /** S3: `session.search {query, limit}` with the desktop's 3 s timeout; throws on failure. */
    suspend fun search(query: String, limit: Int): JsonObject
}

/** Production [SessionsPort] over the app graph's repository, actions and session API. */
class GraphSessionsPort(
    private val repository: suspend () -> SessionsRepository,
    private val actions: suspend () -> SessionActions,
    private val api: suspend () -> HubSessionApi,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : SessionsPort {
    override val snapshot: Flow<SessionsSnapshot> = flow { emitAll(withContext(io) { repository() }.state) }

    override suspend fun onDrawerOpened() = repo().onDrawerOpened()
    override suspend fun refresh(): Boolean = repo().refresh()
    override suspend fun loadOlder(): Boolean = repo().loadOlder()
    override suspend fun loadAll(): Boolean = repo().loadAll()
    override suspend fun requestUsage(visibleIds: List<String>) = repo().requestUsage(visibleIds)
    override suspend fun markSeen(sessionId: String) = repo().markSeen(sessionId)
    override suspend fun rename(sessionId: String, input: String): ActionOutcome = act().rename(sessionId, input)
    override suspend fun setPinned(sessionId: String, pinned: Boolean): ActionOutcome = act().setPinned(sessionId, pinned)
    override suspend fun fork(sessionId: String): ActionOutcome = act().fork(sessionId)
    override suspend fun delete(sessionId: String): ActionOutcome = act().delete(sessionId)

    override suspend fun liveUsage(sessionId: String): RowUsage? =
        usageFromGet(withContext(io) { api() }.get(sessionId, includeSnapshot = true))

    override suspend fun search(query: String, limit: Int): JsonObject {
        val sessions = withContext(io) { api() }
        return withTimeout(SEARCH_TIMEOUT_MS) { sessions.search(query, limit) }
    }

    private suspend fun repo(): SessionsRepository = withContext(io) { repository() }
    private suspend fun act(): SessionActions = withContext(io) { actions() }

    companion object {
        /** `session-command-bar.tsx`: `timeoutMs: 3000`. */
        const val SEARCH_TIMEOUT_MS = 3_000L
    }
}
