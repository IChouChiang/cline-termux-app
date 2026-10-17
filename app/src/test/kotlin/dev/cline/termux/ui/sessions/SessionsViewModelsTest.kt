package dev.cline.termux.ui.sessions

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.cline.termux.data.prefs.SidebarSortMode
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.ScheduleInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The three sessions ViewModels over [FakeSessionsPort]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelsTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private class SortStore : SidebarSortStore {
        val flow = MutableStateFlow(SidebarSortMode.Time)
        override val mode: Flow<SidebarSortMode> get() = flow
        override suspend fun set(mode: SidebarSortMode) {
            flow.value = mode
        }
    }

    private fun TestScope.drawer(port: FakeSessionsPort, sort: SortStore = SortStore()): SessionsDrawerViewModel {
        val vm = SessionsDrawerViewModel(port, sort, flowOf(null), nowMs = { testScheduler.currentTime })
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    // ---- S1 drawer ----------------------------------------------------------------------------------

    @Test fun `drawer projects the snapshot and enables actions only for live connected rows`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        assertEquals(DrawerBody.Loading, vm.uiState.value.body)
        port.loaded(Rows.tasks(3))
        runCurrent()
        val state = vm.uiState.value
        assertTrue(state.body is DrawerBody.Flat)
        assertTrue(state.actionsEnabled)
        assertFalse(state.showingCache)
        port.loaded(Rows.tasks(3), connected = false)
        runCurrent()
        assertFalse(vm.uiState.value.actionsEnabled)
        // The last live list kept after the socket dropped carries the §1.7 caption, like the cache.
        assertTrue(vm.uiState.value.showingCache)
    }

    @Test fun `sort toggle persists and filters reset the windows`() = runTest(main) {
        val port = FakeSessionsPort()
        val sort = SortStore()
        val vm = drawer(port, sort)
        port.loaded(Rows.tasks(70))
        runCurrent()
        vm.showMore(ShowMoreKind.Scheduled)
        vm.toggleSortMode()
        runCurrent()
        assertEquals(SidebarSortMode.Project, sort.flow.value)
        assertEquals(SidebarSortMode.Project, vm.uiState.value.sortMode)
        vm.setStatusFilter(StatusFilter.Running)
        runCurrent()
        assertEquals(StatusFilter.Running, vm.uiState.value.statusFilter)
        assertTrue(vm.uiState.value.filtered)
        vm.setSourceFilter("cli")
        runCurrent()
        assertEquals("cli", vm.uiState.value.sourceFilter)
    }

    @Test fun `show more grows the tasks window and the page-fill loop fetches older history`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        port.loaded(Rows.tasks(30), mayHaveMore = true)
        runCurrent()
        port.onLoadOlder = {
            port.loaded(Rows.tasks(port.state.value.rows.size + 50), mayHaveMore = true)
            true
        }
        vm.showMore(ShowMoreKind.Tasks)
        advanceUntilIdle()
        assertEquals(1, port.calls.count { it == "loadOlder" })
        assertEquals(60, ((vm.uiState.value.body as DrawerBody.Flat).rows.size))
    }

    @Test fun `a failed page halts the loop and reports it`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        port.loaded(Rows.tasks(30), mayHaveMore = true)
        port.onLoadOlder = { false }
        vm.events.test {
            vm.showMore(ShowMoreKind.Tasks)
            assertEquals(SessionsEvent.Message(SessionsMessage.LoadMoreFailed), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertEquals(1, port.calls.count { it == "loadOlder" })
    }

    @Test fun `opening a run drops the stored toggle of its group`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        val runs = listOf(
            Rows.row("r1", 9) { it.copy(schedule = ScheduleInfo("sch", "Daily", 1)) },
            Rows.row("r2", 8) { it.copy(schedule = ScheduleInfo("sch", "Daily", 2)) },
        )
        port.loaded(runs)
        runCurrent()
        vm.toggleGroup("schedule:sch", currentlyExpanded = false)
        runCurrent()
        fun group() = ((vm.uiState.value.body as DrawerBody.Categories).sections.single().rows.single() as DrawerRow.ScheduleGroup)
        assertTrue(group().expanded)
        vm.setActiveSession("r2")
        runCurrent()
        assertTrue("default rule: expanded while it holds the active run", group().expanded)
        vm.toggleGroup("schedule:sch", currentlyExpanded = true)
        runCurrent()
        assertFalse(group().expanded)
    }

    @Test fun `drawer open asks the repository and row taps mark seen then open`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        vm.onDrawerOpened()
        vm.events.test {
            vm.actions.open("s1")
            assertEquals(SessionsEvent.OpenSession("s1"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("drawerOpened" in port.calls)
        assertTrue("markSeen:s1" in port.calls)
    }

    // ---- shared row actions -------------------------------------------------------------------------

    @Test fun `rename keeps the editor open on failure and closes on success`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        val summary = Rows.summary("s1", title = "Old\nsecond line")
        vm.actions.startRename(summary)
        assertEquals(RenameDraft("s1", "Old"), vm.actions.state.value.editing)
        port.renameOutcome = ActionOutcome.Failed(ActionFailure.Kernel("boom"))
        vm.events.test {
            vm.actions.commitRename("New")
            assertEquals(SessionsEvent.Message(SessionsMessage.ActionFailed(FailedAction.Rename, ActionFailure.Kernel("boom"))), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("s1", vm.actions.state.value.editing?.sessionId)
        port.renameOutcome = ActionOutcome.Unchanged
        vm.actions.commitRename("New")
        runCurrent()
        assertNull(vm.actions.state.value.editing)
        assertEquals(listOf("rename:s1:New", "rename:s1:New"), port.calls.filter { it.startsWith("rename") })
    }

    @Test fun `pin failure names the direction, fork opens the new session`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        port.pinOutcome = ActionOutcome.Failed(ActionFailure.NotConnected)
        vm.events.test {
            vm.actions.togglePin(Rows.summary("s1", pinned = true))
            assertEquals(SessionsEvent.Message(SessionsMessage.ActionFailed(FailedAction.Unpin, ActionFailure.NotConnected)), awaitItem())
            vm.actions.fork(Rows.summary("s1"))
            assertEquals(SessionsEvent.OpenSession("fork-1"), awaitItem())
            port.forkOutcome = ActionOutcome.Failed(ActionFailure.EmptySession)
            vm.actions.fork(Rows.summary("s1"))
            assertEquals(SessionsEvent.Message(SessionsMessage.ActionFailed(FailedAction.Fork, ActionFailure.EmptySession)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("pin:s1:false" in port.calls)
        assertTrue("markSeen:fork-1" in port.calls)
    }

    @Test fun `delete closes the dialog either way`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = drawer(port)
        val summary = Rows.summary("s1")
        vm.events.test {
            vm.actions.requestDelete(summary)
            vm.actions.showDetails("s1")
            assertEquals(summary, vm.actions.state.value.deleteCandidate)
            vm.actions.confirmDelete()
            assertEquals(SessionsEvent.Deleted("s1"), awaitItem())
            assertNull(vm.actions.state.value.deleteCandidate)
            assertNull(vm.actions.state.value.detailsFor)
            port.deleteOutcome = ActionOutcome.Failed(ActionFailure.NotApplied)
            vm.actions.requestDelete(summary)
            vm.actions.confirmDelete()
            assertEquals(SessionsEvent.Message(SessionsMessage.ActionFailed(FailedAction.Delete, ActionFailure.NotApplied)), awaitItem())
            assertNull(vm.actions.state.value.deleteCandidate)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- S2 Sessions screen -------------------------------------------------------------------------

    private fun TestScope.screen(port: FakeSessionsPort, saved: SavedStateHandle = SavedStateHandle()): SessionsScreenViewModel {
        val vm = SessionsScreenViewModel(port, saved, cleanupScope = backgroundScope, nowMs = { testScheduler.currentTime })
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test fun `query, facets and oldest-first load the whole history`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = screen(port)
        port.loaded(Rows.tasks(50), mayHaveMore = true)
        runCurrent()
        assertEquals(0, port.calls.count { it == "loadAll" })
        vm.setQuery("t1")
        runCurrent()
        assertEquals(1, port.calls.count { it == "loadAll" })
        assertEquals(11, vm.uiState.value.rows.size) // t1, t10..t19
        vm.setQuery("")
        vm.setSort(SortDirection.Oldest)
        runCurrent()
        assertEquals("t49", vm.uiState.value.rows.first().summary.id)
        assertTrue(port.calls.count { it == "loadAll" } >= 2)
    }

    @Test fun `query and facets survive process death`() = runTest(main) {
        val port = FakeSessionsPort()
        val saved = SavedStateHandle()
        val vm = screen(port, saved)
        vm.setQuery("abc")
        vm.toggleFacet("pinned:yes")
        runCurrent()
        val restored = screen(port, saved)
        assertEquals("abc", restored.uiState.value.query)
        assertEquals(setOf("pinned:yes"), restored.uiState.value.selectedFacets)
        restored.clearFacets()
        runCurrent()
        assertTrue(restored.uiState.value.selectedFacets.isEmpty())
    }

    @Test fun `infinite scroll loads older near the end and halts on failure`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = screen(port)
        port.loaded(Rows.tasks(50), mayHaveMore = true)
        runCurrent()
        vm.onScrolled(10)
        runCurrent()
        assertEquals(0, port.calls.count { it == "loadOlder" })
        port.onLoadOlder = { false }
        vm.events.test {
            vm.onScrolled(46)
            assertEquals(SessionsEvent.Message(SessionsMessage.LoadMoreFailed), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.uiState.value.loadMoreHalted)
        vm.onScrolled(49)
        runCurrent()
        assertEquals(1, port.calls.count { it == "loadOlder" })
        port.onLoadOlder = { true }
        vm.retryLoadOlder()
        runCurrent()
        assertFalse(vm.uiState.value.loadMoreHalted)
        assertEquals(2, port.calls.count { it == "loadOlder" })
    }

    @Test fun `usage is requested for the rows on screen and cancelled on leaving`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = screen(port)
        vm.setVisibleRows(listOf("a", "b"))
        vm.setVisibleRows(listOf("a", "b"))
        runCurrent()
        assertEquals(listOf(listOf("a", "b")), port.usageRequests)
        vm.refresh()
        runCurrent()
        assertTrue("refresh" in port.calls)
    }

    @Test fun `loading, empty and filtered-empty are told apart`() = runTest(main) {
        val port = FakeSessionsPort()
        val vm = screen(port)
        assertTrue(vm.uiState.value.loading)
        port.loaded(emptyList())
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertEquals(0, vm.uiState.value.totalRows)
        port.loaded(listOf(Rows.row("a") { it.copy(kernelStatus = KernelSessionStatus.Failed) }))
        vm.toggleFacet("status:running")
        runCurrent()
        assertEquals(1, vm.uiState.value.totalRows)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    // ---- S3 search ----------------------------------------------------------------------------------

    private fun hits(vararg ids: String) = buildJsonObject {
        put(
            "hits",
            JsonArray(
                ids.map { id ->
                    buildJsonObject {
                        put("sessionId", id)
                        put("documentId", "doc-$id")
                        put("role", "user")
                        put("title", "T $id")
                        put("snippet", "[x]")
                        put("workspaceRoot", "/w")
                    }
                },
            ),
        )
    }

    private fun TestScope.search(port: FakeSessionsPort): SessionSearchViewModel {
        val vm = SessionSearchViewModel(port)
        runCurrent()
        return vm
    }

    @Test fun `search debounces, and the newest query wins`() = runTest(main) {
        val port = FakeSessionsPort()
        port.loaded(emptyList())
        port.onSearch = { q, limit ->
            assertEquals(50, limit)
            hits(q)
        }
        val vm = search(port)
        assertEquals(SearchPhase.Idle, vm.uiState.value.phase)
        vm.setQuery("re")
        runCurrent()
        assertEquals(SearchPhase.Searching, vm.uiState.value.phase)
        advanceTimeBy(100)
        vm.setQuery("redirect")
        advanceTimeBy(179)
        runCurrent()
        assertTrue(port.searches.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("redirect"), port.searches)
        assertEquals(SearchPhase.Results, vm.uiState.value.phase)
        assertEquals("redirect", vm.uiState.value.hits.single().sessionId)
    }

    @Test fun `ime search skips the debounce, blank is idle, failures and empties have their phase`() = runTest(main) {
        val port = FakeSessionsPort()
        port.loaded(emptyList())
        val vm = search(port)
        vm.setQuery("  ")
        runCurrent()
        assertEquals(SearchPhase.Idle, vm.uiState.value.phase)
        vm.setQuery("none")
        vm.submit()
        runCurrent()
        assertEquals(SearchPhase.NoResults, vm.uiState.value.phase)
        port.onSearch = { _, _ -> throw IllegalStateException("socket closed") }
        vm.retry()
        runCurrent()
        assertEquals(SearchPhase.Failed, vm.uiState.value.phase)
        port.onSearch = { _, _ -> withTimeout(10) { delay(1_000); hits() } }
        vm.retry()
        advanceUntilIdle()
        assertEquals(SearchPhase.Failed, vm.uiState.value.phase)
    }

    @Test fun `kernel down waits and runs the pending query when connected`() = runTest(main) {
        val port = FakeSessionsPort()
        port.loaded(emptyList(), connected = false)
        port.onSearch = { q, _ -> hits(q, "b") }
        val vm = search(port)
        vm.setQuery("abc")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(SearchPhase.KernelDown, vm.uiState.value.phase)
        assertTrue(port.searches.isEmpty())
        port.loaded(emptyList(), connected = true)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("abc"), port.searches)
        assertEquals(2, vm.uiState.value.hits.size)
    }

    @Test fun `reveal, highlight and reset`() = runTest(main) {
        val port = FakeSessionsPort()
        port.loaded(emptyList())
        port.onSearch = { _, _ -> hits(*(1..40).map { "s$it" }.toTypedArray()) }
        val vm = search(port)
        vm.setQuery("x")
        vm.submit()
        runCurrent()
        assertEquals(15, vm.uiState.value.visibleHits.size)
        assertTrue(vm.uiState.value.hasMore)
        vm.revealMore()
        assertEquals(30, vm.uiState.value.visibleHits.size)
        vm.moveHighlight(1)
        vm.moveHighlight(1)
        vm.moveHighlight(-1)
        assertEquals(0, vm.uiState.value.highlighted)
        assertEquals("s1", vm.highlightedHit()?.sessionId)
        vm.reset()
        runCurrent()
        assertEquals("", vm.uiState.value.query)
        assertEquals(SearchPhase.Idle, vm.uiState.value.phase)
    }

    @Test fun `a slow older search never overwrites a newer one`() = runTest(main) {
        val port = FakeSessionsPort()
        port.loaded(emptyList())
        val gate = CompletableDeferred<Unit>()
        port.onSearch = { q, _ ->
            if (q == "old") gate.await()
            hits(q)
        }
        val vm = search(port)
        vm.setQuery("old")
        vm.submit()
        runCurrent()
        vm.setQuery("new")
        vm.submit()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("new", vm.uiState.value.hits.single().sessionId)
    }
}
