package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubPendingCommand
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.hub.HubReplyError
import dev.cline.termux.data.chat.SessionCreateRequest
import dev.cline.termux.data.session.SessionHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted Hub behind the `SessionHub` seam (docs/07 §10.1). Commands answer from the
 * settable lambdas; `send_input` hands out requestIds from [nextRequestIds] (fixture replays
 * use the recorded ids) and leaves the reply pending until the test completes it.
 */
class FakeSessionHub(override val clientId: String = "android-test") : SessionHub {
    override val events = MutableSharedFlow<HubEventEnvelope>(extraBufferCapacity = 4096)
    override val connection = MutableStateFlow<HubConnectionState>(HubConnectionState.Connected("ws://127.0.0.1:25463/hub"))
    override val hubStatus = MutableStateFlow<HubLiveStatus?>(null)
    override val draining = MutableStateFlow(false)
    override val notices = MutableSharedFlow<HubClient.Notice>(extraBufferCapacity = 16)

    val subscribed = mutableListOf<String>()
    val unsubscribed = mutableListOf<String>()
    val calls = mutableListOf<String>()
    val creates = mutableListOf<SessionCreateRequest>()
    val sends = mutableListOf<SentInput>()
    val nextRequestIds = ArrayDeque<String>()
    private var counter = 0

    /** Transcript `session.messages` answers with. */
    var history: JsonArray = JsonArray(emptyList())
    var onGet: (String) -> JsonObject = { id -> buildJsonObject { put("session", buildJsonObject { put("sessionId", id); put("status", "idle") }) } }
    var onMessages: (String) -> JsonObject = { id -> buildJsonObject { put("sessionId", id); put("messages", history) } }
    var onAttach: (String) -> JsonObject = { id -> buildJsonObject { put("sessionId", id) } }
    var onDetach: (String) -> JsonObject = { id -> buildJsonObject { put("sessionId", id) } }

    /** `session.compaction.get`: no `state` (nothing compacted yet) unless a test sets one. */
    var onCompaction: (String) -> JsonObject = { id -> buildJsonObject { put("sessionId", id) } }
    var onCreate: (SessionCreateRequest) -> JsonObject = { req ->
        buildJsonObject { put("session", buildJsonObject { put("sessionId", req.spec.resumeSessionId ?: "s-new"); put("status", "idle") }) }
    }
    var onAbort: (String) -> JsonObject = { buildJsonObject { put("applied", true) } }
    var onList: (Int) -> JsonObject = { buildJsonObject { put("sessions", JsonArray(emptyList())) } }

    /** When false, `sendInput` throws like a client without a socket. */
    var socketOpen = true

    data class SentInput(
        val sessionId: String,
        val prompt: String,
        val mode: String?,
        val delivery: String?,
        val requestId: String,
        val reply: CompletableDeferred<HubReplyEnvelope>,
    ) {
        fun ok(payload: JsonObject = JsonObject(emptyMap())) {
            reply.complete(HubReplyEnvelope(requestId = requestId, ok = true, payload = payload))
        }

        fun fail(code: String, message: String = code) {
            reply.complete(HubReplyEnvelope(requestId = requestId, ok = false, error = HubReplyError(code, message)))
        }
    }

    override fun subscribe(sessionId: String) {
        subscribed += sessionId
    }

    override fun unsubscribe(sessionId: String) {
        unsubscribed += sessionId
    }

    override suspend fun get(sessionId: String): JsonObject = record("session.get") { onGet(sessionId) }
    override suspend fun messages(sessionId: String): JsonObject = record("session.messages") { onMessages(sessionId) }
    override suspend fun attach(sessionId: String): JsonObject = record("session.attach") { onAttach(sessionId) }
    override suspend fun detach(sessionId: String): JsonObject = record("session.detach") { onDetach(sessionId) }
    override suspend fun compactionState(sessionId: String): JsonObject = record("session.compaction.get") { onCompaction(sessionId) }
    override suspend fun abort(sessionId: String): JsonObject = record("run.abort") { onAbort(sessionId) }
    override suspend fun list(limit: Int): JsonObject = record("session.list") { onList(limit) }

    /** When set, `session.create` waits for it before it replies (a create in flight). */
    var createGate: CompletableDeferred<Unit>? = null

    override suspend fun create(request: SessionCreateRequest): JsonObject {
        createGate?.await()
        return record("session.create") {
            creates += request
            onCreate(request)
        }
    }

    override fun sendInput(
        sessionId: String,
        prompt: String,
        mode: String?,
        delivery: String?,
        imageDataUrls: List<String>,
        userFiles: List<String>,
    ): HubPendingCommand {
        if (!socketOpen) throw HubConnectionClosedException("not connected")
        calls += "session.send_input"
        val requestId = nextRequestIds.removeFirstOrNull() ?: "$clientId-${++counter}"
        val reply = CompletableDeferred<HubReplyEnvelope>()
        sends += SentInput(sessionId, prompt, mode, delivery, requestId, reply)
        return HubPendingCommand(requestId, reply)
    }

    override suspend fun awaitNotDraining(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { draining.first { !it } } != null

    /** The store's replay floor, as `HubClient.replayFloor` would hold it. */
    var replayFloor: (suspend () -> Long?)? = null
        private set

    override fun setReplayFloor(floor: suspend () -> Long?) {
        replayFloor = floor
    }

    private inline fun record(name: String, block: () -> JsonObject): JsonObject {
        calls += name
        if (!socketOpen) throw HubConnectionClosedException("not connected")
        return block()
    }

    companion object {
        fun error(command: String, code: String, message: String = code) = HubCommandException(command, code, message)
    }
}
