package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.chat.Role
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.InMemoryStopNoticeStore
import dev.cline.termux.fakes.InMemoryTurnStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A17 through the real [SessionStore]: the app's stop notice after the mistake-limit question is
 * a status row placed by its time, kept in the `sessions` DataStore ([StopNoticeStore]) so it
 * survives a cold start and the state's eviction, and dropped with the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopNoticeTest {
    private val sid = "s1"
    private val clock = FakeClock()

    private fun TestScope.newStore(hub: FakeSessionHub, saved: InMemoryStopNoticeStore, turnStore: InMemoryTurnStore = InMemoryTurnStore()) = SessionStore(
        hub = hub,
        turnStore = turnStore,
        providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        hubLifetimeKey = { "1:a" },
        stopNoticeStore = saved,
    )

    private fun notice(requestId: String = "m1", session: String = sid, at: Long = 5_000L) = StopNotice(
        sessionId = session,
        requestId = requestId,
        kind = StopNoticeKind.Stopped,
        detail = "Tool no_such_tool does not exist",
        at = at,
    )

    /** The stopped turn (1 s, 3 s) and the owner's next prompt (7 s): the notice (5 s) sits between them. */
    private val turns = HubJson.parseToJsonElement(
        """[{"role":"user","ts":1000,"content":[{"type":"text","text":"<user_input mode=\"act\">loop</user_input>"}]},
            {"role":"assistant","ts":3000,"content":[{"type":"text","text":"trying again"}]},
            {"role":"user","ts":7000,"content":[{"type":"text","text":"<user_input mode=\"act\">next</user_input>"}]}]""",
    ) as JsonArray

    /** The stopped run's history once it ended: the answer of the model call in flight at the stop (6.5 s) is in it. */
    private val stoppedRun = HubJson.parseToJsonElement(
        """[{"role":"user","ts":1000,"content":[{"type":"text","text":"<user_input mode=\"act\">loop</user_input>"}]},
            {"role":"assistant","ts":3000,"content":[{"type":"text","text":"trying again"}]},
            {"role":"assistant","ts":6500,"content":[{"type":"text","text":"I'll call the tool."}]}]""",
    ) as JsonArray

    private fun event(name: String, seq: Long, at: Long, payload: String) = HubEventEnvelope(
        event = name,
        sessionId = sid,
        sequence = seq,
        timestamp = at,
        payload = HubJson.parseToJsonElement(payload).jsonObject,
    )

    private fun SessionStore.stopRows(sessionId: String = sid) = state(sessionId).value.messages.filter(StopNotices::isStopNotice)

    private fun SessionStore.roles() = state(sid).value.messages.map { it.role }

    @Test
    fun `a stop notice is one status row per request, placed by its time, and saved`() = runTest {
        val hub = FakeSessionHub().apply { history = turns }
        val saved = InMemoryStopNoticeStore()
        val store = newStore(hub, saved)
        store.start()
        runCurrent()
        store.seed(sid)
        runCurrent()

        store.addStopNotice(notice())
        store.addStopNotice(notice())
        runCurrent()

        val row = store.stopRows().single()
        assertEquals(StopNotices.idOf("m1"), row.id)
        assertEquals(Role.Status, row.role)
        assertEquals(StopNotices.NOTICE_TYPE_STOP, row.meta.noticeType)
        assertEquals(StopNotices.REASON_STOPPED, row.meta.reason)
        assertEquals("Tool no_such_tool does not exist", row.content)
        assertEquals(listOf(Role.User, Role.Assistant, Role.Status, Role.User), store.roles())
        assertEquals(listOf(notice()), saved.notices)
    }

    @Test
    fun `a stop taken while its run is open waits for the run's end and sits below the run's last rows`() = runTest {
        // Trial row 21: the Hub took the stop at 5 s; the model call in flight lands at 6 to 7.5 s, run.aborted at 8 s.
        val hub = FakeSessionHub().apply { history = JsonArray(stoppedRun.take(2)) }
        val saved = InMemoryStopNoticeStore()
        val store = newStore(hub, saved)
        store.start()
        runCurrent()
        store.seed(sid)
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, 1, 900L, """{"requestId":"r1","clientId":"tui"}"""))
        store.addStopNotice(notice(at = 5_000L))
        runCurrent()
        assertTrue("no row while the run is open", store.stopRows().isEmpty())
        assertEquals("r1", saved.notices.single().endsRun)

        store.dispatch(event(HubEvents.ASSISTANT_DELTA, 2, 6_000L, """{"requestId":"r1","iteration":2,"text":"I'll call the tool."}"""))
        store.dispatch(event(HubEvents.ASSISTANT_FINISHED, 3, 6_500L, """{"requestId":"r1","iteration":2,"text":"I'll call the tool."}"""))
        store.dispatch(event(HubEvents.SESSION_NOTICE, 4, 7_500L, """{"message":"Compacting the conversation","noticeType":"recovery","reason":"auto_compaction"}"""))
        hub.history = stoppedRun
        store.dispatch(event(HubEvents.RUN_ABORTED, 5, 8_000L, """{"requestId":"r1","reason":"aborted"}"""))
        runCurrent()

        val messages = store.state(sid).value.messages
        assertEquals(StopNotices.idOf("m1"), messages.last().id)
        assertEquals(8_000L, messages.last().createdAt)
        assertTrue("the compaction notice sits above it", messages.indexOfFirst { it.meta.reason == "auto_compaction" } in 0 until messages.lastIndex)
        assertTrue("the run's answer sits above it", messages.dropLast(1).any { it.role == Role.Assistant && "I'll call the tool." in it.content })
        assertEquals(listOf(notice(at = 8_000L)), saved.notices)
    }

    @Test
    fun `a waiting notice is placed when its run is cut by an interruption`() = runTest {
        val saved = InMemoryStopNoticeStore()
        val store = newStore(FakeSessionHub(), saved)
        store.start()
        runCurrent()
        store.dispatch(event(HubEvents.RUN_STARTED, 1, 900L, """{"requestId":"r1","clientId":"tui"}"""))
        store.addStopNotice(notice(at = 5_000L))
        runCurrent()
        assertTrue(store.stopRows().isEmpty())
        store.markInterrupted(sid)
        runCurrent()
        assertEquals(listOf(clock.millis()), store.stopRows().map { it.createdAt })
        assertNull(saved.notices.single().endsRun)
    }

    @Test
    fun `a notice still waiting at a cold start takes the end its run had, else its own time`() = runTest {
        val now = clock.millis()
        val ended = TurnRecord(
            requestId = "r1", sessionId = sid, open = false, started = true, sentAt = now - 9_000L,
            closedAt = now - 2_000L, outcome = TurnOutcome.Aborted, ours = false,
        )
        val saved = InMemoryStopNoticeStore(
            listOf(
                notice("m1", at = now - 5_000L).copy(endsRun = "r1"),
                notice("m2", session = "s2", at = now - 4_000L).copy(endsRun = "gone"),
            ),
        )
        val store = newStore(FakeSessionHub(), saved, InMemoryTurnStore(listOf(ended)))
        store.start()
        runCurrent()
        assertEquals(listOf(now - 2_000L), store.stopRows().map { it.createdAt })
        assertEquals(listOf(now - 4_000L), store.stopRows("s2").map { it.createdAt })
        assertTrue(saved.notices.all { it.endsRun == null })
    }

    @Test
    fun `notices come back after a cold start, survive the seed and an eviction`() = runTest {
        val saved = InMemoryStopNoticeStore()
        val before = newStore(FakeSessionHub(), saved)
        before.start()
        runCurrent()
        before.addStopNotice(notice())
        runCurrent()

        // A cold start: the new store has the row before the session is seeded, and keeps it after.
        val hub = FakeSessionHub().apply { history = turns }
        val store = newStore(hub, saved)
        store.start()
        runCurrent()
        assertEquals(listOf(StopNotices.idOf("m1")), store.stopRows().map { it.id })
        store.seed(sid)
        runCurrent()
        assertEquals(listOf(Role.User, Role.Assistant, Role.Status, Role.User), store.roles())

        // Ten idle minutes evict the state; the one built next starts with the row.
        clock.advanceMillis(11 * 60_000L)
        advanceTimeBy(60_001)
        runCurrent()
        val rebuilt = store.state(sid).value
        assertEquals("evicted and built again", SeedState.NotSeeded, rebuilt.seed)
        assertEquals(listOf(StopNotices.idOf("m1")), rebuilt.messages.filter(StopNotices::isStopNotice).map { it.id })
    }

    @Test
    fun `a deleted session, or one the Hub no longer has, drops its notices`() = runTest {
        val hub = FakeSessionHub()
        val saved = InMemoryStopNoticeStore()
        val store = newStore(hub, saved)
        store.start()
        runCurrent()
        store.addStopNotice(notice("m1"))
        store.addStopNotice(notice("m2", session = "s2"))
        runCurrent()

        store.forgetStopNotices(sid)
        runCurrent()
        assertTrue(store.stopRows().isEmpty())
        assertEquals(listOf("m2"), saved.notices.map { it.requestId })

        hub.onGet = { id -> throw FakeSessionHub.error("session.get", HubErrorCodes.SESSION_NOT_FOUND, "Session not found: $id") }
        store.seed("s2")
        runCurrent()
        assertEquals(SeedState.Missing, store.state("s2").value.seed)
        assertTrue(store.stopRows("s2").isEmpty())
        assertTrue(saved.notices.isEmpty())
    }

    @Test
    fun `the store keeps the newest notices across sessions`() = runTest {
        val saved = InMemoryStopNoticeStore()
        val store = newStore(FakeSessionHub(), saved)
        store.start()
        runCurrent()
        for (n in 1..StopNotices.MAX_NOTICES + 1) {
            store.addStopNotice(notice("m$n", session = if (n % 2 == 0) "s2" else sid, at = n.toLong()))
        }
        runCurrent()
        val kept = saved.notices
        assertEquals(StopNotices.MAX_NOTICES, kept.size)
        assertEquals("m2", kept.first().requestId)
        assertEquals("m${StopNotices.MAX_NOTICES + 1}", kept.last().requestId)
    }

    @Test
    fun `a notice records the kernel's detail for a stop only`() {
        val context = MistakeLimitContext(3, 6, 6, "tool_error", "  Tool no_such_tool does not exist  ")
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.Stopped, detail = "Tool no_such_tool does not exist", at = 7L),
            StopNotices.of(sid, "m1", StopNoticeKind.Stopped, context, at = 7L),
        )
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.TimedOut, detail = "", at = 7L),
            StopNotices.of(sid, "m1", StopNoticeKind.TimedOut, context, at = 7L),
        )
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.Stopped, detail = "", at = 7L),
            StopNotices.of(sid, "m1", StopNoticeKind.Stopped, null, at = 7L),
        )
        val long = "x".repeat(700)
        assertEquals(
            MistakeLimitPrompt.truncatedDetail(long),
            StopNotices.of(sid, "m1", StopNoticeKind.Stopped, context.copy(details = long), at = 7L).detail,
        )
    }

    @Test
    fun `the stored list round-trips, keeps one notice per request, and survives garbage`() {
        val notices = listOf(notice("m1", at = 2L), notice("m2", at = 1L).copy(kind = StopNoticeKind.TimedOut, detail = ""))
        assertEquals(notices, StopNotices.decode(StopNotices.encode(notices)))
        assertEquals(listOf("m2", "m1"), StopNotices.prune(notices + notice("m1", at = 9L)).map { it.requestId })
        assertTrue(StopNotices.decode("not json").isEmpty())
        assertTrue(StopNotices.decode("").isEmpty())
    }

    @Test
    fun `a notice saved with the old mistake count still loads, placed at once`() {
        // v0.1.0-pre.1 stored the request's consecutiveMistakes (dropped for trial row 21).
        val saved = """[{"sessionId":"$sid","requestId":"m1","kind":"Stopped","mistakes":6,"detail":"Tool no_such_tool does not exist","at":5000}]"""
        assertEquals(listOf(notice("m1")), StopNotices.decode(saved))
    }
}
