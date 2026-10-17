package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubErrorCodes
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
import org.junit.Assert.assertEquals
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

    private fun TestScope.newStore(hub: FakeSessionHub, saved: InMemoryStopNoticeStore) = SessionStore(
        hub = hub,
        turnStore = InMemoryTurnStore(),
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
        mistakes = 3,
        detail = "Tool no_such_tool does not exist",
        at = at,
    )

    /** The stopped turn (1 s, 3 s) and the owner's next prompt (7 s): the notice (5 s) sits between them. */
    private val turns = HubJson.parseToJsonElement(
        """[{"role":"user","ts":1000,"content":[{"type":"text","text":"<user_input mode=\"act\">loop</user_input>"}]},
            {"role":"assistant","ts":3000,"content":[{"type":"text","text":"trying again"}]},
            {"role":"user","ts":7000,"content":[{"type":"text","text":"<user_input mode=\"act\">next</user_input>"}]}]""",
    ) as JsonArray

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
        assertEquals(3, row.meta.consecutiveMistakes)
        assertEquals("Tool no_such_tool does not exist", row.content)
        assertEquals(listOf(Role.User, Role.Assistant, Role.Status, Role.User), store.roles())
        assertEquals(listOf(notice()), saved.notices)
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
    fun `a notice records the request's count, and the kernel's detail for a stop only`() {
        val context = MistakeLimitContext(3, 6, 6, "tool_error", "  Tool no_such_tool does not exist  ")
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.Stopped, mistakes = 6, detail = "Tool no_such_tool does not exist", at = 7L),
            StopNotices.of(sid, "m1", StopNoticeKind.Stopped, context, at = 7L),
        )
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.TimedOut, mistakes = 6, detail = "", at = 7L),
            StopNotices.of(sid, "m1", StopNoticeKind.TimedOut, context, at = 7L),
        )
        assertEquals(
            StopNotice(sid, "m1", StopNoticeKind.Stopped, mistakes = null, detail = "", at = 7L),
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
        val notices = listOf(notice("m1", at = 2L), notice("m2", at = 1L).copy(kind = StopNoticeKind.TimedOut, detail = "", mistakes = null))
        assertEquals(notices, StopNotices.decode(StopNotices.encode(notices)))
        assertEquals(listOf("m2", "m1"), StopNotices.prune(notices + notice("m1", at = 9L)).map { it.requestId })
        assertTrue(StopNotices.decode("not json").isEmpty())
        assertTrue(StopNotices.decode("").isEmpty())
    }
}
