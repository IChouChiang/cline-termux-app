package dev.cline.termux.data.sessions

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.session.SessionHub
import dev.cline.termux.data.tools.jsString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The slice of the Hub the sessions list uses (docs/07 rule 6: fake edges). Commands only:
 * events reach [SessionsRepository] through [SessionEventTap] on the one `SessionStore`
 * pump (D15), never through a second collector of `HubClient.events`.
 */
interface SessionsHub {
    val clientId: String
    val connection: StateFlow<HubConnectionState>

    /** The `hub.status` reply of the current connection (`hubId`, `eventLog.lastSequence`). */
    val hubStatus: StateFlow<HubLiveStatus?>

    /** `ReplayTruncated` when the replay budget skipped events (SS §1.2.1 item 2). */
    val notices: Flow<HubClient.Notice>

    /** `session.list {limit, rootOnly:true}`. */
    suspend fun list(limit: Int): JsonObject

    /** `session.get {sessionId, includeSnapshot:true}`. */
    suspend fun get(sessionId: String): JsonObject

    /** `session.messages {sessionId}`. */
    suspend fun messages(sessionId: String): JsonObject

    /** `session.update` with a payload built by [SessionUpdatePayloads] (metadata is wholesale). */
    suspend fun update(payload: JsonObject): JsonObject

    /** `session.delete {sessionId}` (no `deleteCheckpointRefs`, SS F5 step 3). */
    suspend fun delete(sessionId: String): JsonObject

    /** `schedule.list {limit:200, allWorkspaces:true}`. */
    suspend fun scheduleList(): JsonObject

    /** `schedule.active {allWorkspaces:true}`. */
    suspend fun scheduleActive(): JsonObject

    /** `schedule.list_executions {limit:50, allWorkspaces:true}` across every schedule (desktop `list_routine_schedules`). */
    suspend fun scheduleExecutions(): JsonObject
}

/** Production [SessionsHub] over the process's one `HubClient`. */
class HubClientSessionsHub(private val client: HubClient, private val api: HubSessionApi) : SessionsHub {
    override val clientId: String get() = client.clientId
    override val connection: StateFlow<HubConnectionState> get() = client.state
    override val hubStatus: StateFlow<HubLiveStatus?> get() = client.hubStatus
    override val notices: Flow<HubClient.Notice> get() = client.notices

    override suspend fun list(limit: Int): JsonObject = api.list(limit = limit, rootOnly = true)
    override suspend fun get(sessionId: String): JsonObject = api.get(sessionId, includeSnapshot = true)
    override suspend fun messages(sessionId: String): JsonObject = api.messages(sessionId)

    override suspend fun update(payload: JsonObject): JsonObject =
        client.command(HubCommands.SESSION_UPDATE, payload, payload["sessionId"].jsString())

    override suspend fun delete(sessionId: String): JsonObject =
        client.command(HubCommands.SESSION_DELETE, buildJsonObject { put("sessionId", sessionId) }, sessionId)

    override suspend fun scheduleList(): JsonObject = api.scheduleList(limit = SCHEDULE_LIST_LIMIT)
    override suspend fun scheduleActive(): JsonObject = api.scheduleActive()

    override suspend fun scheduleExecutions(): JsonObject = client.command(
        HubCommands.SCHEDULE_LIST_EXECUTIONS,
        buildJsonObject {
            put("allWorkspaces", true)
            put("limit", SCHEDULE_EXECUTIONS_LIMIT)
        },
    )

    companion object {
        const val SCHEDULE_LIST_LIMIT = 200
        const val SCHEDULE_EXECUTIONS_LIMIT = 50
    }
}

/**
 * Hands every event the `SessionStore` pump reads to [observer] first, in order, on the pump's
 * single-threaded dispatcher (docs/07 §4.2 "Side" column: the pump feeds
 * `SessionsRepository`). The pump stays the only collector of `HubClient.events` (D15).
 */
class SessionEventTap(
    private val delegate: SessionHub,
    private val observer: suspend (HubEventEnvelope) -> Unit,
) : SessionHub by delegate {
    override val events: Flow<HubEventEnvelope> get() = delegate.events.onEach { observer(it) }
}
