package dev.cline.termux.core.kernel.hub

import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.InMemoryCursorStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class HubClientTest {
    private val identity = HubClientIdentity(clientId = "android-test", appVersion = "0.1.0", workspaceRoot = "/w")

    private fun TestScope.client(
        hub: FakeHubTransport,
        cursors: HubCursorStore = InMemoryCursorStore(),
        token: String? = "tok",
    ) = HubClient(
        transport = hub,
        identity = identity,
        scope = this,
        cursors = cursors,
        credentials = { HubClient.HubCredentials("ws://127.0.0.1:25463/hub", token) },
        random = Random(1),
    )

    private fun TestScope.finish() = coroutineContext.job.cancelChildren()

    @Test
    fun `connects, registers with the subprotocol token and serves commands`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        val outcome = client.connect()
        advanceUntilIdle()

        assertTrue(outcome is HubClient.ConnectOutcome.Connected)
        assertTrue(client.state.value is HubConnectionState.Connected)
        val socket = hub.sockets.single()
        assertEquals("tok", socket.token)
        val register = socket.commands.first()
        assertEquals(HubCommands.CLIENT_REGISTER, register.command)
        assertEquals("android", register.payload!!["clientType"]!!.jsonPrimitive.content)
        assertEquals("android-test", register.payload!!["clientId"]!!.jsonPrimitive.content)
        assertEquals("/w", register.payload!!["workspaceContext"]!!.let { (it as kotlinx.serialization.json.JsonObject)["workspaceRoot"]!!.jsonPrimitive.content })
        assertEquals(HubCommands.HUB_STATUS, socket.commands[1].command)

        val reply = client.command(HubCommands.HUB_STATUS)
        assertEquals("hub_fake", reply["hubId"]!!.jsonPrimitive.content)
        client.disconnect()
        assertTrue(client.state.value is HubConnectionState.Disconnected)
        finish()
    }

    @Test
    fun `fixture 01 - a fresh client subscribes once globally from the budget floor and session subscribes carry no cursor`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 3 }
        val cursors = InMemoryCursorStore(null)
        val client = client(hub, cursors)
        val notices = mutableListOf<HubClient.Notice>()
        launch { client.notices.collect { notices += it } }
        client.connect()
        advanceUntilIdle()
        client.subscribe("1791064689782_kck3p")

        val socket = hub.sockets.single()
        val global = socket.globalSubscribe!!
        assertEquals("android-test", global.clientId)
        assertNull(global.sessionId)
        assertEquals(0L, global.sinceSequence)
        val perSession = socket.subscribes.single { it.sessionId != null }
        assertEquals("1791064689782_kck3p", perSession.sessionId)
        assertNull("per-session subscribes never carry a cursor (D2)", perSession.sinceSequence)
        assertTrue("no truncation when the whole log fits the budget", notices.isEmpty())
        client.disconnect()
        finish()
    }

    @Test
    fun `fixture 04 - resubscribe from the persisted cursor replays the gap once and dedupes the per-session copies`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 102 }
        hub.replay = { sub ->
            if (sub.sessionId == null && sub.sinceSequence != null) FakeHubTransport.eventsFrom(sub.sinceSequence!! + 1, 102)
            else emptyList()
        }
        val cursors = InMemoryCursorStore(97)
        val client = client(hub, cursors)
        val seen = mutableListOf<Long>()
        launch { client.events.collect { seen += it.sequence!! } }
        client.connect()
        advanceUntilIdle()

        val socket = hub.sockets.single()
        assertEquals(97L, socket.globalSubscribe!!.sinceSequence)
        assertEquals(listOf(98L, 99L, 100L, 101L, 102L), seen)

        // The same events again through a per-session subscription: dropped by sequence.
        FakeHubTransport.eventsFrom(98, 102).forEach { socket.inbound.trySend(HubFrame.Event(it)) }
        socket.event("run.completed", sessionId = "s1", sequence = 103)
        advanceUntilIdle()
        assertEquals(listOf(98L, 99L, 100L, 101L, 102L, 103L), seen)
        assertEquals(103L, client.lastSequence.value)
        client.disconnect()
        assertEquals(103L, cursors.value)
        finish()
    }

    @Test
    fun `a cold start replays from the start of a turn that was still running`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 102 }
        hub.replay = { sub ->
            if (sub.sessionId == null && sub.sinceSequence != null) FakeHubTransport.eventsFrom(sub.sinceSequence!! + 1, 102)
            else emptyList()
        }
        val client = client(hub, InMemoryCursorStore(100))
        client.replayFloor = { 94L }
        val seen = mutableListOf<Long>()
        launch { client.events.collect { seen += it.sequence!! } }
        client.connect()
        advanceUntilIdle()

        assertEquals(94L, hub.sockets.single().globalSubscribe!!.sinceSequence)
        assertEquals((95L..102L).toList(), seen)
        client.disconnect()
        finish()
    }

    @Test
    fun `the replay floor is ignored once this process has seen events`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 102 }
        val client = client(hub, InMemoryCursorStore(100))
        client.replayFloor = { 50L }
        client.connect()
        advanceUntilIdle()
        assertEquals(50L, hub.sockets.last().globalSubscribe!!.sinceSequence)
        hub.sockets.last().event("run.completed", sessionId = "s1", sequence = 103)
        advanceUntilIdle()
        client.disconnect()
        hub.lastSequence = 103
        client.connect()
        advanceUntilIdle()
        assertEquals(103L, hub.sockets.last().globalSubscribe!!.sinceSequence)
        client.disconnect()
        finish()
    }

    @Test
    fun `fixture 05 - the cursor survives a daemon restart and the hub status after it is kept`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 114 }
        hub.replay = { sub -> if (sub.sessionId == null) FakeHubTransport.eventsFrom(sub.sinceSequence!! + 1, 114) else emptyList() }
        val cursors = InMemoryCursorStore(106)
        val client = client(hub, cursors)
        val seen = mutableListOf<Long>()
        launch { client.events.collect { seen += it.sequence!! } }
        val outcome = client.connect()
        advanceUntilIdle()

        assertEquals(106L, hub.sockets.single().globalSubscribe!!.sinceSequence)
        assertEquals((107L..114L).toList(), seen)
        assertEquals(114L, (outcome as HubClient.ConnectOutcome.Connected).hubStatus!!.lastSequence)
        assertEquals(114L, client.hubStatus.value!!.lastSequence)
        client.disconnect()
        finish()
    }

    @Test
    fun `a cursor ahead of the log means the log restarted - replay from the floor and notify`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 114 }
        val cursors = InMemoryCursorStore(500)
        val client = client(hub, cursors)
        val notices = mutableListOf<HubClient.Notice>()
        launch { client.notices.collect { notices += it } }
        client.connect()
        advanceUntilIdle()
        assertEquals(0L, hub.sockets.single().globalSubscribe!!.sinceSequence)
        assertEquals(listOf<HubClient.Notice>(HubClient.Notice.ReplayTruncated(0, 114)), notices)
        client.disconnect()
        finish()
    }

    @Test
    fun `a cursor further behind than the replay budget is truncated to the budget`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 5_000 }
        val cursors = InMemoryCursorStore(10)
        val client = client(hub, cursors)
        val notices = mutableListOf<HubClient.Notice>()
        launch { client.notices.collect { notices += it } }
        client.connect()
        advanceUntilIdle()
        assertEquals(3_000L, hub.sockets.single().globalSubscribe!!.sinceSequence)
        assertEquals(listOf<HubClient.Notice>(HubClient.Notice.ReplayTruncated(3_000, 5_000)), notices)
        client.disconnect()
        finish()
    }

    @Test
    fun `cursor writes are throttled to one per interval and flushed on disconnect`() = runTest {
        val hub = FakeHubTransport()
        val cursors = InMemoryCursorStore()
        val client = client(hub, cursors)
        client.connect()
        advanceUntilIdle()
        val socket = hub.sockets.single()
        socket.event("assistant.delta", "s1", 41)
        socket.event("assistant.delta", "s1", 42)
        socket.event("assistant.delta", "s1", 43)
        advanceTimeBy(HubClient.CURSOR_FLUSH_INTERVAL_MS + 1)
        assertEquals(listOf(43L), cursors.puts)
        socket.event("run.completed", "s1", 44)
        client.disconnect()
        assertEquals(listOf(43L, 44L), cursors.puts)
        assertEquals(44L, cursors.value)
        finish()
    }

    @Test
    fun `error replies become HubCommandException and authority loss re-registers on a fresh socket`() = runTest {
        val hub = FakeHubTransport()
        hub.onCommand = { env ->
            when (env.command) {
                HubCommands.SESSION_GET -> FakeHubTransport.errorReply(env, HubErrorCodes.SESSION_NOT_FOUND, "gone")
                HubCommands.SESSION_LIST -> FakeHubTransport.errorReply(env, HubErrorCodes.CLIENT_NOT_FOUND, "client not found")
                else -> hub.defaultReply(env)
            }
        }
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val error = runCatching { client.command(HubCommands.SESSION_GET, sessionId = "s1") }.exceptionOrNull()
        assertTrue(error is HubCommandException)
        assertEquals(HubErrorCodes.SESSION_NOT_FOUND, (error as HubCommandException).code)
        assertTrue(error.isSessionGone())

        val lost = runCatching { client.command(HubCommands.SESSION_LIST) }.exceptionOrNull() as HubCommandException
        assertTrue(lost.isAuthorityLost())
        advanceUntilIdle()
        assertEquals("a replacement socket registered with the same clientId", 2, hub.sockets.size)
        assertTrue(hub.sockets[0].closed)
        assertEquals("android-test", hub.sockets[1].command(HubCommands.CLIENT_REGISTER)!!.clientId)
        assertTrue(client.state.value is HubConnectionState.Connected)
        client.disconnect()
        finish()
    }

    @Test
    fun `authority loss on a fire-and-forget send re-registers on a fresh socket too`() = runTest {
        val hub = FakeHubTransport()
        hub.onCommand = { env ->
            when (env.command) {
                // HF "Same clientId on a new connection": the stale socket's unregister removed the client.
                HubCommands.SESSION_SEND_INPUT -> FakeHubTransport.errorReply(env, HubErrorCodes.CLIENT_NOT_FOUND, "client not found")
                else -> hub.defaultReply(env)
            }
        }
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val sent = client.send(HubCommands.SESSION_SEND_INPUT, buildJsonObject { put("prompt", "hi") }, sessionId = "s1")
        val reply = sent.reply.await()
        assertFalse(reply.ok)
        assertEquals(HubErrorCodes.CLIENT_NOT_FOUND, reply.error?.code)
        advanceUntilIdle()
        assertEquals("a replacement socket registered with the same clientId", 2, hub.sockets.size)
        assertTrue(hub.sockets[0].closed)
        assertEquals("android-test", hub.sockets[1].command(HubCommands.CLIENT_REGISTER)!!.clientId)
        assertTrue(client.state.value is HubConnectionState.Connected)
        client.disconnect()
        finish()
    }

    @Test
    fun `a dropped socket is replaced only after it reported its close, with the same clientId and the cursor`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 42 }
        val cursors = InMemoryCursorStore()
        val client = client(hub, cursors)
        client.connect()
        advanceUntilIdle()
        client.subscribe("s1")
        val first = hub.sockets.single()
        first.event("assistant.delta", "s1", 41)
        first.event("run.completed", "s1", 42)
        advanceUntilIdle()

        first.drop()
        advanceUntilIdle() // backoff elapses in virtual time

        assertEquals(2, hub.sockets.size)
        assertEquals(0, hub.opensWhilePreviousOpen)
        val second = hub.sockets[1]
        assertTrue(client.state.value is HubConnectionState.Connected)
        assertEquals("android-test", second.command(HubCommands.CLIENT_REGISTER)!!.clientId)
        assertEquals(42L, second.globalSubscribe!!.sinceSequence)
        val perSession = second.subscribes.single { it.sessionId == "s1" }
        assertNull(perSession.sinceSequence)
        client.disconnect()
        finish()
    }

    @Test
    fun `a slow close handshake is cancelled after the close wait and the loop still reconnects`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val first = hub.sockets.single()
        first.deferClose = true
        // The Hub answers session.list with authority loss: the client closes and re-registers.
        hub.onCommand = { env -> if (env.command == HubCommands.SESSION_LIST) FakeHubTransport.errorReply(env, HubErrorCodes.CLIENT_AUTHORITY_MISMATCH) else hub.defaultReply(env) }
        runCatching { client.command(HubCommands.SESSION_LIST) }
        advanceTimeBy(HubClient.CLOSE_WAIT_MS / 2)
        assertEquals("no replacement while the old socket has not reported its close", 1, hub.sockets.size)
        advanceUntilIdle()
        assertEquals(2, hub.sockets.size)
        assertEquals(0, hub.opensWhilePreviousOpen)
        client.disconnect()
        finish()
    }

    @Test
    fun `pending commands fail with hub_connection_closed when the socket drops`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        hub.onCommand = { env -> if (env.command == HubCommands.CLIENT_REGISTER || env.command == HubCommands.HUB_STATUS) hub.defaultReply(env) else null }
        val pending = async { runCatching { client.command(HubCommands.SESSION_LIST) } }
        runCurrent()
        assertFalse(pending.isCompleted)
        hub.sockets.single().drop()
        advanceUntilIdle()
        val error = pending.await().exceptionOrNull() as HubCommandException
        assertEquals(HubErrorCodes.HUB_CONNECTION_CLOSED, error.code)
        assertTrue(error.isLostReply())
        client.disconnect()
        finish()
    }

    @Test
    fun `after three failed reconnects the client is Unavailable and a later connect starts over`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val states = mutableListOf<HubConnectionState>()
        launch { client.state.collect { states += it } }
        repeat(3) { hub.openFailures.addLast(HubUpgradeFailed(null, "connection refused")) }
        hub.sockets.single().drop()
        advanceUntilIdle()

        val unavailable = client.state.value
        assertTrue("$unavailable", unavailable is HubConnectionState.Unavailable)
        assertFalse((unavailable as HubConnectionState.Unavailable).unauthorized)
        assertEquals(listOf(1, 2, 3), states.filterIsInstance<HubConnectionState.Reconnecting>().map { it.attempt })
        assertEquals("one socket per the first connect, none for the refused attempts", 1, hub.sockets.size)

        val again = client.connect()
        advanceUntilIdle()
        assertTrue(again is HubClient.ConnectOutcome.Connected)
        assertEquals(2, hub.sockets.size)
        client.disconnect()
        finish()
    }

    @Test
    fun `connect while reconnecting replaces the loop without publishing Disconnected`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val states = mutableListOf<HubConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.state.collect { states += it } }
        var refuse = true
        hub.openGate = { if (refuse) HubUpgradeFailed(null, "connection refused") else null }
        hub.sockets.single().drop()
        runCurrent()
        assertTrue("${client.state.value}", client.state.value is HubConnectionState.Reconnecting)

        // The coordinator's resume path (onForeground → ensureConnected) calls connect() again.
        refuse = false
        val outcome = client.connect()
        advanceUntilIdle()

        assertTrue(outcome is HubClient.ConnectOutcome.Connected)
        assertTrue(client.state.value is HubConnectionState.Connected)
        assertTrue("$states", states.none { it is HubConnectionState.Disconnected })
        assertEquals(HubConnectionState.Connecting, states[states.indexOfLast { it is HubConnectionState.Reconnecting } + 1])
        assertEquals(2, hub.sockets.size)
        assertEquals(0, hub.opensWhilePreviousOpen)

        // An explicit disconnect still publishes Disconnected.
        client.disconnect()
        assertEquals(HubConnectionState.Disconnected, client.state.value)
        assertEquals(HubConnectionState.Disconnected, states.last())
        finish()
    }

    @Test
    fun `a 401 on the first connect is reported, on a reconnect it stops the loop as unauthorized`() = runTest {
        val hub = FakeHubTransport().apply { acceptedTokens = setOf("good") }
        val bad = client(hub, token = "bad")
        val first = bad.connect()
        advanceUntilIdle()
        assertEquals(HubClient.ConnectOutcome.Unauthorized(401), first)
        assertTrue((bad.state.value as HubConnectionState.Unavailable).unauthorized)

        val good = client(hub, token = "good")
        assertTrue(good.connect() is HubClient.ConnectOutcome.Connected)
        advanceUntilIdle()
        hub.acceptedTokens = setOf("rotated")
        hub.sockets.last().drop()
        advanceUntilIdle()
        val state = good.state.value
        assertTrue("$state", state is HubConnectionState.Unavailable && state.unauthorized)
        finish()
    }

    @Test
    fun `the client timer is always longer than the envelope timer and fires with client_timeout`() = runTest {
        assertNull(HubClient.defaultEnvelopeTimeout(HubCommands.RUN_START))
        assertNull(HubClient.defaultClientTimeout(HubCommands.SESSION_SEND_INPUT))
        assertEquals(120_000L, HubClient.defaultEnvelopeTimeout(HubCommands.SESSION_CREATE))
        assertEquals(125_000L, HubClient.defaultClientTimeout(HubCommands.SESSION_CREATE))
        assertEquals(120_000L, HubClient.defaultEnvelopeTimeout(HubCommands.SESSION_RESTORE))
        assertEquals(130_000L, HubClient.defaultClientTimeout(HubCommands.SESSION_RESTORE))
        val plugins = buildJsonObject { put("includePluginTools", true) }
        assertEquals(60_000L, HubClient.defaultEnvelopeTimeout(HubCommands.SETTINGS_LIST, plugins))
        assertEquals(65_000L, HubClient.defaultClientTimeout(HubCommands.SETTINGS_LIST, plugins))
        assertEquals(30_000L, HubClient.defaultEnvelopeTimeout(HubCommands.SETTINGS_LIST))
        assertEquals(35_000L, HubClient.defaultClientTimeout(HubCommands.SESSION_LIST))

        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        // The Hub sits on both: the list until the client timer fires, the turn until the socket closes.
        hub.onCommand = { env -> if (env.command == HubCommands.SESSION_LIST || env.command == HubCommands.SESSION_SEND_INPUT) null else hub.defaultReply(env) }
        val pending = async { runCatching { client.command(HubCommands.SESSION_LIST) } }
        advanceTimeBy(HubClient.DEFAULT_CLIENT_TIMEOUT_MS + 1)
        val error = pending.await().exceptionOrNull() as HubCommandException
        assertEquals(HubErrorCodes.CLIENT_TIMEOUT, error.code)
        val envelope = hub.sockets.single().command(HubCommands.SESSION_LIST)!!
        assertEquals(30_000L, envelope.timeoutMs)

        val longRunning = client.send(HubCommands.SESSION_SEND_INPUT, buildJsonObject { put("prompt", "hi") }, "s1")
        assertNotNull(longRunning.requestId)
        assertNull(hub.sockets.single().command(HubCommands.SESSION_SEND_INPUT)!!.timeoutMs)
        client.disconnect()
        assertEquals(HubErrorCodes.HUB_CONNECTION_CLOSED, longRunning.reply.await().error!!.code)
        finish()
    }

    @Test
    fun `drain events flip the draining flag and side events reach the side stream`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        val side = mutableListOf<String>()
        launch { client.sideEvents.collect { side += it.event } }
        client.connect()
        advanceUntilIdle()
        assertFalse(client.draining.value)
        hub.sockets.single().event(HubEvents.HUB_DRAIN_CHANGED, sequence = 7, payload = buildJsonObject { put("draining", true) })
        advanceUntilIdle()
        assertTrue(client.draining.value)
        val waiter = async { client.awaitNotDraining(5_000) }
        hub.sockets.single().event(HubEvents.HUB_DRAIN_CHANGED, sequence = 8, payload = buildJsonObject { put("draining", false) })
        advanceUntilIdle()
        assertTrue(waiter.await())
        assertEquals(listOf(HubEvents.HUB_DRAIN_CHANGED, HubEvents.HUB_DRAIN_CHANGED), side)
        client.disconnect()
        finish()
    }

    @Test
    fun `an old Hub's drain changes replayed from the shared log never revive its drain`() = runTest {
        // G5, as measured on a bench Hub replacement: the new Hub's durable log replays the old
        // Hub's drain changes (3 on, 4 off, 5 on) while its own hub.status says not draining.
        val drain = { seq: Long, on: Boolean ->
            HubEventEnvelope(event = HubEvents.HUB_DRAIN_CHANGED, sequence = seq, payload = buildJsonObject { put("draining", on) })
        }
        val hub = FakeHubTransport().apply {
            lastSequence = 8
            draining = false
            replay = { sub -> if (sub.sessionId == null) listOf(drain(3, true), drain(4, false), drain(5, true)) else emptyList() }
        }
        val client = client(hub, cursors = InMemoryCursorStore(2))
        val side = mutableListOf<Long?>()
        launch { client.sideEvents.collect { side += it.sequence } }
        runCurrent()
        client.connect()
        advanceUntilIdle()
        assertEquals("the replay is still delivered", listOf<Long?>(3, 4, 5), side)
        assertFalse("hub.status at sequence 8 already covers them", client.draining.value)
        hub.sockets.single().event(HubEvents.HUB_DRAIN_CHANGED, sequence = 9, payload = buildJsonObject { put("draining", true) })
        advanceUntilIdle()
        assertTrue("a live change after the status applies", client.draining.value)
        client.disconnect()
        finish()
    }

    @Test
    fun `a new connection whose hub status fails forgets the old connection's drain`() = runTest {
        val hub = FakeHubTransport().apply { draining = true }
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        assertTrue(client.draining.value)
        hub.onCommand = { env ->
            if (env.command == HubCommands.HUB_STATUS) FakeHubTransport.errorReply(env, "internal_error") else hub.defaultReply(env)
        }
        hub.current.drop()
        advanceUntilIdle()
        assertTrue(hub.sockets.size > 1)
        assertFalse("nothing is known about the Hub behind the new socket", client.draining.value)
        client.disconnect()
        finish()
    }

    @Test
    fun `per-session subscribes go out only after the replay reached the hub status sequence`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 20 }
        var socketForReplay: FakeHubTransport.FakeSocket? = null
        hub.replay = { sub ->
            if (sub.sessionId == null) socketForReplay = hub.current
            emptyList()
        }
        val client = client(hub, InMemoryCursorStore(10))
        client.subscribe("s1")
        client.connect()
        runCurrent()
        val socket = hub.sockets.single()
        assertFalse(client.replayDrained.value)
        assertEquals("only the global subscribe while the replay drains", listOf<String?>(null), socket.subscribes.map { it.sessionId })

        // A session opened during the drain is queued with the others.
        client.subscribe("s2")
        FakeHubTransport.eventsFrom(11, 19).forEach { socketForReplay!!.inbound.trySend(HubFrame.Event(it)) }
        runCurrent()
        assertFalse(client.replayDrained.value)
        assertEquals(1, socket.subscribes.size)

        socket.event("assistant.delta", "s1", 20)
        runCurrent()
        assertTrue(client.replayDrained.value)
        assertEquals(listOf(null, "s1", "s2"), socket.subscribes.map { it.sessionId })
        assertTrue(socket.subscribes.drop(1).all { it.sinceSequence == null })

        // Once drained, a new session subscribes at once.
        client.subscribe("s3")
        assertEquals("s3", socket.subscribes.last().sessionId)
        client.disconnect()
        assertFalse(client.replayDrained.value)
        finish()
    }

    @Test
    fun `nothing to replay drains at once and a missing target drains after the timeout`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 50 }
        val client = client(hub, InMemoryCursorStore(50))
        client.subscribe("s1")
        client.connect()
        runCurrent()
        assertTrue("cursor at the log head: nothing to wait for", client.replayDrained.value)
        assertEquals(listOf(null, "s1"), hub.sockets.single().subscribes.map { it.sessionId })
        client.disconnect()

        // The log moved on but its last events never come back (pruned): the timer releases the drain.
        hub.lastSequence = 80
        client.connect()
        runCurrent()
        val second = hub.sockets.last()
        assertFalse(client.replayDrained.value)
        advanceTimeBy(HubClient.REPLAY_DRAIN_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(1, second.subscribes.size)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(client.replayDrained.value)
        assertEquals(listOf(null, "s1"), second.subscribes.map { it.sessionId })
        client.disconnect()
        finish()
    }

    @Test
    fun `a drain timer of a dropped connection never releases the next connection's subscribes`() = runTest {
        val hub = FakeHubTransport().apply { lastSequence = 30 }
        val client = client(hub, InMemoryCursorStore(10))
        client.subscribe("s1")
        client.connect()
        runCurrent()
        val first = hub.sockets.single()
        advanceTimeBy(HubClient.REPLAY_DRAIN_TIMEOUT_MS / 2)
        first.drop()
        // Reconnect after the backoff; the replay of the new connection is still pending.
        advanceTimeBy(1_000)
        runCurrent()
        val second = hub.sockets.last()
        assertEquals(2, hub.sockets.size)
        advanceTimeBy(HubClient.REPLAY_DRAIN_TIMEOUT_MS / 2 + 10)
        runCurrent()
        assertFalse("the first connection's timer was cancelled with it", client.replayDrained.value)
        assertEquals(1, second.subscribes.size)
        client.disconnect()
        finish()
    }

    @Test
    fun `every registration is a new connection generation`() = runTest {
        val hub = FakeHubTransport()
        val client = client(hub)
        client.connect()
        advanceUntilIdle()
        val first = (client.state.value as HubConnectionState.Connected).generation
        hub.sockets.single().drop()
        advanceUntilIdle()
        val second = (client.state.value as HubConnectionState.Connected).generation
        assertTrue("$first then $second", second > first)
        assertEquals("ws://127.0.0.1:25463/hub", (client.state.value as HubConnectionState.Connected).hubUrl)
        client.disconnect()
        finish()
    }

    @Test
    fun `a handshake that never answers fails after 10 s and releases the lifecycle lock`() = runTest {
        val hub = FakeHubTransport().apply { hangOpen = true }
        val client = client(hub)
        val outcome = async { client.connect() }
        advanceTimeBy(HubClient.OPEN_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("still inside the H5 budget", outcome.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(HubClient.ConnectOutcome.Failed(HubClient.HANDSHAKE_TIMED_OUT, null), outcome.await())
        val state = client.state.value
        assertTrue("$state", state is HubConnectionState.Unavailable && !state.unauthorized)
        assertEquals(1, hub.hungOpens)

        // The lock is free: a disconnect and the next connect are not queued behind the hung open.
        client.disconnect()
        hub.hangOpen = false
        assertTrue(client.connect() is HubClient.ConnectOutcome.Connected)

        // A reconnect whose handshake hangs counts as a failed attempt, so the loop still gives up.
        hub.hangOpen = true
        hub.sockets.last().drop()
        advanceTimeBy(4 * (HubClient.OPEN_TIMEOUT_MS + 10_000))
        runCurrent()
        assertTrue("${client.state.value}", client.state.value is HubConnectionState.Unavailable)
        assertEquals(1 + client.maxReconnectAttempts, hub.hungOpens)
        finish()
    }

    @Test
    fun `backoff is bounded and jittered`() = runTest {
        val client = client(FakeHubTransport())
        val delays = (1..8).map { client.backoffMillis(it) }
        assertTrue(delays[0] in 250L..375L)
        assertTrue(delays.all { it <= 7_500L })
        assertTrue(delays.last() >= 5_000L)
    }

    @Test
    fun `the dedupe ring forgets the oldest sequence first`() {
        val ring = LongRingSet(3)
        assertTrue(ring.add(1)); assertTrue(ring.add(2)); assertTrue(ring.add(3))
        assertFalse(ring.add(2))
        assertTrue(ring.add(4))
        assertFalse(ring.contains(1))
        assertTrue(ring.contains(4))
        ring.clear()
        assertTrue(ring.add(2))
    }
}
