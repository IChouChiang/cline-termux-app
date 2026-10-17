package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubFrame
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.hub.HubReplyError
import dev.cline.termux.core.kernel.hub.HubSocket
import dev.cline.termux.core.kernel.hub.HubSocketClosed
import dev.cline.termux.core.kernel.hub.HubTransport
import dev.cline.termux.core.kernel.hub.HubUpgradeFailed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A scripted Hub behind the `HubTransport` seam (docs/07 §10.1). Every opened socket records
 * the frames the client sent; replies come from [onCommand] the moment a command is sent
 * (like a loopback Hub), events and replays are pushed by the test or by [replay].
 */
class FakeHubTransport : HubTransport {
    class FakeSocket(val url: String, val token: String?) : HubSocket {
        val inbound = Channel<HubFrame>(Channel.UNLIMITED)
        val sent = mutableListOf<HubFrame>()

        @Volatile var closed = false
            private set

        /** Set by the transport: what to do with an outgoing frame after recording it. */
        var onSend: ((HubFrame) -> Unit)? = null

        /** When true the close handshake never completes until [completeClose] (slow peer). */
        @Volatile var deferClose = false
        private var pendingClose: Pair<Int, String>? = null

        override val incoming: Flow<HubFrame> = inbound.receiveAsFlow()

        override fun send(frame: HubFrame): Boolean {
            if (closed) return false
            sent += frame
            onSend?.invoke(frame)
            return true
        }

        override fun close(code: Int, reason: String) {
            if (closed) return
            closed = true
            if (deferClose) {
                pendingClose = code to reason
            } else {
                inbound.close(HubSocketClosed(code, reason))
            }
        }

        override fun cancel() {
            if (!closed) closed = true
            // A cancel always ends the stream, even for a peer that sat on the close handshake.
            pendingClose = null
            inbound.close(HubSocketClosed(1001, "client cancelled"))
        }

        /** Completes a deferred close handshake. */
        fun completeClose() {
            val (code, reason) = pendingClose ?: return
            pendingClose = null
            inbound.close(HubSocketClosed(code, reason))
        }

        /** The peer went away (1006). */
        fun drop(code: Int = 1006, reason: String = "dropped") {
            closed = true
            inbound.close(HubSocketClosed(code, reason))
        }

        fun event(name: String, sessionId: String? = null, sequence: Long? = null, payload: JsonObject? = null) {
            inbound.trySend(HubFrame.Event(HubEventEnvelope(event = name, sessionId = sessionId, sequence = sequence, payload = payload)))
        }

        fun reply(requestId: String, ok: Boolean = true, payload: JsonObject? = null, error: HubReplyError? = null) {
            inbound.trySend(HubFrame.Reply(HubReplyEnvelope(requestId = requestId, ok = ok, payload = payload, error = error)))
        }

        val commands: List<HubCommandEnvelope> get() = sent.filterIsInstance<HubFrame.Command>().map { it.envelope }
        val subscribes: List<HubFrame.StreamSubscribe> get() = sent.filterIsInstance<HubFrame.StreamSubscribe>()
        val globalSubscribe: HubFrame.StreamSubscribe? get() = subscribes.firstOrNull { it.sessionId == null }
        fun command(name: String): HubCommandEnvelope? = commands.firstOrNull { it.command == name }
    }

    val sockets = mutableListOf<FakeSocket>()

    /** `hub.status.eventLog.lastSequence` the fake reports. */
    var lastSequence: Long = 0L
    var draining: Boolean = false
    var hubId: String = "hub_fake"

    /** Tokens the upgrade accepts; null accepts anything. Others get HTTP 401. */
    var acceptedTokens: Set<String?>? = null

    /** Thrown by [open] instead of opening (e.g. a refused handshake). Consumed once per call. */
    var openFailures = ArrayDeque<Exception>()

