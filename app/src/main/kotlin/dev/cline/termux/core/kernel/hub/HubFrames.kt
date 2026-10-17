package dev.cline.termux.core.kernel.hub

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wire types of the Cline Hub WebSocket protocol, transcribed from
 * `sdk/packages/shared/src/hub.ts` (`HubTransportFrame`, `HubCommandEnvelope`,
 * `HubReplyEnvelope`, `HubEventEnvelope`). Payloads stay as [JsonObject]; typed
 * views live next to the features that read them.
 */
const val HUB_PROTOCOL_VERSION = "v1"

/** Lenient JSON for everything that crosses the Hub socket. */
val HubJson: Json = Json {
    ignoreUnknownKeys = true
    // Defaults are encoded so `version: "v1"` is always on the wire; nulls are
    // dropped so absent optionals are omitted rather than sent as null.
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

@Serializable(with = HubFrameSerializer::class)
sealed interface HubFrame {
    @Serializable
    data class Command(val envelope: HubCommandEnvelope) : HubFrame

    @Serializable
    data class Reply(val envelope: HubReplyEnvelope) : HubFrame

    @Serializable
    data class StreamSubscribe(
        val clientId: String,
        val sessionId: String? = null,
        /** Replay cursor: events with `sequence > sinceSequence` are replayed first. */
        val sinceSequence: Long? = null,
    ) : HubFrame

    @Serializable
    data class StreamUnsubscribe(
        val clientId: String,
        val sessionId: String? = null,
    ) : HubFrame

    @Serializable
    data class Event(val envelope: HubEventEnvelope) : HubFrame
}

@Serializable
data class HubCommandEnvelope(
    val version: String = HUB_PROTOCOL_VERSION,
    val command: String,
    val requestId: String? = null,
    val clientId: String? = null,
    val sessionId: String? = null,
    /** `null` disables the server-side timeout (run.start / session.send_input). */
    val timeoutMs: Long? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class HubReplyError(
    val code: String,
    val message: String,
    /** Free-form; upstream types it as a record but nothing guarantees an object on the wire. */
    val details: JsonElement? = null,
)

@Serializable
data class HubReplyEnvelope(
    val version: String = HUB_PROTOCOL_VERSION,
    val requestId: String? = null,
    val ok: Boolean,
    val payload: JsonObject? = null,
    val error: HubReplyError? = null,
)

@Serializable
data class HubEventEnvelope(
    val version: String = HUB_PROTOCOL_VERSION,
    val event: String,
    val eventId: String? = null,
    /** Monotonic global sequence from the Hub's durable event log; absent without a log. */
    val sequence: Long? = null,
    val sessionId: String? = null,
    val clientId: String? = null,
    val sourceHubId: String? = null,
    val timestamp: Long? = null,
    val payload: JsonObject? = null,
)

/** `{"kind": ..., ...}` discriminated union, flattened like the TypeScript source. */
object HubFrameSerializer : KSerializer<HubFrame> {
    private val json get() = HubJson

    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("HubFrame")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): HubFrame {
        val input = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("HubFrame can only be decoded from JSON")
        val obj = input.decodeJsonElement().jsonObject
        return decodeFrame(obj)
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: HubFrame) {
        val output = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: error("HubFrame can only be encoded to JSON")
        output.encodeJsonElement(encodeFrame(value))
    }

    fun decodeFrame(obj: JsonObject): HubFrame {
        val kind = obj["kind"]?.jsonPrimitive?.content ?: throw HubProtocolException("frame without kind")
        return when (kind) {
            "command" -> HubFrame.Command(json.decodeFromJsonElement(HubCommandEnvelope.serializer(), envelopeOf(obj)))
            "reply" -> HubFrame.Reply(json.decodeFromJsonElement(HubReplyEnvelope.serializer(), envelopeOf(obj)))
            "event" -> HubFrame.Event(json.decodeFromJsonElement(HubEventEnvelope.serializer(), envelopeOf(obj)))
            "stream.subscribe" -> json.decodeFromJsonElement(HubFrame.StreamSubscribe.serializer(), withoutKind(obj))
            "stream.unsubscribe" -> json.decodeFromJsonElement(HubFrame.StreamUnsubscribe.serializer(), withoutKind(obj))
            else -> throw HubProtocolException("unknown frame kind '$kind'")
        }
    }

    fun encodeFrame(frame: HubFrame): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        when (frame) {
            is HubFrame.Command -> {
                fields["kind"] = kotlinx.serialization.json.JsonPrimitive("command")
                fields["envelope"] = json.encodeToJsonElement(HubCommandEnvelope.serializer(), frame.envelope)
            }
            is HubFrame.Reply -> {
                fields["kind"] = kotlinx.serialization.json.JsonPrimitive("reply")
                fields["envelope"] = json.encodeToJsonElement(HubReplyEnvelope.serializer(), frame.envelope)
            }
            is HubFrame.Event -> {
                fields["kind"] = kotlinx.serialization.json.JsonPrimitive("event")
                fields["envelope"] = json.encodeToJsonElement(HubEventEnvelope.serializer(), frame.envelope)
            }
            is HubFrame.StreamSubscribe -> {
                fields["kind"] = kotlinx.serialization.json.JsonPrimitive("stream.subscribe")
                fields.putAll(json.encodeToJsonElement(HubFrame.StreamSubscribe.serializer(), frame).jsonObject)
            }
            is HubFrame.StreamUnsubscribe -> {
                fields["kind"] = kotlinx.serialization.json.JsonPrimitive("stream.unsubscribe")
                fields.putAll(json.encodeToJsonElement(HubFrame.StreamUnsubscribe.serializer(), frame).jsonObject)
            }
        }
        return JsonObject(fields)
    }

    private fun envelopeOf(obj: JsonObject): JsonElement =
        obj["envelope"] ?: throw HubProtocolException("frame without envelope")

    private fun withoutKind(obj: JsonObject): JsonObject = JsonObject(obj.filterKeys { it != "kind" })
}

class HubProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Command names the app uses (subset of `HubCommandName`). */
object HubCommands {
    const val CLIENT_REGISTER = "client.register"
    const val CLIENT_UPDATE = "client.update"
    const val CLIENT_UNREGISTER = "client.unregister"
    const val CLIENT_LIST = "client.list"
    const val HUB_STATUS = "hub.status"
    const val HUB_DRAIN = "hub.drain"
    const val SESSION_LIST = "session.list"
    const val SESSION_SEARCH = "session.search"
    const val SESSION_CREATE = "session.create"
    const val SESSION_ATTACH = "session.attach"
    const val SESSION_DETACH = "session.detach"
    const val SESSION_GET = "session.get"
    const val SESSION_MESSAGES = "session.messages"
    const val SESSION_RESTORE = "session.restore"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_UPDATE = "session.update"
    const val SESSION_UPDATE_CONNECTION = "session.update_connection"
    const val SESSION_PENDING_PROMPTS = "session.pending_prompts"
    const val SESSION_STEER_FIRST_PENDING_PROMPT = "session.steer_first_pending_prompt"
    const val SESSION_UPDATE_PENDING_PROMPT = "session.update_pending_prompt"
    const val SESSION_REMOVE_PENDING_PROMPT = "session.remove_pending_prompt"
    const val SESSION_SEND_INPUT = "session.send_input"
    /** Declared by the Hub but never dispatched (sessions.md); forks go through `session.create`. */
    const val SESSION_FORK = "session.fork"
    const val SESSION_COMPACTION_GET = "session.compaction.get"
    const val RUN_START = "run.start"
    const val RUN_ENQUEUE = "run.enqueue"
    const val RUN_ABORT = "run.abort"
    const val RUN_PROCEED_WHILE_RUNNING = "run.proceed_while_running"
    const val APPROVAL_RESPOND = "approval.respond"
    const val CAPABILITY_RESPOND = "capability.respond"
    const val SETTINGS_LIST = "settings.list"
    const val SETTINGS_GET = "settings.get"
    const val SETTINGS_PATCH = "settings.patch"
    const val SETTINGS_TOGGLE = "settings.toggle"
    const val SCHEDULE_LIST = "schedule.list"
    const val SCHEDULE_GET = "schedule.get"
    const val SCHEDULE_CREATE = "schedule.create"
    const val SCHEDULE_UPDATE = "schedule.update"
    const val SCHEDULE_DELETE = "schedule.delete"
    const val SCHEDULE_ENABLE = "schedule.enable"
    const val SCHEDULE_DISABLE = "schedule.disable"
    const val SCHEDULE_TRIGGER = "schedule.trigger"
    const val SCHEDULE_LIST_EXECUTIONS = "schedule.list_executions"
    const val SCHEDULE_UPCOMING = "schedule.upcoming"
    const val SCHEDULE_ACTIVE = "schedule.active"
    const val SCHEDULE_STATS = "schedule.stats"
    const val CONNECTOR_CHANNELS = "connector.channels"
    const val CONNECTOR_CONFIGURE = "connector.configure"
    const val CONNECTOR_DELETE_CONFIG = "connector.delete_config"
    const val CONNECTOR_START = "connector.start"
    const val CONNECTOR_STOP = "connector.stop"
    const val UI_NOTIFY = "ui.notify"

    /** Commands whose reply waits for a whole agent turn: no server-side timeout. */
    val LONG_RUNNING = setOf(RUN_START, SESSION_SEND_INPUT)

    /** The only commands the Hub refuses with `hub_draining` (`DRAIN_REFUSED_COMMANDS`; docs/07 §3.1). */
    val DRAIN_GATED = setOf(SESSION_CREATE, SESSION_RESTORE, SESSION_FORK, RUN_START, SESSION_SEND_INPUT, RUN_ENQUEUE)

    /**
     * Upstream `HUB_DEFAULT_COMMAND_TIMEOUT_MS` (`sdk/packages/shared/src/hub.ts`): the Hub's own
     * timer for every command without a longer one. Stop waits as long for the run to end before
     * it says the agent did not stop (A4).
     */
    const val DEFAULT_TIMEOUT_MS = 30_000L
}

