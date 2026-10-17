package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.data.sessions.SessionsHub
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted Hub behind the `SessionsHub` seam (docs/07 §10.1): each command answers from a
 * settable lambda and is recorded in [calls]; `session.update` payloads land in [updates].
 */
class FakeSessionsHub(override val clientId: String = "android-test") : SessionsHub {
    override val connection = MutableStateFlow<HubConnectionState>(HubConnectionState.Disconnected)
    override val hubStatus = MutableStateFlow<HubLiveStatus?>(null)
    override val notices = MutableSharedFlow<HubClient.Notice>(extraBufferCapacity = 16)

    val calls = mutableListOf<String>()
    val listLimits = mutableListOf<Int>()
    val updates = mutableListOf<JsonObject>()
    val gets = mutableListOf<String>()

    var onList: suspend (Int) -> JsonObject = { buildJsonObject { put("sessions", JsonArray(emptyList())) } }
    var onGet: suspend (String) -> JsonObject = { id -> buildJsonObject { put("session", buildJsonObject { put("sessionId", id) }) } }
    var onMessages: suspend (String) -> JsonObject = { id -> buildJsonObject { put("sessionId", id); put("messages", JsonArray(emptyList())) } }
    var onUpdate: suspend (JsonObject) -> JsonObject = { buildJsonObject { put("updated", true) } }
    var onDelete: suspend (String) -> JsonObject = { buildJsonObject { put("deleted", true) } }
    var onScheduleList: suspend () -> JsonObject = { buildJsonObject { put("schedules", JsonArray(emptyList())) } }
    var onScheduleActive: suspend () -> JsonObject = { buildJsonObject { put("executions", JsonArray(emptyList())) } }
    var onScheduleExecutions: suspend () -> JsonObject = { buildJsonObject { put("executions", JsonArray(emptyList())) } }

    /** When false, every command throws like a client without a socket. */
    var socketOpen = true

    /** [generation]: a new value is a new registration even without a state in between (a conflated reconnect). */
    fun connect(lastSequence: Long = 0, hubId: String? = "hub_a", generation: Long = 0) {
        hubStatus.value = HubLiveStatus(draining = false, activeRpcTurns = 0, pendingRuns = 0, lastSequence = lastSequence, idle = true, hubId = hubId)
        connection.value = HubConnectionState.Connected("ws://127.0.0.1:25463/hub", generation)
    }

    fun disconnect() {
        connection.value = HubConnectionState.Reconnecting(1, "closed")
    }

    override suspend fun list(limit: Int): JsonObject = record("session.list") {
        listLimits += limit
        onList(limit)
    }

    override suspend fun get(sessionId: String): JsonObject = record("session.get") {
        gets += sessionId
        onGet(sessionId)
    }

    override suspend fun messages(sessionId: String): JsonObject = record("session.messages") { onMessages(sessionId) }

    override suspend fun update(payload: JsonObject): JsonObject = record("session.update") {
        updates += payload
        onUpdate(payload)
    }

    override suspend fun delete(sessionId: String): JsonObject = record("session.delete") { onDelete(sessionId) }
    override suspend fun scheduleList(): JsonObject = record("schedule.list") { onScheduleList() }
    override suspend fun scheduleActive(): JsonObject = record("schedule.active") { onScheduleActive() }
    override suspend fun scheduleExecutions(): JsonObject = record("schedule.list_executions") { onScheduleExecutions() }

    private suspend fun record(name: String, block: suspend () -> JsonObject): JsonObject {
        calls += name
        if (!socketOpen) throw HubConnectionClosedException("not connected")
        return block()
    }

    companion object {
        fun error(command: String, code: String, message: String = code) = HubCommandException(command, code, message)
    }
}
