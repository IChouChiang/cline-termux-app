package dev.cline.termux.ui.sessions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.SessionsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One S2 row: the summary, its one visual, and whether its usage is being read (§1.5 shimmer). */
@Immutable
data class SessionsScreenRow(val summary: SessionSummary, val visual: SessionVisual, val usageLoading: Boolean)

/** sessions.md §1.11 `SessionsScreenUiState` (the kernel notice comes from the shell's gate). */
@Immutable
data class SessionsScreenUiState(
    val showingCache: Boolean = false,
    val connected: Boolean = false,
    val actionsEnabled: Boolean = false,
    val load: ListLoad = ListLoad.Initial,
    /** Nothing has arrived yet (no cache, no reply): skeleton rows. */
    val loading: Boolean = true,
    val query: String = "",
    val sort: SortDirection = SortDirection.Newest,
    val selectedFacets: Set<String> = emptySet(),
    val facetOptions: List<String> = emptyList(),
    val loadingAll: Boolean = false,
    val rows: List<SessionsScreenRow> = emptyList(),
    /** Rows before any filter (first-run empty state vs filtered empty). */
    val totalRows: Int = 0,
    val totalFiltered: Int = 0,
    val mayHaveMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** A page fetch failed: the footer shows "Show more" to retry (§3.5). */
    val loadMoreHalted: Boolean = false,
    val refreshing: Boolean = false,
    val filterSheetOpen: Boolean = false,
    val editing: RenameDraft? = null,
    val pending: Map<String, PendingKind> = emptyMap(),
    val deleteCandidate: SessionSummary? = null,
    val detailsFor: String? = null,
) {
    val needsCompleteHistory: Boolean
        get() = SessionsScreenModel.needsCompleteHistory(query, selectedFacets, sort, filterSheetOpen)
}

/**
 * S2 (design/screens/sessions.md §3): local search, sort and OR facets over the repository's
 * rows, "load all history" whenever they need it, infinite scroll (+50), pull-to-refresh,
 * usage reads for the rows on screen, and the shared row actions. Query and facets survive
 * process death (`SavedStateHandle`); sort resets like the desktop.
 */
