package dev.cline.termux.core.kernel.dashboard

import dev.cline.termux.expectThrows
import dev.cline.termux.failedWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/07 §3.5 / §10.1 "Settings helper client": `/health`, the upgrade and request correlation. */
class DashboardClientTest {
    private lateinit var server: MockWebServer
    private val transport = OkHttpDashboardTransport()
    private val secret = "0123456789abcdef0123456789abcdef"

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `probeHealth sends the secret, Host and Origin and maps the status codes`() = runBlocking<Unit> {
        // The shipped dashboard's answers to a plain GET /browser (no upgrade headers):
        // authorised → Bun's failed upgrade, 400 "upgrade failed"; wrong or missing secret → 403.
        server.enqueue(plain(400, "upgrade failed"))
        server.enqueue(plain(403, """{"error":"unauthorized_browser"}"""))
        server.enqueue(plain(500, ""))
        server.enqueue(plain(400, "Bad Request"))
        server.enqueue(plain(200, """{"status":"healthy"}"""))

        assertEquals(HealthProbe.Ok, transport.probeHealth(server.port, secret))
        val request = server.takeRequest()
        assertEquals("/browser?roomSecret=$secret", request.target)
        assertEquals("127.0.0.1:${server.port}", request.headers["Host"])
        assertEquals("http://127.0.0.1:${server.port}", request.headers["Origin"])
        assertEquals(null, request.headers["Upgrade"])

        assertEquals(HealthProbe.Forbidden, transport.probeHealth(server.port, null))
        assertEquals("/browser", server.takeRequest().target)
        assertEquals(HealthProbe.Unexpected(500), transport.probeHealth(server.port, secret))
        // A 400 that is not the dashboard's failed upgrade, or a 200 from a public route, is not a dashboard that took the secret.
        assertEquals(HealthProbe.Unexpected(400), transport.probeHealth(server.port, secret))
        assertEquals(HealthProbe.Unexpected(200), transport.probeHealth(server.port, secret))
    }

    @Test
    fun `probeHealth answers Refused when nothing listens`() = runBlocking<Unit> {
        val free = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(HealthProbe.Refused, transport.probeHealth(free, secret))
    }

