package dev.cline.termux.core.kernel.hub

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One open WebSocket to a Hub. Frames in, frames out; closing is final. */
interface HubSocket {
    val incoming: Flow<HubFrame>
    fun send(frame: HubFrame): Boolean

    /** Graceful close handshake; [incoming] completes when the peer acknowledges (or the socket fails). */
    fun close(code: Int = 1000, reason: String = "client closed")

    /** Tears the connection down without a handshake; [incoming] completes with a failure. */
    fun cancel() = close(1001, "client cancelled")
}

/** How a [HubSocket] ended, surfaced through [HubSocket.incoming] completion. */
class HubSocketClosed(val code: Int, val reason: String, cause: Throwable? = null) :
    Exception("hub socket closed ($code): $reason", cause)

/** Opens sockets. Abstracted so the client can be unit-tested with a fake. */
interface HubTransport {
    /**
     * Opens `url` (ws://127.0.0.1:<port>/hub). With [token] the upgrade carries
     * `Sec-WebSocket-Protocol: cline-hub-auth.<token>`; without it the Hub only
     * accepts loopback `Origin` headers with reduced authority (diagnostic use).
     */
    suspend fun open(url: String, token: String?): HubSocket
}

class HubUpgradeFailed(val httpStatus: Int?, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * OkHttp implementation. OkHttp answers the Hub's pings on its own while the process runs,
 * and pings the Hub itself every [PING_INTERVAL_S] s: a pong missing by the next ping fails
 * the socket (onFailure → 1006), so a Hub whose event loop hung or whose Termux froze is
 * noticed instead of leaving the app Ready with commands waiting forever (docs/02 keep-alive).
 * The handshake deadline is the caller's (`HubClient` wraps [open] in a 10 s timeout and the
 * cancellation cancels the call).
 */
class OkHttpHubTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // liveness is the ping below, not a read deadline
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build(),
) : HubTransport {

    override suspend fun open(url: String, token: String?): HubSocket {
        val request = Request.Builder().url(url).apply {
            if (token != null) {
                header("Sec-WebSocket-Protocol", "$AUTH_PROTOCOL_PREFIX$token")
            } else {
                header("Origin", "http://localhost")
            }
        }.build()
        val frames = Channel<HubFrame>(Channel.UNLIMITED)
        lateinit var socket: OkHttpHubSocket

        val webSocket = suspendCancellableCoroutine<WebSocket> { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cont.isActive) cont.resume(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = try {
                        HubJson.decodeFromString(HubFrame.serializer(), text)
                    } catch (e: Exception) {
                        // Unknown frame kinds are not fatal; the protocol may grow.
                        return
                    }
                    frames.trySend(frame)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    frames.close(HubSocketClosed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            HubUpgradeFailed(response?.code, "websocket upgrade failed: ${t.message}", t),
                        )
                    } else {
                        frames.close(HubSocketClosed(1006, t.message ?: "connection failure", t))
                    }
                }
            }
            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }
        socket = OkHttpHubSocket(webSocket, frames)
        return socket
    }

    private class OkHttpHubSocket(
        private val webSocket: WebSocket,
        private val frames: Channel<HubFrame>,
    ) : HubSocket {
        override val incoming: Flow<HubFrame> = frames.receiveAsFlow()

        override fun send(frame: HubFrame): Boolean =
            webSocket.send(HubJson.encodeToString(HubFrame.serializer(), frame))

        override fun close(code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun cancel() {
            // OkHttp reports a cancelled socket through onFailure, which closes [frames].
            webSocket.cancel()
        }
    }

    companion object {
        const val AUTH_PROTOCOL_PREFIX = "cline-hub-auth."

        /** Client-side ping, the same cadence as the Hub's own sweep. */
        const val PING_INTERVAL_S = 30L
    }
}
