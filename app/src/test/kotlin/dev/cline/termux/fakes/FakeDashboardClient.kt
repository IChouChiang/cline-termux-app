package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.dashboard.DashboardClient
import dev.cline.termux.core.kernel.dashboard.DashboardTimeouts
import dev.cline.termux.core.kernel.dashboard.DashboardTransport
import dev.cline.termux.core.kernel.dashboard.DesktopCommandException
import dev.cline.termux.core.kernel.dashboard.HealthProbe
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import java.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias DesktopResponder = suspend (JsonObject) -> JsonElement

/** docs/07 §10.1: canned `desktopCommand` replies keyed by command name; records every call. */
class FakeDashboardClient : DashboardClient {
    data class Call(val command: String, val args: JsonObject, val timeoutMs: Long)

    val calls = mutableListOf<Call>()
    private val responders = HashMap<String, DesktopResponder>()

    /** Set by the transport so a dropped connection reaches the lease holder. */
    var onClosed: ((Throwable?) -> Unit)? = null

    var closed: Boolean = false
        private set

    override val isOpen: Boolean get() = !closed

    fun respond(command: String, result: JsonElement) = respond(command) { result }

    fun respond(command: String, responder: DesktopResponder) {
        responders[command] = responder
    }

    /** `desktopCommandResult {ok:false, error}`. */
    fun fail(command: String, error: String) = respond(command) { throw DesktopCommandException(command, error) }

    fun callsFor(command: String): List<Call> = calls.filter { it.command == command }

    override suspend fun desktopCommand(command: String, args: JsonObject, timeoutMs: Long): JsonElement {
        if (closed) throw HelperUnavailable.TransportClosed()
        calls += Call(command, args, timeoutMs)
        val responder = responders[command] ?: throw DesktopCommandException(command, "Unsupported desktop command: $command")
        return responder(args)
    }

    override fun close() {
        if (closed) return
        closed = true
        onClosed?.invoke(null)
    }

    /** The dashboard process died or the socket failed mid-session. */
    fun dropConnection(cause: Throwable = IOException("connection dropped")) {
        if (closed) return
        closed = true
        onClosed?.invoke(cause)
    }

    companion object {
        fun defaultTimeout(command: String) = DashboardTimeouts.forCommand(command)
    }
}

/** The two network edges of `SettingsHelper`, scripted: a probe function and connect outcomes. */
class FakeDashboardTransport : DashboardTransport {
    data class Probe(val port: Int, val roomSecret: String?)

    val probes = mutableListOf<Probe>()
    val clients = mutableListOf<FakeDashboardClient>()
    var connectAttempts = 0
        private set
    var lastConnectSecret: String? = null
        private set

    /** What `/health` answers for a given secret (null = probed without one). */
    var probeResponder: (roomSecret: String?) -> HealthProbe = { HealthProbe.Refused }

    /** Thrown by the next `connect`; cleared after one use when [failConnectOnce] is set. */
    var connectFailure: Throwable? = null
    var failConnectOnce: Boolean = false

    /** Scripts each new client (canned replies) before the helper can use it. */
    var onConnect: (FakeDashboardClient) -> Unit = {}

    override suspend fun probeHealth(port: Int, roomSecret: String?): HealthProbe {
        probes += Probe(port, roomSecret)
        return probeResponder(roomSecret)
    }

    override suspend fun connect(port: Int, roomSecret: String, onClosed: (Throwable?) -> Unit): DashboardClient {
        connectAttempts++
        lastConnectSecret = roomSecret
        connectFailure?.let { failure ->
            if (failConnectOnce) connectFailure = null
            throw failure
        }
        return FakeDashboardClient().also { client ->
            client.onClosed = onClosed
            clients += client
            onConnect(client)
        }
    }

    val lastClient: FakeDashboardClient? get() = clients.lastOrNull()
}
