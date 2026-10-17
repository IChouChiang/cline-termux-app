package dev.cline.termux.data.sessions

import app.cash.turbine.test
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.prefs.SessionListCache
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeSessionHub
import dev.cline.termux.fakes.FakeSessionsHub
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.InMemorySessionsPersistence
import dev.cline.termux.fakes.InMemoryTurnStore
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SS §1 list model: fixture replay (02, 05), refresh policy, unread, interrupted rule, cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock(Instant.ofEpochMilli(1_791_064_710_000L))
    private val fixture02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val fixture05 by lazy { HubFixtures.load("05-resume-after-restart.jsonl") }

    private class Env(
        val repo: SessionsRepository,
        val hub: FakeSessionsHub,
        val persistence: InMemorySessionsPersistence,
        val cache: SessionListCache<SessionsCachePayload>,
    )

    private fun TestScope.env(
        hub: FakeSessionsHub = FakeSessionsHub(FIXTURE_CLIENT),
        persistence: InMemorySessionsPersistence = InMemorySessionsPersistence(),
        seedCache: SessionsCachePayload? = null,
        loadedSession: (String) -> StateFlow<SessionState>? = { null },
    ): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SessionListCache(tmp.root, SessionsCachePayload.serializer(), clock = clock, io = dispatcher)
        if (seedCache != null) {
            backgroundScope.launch { cache.write(seedCache) }
            runCurrent()
        }
        val repo = SessionsRepository(hub, persistence, cache, backgroundScope, dispatcher, clock, loadedSession)
        repo.start()
        runCurrent()
        return Env(repo, hub, persistence, cache)
    }

    private fun j(text: String): JsonObject = HubJson.parseToJsonElement(text).jsonObject

    private fun record(
        id: String,
        updatedAt: Long = 1_000,
        status: String = "completed",
        title: String? = "Title $id",
        participants: String = "[]",
        metadataExtra: String = "",
    ): JsonObject {
        val titleField = title?.let { ""","title":"$it"""" }.orEmpty()
        return j(
            """{"sessionId":"$id","workspaceRoot":"/w","cwd":"/w","createdAt":100,"updatedAt":$updatedAt,"status":"$status",""" +
                """"participants":$participants,"metadata":{"provider":"p","model":"m"$titleField$metadataExtra}}""",
        )
    }

    private fun sessions(vararg records: JsonObject): JsonObject = buildJsonObject { put("sessions", JsonArray(records.toList())) }

    private fun event(name: String, seq: Long?, sessionId: String?, payload: String = "{}", timestamp: Long? = null, clientId: String? = null) =
        HubEventEnvelope(event = name, sequence = seq, sessionId = sessionId, timestamp = timestamp, clientId = clientId, payload = j(payload))

    private fun SessionsRepository.row(id: String): SessionRow? = state.value.row(id)

    // ---- list loading -----------------------------------------------------------------------------

    @Test fun `initial list maps fixture 05 sorted by activity and writes the cache`() = runTest {
        val e = env()
        e.hub.onList = { HubFixtures.replyTo(fixture05, "session-list-005").payload!! }
        e.hub.connect(lastSequence = 114, hubId = "hub_1791064706836_nwdaq")
        settle()
        val s = e.repo.state.value
        assertEquals(ListSource.Live, s.source)
        assertEquals(ListLoad.Loaded, s.load)
        assertEquals(listOf("1791064691937_rkppp", "1791064689782_kck3p"), s.rows.map { it.id })
        assertEquals(listOf(50), e.hub.listLimits)
        assertFalse(s.mayHaveMore)
        assertEquals(SessionVisual.Cancelled, s.row("1791064691937_rkppp")!!.visual)
        val cached = e.cache.read()!!.value
        assertEquals(2, cached.rows.size)
        assertTrue(cached.rows.all { it.metadata.isEmpty() })
        assertTrue("schedule links are read after connect", "schedule.list_executions" in e.hub.calls)
    }

    @Test fun `initial-load failures retry every 2 s and stay silent`() = runTest {
        val e = env()
        var attempts = 0
        e.hub.onList = { if (attempts++ < 2) throw FakeSessionsHub.error("session.list", "client_timeout", "no reply") else sessions(record("s1")) }
        e.hub.connect()
        runCurrent()
        assertEquals(ListLoad.Initial, e.repo.state.value.load)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(ListLoad.Initial, e.repo.state.value.load)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(ListLoad.Loaded, e.repo.state.value.load)
        // After a load on this connection, a failure is reported.
        e.hub.onList = { throw FakeSessionsHub.error("session.list", "command_failed", "boom") }
        assertFalse(e.repo.refresh())
        assertEquals(ListLoad.Failed("boom"), e.repo.state.value.load)
        assertEquals(listOf("s1"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `cached rows show before connecting and never mix with live rows`() = runTest {
        val cachedRow = SessionSummary.fromRecord(record("old"))!!.forCache()
        val e = env(seedCache = SessionsCachePayload(rows = listOf(cachedRow)))
        assertEquals(ListSource.Cache, e.repo.state.value.source)
        assertEquals(listOf("old"), e.repo.state.value.rows.map { it.id })
        e.hub.onList = { sessions(record("new")) }
        e.hub.connect()
        settle()
        assertEquals(ListSource.Live, e.repo.state.value.source)
        assertEquals(listOf("new"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `concurrent refreshes coalesce into one request`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1")) }
        e.hub.connect()
        settle()
        val gate = CompletableDeferred<Unit>()
        e.hub.onList = {
            gate.await()
            sessions(record("s1"), record("s2"))
        }
        val calls = e.hub.listLimits.size
        launch { e.repo.refresh() }
        launch { e.repo.refresh() }
        runCurrent()
        gate.complete(Unit)
        settle()
        assertEquals(calls + 1, e.hub.listLimits.size)
        assertEquals(2, e.repo.state.value.rows.size)
    }

    @Test fun `load older adds a page and load all doubles until a short reply`() = runTest {
        val e = env()
        e.hub.onList = { limit -> sessions(*(1..minOf(limit, 120)).map { record("s$it", updatedAt = 1_000L + it) }.toTypedArray()) }
        e.hub.connect()
        settle()
        assertTrue(e.repo.state.value.mayHaveMore)
        assertTrue(e.repo.loadOlder())
        assertEquals(100, e.repo.state.value.limit)
        assertTrue(e.repo.loadAll())
        assertEquals(listOf(50, 100, 200), e.hub.listLimits)
        assertEquals(120, e.repo.state.value.rows.size)
        assertFalse(e.repo.state.value.mayHaveMore)
    }

    @Test fun `subagent and unknown-model records are dropped`() = runTest {
        val e = env()
        e.hub.onList = {
            sessions(
                record("root"),
                record("child", metadataExtra = ""","parentSessionId":"root""""),
                j("""{"sessionId":"nomodel","createdAt":1,"updatedAt":1,"status":"idle","metadata":{"provider":"unknown","model":"m"}}"""),
            )
        }
        e.hub.connect()
        settle()
        assertEquals(listOf("root"), e.repo.state.value.rows.map { it.id })
    }

    // ---- events -----------------------------------------------------------------------------------

    @Test fun `fixture 02 drives status, approval flag and unread`() = runTest {
        val e = env()
        e.hub.connect(lastSequence = 3)
        settle()
        val id = "1791064689782_kck3p"
        val created = HubFixtures.events(fixture02).first { it.event == HubEvents.SESSION_CREATED }.payload!!["session"]!!.jsonObject
        e.hub.onList = { sessions(created) }
        for (ev in HubFixtures.events(fixture02)) {
            e.repo.observe(ev)
            if (ev.event == HubEvents.APPROVAL_REQUESTED) {
                assertEquals(SessionVisual.ApprovalRequired, e.repo.row(id)!!.visual)
            }
            if (ev.event == HubEvents.APPROVAL_RESOLVED) assertTrue(e.repo.row(id)!!.flags.openApprovalIds.isEmpty())
        }
        settle()
        val row = e.repo.row(id)!!
        assertEquals(KernelSessionStatus.Idle, row.summary.kernelStatus)
        assertTrue(row.flags.unread)
        assertEquals(SessionVisual.Unread, row.visual)
        assertEquals(2500L, row.summary.usage?.inputTokens)
        assertEquals(120L, row.summary.usage?.outputTokens)
        e.repo.markSeen(id)
        settle()
        assertFalse(e.repo.row(id)!!.flags.unread)
        assertEquals(49L, e.persistence.lastSeenMap[id])
        assertTrue(e.persistence.unread.isEmpty())
    }

    @Test fun `the session on screen never turns unread`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1")) }
        e.hub.connect()
        settle()
        e.repo.setVisibleSession("s1")
        settle()
        e.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 10, "s1"))
        assertFalse(e.repo.row("s1")!!.flags.unread)
        settle()
        assertEquals("the answer on screen is saved as seen (trial row 20)", 10L, e.persistence.lastSeenMap["s1"])
        e.repo.setVisibleSession(null)
        settle()
        e.repo.observe(event(HubEvents.RUN_COMPLETED, 11, "s1", """{"reason":"disposed"}"""))
        assertFalse("disposed is not success", e.repo.row("s1")!!.flags.unread)
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 12, "s1", """{"sessionId":"s1","snapshot":{"status":"completed"}}"""))
        assertFalse("session.updated never counts", e.repo.row("s1")!!.flags.unread)
        e.repo.observe(event(HubEvents.RUN_COMPLETED, 13, "s1", """{"reason":"completed"}"""))
        assertTrue(e.repo.row("s1")!!.flags.unread)
    }

    @Test fun `streamed deltas for the session on screen never rebuild the list`() = runTest {
        val sessionHub = FakeSessionHub(FIXTURE_CLIENT)
        val store = SessionStore(
            hub = sessionHub,
            turnStore = InMemoryTurnStore(),
            providerAuth = ProviderAuthResolver(ProviderAuthCatalog.None),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            hubLifetimeKey = { "1:a" },
        )
        store.start()
        runCurrent()
        val e = env(loadedSession = { id -> store.state(id) })
        e.hub.onList = { sessions(record("s1", status = "running")) }
        e.hub.connect()
        settle()
        store.open("s1")
        settle()
        assertTrue(store.state("s1").value.seed is SeedState.Seeded)
        e.repo.setVisibleSession("s1")
        val started = event(HubEvents.RUN_STARTED, 1, "s1", """{"requestId":"r1","clientId":"tui"}""")
        store.dispatch(started)
        e.repo.observe(started)
        settle()

        e.repo.state.test {
            val before = awaitItem()
            val publishes = e.repo.publishCount
            val seenWrites = e.persistence.lastSeenWrites
            for (seq in 2L..101L) {
                val delta = event(HubEvents.ASSISTANT_DELTA, seq, "s1", """{"requestId":"r1","iteration":1,"text":"chunk $seq "}""")
                store.dispatch(delta)
                e.repo.observe(delta)
            }
            settle()
            assertTrue("the deltas reached the transcript", store.state("s1").value.messages.isNotEmpty())
            expectNoEvents()
            assertEquals("no snapshot was rebuilt for a delta", publishes, e.repo.publishCount)
            assertEquals("no last-seen write for a delta", seenWrites, e.persistence.lastSeenWrites)
            assertEquals(before, e.repo.state.value)

            // A fact the row reads still flows through: the store's approval shows on the row.
            store.dispatch(
                event(
                    HubEvents.APPROVAL_REQUESTED,
                    102,
                    "s1",
                    """{"approvalId":"ap1","toolCallId":"call_1","toolName":"run_commands","inputJson":"{}"}""",
                ),
            )
            settle()
            assertEquals(setOf("ap1"), awaitItem().row("s1")!!.flags.openApprovalIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `replayed events mark unread only when newer than the last disconnect`() = runTest {
        val cap = 5_000L
        val e = env(persistence = InMemorySessionsPersistence(lastDisconnect = cap, lastSeenMap = mutableMapOf("s1" to 40L)))
        e.hub.onList = { sessions(record("s1"), record("s2")) }
        e.hub.connect(lastSequence = 100)
        settle()
        e.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 50, "s1", timestamp = cap - 1))
        assertFalse(e.repo.row("s1")!!.flags.unread)
        e.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 30, "s2", timestamp = cap + 1))
        assertTrue(e.repo.row("s2")!!.flags.unread)
        e.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 35, "s1", timestamp = cap + 1))
        assertFalse("at or below lastSeen", e.repo.row("s1")!!.flags.unread)
        e.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 101, "s1", timestamp = 1))
        assertTrue("live events are not capped", e.repo.row("s1")!!.flags.unread)
        settle()
        assertEquals(mapOf("s1" to 1, "s2" to 1), e.persistence.unread)
    }

    @Test fun `an answer the open chat showed stays read after a force-stop and cold start`() = runTest {
        // Trial row 20: a force-stop runs no ON_STOP, so the saved cap is older than the answer.
        val cap = 5_000L
        val saved = InMemorySessionsPersistence(lastDisconnect = cap)
        val before = env(persistence = saved)
        before.hub.onList = { sessions(record("s1")) }
        before.hub.connect(lastSequence = 9)
        settle()
        before.repo.setVisibleSession("s1")
        settle()
        val finished = event(HubEvents.ASSISTANT_FINISHED, 10, "s1", timestamp = cap + 1_000)
        val completed = event(HubEvents.RUN_COMPLETED, 11, "s1", """{"reason":"completed"}""", timestamp = cap + 1_100)
        before.repo.observe(finished)
        before.repo.observe(completed)
        settle()
        assertEquals(11L, saved.lastSeenMap["s1"])

        // The cold start: the same saved state, no chat on screen, and the cursor behind the answer, so it is replayed.
        val after = env(persistence = saved)
        after.hub.onList = { sessions(record("s1")) }
        after.hub.connect(lastSequence = 20)
        settle()
        after.repo.observe(finished)
        after.repo.observe(completed)
        settle()
        assertFalse(after.repo.row("s1")!!.flags.unread)
        assertTrue(saved.unread.isEmpty())
        after.repo.observe(event(HubEvents.ASSISTANT_FINISHED, 21, "s1", timestamp = cap + 2_000))
        assertTrue("a newer answer still counts", after.repo.row("s1")!!.flags.unread)
    }

    @Test fun `a replay that arrives before restore and connect handling never marks unread`() = runTest {
        // A service-only process start: the pump's first replayed frames create the repository,
        // so they reach observe() while the saved marks are still being read.
        val id = "1791064689782_kck3p"
        val events = HubFixtures.events(fixture02)
        val cap = events.mapNotNull { it.timestamp }.max() + 60_000L
        val lastSequence = events.mapNotNull { it.sequence }.max()
        val saved = InMemorySessionsPersistence(lastDisconnect = cap)
        val gate = CompletableDeferred<Unit>()
        val persistence = object : SessionsPersistence by saved {
            override suspend fun unreadCounts(): Map<String, Int> {
                gate.await()
                return saved.unreadCounts()
            }
        }
        val hub = FakeSessionsHub(FIXTURE_CLIENT)
        val created = events.first { it.event == HubEvents.SESSION_CREATED }.payload!!["session"]!!.jsonObject
        hub.onList = { sessions(created) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SessionListCache(tmp.root, SessionsCachePayload.serializer(), clock = clock, io = dispatcher)
        val repo = SessionsRepository(hub, persistence, cache, backgroundScope, dispatcher, clock)
        repo.start()
        // HubClient sets hub.status and Connected before the subscribe frame's replay arrives.
        hub.connect(lastSequence = lastSequence)
        val pump = launch { for (ev in events) repo.observe(ev) }
        runCurrent()
        assertFalse("the pump waits for the saved state", pump.isCompleted)
        gate.complete(Unit)
        settle()
        assertTrue(pump.isCompleted)
        val row = repo.row(id)!!
        assertFalse(row.flags.unread)
        assertTrue(saved.unread.isEmpty())
    }

    @Test fun `question flag follows capability requested and resolved`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1", status = "running")) }
        e.hub.connect()
        settle()
        e.repo.observe(event(HubEvents.CAPABILITY_REQUESTED, 5, "s1", """{"requestId":"q1","capabilityName":"tool_executor.askQuestion","targetClientId":"cli"}"""))
        assertEquals(SessionVisual.QuestionAsked, e.repo.row("s1")!!.visual)
        e.repo.observe(event(HubEvents.CAPABILITY_RESOLVED, 6, "s1", """{"requestId":"q1","ok":false,"cancelled":true}"""))
        assertTrue(e.repo.row("s1")!!.flags.openQuestionIds.isEmpty())
        // G2: the mistake-limit prompt waits on the user like a question; other contributions do not.
        e.repo.observe(event(HubEvents.CAPABILITY_REQUESTED, 7, "s1", """{"requestId":"t1","capabilityName":"custom_tool.switch_to_act_mode","targetClientId":"cli"}"""))
        assertTrue(e.repo.row("s1")!!.flags.openQuestionIds.isEmpty())
        e.repo.observe(event(HubEvents.CAPABILITY_REQUESTED, 8, "s1", """{"requestId":"m1","capabilityName":"mistake_limit.decide","targetClientId":"cli"}"""))
        assertEquals(setOf("m1"), e.repo.row("s1")!!.flags.openQuestionIds)
        e.repo.observe(event(HubEvents.CAPABILITY_RESOLVED, 9, "s1", """{"requestId":"m1","ok":true}"""))
        assertTrue(e.repo.row("s1")!!.flags.openQuestionIds.isEmpty())
        e.repo.observe(event(HubEvents.APPROVAL_REQUESTED, 10, "s1", """{"approvalId":"a1"}"""))
        e.repo.observe(event(HubEvents.APPROVAL_REQUESTED, 11, "s1", """{"approvalId":"a1"}"""))
        assertEquals(setOf("a1"), e.repo.row("s1")!!.flags.openApprovalIds)
        e.repo.observe(event(HubEvents.RUN_ABORTED, 12, "s1"))
        assertTrue("a terminal run clears approvals", e.repo.row("s1")!!.flags.openApprovalIds.isEmpty())
    }

    @Test fun `an event for an unknown session refreshes once after 250 ms`() = runTest {
        val e = env()
        e.hub.connect()
        settle()
        val before = e.hub.listLimits.size
        e.repo.observe(event(HubEvents.ASSISTANT_DELTA, 5, "ghost", """{"text":"x"}"""))
        advanceTimeBy(200)
        assertEquals(before, e.hub.listLimits.size)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(before + 1, e.hub.listLimits.size)
        e.repo.observe(event(HubEvents.ASSISTANT_DELTA, 6, "ghost", """{"text":"y"}"""))
        settle()
        assertEquals("a teammate the list never shows is not chased again", before + 1, e.hub.listLimits.size)
    }

    @Test fun `session created event inserts and survives a racing short list`() = runTest {
        val e = env()
        e.hub.connect()
        settle()
        // The list goes out before the create; its reply comes back after the create's event.
        val reply = CompletableDeferred<JsonObject>()
        e.hub.onList = { reply.await() }
        val refreshed = async { e.repo.refresh() }
        runCurrent()
        e.repo.observe(event(HubEvents.SESSION_CREATED, 5, "n1", """{"session":${record("n1", status = "idle", metadataExtra = ""","createdByClientId":"x"""")}}"""))
        assertEquals(listOf("n1"), e.repo.state.value.rows.map { it.id })
        assertTrue("created by another client counts as unread", e.repo.row("n1")!!.flags.unread)
        reply.complete(sessions())
        assertTrue(refreshed.await())
        assertEquals(listOf("n1"), e.repo.state.value.rows.map { it.id })
    }

    // ---- reconciliation with session.list (trial row 8) ----------------------------------------------

    @Test fun `a list requested after a row appeared is authoritative, even for a session created here`() = runTest {
        val e = env()
        e.hub.connect()
        settle()
        e.repo.observe(event(HubEvents.SESSION_CREATED, 5, "n1", """{"session":${record("n1", status = "idle")}}""", clientId = FIXTURE_CLIENT))
        e.repo.noteCreated("n1")
        assertEquals(listOf("n1"), e.repo.state.value.rows.map { it.id })
        // Deleted by another client before this list was asked for: the reply does not have it.
        assertTrue(e.repo.refresh())
        assertTrue(e.repo.state.value.rows.isEmpty())
    }

    @Test fun `a list drops cached and live rows it does not return and rewrites the cache`() = runTest {
        val cached = listOf(record("a"), record("gone")).map { SessionSummary.fromRecord(it)!!.forCache() }
        val e = env(seedCache = SessionsCachePayload(rows = cached))
        assertEquals(setOf("a", "gone"), e.repo.state.value.rows.map { it.id }.toSet())
        e.hub.onList = { sessions(record("a"), record("b")) }
        e.hub.connect(lastSequence = 100)
        settle()
        assertEquals(setOf("a", "b"), e.repo.state.value.rows.map { it.id }.toSet())
        assertEquals(setOf("a", "b"), e.cache.read()!!.value.rows.map { it.id }.toSet())
        // Events for a row only the old cache had never mark it unread again.
        e.repo.observe(event(HubEvents.RUN_COMPLETED, 101, "gone", """{"reason":"completed"}""", timestamp = clock.millis()))
        assertNull(e.repo.row("gone"))
        // Deleted by another client: the next list drops it, here and in the file.
        e.hub.onList = { sessions(record("a")) }
        assertTrue(e.repo.refresh())
        settle()
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("a"), e.cache.read()!!.value.rows.map { it.id })
    }

    @Test fun `a full page drops event rows it does not return`() = runTest {
        val e = env()
        e.hub.onList = { limit -> sessions(*(1..limit).map { record("p$it") }.toTypedArray()) }
        e.hub.connect(lastSequence = 100)
        settle()
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 101, "old", """{"session":${record("old", updatedAt = 9_000)}}"""))
        assertTrue(e.repo.row("old") != null)
        assertTrue(e.repo.refresh())
        assertNull("beyond the page or deleted: the desktop's list does not show it either", e.repo.row("old"))
        assertEquals(50, e.repo.state.value.rows.size)
    }

    @Test fun `replayed records of a session deleted since never bring its row back`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("a")) }
        e.hub.connect(lastSequence = 100)
        settle()
        // The replay drains after the list: the deleted session's old create and update.
        e.repo.observe(event(HubEvents.SESSION_CREATED, 40, "gone", """{"session":${record("gone")}}"""))
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 41, "gone", """{"session":${record("gone", updatedAt = 5_000)}}"""))
        settle()
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("a"), e.cache.read()!!.value.rows.map { it.id })
        // A live create after the list still inserts its row at once.
        e.repo.observe(event(HubEvents.SESSION_CREATED, 101, "new", """{"session":${record("new", updatedAt = 9_000)}}"""))
        assertEquals(listOf("new", "a"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `a replay racing the first list is dropped by it while a live create stays`() = runTest {
        val e = env()
        val reply = CompletableDeferred<JsonObject>()
        e.hub.onList = { reply.await() }
        e.hub.connect(lastSequence = 100)
        runCurrent()
        assertEquals("the first list is on its way", listOf(50), e.hub.listLimits)
        e.repo.observe(event(HubEvents.SESSION_CREATED, 60, "gone", """{"session":${record("gone")}}"""))
        e.repo.observe(event(HubEvents.SESSION_CREATED, 101, "racer", """{"session":${record("racer")}}"""))
        reply.complete(sessions(record("a")))
        settle()
        assertEquals(setOf("a", "racer"), e.repo.state.value.rows.map { it.id }.toSet())
        assertEquals("rows no list confirmed stay out of the cache", listOf("a"), e.cache.read()!!.value.rows.map { it.id })
        // The next list decides about the racer.
        e.hub.onList = { sessions(record("a"), record("racer")) }
        assertTrue(e.repo.refresh())
        settle()
        assertEquals(setOf("a", "racer"), e.cache.read()!!.value.rows.map { it.id }.toSet())
    }

    @Test fun `a reconnect to the same log keeps refusing stale replays, another log does not`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("a")) }
        e.hub.connect(lastSequence = 100)
        settle()
        e.hub.disconnect()
        runCurrent()
        val reply = CompletableDeferred<JsonObject>()
        e.hub.onList = { reply.await() }
        e.hub.connect(lastSequence = 120, generation = 1)
        runCurrent()
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 90, "gone", """{"session":${record("gone")}}"""))
        assertNull("the last list, asked for after this event, did not have it", e.repo.row("gone"))
        reply.complete(sessions(record("a")))
        settle()
        // A fresh data dir: sequences start again, so the old list's bound means nothing there.
        e.hub.disconnect()
        runCurrent()
        val other = CompletableDeferred<JsonObject>()
        e.hub.onList = { other.await() }
        e.hub.connect(lastSequence = 10, hubId = "hub_b", generation = 2)
        runCurrent()
        e.repo.observe(event(HubEvents.SESSION_CREATED, 11, "b1", """{"session":${record("b1")}}"""))
        assertTrue(e.repo.row("b1") != null)
        other.complete(sessions(record("b1")))
        settle()
        assertEquals(listOf("b1"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `a session the app forgot stays gone despite a stale reply and late events`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("a"), record("x")) }
        e.hub.connect(lastSequence = 100)
        settle()
        // A list already on its way when the session goes still carries it.
        val stale = CompletableDeferred<JsonObject>()
        e.hub.onList = { stale.await() }
        val refreshed = async { e.repo.refresh() }
        runCurrent()
        e.repo.forget("x")
        // The status update the delete itself published arrives after the delete's reply.
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 101, "x", """{"session":${record("x", updatedAt = 7_000)}}"""))
        stale.complete(sessions(record("a"), record("x")))
        assertTrue(refreshed.await())
        settle()
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("a"), e.cache.read()!!.value.rows.map { it.id })
        // Created again under its id (a resume after a delete elsewhere): back at once.
        e.repo.observe(event(HubEvents.SESSION_CREATED, 102, "x", """{"session":${record("x", status = "idle", updatedAt = 8_000)}}"""))
        assertEquals(listOf("x", "a"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `a list asked for after a forget brings the session back when the Hub has it`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("a"), record("x")) }
        e.hub.connect(lastSequence = 100)
        settle()
        e.repo.forget("x")
        assertNull(e.repo.row("x"))
        assertTrue(e.repo.refresh())
        assertTrue(e.repo.row("x") != null)
    }

    @Test fun `session_not_found on a usage read drops the row at once`() = runTest {
        val e = env()
        e.hub.onList = {
            sessions(
                record("a", status = "running", participants = """[{"clientId":"cli"}]"""),
                record("x", status = "running", participants = """[{"clientId":"cli"}]"""),
            )
        }
        e.hub.onGet = { id ->
            if (id == "x") throw FakeSessionsHub.error("session.get", "session_not_found", "Unknown session: x")
            j("""{"session":{"sessionId":"$id","metadata":{}},"usage":{"inputTokens":1,"outputTokens":2}}""")
        }
        e.hub.connect(lastSequence = 100)
        settle()
        e.repo.removed.test {
            e.repo.requestUsage(listOf("a", "x"))
            settle()
            assertEquals("x", awaitItem())
        }
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("x"), e.persistence.forgotten)
        assertEquals(listOf("a"), e.cache.read()!!.value.rows.map { it.id })
    }

    @Test fun `session_not_found on a title read drops the row at once`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("abcdef123456", title = null), record("a")) }
        e.hub.onMessages = { id -> throw FakeSessionsHub.error("session.messages", "session_not_found", "Unknown session: $id") }
        e.hub.connect(lastSequence = 100)
        settle()
        assertNull(e.repo.row("abcdef123456"))
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
    }

    @Test fun `a forgotten row leaves the cache while still offline`() = runTest {
        val cached = listOf(record("a"), record("gone")).map { SessionSummary.fromRecord(it)!!.forCache() }
        val e = env(seedCache = SessionsCachePayload(rows = cached))
        e.repo.forget("gone")
        settle()
        assertEquals(listOf("a"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("a"), e.cache.read()!!.value.rows.map { it.id })
    }

    // ---- annotation override (§1.3 rule 5) ----------------------------------------------------------

    @Test fun `annotation keeps the row's age until real activity`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1", updatedAt = 1_000)) }
        e.hub.connect()
        settle()
        e.repo.armAnnotationOverride("s1")
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 5, "s1", """{"session":${record("s1", updatedAt = 9_000)}}"""))
        assertEquals(1_000L, e.repo.row("s1")!!.summary.activityAt)
        assertTrue(e.repo.refresh())
        assertEquals(1_000L, e.repo.row("s1")!!.summary.activityAt)
        settle()
        assertEquals(AnnotationOverride(1_000, clock.millis()), e.cache.read()!!.value.overrides["s1"])
        e.repo.observe(event(HubEvents.RUN_STARTED, 6, "s1", """{"requestId":"r","clientId":"cli"}"""))
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 7, "s1", """{"session":${record("s1", updatedAt = 9_500, status = "running")}}"""))
        assertEquals(9_500L, e.repo.row("s1")!!.summary.activityAt)
    }

    // ---- interrupted rule (§1.3) ----------------------------------------------------------------------

    @Test fun `own turn cut by a daemon restart is interrupted`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1", status = "running")) }
        e.hub.connect(hubId = "hub_a")
        settle()
        e.repo.observe(event(HubEvents.RUN_STARTED, 5, "s1", """{"requestId":"r1","clientId":"$FIXTURE_CLIENT"}"""))
        e.hub.disconnect()
        settle()
        assertEquals(setOf("s1"), e.persistence.suspects)
        assertEquals(clock.millis(), e.persistence.lastDisconnect)
        e.hub.connect(lastSequence = 5, hubId = "hub_b")
        settle()
        assertTrue(e.repo.row("s1")!!.flags.interrupted)
        assertTrue(e.persistence.suspects.isEmpty())
        e.repo.observe(event(HubEvents.RUN_STARTED, 6, "s1", """{"requestId":"r2","clientId":"$FIXTURE_CLIENT"}"""))
        assertFalse(e.repo.row("s1")!!.flags.interrupted)
    }

    @Test fun `a daemon restart seen only as a new connection generation still interrupts the cut turn`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1", status = "running")) }
        e.hub.connect(hubId = "hub_a", generation = 1)
        settle()
        e.repo.observe(event(HubEvents.RUN_STARTED, 5, "s1", """{"requestId":"r1","clientId":"$FIXTURE_CLIENT"}"""))
        // The drop and the re-register were conflated: Connected → Connected with a new generation.
        e.hub.connect(lastSequence = 5, hubId = "hub_b", generation = 2)
        settle()
        assertEquals("the drop's bookkeeping ran", clock.millis(), e.persistence.lastDisconnect)
        assertTrue(e.repo.row("s1")!!.flags.interrupted)
        assertTrue(e.persistence.suspects.isEmpty())
    }

    @Test fun `the replay's real terminal event clears a suspect without the flag`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1", status = "running")) }
        e.hub.connect(hubId = "hub_a")
        settle()
        e.repo.observe(event(HubEvents.RUN_STARTED, 5, "s1", """{"requestId":"r1","clientId":"$FIXTURE_CLIENT"}"""))
        e.hub.disconnect()
        settle()
        e.hub.onList = { sessions(record("s1", status = "idle")) }
        e.hub.connect(lastSequence = 10, hubId = "hub_a")
        runCurrent()
        // Normal end: the idle snapshot arrives before run.completed (fixture 02 seq 47 vs 49).
        e.repo.observe(event(HubEvents.SESSION_UPDATED, 8, "s1", """{"sessionId":"s1","snapshot":{"status":"idle"}}"""))
        e.repo.observe(event(HubEvents.RUN_COMPLETED, 9, "s1", """{"reason":"completed"}"""))
        e.repo.observe(event(HubEvents.CLIENT_DISCONNECTED, 10, null))
        settle()
        assertFalse(e.repo.row("s1")!!.flags.interrupted)
    }

    @Test fun `the shutdown projection of fixture 05 marks the suspect interrupted`() = runTest {
        val e = env()
        val id = "1791064689782_kck3p"
        e.hub.onList = { sessions(record(id, status = "running")) }
        e.hub.connect(hubId = "hub_1791064687984_lzgjs")
        settle()
        e.repo.observe(event(HubEvents.RUN_STARTED, 50, id, """{"requestId":"r1","clientId":"$FIXTURE_CLIENT"}"""))
        e.hub.disconnect()
        settle()
        // Same hubId reported (the restart is detected from the projection, not the id).
        e.hub.connect(lastSequence = 114, hubId = "hub_1791064687984_lzgjs")
        runCurrent()
        val replay = HubFixtures.events(fixture05).filter { (it.sequence ?: 0) in 107..114 }
        replay.forEach { e.repo.observe(it) }
        settle()
        assertTrue(e.repo.row(id)!!.flags.interrupted)
    }

    @Test fun `suspects survive a process restart`() = runTest {
        val persistence = InMemorySessionsPersistence(suspects = mutableSetOf("s1"))
        val payload = SessionsCachePayload(
            rows = listOf(SessionSummary.fromRecord(record("s1"))!!.forCache()),
            suspects = mapOf("s1" to SuspectRecord("hub_a", "r1")),
        )
        val e = env(persistence = persistence, seedCache = payload)
        e.hub.onList = { sessions(record("s1")) }
        e.hub.connect(hubId = "hub_b")
        settle()
        assertTrue(e.repo.row("s1")!!.flags.interrupted)
    }

    // ---- schedule links (§1.3 rule 6) --------------------------------------------------------------

    @Test fun `schedule links fill schedule info and merge across refreshes`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1"), record("s2")) }
        e.hub.onScheduleList = { j("""{"schedules":[{"scheduleId":"sch1","name":" Daily standup "}]}""") }
        e.hub.onScheduleActive = { j("""{"executions":[{"sessionId":"s2","scheduleId":"sch1"}]}""") }
        e.hub.onScheduleExecutions = { j("""{"executions":[{"sessionId":"s1","scheduleId":"sch1","executionId":"x"}]}""") }
        e.hub.connect()
        settle()
        assertEquals(ScheduleInfo("sch1", "Daily standup", null), e.repo.row("s1")!!.summary.schedule)
        assertEquals(ScheduleInfo("sch1", "Daily standup", null), e.repo.row("s2")!!.summary.schedule)
        e.hub.onScheduleActive = { j("""{"executions":[]}""") }
        e.hub.onScheduleExecutions = { j("""{"executions":[]}""") }
        e.repo.observe(event(HubEvents.SCHEDULE_EXECUTION_COMPLETED, 9, null))
        settle()
        assertTrue(e.repo.refresh())
        assertEquals("links merge, never replace", "sch1", e.repo.row("s1")!!.summary.schedule?.scheduleId)
        assertEquals("sch1", e.cache.read()!!.value.scheduleLinks["s1"]?.scheduleId)
    }

    @Test fun `scheduleLinksFrom pairs executions with schedule names`() {
        val links = scheduleLinksFrom(
            listOf(j("""{"sessionId":"a","scheduleId":"x"}"""), j("""{"sessionId":"b"}"""), j("""{"scheduleId":"x"}""")),
            mapOf("x" to "Nightly"),
        )
        assertEquals(mapOf("a" to ScheduleLink("x", "Nightly"), "b" to ScheduleLink(null, null)), links)
    }

    // ---- hydration ------------------------------------------------------------------------------------

    @Test fun `placeholder titles hydrate from the first user message`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("abcdef123456", title = null)) }
        e.hub.onMessages = { j("""{"messages":[{"role":"user","content":[{"type":"text","text":"<user_input mode=\"act\">Fix the login\nmore</user_input>"}]}]}""") }
        e.hub.connect()
        runCurrent()
        assertEquals("Session 123456", e.repo.row("abcdef123456")!!.summary.title)
        advanceTimeBy(1_201)
        runCurrent()
        assertEquals("Fix the login", e.repo.row("abcdef123456")!!.summary.title)
        assertTrue(e.repo.refresh())
        assertEquals("kept across refreshes", "Fix the login", e.repo.row("abcdef123456")!!.summary.title)
    }

    @Test fun `usage is read for live rows only`() = runTest {
        val e = env()
        e.hub.onList = {
            sessions(
                record("live", status = "running", participants = """[{"clientId":"cli"}]"""),
                record("persisted"),
                record("known", status = "running", participants = """[{"clientId":"cli"}]""", metadataExtra = ""","usage":{"inputTokens":1,"outputTokens":2}"""),
            )
        }
        val gate = CompletableDeferred<Unit>()
        e.hub.onGet = { id ->
            gate.await()
            j("""{"session":{"sessionId":"$id","metadata":{"totalCost":0.0042}},"usage":{"inputTokens":12500,"outputTokens":3200}}""")
        }
        e.hub.connect()
        settle()
        e.repo.requestUsage(listOf("live", "persisted", "known"))
        runCurrent()
        assertEquals(setOf("live"), e.repo.state.value.usageLoading)
        gate.complete(Unit)
        settle()
        assertEquals(listOf("live"), e.hub.gets)
        assertEquals(RowUsage(12500, 3200, 0.0042), e.repo.row("live")!!.summary.usage)
        assertNull(e.repo.row("persisted")!!.summary.usage)
        assertTrue(e.repo.state.value.usageLoading.isEmpty())
        e.repo.observe(event(HubEvents.USAGE_UPDATED, 9, "live", """{"usage":{"inputTokens":13000,"outputTokens":3300}}"""))
        assertEquals(RowUsage(13000, 3300, 0.0042), e.repo.row("live")!!.summary.usage)
    }

    // ---- misc ---------------------------------------------------------------------------------------

    @Test fun `forget removes the row everywhere and announces it`() = runTest {
        val e = env(persistence = InMemorySessionsPersistence(unread = mutableMapOf("s1" to 2)))
        e.hub.onList = { sessions(record("s1"), record("s2")) }
        e.hub.connect()
        settle()
        assertTrue(e.repo.row("s1")!!.flags.unread)
        e.repo.removed.test {
            e.repo.forget("s1")
            assertEquals("s1", awaitItem())
        }
        assertEquals(listOf("s2"), e.repo.state.value.rows.map { it.id })
        assertEquals(listOf("s1"), e.persistence.forgotten)
        assertEquals(listOf("s1"), e.persistence.removedKnown)
    }

    @Test fun `replay truncation is surfaced`() = runTest {
        val e = env()
        e.hub.notices.emit(HubClient.Notice.ReplayTruncated(fromSequence = 1_000, lastSequence = 3_000))
        runCurrent()
        assertTrue(e.repo.state.value.replayTruncated)
    }

    @Test fun `resume and drawer refresh only when the list is stale`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1")) }
        e.hub.connect()
        settle()
        val calls = e.hub.listLimits.size
        e.repo.onResume()
        e.repo.onDrawerOpened()
        settle()
        assertEquals(calls, e.hub.listLimits.size)
        clock.advanceMillis(10_001)
        e.repo.onDrawerOpened()
        settle()
        assertEquals(calls + 1, e.hub.listLimits.size)
    }

    @Test fun `pending actions show on the row`() = runTest {
        val e = env()
        e.hub.onList = { sessions(record("s1")) }
        e.hub.connect()
        settle()
        assertTrue(e.repo.beginAction("s1", PendingKind.Rename))
        assertFalse(e.repo.beginAction("s1", PendingKind.Fork))
        assertEquals(SessionVisual.ActionPending(PendingKind.Rename), e.repo.row("s1")!!.visual)
        e.repo.endAction("s1")
        assertNull(e.repo.row("s1")!!.flags.pendingAction)
    }

    private companion object {
        const val FIXTURE_CLIENT = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    }
}

/** Runs foreground and background work (backgroundScope tasks are skipped by advanceUntilIdle) through 30 s of virtual time. */
private fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(30_000)
    runCurrent()
}