/**
 * `run.heartbeat` as a typed event (A4). The Hub publishes it every [INTERVAL_MS] while a turn
 * runs, whatever the turn does (`HUB_RUN_HEARTBEAT_MS` in
 * `sdk/packages/core/src/hub/server/handlers/run-handlers.ts`): payload
 * `{requestId, elapsedMs, timeoutMs?}`, the session on the envelope. [timestamp] is the
 * envelope's (the Hub's clock), null when the frame carried none.
 */
data class RunHeartbeat(
    val requestId: String?,
    val elapsedMs: Long?,
    val timeoutMs: Long?,
    val timestamp: Long?,
) {
    companion object {
        /** Upstream `HUB_RUN_HEARTBEAT_MS`. */
        const val INTERVAL_MS = 30_000L

        /** The typed view of [envelope]; null when it is not a `run.heartbeat`. */
        fun of(envelope: HubEventEnvelope): RunHeartbeat? {
            if (envelope.event != HubEvents.RUN_HEARTBEAT) return null
            val p = envelope.payload
            return RunHeartbeat(
                requestId = (p?.get("requestId") as? JsonPrimitive)?.contentOrNull,
                elapsedMs = (p?.get("elapsedMs") as? JsonPrimitive)?.longOrNull,
                timeoutMs = (p?.get("timeoutMs") as? JsonPrimitive)?.longOrNull,
                timestamp = envelope.timestamp,
            )
        }
    }
}

/** Event names (`HubEventName`) the transcript reducer and the shell react to. */
object HubEvents {
    const val CLIENT_REGISTERED = "hub.client.registered"
    const val CLIENT_DISCONNECTED = "hub.client.disconnected"
    const val CLIENT_UPDATED = "hub.client.updated"
    const val SESSION_CREATED = "session.created"
    const val SESSION_UPDATED = "session.updated"
    const val SESSION_ATTACHED = "session.attached"
    const val SESSION_DETACHED = "session.detached"
    const val SESSION_FORKED = "session.forked"
    const val SESSION_PENDING_PROMPTS = "session.pending_prompts"
    const val SESSION_PENDING_PROMPT_SUBMITTED = "session.pending_prompt_submitted"
    const val SESSION_NOTICE = "session.notice"
    const val RUN_STARTED = "run.started"
    const val RUN_HEARTBEAT = "run.heartbeat"
    const val RUN_ABORTED = "run.aborted"
    const val RUN_COMPLETED = "run.completed"
    const val RUN_FAILED = "run.failed"
    const val RUN_ENQUEUED = "run.enqueued"
    const val RUN_INTERRUPTED = "run.interrupted"
    const val HUB_DRAIN_CHANGED = "hub.drain_changed"
    const val ITERATION_STARTED = "iteration.started"
    const val ITERATION_FINISHED = "iteration.finished"
    const val ASSISTANT_DELTA = "assistant.delta"
    const val ASSISTANT_MEDIA = "assistant.media"
    const val ASSISTANT_FINISHED = "assistant.finished"
    const val REASONING_DELTA = "reasoning.delta"
    const val REASONING_FINISHED = "reasoning.finished"
    const val AGENT_DONE = "agent.done"
    const val USAGE_UPDATED = "usage.updated"
    const val TOOL_STARTED = "tool.started"
    const val TOOL_UPDATED = "tool.updated"
    const val TOOL_FINISHED = "tool.finished"
    const val APPROVAL_REQUESTED = "approval.requested"
    const val APPROVAL_RESOLVED = "approval.resolved"
    const val CAPABILITY_REQUESTED = "capability.requested"
    const val CAPABILITY_RESOLVED = "capability.resolved"
    const val TEAM_PROGRESS = "team.progress"
    const val DIFF_CREATED = "diff.created"
    const val ARTIFACT_CREATED = "artifact.created"
    const val SCHEDULE_CREATED = "schedule.created"
    const val SCHEDULE_UPDATED = "schedule.updated"
    const val SCHEDULE_DELETED = "schedule.deleted"
    const val SCHEDULE_TRIGGERED = "schedule.triggered"
    const val SCHEDULE_EXECUTION_COMPLETED = "schedule.execution_completed"
    const val SCHEDULE_EXECUTION_FAILED = "schedule.execution_failed"
    const val SETTINGS_CHANGED = "settings.changed"
    const val UI_NOTIFY = "ui.notify"
}

/** Capability names this client advertises in `client.register` or answers as a session contribution. */
object HubCapabilities {
    const val APPROVAL_RESPOND = "approval.respond"
    const val ASK_QUESTION = "tool_executor.askQuestion"

    /** The `mistakeLimit` contribution (`HUB_MISTAKE_LIMIT_CAPABILITY`, `@cline/shared` hub.ts). */
    const val MISTAKE_LIMIT = "mistake_limit.decide"

    /** `HUB_CUSTOM_TOOL_CAPABILITY_PREFIX` + the tool name: the `switch_to_act_mode` tool contribution. */
    const val SWITCH_TO_ACT_MODE = "custom_tool.switch_to_act_mode"
}
