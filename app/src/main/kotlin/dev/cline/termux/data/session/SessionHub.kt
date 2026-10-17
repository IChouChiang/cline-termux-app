package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubPendingCommand
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.chat.SessionCreateRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The slice of the Hub the session store uses (docs/07 rule 6: fake edges). Production is
 * [HubClientSessionHub] over the process's one `HubClient`; tests script a fake.
 */
interface SessionHub {
    val clientId: String

    /** Every deduplicated event in arrival order; the store is its only collector (D15). */
    val events: Flow<HubEventEnvelope>
    val connection: StateFlow<HubConnectionState>

    /** The `hub.status` reply of the current connection (`eventLog.lastSequence` for the replay drain). */
    val hubStatus: StateFlow<HubLiveStatus?>
    val draining: StateFlow<Boolean>
    val notices: Flow<HubClient.Notice>

    /** Per-session subscribe without a cursor (approval re-issue only, HC §1.1). */
    fun subscribe(sessionId: String)
    fun unsubscribe(sessionId: String)

    suspend fun get(sessionId: String): JsonObject
    suspend fun messages(sessionId: String): JsonObject
    suspend fun attach(sessionId: String): JsonObject

    /** `session.detach`: this client stops being a participant; the runtime stays (batch 1 F2, a restart's first step). */
    suspend fun detach(sessionId: String): JsonObject

    /** `session.compaction.get` (`{sessionId, state?}`): a restart passes the state on (decided A1). Owner only. */
    suspend fun compactionState(sessionId: String): JsonObject
    suspend fun create(request: SessionCreateRequest): JsonObject
    suspend fun abort(sessionId: String): JsonObject

    /** `session.list` (root sessions, newest first): lost-reply recovery of a first send's create. */
    suspend fun list(limit: Int): JsonObject

    /** Fire-and-forget `session.send_input`; throws `HubConnectionClosedException` when no socket is open. */
    fun sendInput(
        sessionId: String,
        prompt: String,
        mode: String?,
        delivery: String?,
        imageDataUrls: List<String>,
        userFiles: List<String>,
    ): HubPendingCommand

    suspend fun awaitNotDraining(timeoutMs: Long): Boolean

    /** Where the first subscribe of this process replays from at the latest (`HubClient.replayFloor`). */
    fun setReplayFloor(floor: suspend () -> Long?) = Unit
}

class HubClientSessionHub(private val client: HubClient, private val api: HubSessionApi) : SessionHub {
    override val clientId: String get() = client.clientId
    override val events: Flow<HubEventEnvelope> get() = client.events
    override val connection: StateFlow<HubConnectionState> get() = client.state
    override val hubStatus: StateFlow<HubLiveStatus?> get() = client.hubStatus
    override val draining: StateFlow<Boolean> get() = client.draining
    override val notices: Flow<HubClient.Notice> get() = client.notices

    override fun subscribe(sessionId: String) = client.subscribe(sessionId)
    override fun unsubscribe(sessionId: String) = client.unsubscribe(sessionId)

    override suspend fun get(sessionId: String): JsonObject = api.get(sessionId, includeSnapshot = true)
    override suspend fun messages(sessionId: String): JsonObject = api.messages(sessionId)
    override suspend fun attach(sessionId: String): JsonObject = api.attach(sessionId)
    override suspend fun detach(sessionId: String): JsonObject = api.detach(sessionId)
    override suspend fun compactionState(sessionId: String): JsonObject = api.compaction(sessionId)
    override suspend fun create(request: SessionCreateRequest): JsonObject = api.create(request.spec, request.metadata)
    override suspend fun abort(sessionId: String): JsonObject = api.abort(sessionId)
    override suspend fun list(limit: Int): JsonObject = api.list(limit = limit, rootOnly = true)

    override fun sendInput(
        sessionId: String,
        prompt: String,
        mode: String?,
        delivery: String?,
        imageDataUrls: List<String>,
        userFiles: List<String>,
    ): HubPendingCommand = api.sendInput(sessionId, prompt, mode, delivery, imageDataUrls, userFiles)

    override suspend fun awaitNotDraining(timeoutMs: Long): Boolean = client.awaitNotDraining(timeoutMs)

    override fun setReplayFloor(floor: suspend () -> Long?) {
        client.replayFloor = floor
    }
}
