package dev.cline.termux.core.kernel.hub

import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.InMemoryCursorStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Replays the recorded Hub traffic of docs/08 (fixtures 01, 04, 05) through [HubClient]:
 * the scripted transport answers every command with the reply the real Hub sent for it
 * and replays the recorded events the way the Hub does (`sequence > sinceSequence`), so
 * the registration shape, the one global cursor, the replay budget and the sequence
 * dedupe are checked against real frames rather than hand-written ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubClientFixtureReplayTest {
    private data class Line(val dir: String, val conn: String?, val frame: HubFrame?)

    private fun fixture(name: String): List<Line> {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("hub-fixtures/$name")) { "missing fixture $name" }
        return stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val obj = HubJson.parseToJsonElement(line).jsonObject
            val frame = (obj["frame"] as? JsonObject)?.let { HubFrameSerializer.decodeFrame(it) }
            Line(obj["dir"]!!.jsonPrimitive.content, obj["conn"]?.jsonPrimitive?.content, frame)
        }
    }

    /** Recorded replies per command name on [conn], paired with their commands in send order. */
    private fun recordedReplies(lines: List<Line>, conn: String): Map<String, ArrayDeque<HubReplyEnvelope>> {
        val sentCommands = ArrayDeque<String>()
        val replies = LinkedHashMap<String, ArrayDeque<HubReplyEnvelope>>()
        for (line in lines.filter { it.conn == conn }) {
            when (val frame = line.frame) {
                is HubFrame.Command -> if (line.dir == "send") sentCommands.addLast(frame.envelope.command)
                is HubFrame.Reply -> if (line.dir == "recv") {
                    val command = sentCommands.removeFirstOrNull() ?: continue
                    replies.getOrPut(command) { ArrayDeque() }.addLast(frame.envelope)
                }
                else -> Unit
            }
        }
        return replies
    }

    private fun recordedEvents(lines: List<Line>, conn: String, range: IntRange = lines.indices): List<HubEventEnvelope> =
        range.map { lines[it] }.filter { it.conn == conn && it.dir == "recv" }.mapNotNull { (it.frame as? HubFrame.Event)?.envelope }

    private fun recordedSends(lines: List<Line>, conn: String): List<HubFrame> =
        lines.filter { it.conn == conn && it.dir == "send" }.mapNotNull { it.frame }

    /** Answers from the recording (with our requestId); anything unrecorded gets the fake's default. */
    private fun FakeHubTransport.answerFrom(replies: Map<String, ArrayDeque<HubReplyEnvelope>>) {
        onCommand = { env -> replies[env.command]?.removeFirstOrNull()?.copy(requestId = env.requestId) ?: defaultReply(env) }
    }

    private fun TestScope.client(hub: FakeHubTransport, cursors: HubCursorStore, clientId: String) = HubClient(
        transport = hub,
        identity = HubClientIdentity(clientId = clientId, appVersion = "0.1.0", workspaceRoot = "/data/data/com.termux/files/home"),
        scope = this,
        cursors = cursors,
        credentials = { HubClient.HubCredentials("ws://127.0.0.1:25463/hub", "tok") },
        random = Random(3),
    )

    private fun TestScope.finish() = coroutineContext.job.cancelChildren()

    @Test
    fun `fixture 01 - registration matches the recorded frame and a fresh install subscribes globally once`() = runTest {
        val lines = fixture("01-register-and-status.jsonl")
        val recordedRegister = (recordedSends(lines, "c1").first() as HubFrame.Command).envelope
        val clientId = recordedRegister.clientId!!
        val hub = FakeHubTransport().apply { answerFrom(recordedReplies(lines, "c1")) }
        val cursors = InMemoryCursorStore(null)
        val client = client(hub, cursors, clientId)
        val notices = mutableListOf<HubClient.Notice>()
        launch { client.notices.collect { notices += it } }

        val outcome = client.connect()
        advanceUntilIdle()

        val socket = hub.sockets.single()
        val register = socket.command(HubCommands.CLIENT_REGISTER)!!.payload!!
        val recorded = recordedRegister.payload!!
        for (key in listOf("clientId", "clientType", "transport", "actorKind", "protocolVersion")) {
            assertEquals(key, recorded[key], register[key])
        }
        // The recorder labelled itself "(fixture recorder)"; the app sends the KC display name.
        assertEquals("Cline Termux", register["displayName"]!!.jsonPrimitive.content)
        assertTrue(recorded["displayName"]!!.jsonPrimitive.content.startsWith("Cline for Android"))
        assertEquals(recorded["capabilities"], register["capabilities"])
        val metadata = register["metadata"]!!.jsonObject
        assertEquals("android", metadata["platform"]!!.jsonPrimitive.content)
        assertEquals(0, metadata["pid"]!!.jsonPrimitive.content.toInt())
        assertEquals(recorded["workspaceContext"]!!.jsonObject.keys, register["workspaceContext"]!!.jsonObject.keys)

        // The recorded hub.status reply drives the replay floor.
        val recordedStatus = HubLiveStatus.parse(recordedReplies(lines, "c1")[HubCommands.HUB_STATUS]!!.first().payload!!)
        assertEquals(recordedStatus, (outcome as HubClient.ConnectOutcome.Connected).hubStatus)
        val global = socket.globalSubscribe!!
        assertNull(global.sessionId)
        assertEquals(0L, global.sinceSequence)
        assertEquals("exactly one global subscribe", 1, socket.subscribes.count { it.sessionId == null })
        assertTrue("the whole log fits the budget, nothing was skipped", notices.isEmpty())
        client.disconnect()
        finish()
    }

    @Test
    fun `fixture 04 - a reconnect resumes from the cursor and drops every per-session duplicate`() = runTest {
        val lines = fixture("04-history-and-replay.jsonl")
        val sessionId = "1791064689782_kck3p"
        val c2Global = recordedEvents(lines, "c2", 23..27)
        val c2Session = recordedEvents(lines, "c2", 30..30)
        val hub = FakeHubTransport().apply {
            lastSequence = 99
            answerFrom(recordedReplies(lines, "c1"))
            // c1 is the live socket of the recording (no replay); c2 replays what the Hub replayed.
            replay = { sub ->
                if (sockets.size < 2) emptyList()
                else (if (sub.sessionId == null) c2Global else c2Session)
                    .filter { sub.sinceSequence == null || (it.sequence ?: 0) > sub.sinceSequence!! }
            }
        }
        val cursors = InMemoryCursorStore(97)
        val clientId = (recordedSends(lines, "c2").first() as HubFrame.Command).envelope.clientId!!
        val client = client(hub, cursors, clientId)
        val seen = mutableListOf<Long>()
        val side = mutableListOf<String>()
        launch { client.events.collect { seen += it.sequence!! } }
        launch { client.sideEvents.collect { side += it.event } }

        client.connect()
        advanceUntilIdle()
        val first = hub.sockets.single()
        assertEquals(97L, first.globalSubscribe!!.sinceSequence)
        // c2's recorded global replay is 98-102; on this first socket the log ends at 99.
        // The session.update pair (c1 lines 11-12) carries sequence 100 twice.
        recordedEvents(lines, "c1").forEach { first.inbound.trySend(HubFrame.Event(it)) }
        advanceUntilIdle()

        // c1 closes; the replacement opens only after the close (fixture 04 rule).
        hub.lastSequence = 102
        first.drop()
        advanceUntilIdle()
        assertEquals(2, hub.sockets.size)
        assertEquals(0, hub.opensWhilePreviousOpen)
        val second = hub.sockets.last()
        assertEquals(clientId, second.command(HubCommands.CLIENT_REGISTER)!!.payload!!["clientId"]!!.jsonPrimitive.content)
        assertEquals(100L, second.globalSubscribe!!.sinceSequence)

        client.subscribe(sessionId)
        advanceUntilIdle()
        assertNull("per-session subscribes never carry a cursor (D2)", second.subscribes.single { it.sessionId == sessionId }.sinceSequence)
        assertEquals(listOf(100L, 101L, 102L), seen)
        assertEquals(listOf(HubEvents.CLIENT_DISCONNECTED, HubEvents.CLIENT_REGISTERED), side)
        client.disconnect()
        assertEquals(102L, cursors.value)
        finish()
    }

    @Test
    fun `fixture 05 - the cursor survives the daemon restart and the doubled run events reach the reducer once`() = runTest {
        val lines = fixture("05-resume-after-restart.jsonl")
        val sessionId = "1791064689782_kck3p"
        val replayed = recordedEvents(lines, "c5", 16..23)
        val replies = recordedReplies(lines, "c5")
        val statusAfterRestart = HubLiveStatus.parse(replies[HubCommands.HUB_STATUS]!!.first().payload!!)
        val hub = FakeHubTransport().apply {
            answerFrom(replies)
            replay = { sub -> if (sub.sessionId == null) replayed.filter { (it.sequence ?: 0) > (sub.sinceSequence ?: 0) } else emptyList() }
        }
        val cursors = InMemoryCursorStore(106)
        val clientId = (recordedSends(lines, "c5").first() as HubFrame.Command).envelope.clientId!!
        val client = client(hub, cursors, clientId)
        val seen = mutableListOf<Long>()
        launch { client.events.collect { seen += it.sequence!! } }

        val outcome = client.connect()
        advanceUntilIdle()
        val socket = hub.sockets.single()
        assertEquals(statusAfterRestart, (outcome as HubClient.ConnectOutcome.Connected).hubStatus)
        assertEquals("the recorded resubscribe cursor", 106L, socket.globalSubscribe!!.sinceSequence)
        assertEquals((107L..114L).toList(), seen)

        // send_input to the not-yet-resumed session: the recorded reply is session_not_found.
        val pending = client.send(HubCommands.SESSION_SEND_INPUT, HubPayloads.sendInput("again"), sessionId)
        val failed = pending.reply.await()
        val error = HubCommandException(HubCommands.SESSION_SEND_INPUT, failed.error!!.code, failed.error!!.message)
        assertTrue(error.isSessionGone())

        // After the resume the app subscribes per session; every run event then arrives twice.
        client.subscribe(sessionId)
        val tail = recordedEvents(lines, "c5", 50 until lines.size)
        assertTrue("the recording doubles the run events", tail.size > tail.map { it.sequence }.distinct().size)
        tail.forEach { socket.inbound.trySend(HubFrame.Event(it)) }
        advanceUntilIdle()
        val expected = (107L..114L).toList() + tail.mapNotNull { it.sequence }.distinct()
        assertEquals(expected, seen)
        client.disconnect()
        assertEquals(tail.mapNotNull { it.sequence }.max(), cursors.value)
        finish()
    }

    @Test
    fun `every recorded frame of fixtures 01, 04 and 05 decodes`() {
        for (name in listOf("01-register-and-status.jsonl", "04-history-and-replay.jsonl", "05-resume-after-restart.jsonl")) {
            val lines = fixture(name)
            val frames = lines.filter { it.dir != "note" }
            assertTrue(name, frames.isNotEmpty() && frames.all { it.frame != null })
            val subscribe = frames.mapNotNull { it.frame as? HubFrame.StreamSubscribe }
            assertTrue(name, subscribe.all { it.clientId.startsWith("android-") })
        }
        val sessionList = fixture("05-resume-after-restart.jsonl").mapNotNull { (it.frame as? HubFrame.Reply)?.envelope?.payload?.get("sessions") }
        assertTrue(sessionList.single().jsonArray.isNotEmpty())
    }
}
