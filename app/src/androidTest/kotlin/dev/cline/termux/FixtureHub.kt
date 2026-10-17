package dev.cline.termux

import android.util.Log
import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubFrame
import dev.cline.termux.core.kernel.hub.HubFrameSerializer
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.hub.HubReplyError
import dev.cline.termux.core.kernel.hub.HubSocket
import dev.cline.termux.core.kernel.hub.HubSocketClosed
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Hub that replays recorded traffic (`docs/08-hub-fixtures.md`) to the app's real
 * `HubClient` for the Compose UI flows of docs/07 §10.2.
 *
 * The fixture is cut into segments at every frame the recorder **sent**. When the app sends a
 * [scripted] command, the next recorded send of the same command (from the cursor on) is
 * matched and every frame the recorder received after it, up to the next recorded send, is
 * played back: events as recorded, replies under the app's own request id. The recorder's
 * request ids and client id are rewritten to the app's inside every frame, so `run.started`
 * finds the app's open turn and approvals target this install. Commands the fixture does not
 * drive (registration, status, lists, seeding) get neutral replies.
 */
class FixtureHub(
    private val lines: List<JsonObject>,
    private val scripted: Set<String> = DEFAULT_SCRIPTED,
    /** Playback pace between recorded frames (ms); the recording's own gaps are capped at this. */
    private val frameDelayMs: Long = 25,
    /**
     * Recorded `session.messages` histories by session id (see [historiesOf]). The app reconciles
     * a finished turn against `session.messages` (HC §3.5), so a session's history is served once
     * the playback reached its `run.completed`; before that the reply is empty, as for a new session.
     */
    private val histories: Map<String, JsonArray> = historiesOf(lines),
) {
    private val completedSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val playback = Mutex()
    private var cursor = 0
    private val requestIds = HashMap<String, String>()
    /**
     * Every fixture hub of a test process replays the same recording, but the app keeps its
     * session stores and applied sequence for the whole process (one Hub, `hub_fixture`). Each
     * instance therefore gets its own session ids (`<recorded>-f<n>`) and a sequence range above
     * the previous instance's, as a real Hub would hand out.
     */
    private val instance: Long = INSTANCES.incrementAndGet()
    private val sessionSuffix = "-f$instance"
    private val sequenceBase: Long = instance * 100_000_000L
    private val recordedSessionIds: Set<String> = lines.flatMapTo(HashSet()) { line ->
        SESSION_ID.findAll(line.toString()).map { it.groupValues[1] }
    }
    private val recordedClientId: String? = lines.firstNotNullOfOrNull { line ->
        line.envelopeOf("send")?.get("clientId")?.jsonPrimitive?.contentOrNull
    }
    private val failures = HashMap<String, HubReplyError>()

    /** Every command the app sent, in order (assertions read payloads from here). */
    val received: MutableList<HubCommandEnvelope> = CopyOnWriteArrayList()

    /** Per-session `stream.subscribe` frames the app sent (session ids). */
    val sessionSubscribes: MutableList<String> = CopyOnWriteArrayList()

    /** Called after a per-session subscribe: lets a test re-issue a pending approval (CA §0.6). */
    @Volatile var onSessionSubscribe: (sessionId: String) -> Unit = {}

    /** The app's client id, from its `client.register`. */
    @Volatile var clientId: String? = null
        private set

    /** The next [command] fails with [code] / [message] instead of being matched. */
    fun failNext(command: String, code: String, message: String) {
        synchronized(failures) { failures[command] = HubReplyError(code, message) }
    }

    /** A synthetic event on every open socket (`run.interrupted`, `capability.requested`, …). */
    fun emit(event: String, sessionId: String?, payload: JsonObject) {
        val envelope = HubEventEnvelope(
            event = event,
            eventId = "fixture-${nextSequence}",
            sequence = nextSequence++,
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            payload = payload,
        )
        sockets.forEach { it.deliver(HubFrame.Event(envelope)) }
    }

    /** The first recorded event named [event] (rewritten for this app), for re-issuing it later. */
    fun recordedEvent(event: String): HubEventEnvelope? = lines.asSequence()
        .filter { it["dir"]?.jsonPrimitive?.contentOrNull == "recv" }
        .mapNotNull { rewrite(it["frame"]!!.jsonObject) as? HubFrame.Event }
        .map { it.envelope }
        .firstOrNull { it.event == event }

    /** Re-sends [envelope] with a fresh sequence (the Hub re-issues pending approvals on subscribe). */
    fun reissue(envelope: HubEventEnvelope) {
        val copy = envelope.copy(sequence = nextSequence++)
        sockets.forEach { it.deliver(HubFrame.Event(copy)) }
    }

    /** The socket factory for `KernelOverrides.testHubSockets`. */
    fun open(): HubSocket = Socket().also { sockets += it }

    /** Drops every socket (the client reconnects to whatever hub the test installs next) and stops playback. */
    fun close() {
        sockets.forEach { it.drop() }
        sockets.clear()
        scope.cancel()
    }

    @Volatile private var nextSequence = sequenceBase + 90_000_000L

    private fun handle(socket: Socket, envelope: HubCommandEnvelope) {
        received += envelope
        Log.i(TAG, "command ${envelope.command} ${envelope.payload?.toString()?.take(400)}")
        if (envelope.command == HubCommands.CLIENT_REGISTER) clientId = envelope.clientId
        val failure = synchronized(failures) { failures.remove(envelope.command) }
        if (failure != null) {
            socket.deliver(HubFrame.Reply(HubReplyEnvelope(requestId = envelope.requestId, ok = false, error = failure)))
            return
        }
        if (envelope.command in scripted) {
            scope.launch { playback.withLock { play(socket, envelope) } }
        } else {
            socket.deliver(HubFrame.Reply(HubReplyEnvelope(requestId = envelope.requestId, ok = true, payload = neutralReply(envelope))))
        }
    }

    /** Plays the segment that follows the next recorded send of [envelope]'s command. */
    private suspend fun play(socket: Socket, envelope: HubCommandEnvelope) {
        val index = (cursor until lines.size).firstOrNull { i ->
            lines[i].envelopeOf("send")?.get("command")?.jsonPrimitive?.contentOrNull == envelope.command
        }
        if (index == null) {
            Log.w(TAG, "no recorded ${envelope.command} after frame $cursor; neutral reply")
            socket.deliver(HubFrame.Reply(HubReplyEnvelope(requestId = envelope.requestId, ok = true, payload = neutralReply(envelope))))
            return
        }
        val recordedId = lines[index].envelopeOf("send")?.get("requestId")?.jsonPrimitive?.contentOrNull
        if (recordedId != null && envelope.requestId != null) synchronized(requestIds) { requestIds[recordedId] = envelope.requestId }
        var i = index + 1
        var lastT = lines[index]["t"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        while (i < lines.size && lines[i]["dir"]?.jsonPrimitive?.contentOrNull != "send") {
            val line = lines[i]
            if (line["dir"]?.jsonPrimitive?.contentOrNull == "recv") {
                val t = line["t"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val gap = if (t != null && lastT != null) (t - lastT).toLong().coerceIn(0, frameDelayMs) else frameDelayMs
                lastT = t ?: lastT
                delay(gap)
                val frame = rewrite(line["frame"]!!.jsonObject)
                Log.i(TAG, "replay ${frame?.let { it::class.simpleName } ?: "dropped"} ${(frame as? HubFrame.Event)?.envelope?.event ?: ""}")
                (frame as? HubFrame.Event)?.envelope?.let { event ->
                    if (event.event == HubEvents.RUN_COMPLETED) event.sessionId?.let(completedSessions::add)
                }
                frame?.let(socket::deliver)
            }
            i++
        }
        cursor = i
    }

    /** The recorded frame with the recorder's request and client ids replaced by the app's. */
    private fun rewrite(frame: JsonObject): HubFrame? {
        var text = HubJson.encodeToString(JsonObject.serializer(), frame)
        synchronized(requestIds) {
            requestIds.forEach { (recorded, app) -> text = text.replace("\"$recorded\"", "\"$app\"") }
        }
        val mine = clientId
        if (recordedClientId != null && mine != null) text = text.replace("\"$recordedClientId\"", "\"$mine\"")
        recordedSessionIds.forEach { id -> text = text.replace("\"$id\"", "\"$id$sessionSuffix\"") }
        val frameObject = HubJson.parseToJsonElement(text).jsonObject
        val decoded = runCatching { HubFrameSerializer.decodeFrame(frameObject) }.getOrNull()
            ?.let { if (it is HubFrame.Event) HubFrame.Event(it.envelope.copy(sequence = it.envelope.sequence?.plus(sequenceBase))) else it }
            ?: return null
        // A reply whose request the app never sent (its command was not replayed) is dropped.
        if (decoded is HubFrame.Reply) {
            val id = decoded.envelope.requestId
            val known = synchronized(requestIds) { id in requestIds.values }
            if (!known) return null
        }
        return decoded
    }

    private fun neutralReply(envelope: HubCommandEnvelope): JsonObject = when (envelope.command) {
        HubCommands.CLIENT_REGISTER -> buildJsonObject { put("registered", true) }
        HubCommands.HUB_STATUS -> buildJsonObject {
            put("hubId", "hub_fixture")
            put("draining", false)
            put("activeRpcTurns", 0)
            put("pendingRuns", 0)
            putJsonObject("eventLog") { put("lastSequence", 0) }
            put("idle", true)
        }
        HubCommands.SESSION_LIST, HubCommands.SESSION_SEARCH -> buildJsonObject { putJsonArray("sessions") {} }
        HubCommands.SESSION_MESSAGES -> {
            val sessionId = envelope.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: envelope.sessionId
            val history = sessionId?.takeIf { it in completedSessions }?.let { histories[it.removeSuffix(sessionSuffix)] }
            buildJsonObject {
                sessionId?.let { put("sessionId", it) }
                put("messages", history ?: JsonArray(emptyList()))
            }
        }
        HubCommands.SESSION_PENDING_PROMPTS -> buildJsonObject { putJsonArray("prompts") {} }
        HubCommands.SETTINGS_LIST -> buildJsonObject { putJsonArray("items") {} }
        HubCommands.SCHEDULE_LIST, HubCommands.SCHEDULE_ACTIVE, HubCommands.SCHEDULE_UPCOMING -> buildJsonObject {
            putJsonArray("schedules") {}
            putJsonArray("executions") {}
        }
        else -> JsonObject(emptyMap())
    }

    private inner class Socket : HubSocket {
        private val inbound = Channel<HubFrame>(Channel.UNLIMITED)
        @Volatile private var closed = false

        override val incoming: Flow<HubFrame> = inbound.receiveAsFlow()

        override fun send(frame: HubFrame): Boolean {
            if (closed) return false
            when (frame) {
                is HubFrame.Command -> handle(this, frame.envelope)
                is HubFrame.StreamSubscribe -> frame.sessionId?.let { id ->
                    sessionSubscribes += id
                    onSessionSubscribe(id)
                }
                else -> Unit
            }
            return true
        }

        fun deliver(frame: HubFrame) {
            if (!closed) inbound.trySend(frame)
        }

        override fun close(code: Int, reason: String) {
            if (closed) return
            closed = true
            sockets.remove(this)
            inbound.close(HubSocketClosed(code, reason))
        }

        fun drop() {
            closed = true
            inbound.close(HubSocketClosed(1006, "fixture hub closed"))
        }
    }

    companion object {
        /** Logcat tag: every command the app sends and every replayed frame (`adb logcat -s FixtureHub`). */
        private const val TAG = "FixtureHub"

        private val INSTANCES = java.util.concurrent.atomic.AtomicLong(0)
        private val SESSION_ID = Regex("\"sessionId\":\"([^\"]+)\"")

        /** The commands whose recorded replies and follow-up events are replayed. */
        val DEFAULT_SCRIPTED: Set<String> = setOf(
            HubCommands.SESSION_CREATE,
            HubCommands.SESSION_SEND_INPUT,
            HubCommands.APPROVAL_RESPOND,
            HubCommands.CAPABILITY_RESPOND,
            HubCommands.RUN_ABORT,
        )

        /** The last recorded `session.messages` reply per session id in [lines]. */
        fun historiesOf(lines: List<JsonObject>): Map<String, JsonArray> {
            val requests = HashMap<String, String>()
            val histories = LinkedHashMap<String, JsonArray>()
            for (line in lines) {
                val frame = line["frame"] as? JsonObject ?: continue
                val envelope = frame["envelope"] as? JsonObject ?: continue
                val kind = frame["kind"]?.jsonPrimitive?.contentOrNull
                val requestId = envelope["requestId"]?.jsonPrimitive?.contentOrNull ?: continue
                if (line["dir"]?.jsonPrimitive?.contentOrNull == "send" && kind == "command" &&
                    envelope["command"]?.jsonPrimitive?.contentOrNull == HubCommands.SESSION_MESSAGES
                ) {
                    val sessionId = (envelope["payload"] as? JsonObject)?.get("sessionId")?.jsonPrimitive?.contentOrNull
                        ?: envelope["sessionId"]?.jsonPrimitive?.contentOrNull ?: continue
                    requests[requestId] = sessionId
                } else if (kind == "reply") {
                    val sessionId = requests.remove(requestId) ?: continue
                    val messages = (envelope["payload"] as? JsonObject)?.get("messages") as? JsonArray ?: continue
                    histories[sessionId] = messages
                }
            }
            return histories
        }

        /** Parses a `*.jsonl` fixture. */
        fun parse(text: String): List<JsonObject> =
            text.lineSequence().filter { it.isNotBlank() }.map { HubJson.parseToJsonElement(it).jsonObject }.toList()

        private fun JsonObject.envelopeOf(dir: String): JsonObject? {
            if (this["dir"]?.jsonPrimitive?.contentOrNull != dir) return null
            val frame = this["frame"]?.jsonObject ?: return null
            if (frame["kind"]?.jsonPrimitive?.contentOrNull != "command") return null
            return frame["envelope"]?.jsonObject
        }

        /** `capability.requested` for `tool_executor.askQuestion` (CA C4), targeted at [clientId]. */
        fun askQuestionPayload(requestId: String, clientId: String, question: String, options: List<String>): JsonObject = buildJsonObject {
            put("requestId", requestId)
            put("targetClientId", clientId)
            put("capabilityName", "tool_executor.askQuestion")
            putJsonObject("payload") {
                put("args", JsonArray(listOf(JsonPrimitive(question), JsonArray(options.map(::JsonPrimitive)))))
            }
        }
    }
}
