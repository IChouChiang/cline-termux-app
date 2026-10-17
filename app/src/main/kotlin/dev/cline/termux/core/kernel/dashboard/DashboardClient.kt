package dev.cline.termux.core.kernel.dashboard

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The loopback dashboard (`cline dashboard --host 127.0.0.1 --port 8787`). */
object DashboardEndpoints {
    const val DEFAULT_PORT = 8787
    const val HOST = "127.0.0.1"

    fun hostHeader(port: Int = DEFAULT_PORT) = "$HOST:$port"
    fun origin(port: Int = DEFAULT_PORT) = "http://$HOST:$port"

    /**
     * The secret probe (SP §0.3 step 1.4). The shipped dashboard lists `/health` among its
     * public routes (`apps/cline-hub/src/server.ts` `PUBLIC_BROWSER_PATHS`), so `/health`
     * answers 200 whatever secret is sent and cannot tell the app's dashboard from another.
     * A plain `GET /browser` (no upgrade headers) is not public: with Host, Origin and the
     * dashboard's own secret it passes `browser-auth.ts` and the failed upgrade answers
     * 400 "upgrade failed"; with another secret, or none on a secret-protected dashboard,
     * it answers 403 `unauthorized_browser`.
     */
    fun probeUrl(port: Int = DEFAULT_PORT, roomSecret: String?) =
        "http://$HOST:$port/browser" + (roomSecret?.let { "?roomSecret=$it" } ?: "")

    /** The body of the dashboard's answer to an authorised `GET /browser` without upgrade headers. */
    const val UPGRADE_FAILED_BODY = "upgrade failed"

    fun browserUrl(port: Int = DEFAULT_PORT, roomSecret: String) = "ws://$HOST:$port/browser?roomSecret=$roomSecret"
}

/** Client timers per `desktopCommand` (docs/07 §3.5). */
object DashboardTimeouts {
    const val DEFAULT_MS = 30_000L
    const val ADD_PROVIDER_MS = 60_000L
    const val MARKETPLACE_MS = 300_000L
    const val HEALTH_PROBE_MS = 1_000L

    fun forCommand(command: String): Long = when (command) {
        "add_provider" -> ADD_PROVIDER_MS
        "install_marketplace_entry", "uninstall_marketplace_entry" -> MARKETPLACE_MS
        else -> DEFAULT_MS
    }
}

/** `GET /browser?roomSecret=…` with Host and Origin, without upgrade headers (SP §0.3 step 1.4, [DashboardEndpoints.probeUrl]). */
sealed interface HealthProbe {
    /** Connection refused, timeout, reset or garbage: nothing usable answers. */
    data object Refused : HealthProbe

    /** A dashboard is up and accepted the secret sent (or enforces none when none was sent). */
    data object Ok : HealthProbe

    /** `unauthorized_browser`: a dashboard with another secret, or none was sent. */
    data object Forbidden : HealthProbe
    data class Unexpected(val code: Int) : HealthProbe
}

/** A `desktopCommandResult {ok:false, error}`: the dashboard's own message, verbatim. */
class DesktopCommandException(val command: String, val error: String) : Exception("$command failed: $error")

/** The upgrade to `/browser` got an answer that was not 101 (or no answer at all). */
class DashboardUpgradeFailed(val httpStatus: Int?, message: String, cause: Throwable? = null) : IOException(message, cause)

/** docs/07 §9: thrown to repositories; `TransportClosed` means an unknown outcome → re-read. */
sealed class HelperUnavailable(message: String) : Exception(message) {
    /** No lease, or the helper settled in [reason] (null = idle, nothing holds a lease). */
    class NotRunning(val reason: HelperFailure?) : HelperUnavailable("settings helper is not running: ${reason ?: "idle"}")
    class TransportClosed : HelperUnavailable("settings helper connection closed")
    class Timeout(val command: String, val afterMs: Long) : HelperUnavailable("settings helper did not answer $command within $afterMs ms")
}

/** One open `/browser` socket; `ready` has been sent. */
interface DashboardClient {
    val isOpen: Boolean

    /**
     * `{type:"desktopCommand", id, command, args}` → the `result` of the matching
     * `desktopCommandResult`.
     *
     * @throws DesktopCommandException for `ok:false`;
     *   [HelperUnavailable.TransportClosed] when the socket dropped (unknown outcome);
     *   [HelperUnavailable.Timeout] when no result arrived within [timeoutMs].
     */
    suspend fun desktopCommand(
        command: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DashboardTimeouts.forCommand(command),
    ): JsonElement

    fun close()
}

/** The two network edges of [SettingsHelper]; faked in tests. */
interface DashboardTransport {
    suspend fun probeHealth(port: Int, roomSecret: String?): HealthProbe