class SessionsScreenViewModel(
    val port: SessionsPort,
    private val savedState: SavedStateHandle,
    /** Outlives this ViewModel: cancels the usage reads after it is cleared (§1.5 "leaving S2"). */
    private val cleanupScope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Local(
        val query: String,
        val sort: SortDirection = SortDirection.Newest,
        val facets: Set<String>,
        val filterSheetOpen: Boolean = false,
        val halted: Boolean = false,
        val resortTick: Long = 0,
    )

    val actions = SessionRowActions(port, viewModelScope)
    val events = actions.events

    private val local = MutableStateFlow(
        Local(
            query = savedState[KEY_QUERY] ?: "",
            facets = savedState.get<ArrayList<String>>(KEY_FACETS)?.toSet().orEmpty(),
        ),
    )
    private val order = StableOrder()
    private var resortJob: Job? = null
    private var loadAllJob: Job? = null
    private var olderJob: Job? = null
    private var visibleIds: List<String> = emptyList()

    private val snapshot: StateFlow<SessionsSnapshot> =
        port.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsSnapshot())

    val uiState: StateFlow<SessionsScreenUiState> = combine(snapshot, local, actions.state) { snap, l, row ->
        project(snap, l, row)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsScreenUiState())

    init {
        // §3.4: query, facets, oldest-first or the open filter sheet need the whole history.
        viewModelScope.launch {
            combine(snapshot, local) { snap, l ->
                snap.connected && snap.mayHaveMore &&
                    SessionsScreenModel.needsCompleteHistory(l.query, l.facets, l.sort, l.filterSheetOpen)
            }.collect { needed -> if (needed) loadAll() }
        }
    }

    private fun project(snap: SessionsSnapshot, l: Local, row: RowActionsState): SessionsScreenUiState {
        val ordered = order.apply(snap.rows, nowMs())
        order.pendingResortAt?.let(::scheduleResort)
        val filtered = SessionsScreenModel.apply(ordered, l.query, l.facets, l.sort)
        val stable = if (l.sort == SortDirection.Newest) keepOrder(ordered, filtered) else filtered
        return SessionsScreenUiState(
            showingCache = snap.showingLastLoaded,
            connected = snap.connected,
            actionsEnabled = snap.connected && !snap.showingCache,
            load = snap.load,
            loading = snap.rows.isEmpty() && snap.load == ListLoad.Initial && !snap.showingCache,
            query = l.query,
            sort = l.sort,
            selectedFacets = l.facets,
            facetOptions = SessionsScreenModel.facetOptions(snap.rows),
            loadingAll = snap.loadingAll,
            rows = stable.map { SessionsScreenRow(it.summary, it.visual, it.id in snap.usageLoading) },
            totalRows = snap.rows.size,
            totalFiltered = stable.size,
            mayHaveMore = snap.mayHaveMore,
            isLoadingMore = snap.loadingMore,
            loadMoreHalted = l.halted,
            refreshing = snap.refreshing,
            filterSheetOpen = l.filterSheetOpen,
            editing = row.editing,
            pending = snap.pending,
            deleteCandidate = row.deleteCandidate,
            detailsFor = row.detailsFor,
        )
    }

    /** Newest first keeps the held (§2.7 "re-sort at most every 5 s") order of the repository rows. */
    private fun keepOrder(
        ordered: List<dev.cline.termux.data.sessions.SessionRow>,
        filtered: List<dev.cline.termux.data.sessions.SessionRow>,
    ): List<dev.cline.termux.data.sessions.SessionRow> {
        val keep = filtered.mapTo(HashSet()) { it.id }
        return ordered.filter { it.id in keep }
    }

    private fun scheduleResort(at: Long) {
        if (resortJob?.isActive == true) return
        resortJob = viewModelScope.launch {
            delay((at - nowMs()).coerceAtLeast(0))
            local.update { it.copy(resortTick = it.resortTick + 1) }
        }
    }

    // ---- search, sort, filter ------------------------------------------------------------------------

    fun setQuery(query: String) {
        savedState[KEY_QUERY] = query
        local.update { it.copy(query = query) }
    }

    fun setSort(sort: SortDirection) {
        local.update { it.copy(sort = sort) }
    }

    fun toggleFacet(facet: String) {
        local.update {
            val next = if (facet in it.facets) it.facets - facet else it.facets + facet
            savedState[KEY_FACETS] = ArrayList(next)
            it.copy(facets = next)
        }
    }

    fun clearFacets() {
        savedState[KEY_FACETS] = ArrayList<String>()
        local.update { it.copy(facets = emptySet()) }
    }

    fun setFilterSheetOpen(open: Boolean) {
        local.update { it.copy(filterSheetOpen = open) }
    }

    // ---- loading -------------------------------------------------------------------------------------

    private fun loadAll() {
        if (loadAllJob?.isActive == true) return
        loadAllJob = viewModelScope.launch { runCatching { port.loadAll() } }
    }

    /** §3.5 infinite scroll: called with the last visible index. */
    fun onScrolled(lastVisibleIndex: Int) {
        val state = uiState.value
        if (!SessionsScreenModel.shouldLoadOlder(
                lastVisibleIndex, state.rows.size, state.mayHaveMore, state.needsCompleteHistory,
                loading = state.isLoadingMore || state.loadingAll || olderJob?.isActive == true, halted = state.loadMoreHalted,
            )
        ) {
            return
        }
        loadOlder()
    }

    /** The footer's "Show more" after a failure (§3.5). */
    fun retryLoadOlder() {
        local.update { it.copy(halted = false) }
        loadOlder()
    }

    private fun loadOlder() {
        if (olderJob?.isActive == true) return
        olderJob = viewModelScope.launch {
            val ok = runCatching { port.loadOlder() }.getOrDefault(false)
            if (!ok && snapshot.value.connected) {
                local.update { it.copy(halted = true) }
                actions.message(SessionsMessage.LoadMoreFailed)
            }
        }
    }

    /** Pull-to-refresh (§3.6; disabled by the screen while the kernel is not Ready). */
    fun refresh() {
        viewModelScope.launch { runCatching { port.refresh() } }
    }

    /** The error notice's "Retry". */
    fun retry() = refresh()

    /** §1.5 step 2: the ids on screen; the empty list on leaving cancels the reads. */
    fun setVisibleRows(ids: List<String>) {
        if (ids == visibleIds) return
        visibleIds = ids
        viewModelScope.launch { runCatching { port.requestUsage(ids) } }
    }

    override fun onCleared() {
        // Leaving S2 cancels the pending usage reads (§1.5); the repository outlives this scope.
        val port = port
        cleanupScope.launch { runCatching { port.requestUsage(emptyList()) } }
    }

    companion object {
        private const val KEY_QUERY = "sessions.query"
        private const val KEY_FACETS = "sessions.facets"

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SessionsScreenViewModel(
                    port = SessionsDrawerViewModel.graphPort(graph),
                    savedState = createSavedStateHandle(),
                    cleanupScope = graph.appScope,
                )
            }
        }
    }
}