    /** Scripted dashboard side of the `/browser` socket. */
    private class Room : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        var onCommand: (WebSocket, id: String, command: String) -> Unit = { _, _, _ -> }
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
            opened.countDown()
        }

        /** A real server answers a Close frame; OkHttp's listener leaves that to the app. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val frame = Json.parseToJsonElement(text).jsonObject
            if (frame["type"]?.jsonPrimitive?.content == "desktopCommand") {
                onCommand(webSocket, frame["id"]!!.jsonPrimitive.content, frame["command"]!!.jsonPrimitive.content)
            }
        }
    }

    private fun upgrade(room: Room): MockResponse = MockResponse.Builder().webSocketUpgrade(room).build()

    private fun plain(code: Int, body: String): MockResponse = MockResponse.Builder().code(code).body(body).build()

    @Test
    fun `connect upgrades with the secret, sends ready and correlates results by id`() = runBlocking<Unit> {
        val room = Room().apply {
            onCommand = { ws, id, command ->
                ws.send("""{"type":"status","hub":{"state":"running"}}""")
                ws.send("""{"type":"desktopCommandResult","id":"someone-else","ok":true,"result":1}""")
                ws.send("""{"type":"desktopCommandResult","id":"$id","ok":true,"result":{"echo":"$command"}}""")
            }
        }
        server.enqueue(upgrade(room))
        val closedWith = AtomicReference<Throwable?>(IllegalStateException("never called"))
        val closedCalls = AtomicInteger()
        val client = transport.connect(server.port, secret) { closedCalls.incrementAndGet(); closedWith.set(it) }
        val request = server.takeRequest()
        assertEquals("/browser?roomSecret=$secret", request.target)
        assertEquals("127.0.0.1:${server.port}", request.headers["Host"])
        assertEquals("http://127.0.0.1:${server.port}", request.headers["Origin"])
        assertTrue(room.opened.await(5, TimeUnit.SECONDS))
        assertTrue(client.isOpen)

        val result = client.desktopCommand("list_providers", buildJsonObject { put("x", 1) })
        assertEquals("list_providers", result.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"ready"}""", room.received.first())
        val sent = Json.parseToJsonElement(room.received[1]).jsonObject
        assertEquals("desktopCommand", sent["type"]!!.jsonPrimitive.content)
        assertEquals(1, sent["args"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())

        client.close()
        waitUntil { closedCalls.get() == 1 }
        assertEquals(1, closedCalls.get())
        assertNull(closedWith.get())
        assertFalse(client.isOpen)
        expectThrows<HelperUnavailable.TransportClosed> { client.desktopCommand("list_providers") }
    }

    @Test
    fun `an ok false result becomes DesktopCommandException with the dashboard's message`() = runBlocking<Unit> {
        val room = Room().apply {
            onCommand = { ws, id, _ -> ws.send("""{"type":"desktopCommandResult","id":"$id","ok":false,"error":"Unsupported desktop command: nope"}""") }
        }
        server.enqueue(upgrade(room))
        val client = transport.connect(server.port, secret) {}
        val error = expectThrows<DesktopCommandException> { client.desktopCommand("nope") }
        assertEquals("nope", error.command)
        assertEquals("Unsupported desktop command: nope", error.error)
        assertTrue(client.isOpen)
        client.close()
    }

    @Test
    fun `a silent dashboard times out per command`() = runBlocking<Unit> {
        val room = Room()
        server.enqueue(upgrade(room))
        val client = transport.connect(server.port, secret) {}
        val error = expectThrows<HelperUnavailable.Timeout> { client.desktopCommand("list_providers", timeoutMs = 200) }
        assertEquals("list_providers", error.command)
        assertEquals(200L, error.afterMs)
        client.close()
    }

    @Test
    fun `a dropped socket fails the pending command with TransportClosed and reports the close once`() = runBlocking<Unit> {
        val room = Room().apply { onCommand = { ws, _, _ -> ws.close(1001, "dashboard exiting") } }
        server.enqueue(upgrade(room))
        val closes = AtomicInteger()
        val client = transport.connect(server.port, secret) { closes.incrementAndGet() }
        expectThrows<HelperUnavailable.TransportClosed> { client.desktopCommand("list_providers") }
        waitUntil { closes.get() == 1 }
        assertFalse(client.isOpen)
        client.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `a non-101 upgrade answer carries its status`() = runBlocking<Unit> {
        server.enqueue(plain(403, "forbidden"))
        val forbidden = expectThrows<DashboardUpgradeFailed> { transport.connect(server.port, secret) {} }
        assertEquals(403, forbidden.httpStatus)

        server.enqueue(plain(426, "upgrade required"))
        val unsupported = expectThrows<DashboardUpgradeFailed> { transport.connect(server.port, secret) {} }
        assertEquals(426, unsupported.httpStatus)
        assertFalse(unsupported.message!!.contains(secret))
    }

    @Test
    fun `a refused connection has no status`() = runBlocking<Unit> {
        val free = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val error = expectThrows<DashboardUpgradeFailed> { transport.connect(free, secret) {} }
        assertNull(error.httpStatus)
    }

    @Test
    fun `endpoints and timers follow the spec table`() {
        assertEquals("http://127.0.0.1:8787/browser?roomSecret=abc", DashboardEndpoints.probeUrl(roomSecret = "abc"))
        assertEquals("http://127.0.0.1:8787/browser", DashboardEndpoints.probeUrl(roomSecret = null))
        assertEquals("ws://127.0.0.1:8787/browser?roomSecret=abc", DashboardEndpoints.browserUrl(roomSecret = "abc"))
        assertEquals(30_000L, DashboardTimeouts.forCommand("list_providers"))
        assertEquals(60_000L, DashboardTimeouts.forCommand("add_provider"))
        assertEquals(300_000L, DashboardTimeouts.forCommand("install_marketplace_entry"))
        assertEquals(300_000L, DashboardTimeouts.forCommand("uninstall_marketplace_entry"))
    }

    private fun waitUntil(deadlineMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("condition not met within $deadlineMs ms", condition())
    }
}

/** The correlation core on its own, driven on the virtual clock with a scripted socket. */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpDashboardClientUnitTest {
    private class ScriptedSocket : WebSocket {
        val sent = mutableListOf<String>()
        var accept = true
        var closed: Pair<Int, String>? = null
        override fun request(): Request = Request.Builder().url("ws://127.0.0.1:8787/browser").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { if (accept) sent += text; return accept }
        override fun send(bytes: ByteString): Boolean = accept
        override fun close(code: Int, reason: String?): Boolean { closed = code to reason.orEmpty(); return true }
        override fun cancel() = Unit
    }

    private fun idOf(frame: String) = Json.parseToJsonElement(frame).jsonObject["id"]!!.jsonPrimitive.content

    @Test
    fun `before attach every command is TransportClosed`() = runTest {
        val client = OkHttpDashboardClient {}
        assertFalse(client.isOpen)
        expectThrows<HelperUnavailable.TransportClosed> { client.desktopCommand("x") }
    }

    @Test
    fun `results are matched by id and unknown ids are dropped`() = runTest {
        var closes = 0
        val client = OkHttpDashboardClient { closes++ }
        val socket = ScriptedSocket()
        client.attach(socket)
        assertTrue(client.isOpen)
        val first = async { runCatching { client.desktopCommand("a") } }
        val second = async { client.desktopCommand("b") }
        runCurrent()
        assertEquals(2, socket.sent.size)
        val idA = idOf(socket.sent[0])
        val idB = idOf(socket.sent[1])
        client.onText("""{"type":"desktopCommandResult","id":"ghost","ok":true,"result":0}""")
        client.onText("""{"type":"providers","items":[]}""")
        client.onText("""{"type":"desktopCommandResult","id":"$idB","ok":true,"result":"B"}""")
        client.onText("""{"type":"desktopCommandResult","id":"$idA","ok":false,"error":"boom"}""")
        assertEquals(JsonPrimitive("B"), second.await())
        assertEquals("boom", first.await().failedWith<DesktopCommandException>().error)
        assertEquals(0, closes)
    }

    @Test
    fun `a timed out command is forgotten so its late result is dropped`() = runTest {
        val client = OkHttpDashboardClient {}
        val socket = ScriptedSocket()
        client.attach(socket)
        val pending = async { runCatching { client.desktopCommand("slow", timeoutMs = 1_000) } }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1_000L, pending.await().failedWith<HelperUnavailable.Timeout>().afterMs)
        client.onText("""{"type":"desktopCommandResult","id":"${idOf(socket.sent[0])}","ok":true,"result":1}""")
        assertTrue(client.isOpen)
    }

    @Test
    fun `an end fails every pending command once and reports the cause`() = runTest {
        val causes = mutableListOf<Throwable?>()
        val client = OkHttpDashboardClient { causes += it }
        val socket = ScriptedSocket()
        client.attach(socket)
        val pending = async { runCatching { client.desktopCommand("a") } }
        runCurrent()
        val cause = java.io.IOException("reset")
        client.onEnd(cause)
        client.onEnd(null)
        pending.await().failedWith<HelperUnavailable.TransportClosed>()
        assertEquals(listOf<Throwable?>(cause), causes)
        assertFalse(client.isOpen)
        expectThrows<HelperUnavailable.TransportClosed> { client.desktopCommand("b") }
    }

    @Test
    fun `a send the socket refuses is TransportClosed and close sends 1000`() = runTest {
        val client = OkHttpDashboardClient {}
        val socket = ScriptedSocket().apply { accept = false }
        client.attach(socket)
        expectThrows<HelperUnavailable.TransportClosed> { client.desktopCommand("a") }
        client.close()
        assertEquals(1000 to "client closed", socket.closed)
    }
}