    /**
     * Opens `/browser` and sends `ready`. [onClosed] fires once when the socket ends for
     * any reason after a successful open.
     *
     * @throws DashboardUpgradeFailed when the upgrade fails.
     */
    suspend fun connect(port: Int, roomSecret: String, onClosed: (Throwable?) -> Unit): DashboardClient
}

/**
 * OkHttp implementation. No `HttpLoggingInterceptor`, ever: the room secret is in the URL
 * (docs/07 §3.5). The probe client has 1 s timeouts; the socket client has no read deadline.
 */
class OkHttpDashboardTransport(
    baseClient: OkHttpClient = OkHttpClient(),
) : DashboardTransport {
    private val probeClient = baseClient.newBuilder()
        .connectTimeout(DashboardTimeouts.HEALTH_PROBE_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DashboardTimeouts.HEALTH_PROBE_MS, TimeUnit.MILLISECONDS)
        .callTimeout(DashboardTimeouts.HEALTH_PROBE_MS * 2, TimeUnit.MILLISECONDS)
        .build()
    private val socketClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun probeHealth(port: Int, roomSecret: String?): HealthProbe = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(DashboardEndpoints.probeUrl(port, roomSecret))
            .header("Host", DashboardEndpoints.hostHeader(port))
            .header("Origin", DashboardEndpoints.origin(port))
            .build()
        try {
            probeClient.newCall(request).execute().use { response ->
                when (response.code) {
                    400 -> if (response.body.string().trim() == DashboardEndpoints.UPGRADE_FAILED_BODY) {
                        HealthProbe.Ok
                    } else {
                        HealthProbe.Unexpected(400)
                    }
                    403 -> HealthProbe.Forbidden
                    else -> HealthProbe.Unexpected(response.code)
                }
            }
        } catch (_: IOException) {
            HealthProbe.Refused
        }
    }

    override suspend fun connect(port: Int, roomSecret: String, onClosed: (Throwable?) -> Unit): DashboardClient {
        val request = Request.Builder()
            .url(DashboardEndpoints.browserUrl(port, roomSecret))
            .header("Host", DashboardEndpoints.hostHeader(port))
            .header("Origin", DashboardEndpoints.origin(port))
            .build()
        val client = OkHttpDashboardClient(onClosed)
        return suspendCancellableCoroutine { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    client.attach(webSocket)
                    webSocket.send(HelperFrames.encodeReady())
                    if (cont.isActive) cont.resume(client)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = client.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = client.onEnd(null)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            DashboardUpgradeFailed(response?.code, "dashboard websocket upgrade failed: ${t.javaClass.simpleName}", t),
                        )
                    } else {
                        client.onEnd(t)
                    }
                }
            }
            val ws = socketClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }
    }
}

/** Request correlation over one socket; shared by the OkHttp transport and the tests. */
internal class OkHttpDashboardClient(private val onClosed: (Throwable?) -> Unit) : DashboardClient {
    private class Pending(val command: String, val deferred: CompletableDeferred<JsonElement>)

    private val pending = ConcurrentHashMap<String, Pending>()

    @Volatile private var webSocket: WebSocket? = null

    @Volatile private var ended = false

    override val isOpen: Boolean get() = webSocket != null && !ended

    fun attach(socket: WebSocket) {
        webSocket = socket
    }

    fun onText(text: String) {
        val frame = HelperFrames.decode(text) as? HelperInbound.CommandResult ?: return
        val waiting = pending.remove(frame.id) ?: return // unknown or late id: dropped
        if (frame.ok) {
            waiting.deferred.complete(frame.result ?: JsonNull)
        } else {
            waiting.deferred.completeExceptionally(DesktopCommandException(waiting.command, frame.error ?: "unknown error"))
        }
    }

    fun onEnd(cause: Throwable?) {
        if (ended) return
        ended = true
        val closed = HelperUnavailable.TransportClosed()
        pending.keys.toList().forEach { id -> pending.remove(id)?.deferred?.completeExceptionally(closed) }
        onClosed(cause)
    }

    override suspend fun desktopCommand(command: String, args: JsonObject, timeoutMs: Long): JsonElement {
        val socket = webSocket
        if (socket == null || ended) throw HelperUnavailable.TransportClosed()
        val id = UUID.randomUUID().toString()
        val waiting = Pending(command, CompletableDeferred())
        pending[id] = waiting
        if (!socket.send(HelperFrames.encodeCommand(id, command, args.takeIf { it.isNotEmpty() }))) {
            pending.remove(id)
            throw HelperUnavailable.TransportClosed()
        }
        return try {
            withTimeout(timeoutMs) { waiting.deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw HelperUnavailable.Timeout(command, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        val socket = webSocket
        if (socket == null) {
            onEnd(null)
            return
        }
        if (!socket.close(1000, "client closed")) onEnd(null)
    }
}