    /** Consulted on every open: a non-null exception means "nothing listens" (daemon down). */
    var openGate: () -> Exception? = { null }

    /**
     * True: the TCP connect succeeds but the upgrade answer never comes (a frozen Hub on
     * loopback), so [open] suspends until it is cancelled. [hungOpens] counts those calls.
     */
    @Volatile var hangOpen = false
    var hungOpens = 0
        private set

    /** Token check with access to live state (a rotating daemon token); null = use [acceptedTokens]. */
    var acceptToken: ((String?) -> Boolean)? = null

    /** Every command gets this reply synchronously, unless it returns null (the test replies by hand). */
    var onCommand: (HubCommandEnvelope) -> HubReplyEnvelope? = { env -> defaultReply(env) }

    /** Events to push when the client subscribes (keyed by the subscribe frame). */
    var replay: (HubFrame.StreamSubscribe) -> List<HubEventEnvelope> = { emptyList() }

    /** Counts opens while an earlier socket had not reported its close (fixture 04 rule). */
    var opensWhilePreviousOpen = 0
        private set

    val openedTokens: List<String?> get() = sockets.map { it.token }

    /** Every token an upgrade was attempted with, including the ones answered with 401. */
    val attemptedTokens = mutableListOf<String?>()
    val current: FakeSocket get() = sockets.last()

    override suspend fun open(url: String, token: String?): HubSocket {
        attemptedTokens += token
        openFailures.removeFirstOrNull()?.let { throw it }
        openGate()?.let { throw it }
        if (hangOpen) {
            hungOpens++
            awaitCancellation()
        }
        val accepted = acceptedTokens
        if (accepted != null && token !in accepted) throw HubUpgradeFailed(401, "websocket upgrade failed: 401")
        acceptToken?.let { if (!it(token)) throw HubUpgradeFailed(401, "websocket upgrade failed: 401") }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        if (sockets.any { !it.inbound.isClosedForReceive }) opensWhilePreviousOpen++
        val socket = FakeSocket(url, token)
        socket.onSend = { frame ->
            when (frame) {
                is HubFrame.Command -> onCommand(frame.envelope)?.let { socket.inbound.trySend(HubFrame.Reply(it)) }
                is HubFrame.StreamSubscribe -> replay(frame).forEach { socket.inbound.trySend(HubFrame.Event(it)) }
                else -> Unit
            }
        }
        sockets += socket
        return socket
    }

    fun defaultReply(env: HubCommandEnvelope): HubReplyEnvelope = when (env.command) {
        HubCommands.CLIENT_REGISTER -> HubReplyEnvelope(requestId = env.requestId, ok = true, payload = buildJsonObject { put("clientId", env.clientId ?: "") })
        HubCommands.HUB_STATUS -> HubReplyEnvelope(requestId = env.requestId, ok = true, payload = statusPayload())
        else -> HubReplyEnvelope(requestId = env.requestId, ok = true, payload = buildJsonObject { put("echo", env.command) })
    }

    fun statusPayload(activeRpcTurns: Int = 0, pendingRuns: Int = 0): JsonObject = buildJsonObject {
        put("hubId", hubId)
        put("draining", draining)
        put("activeRpcTurns", activeRpcTurns)
        put("pendingRuns", pendingRuns)
        put("idle", activeRpcTurns == 0 && pendingRuns == 0)
        putJsonObject("eventLog") { put("lastSequence", lastSequence) }
    }

    companion object {
        fun errorReply(env: HubCommandEnvelope, code: String, message: String = code): HubReplyEnvelope =
            HubReplyEnvelope(requestId = env.requestId, ok = false, error = HubReplyError(code, message))

        fun eventsFrom(from: Long, to: Long, sessionId: String? = "s1", name: String = "assistant.delta"): List<HubEventEnvelope> =
            (from..to).map { HubEventEnvelope(event = name, eventId = "evt-$it", sequence = it, sessionId = sessionId) }
    }
}
