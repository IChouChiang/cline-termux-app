package dev.cline.termux.ui.sessions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.data.prefs.SidebarSortMode
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.SessionsSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The footer's account (SP S1 summary: `account.displayName` holds the display name or the email). */
@Immutable
data class AccountFooter(val summary: String, val scope: String?) {
    /** Desktop footer rule: the first word of the display name, else the email's local part. */
    val name: String
        get() {
            val trimmed = summary.trim()
            return if ('@' in trimmed) trimmed.substringBefore('@') else trimmed.substringBefore(' ')
        }

    val initial: Char? get() = name.firstOrNull()?.uppercaseChar()
}

/** sessions.md §1.11 `SessionsDrawerUiState` (the notice comes from the shell's gate). */
@Immutable
data class SessionsDrawerUiState(
    val showingCache: Boolean = false,
    /** Row actions need the socket and live rows (§1.7). */
    val actionsEnabled: Boolean = false,
    val load: ListLoad = ListLoad.Initial,
    val sortMode: SidebarSortMode = SidebarSortMode.Time,
    val statusFilter: StatusFilter = StatusFilter.All,
    val sourceFilter: String? = null,
    val sourceOptions: List<SourceOption> = emptyList(),
    val body: DrawerBody = DrawerBody.Loading,
    val activeSessionId: String? = null,
    val editing: RenameDraft? = null,
    val pending: Map<String, PendingKind> = emptyMap(),
    val deleteCandidate: dev.cline.termux.data.sessions.SessionSummary? = null,
    val detailsFor: String? = null,
    val isLoadingMore: Boolean = false,
    val mayHaveMore: Boolean = false,
    val account: AccountFooter? = null,
    val anyUnread: Boolean = false,
) {
    /** A non-default filter is active (the filter button turns tonal, §2.6). */
    val filtered: Boolean get() = statusFilter != StatusFilter.All || sourceFilter != null
}

/** Where the sort mode persists (DataStore `sessions.sidebarSort`). */
interface SidebarSortStore {
    val mode: Flow<SidebarSortMode>

    suspend fun set(mode: SidebarSortMode)
}

/**
 * S1 (design/screens/sessions.md §2): the drawer and the expanded list pane share this
 * ViewModel (shell scope). It folds the repository snapshot through [DrawerBodies], keeps the
 * filters, paging windows and schedule-group toggles, runs the Tasks page-fill loop, and
 * delegates row actions to [SessionRowActions].
 */
class SessionsDrawerViewModel(
    val port: SessionsPort,
    private val sortStore: SidebarSortStore,
    account: Flow<AccountFooter?>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Local(
        val statusFilter: StatusFilter = StatusFilter.All,
        val sourceFilter: String? = null,
        val visible: VisibleCounts = VisibleCounts(),
        val groupToggles: Map<String, Boolean> = emptyMap(),
        val activeId: String? = null,
        /** A failed page fetch halts the page-fill loop until the next tap (§2.5 item 2). */
        val halted: Boolean = false,
        val resortTick: Long = 0,
    )

    val actions = SessionRowActions(port, viewModelScope)
    val events = actions.events

    private val local = MutableStateFlow(Local())
    private val order = StableOrder()
    private var fillJob: Job? = null
    private var resortJob: Job? = null

    private val snapshot: StateFlow<SessionsSnapshot> =
        port.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsSnapshot())

    private val sortMode: StateFlow<SidebarSortMode> =
        sortStore.mode.stateIn(viewModelScope, SharingStarted.Eagerly, SidebarSortMode.Time)

    val uiState: StateFlow<SessionsDrawerUiState> = combine(
        snapshot,
        sortMode,
        local,
        actions.state,
        account,
    ) { snap, sort, l, row, acc -> project(snap, sort, l, row, acc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsDrawerUiState())

    private fun project(
        snap: SessionsSnapshot,
        sort: SidebarSortMode,
        l: Local,
        row: RowActionsState,
        account: AccountFooter?,
    ): SessionsDrawerUiState {
        val rows = order.apply(snap.rows, nowMs())
        order.pendingResortAt?.let(::scheduleResort)
        val inputs = DrawerInputs(
            rows = rows,
            source = snap.source,
            load = snap.load,
            sortMode = sort,
            statusFilter = l.statusFilter,
            sourceFilter = l.sourceFilter,
            visible = l.visible,
            mayHaveMore = snap.mayHaveMore,
            loadingMore = snap.loadingMore,
            activeSessionId = l.activeId,
            groupToggles = l.groupToggles,
        )
        return SessionsDrawerUiState(
            showingCache = snap.showingLastLoaded,
            actionsEnabled = snap.connected && !snap.showingCache,
            load = snap.load,
            sortMode = sort,
            statusFilter = l.statusFilter,
            sourceFilter = l.sourceFilter,
            sourceOptions = DrawerBodies.sourceOptions(snap.rows),
            body = DrawerBodies.build(inputs),
            activeSessionId = l.activeId,
            editing = row.editing,
            pending = snap.pending,
            deleteCandidate = row.deleteCandidate,
            detailsFor = row.detailsFor,
            isLoadingMore = snap.loadingMore,
            mayHaveMore = snap.mayHaveMore,
            account = account,
            anyUnread = snap.anyUnread,
        )
    }

    private fun scheduleResort(at: Long) {
        if (resortJob?.isActive == true) return
        resortJob = viewModelScope.launch {
            delay((at - nowMs()).coerceAtLeast(0))
            local.update { it.copy(resortTick = it.resortTick + 1) }
        }
    }

    // ---- shell inputs ------------------------------------------------------------------------------

    /** The chat on screen; opening a run drops the stored choice for its schedule group (§2.4). */
    fun setActiveSession(sessionId: String?) {
        local.update { l ->
            if (l.activeId == sessionId) return@update l
            val key = sessionId?.let { id -> snapshot.value.row(id)?.summary?.let(DrawerBodies::groupKeyOf) }
            l.copy(activeId = sessionId, groupToggles = if (key != null) l.groupToggles - key else l.groupToggles)
        }
    }

    /** The error notice's "Retry" (§2.7): a fresh `session.list`. */
    fun retry() {
        viewModelScope.launch { runCatching { port.refresh() } }
    }

    /** §1.9 item 3. */
    fun onDrawerOpened() {
        viewModelScope.launch { runCatching { port.onDrawerOpened() } }
    }

    // ---- controls ----------------------------------------------------------------------------------

    fun toggleSortMode() {
        val next = if (sortMode.value == SidebarSortMode.Time) SidebarSortMode.Project else SidebarSortMode.Time
        viewModelScope.launch { sortStore.set(next) }
    }

    /** §2.5 item 5: changing a filter resets every window to 30. */
    fun setStatusFilter(filter: StatusFilter) {
        local.update { if (it.statusFilter == filter) it else it.copy(statusFilter = filter, visible = VisibleCounts(), halted = false) }
    }

    fun setSourceFilter(source: String?) {
        local.update { if (it.sourceFilter == source) it else it.copy(sourceFilter = source, visible = VisibleCounts(), halted = false) }
    }

    fun toggleGroup(groupId: String, currentlyExpanded: Boolean) {
        local.update { it.copy(groupToggles = it.groupToggles + (groupId to !currentlyExpanded)) }
    }

    fun showMore(kind: ShowMoreKind) {
        when (kind) {
            ShowMoreKind.Tasks -> {
                local.update { it.copy(visible = it.visible.copy(tasks = it.visible.tasks + PAGE_STEP), halted = false) }
                fillTasks()
            }
            ShowMoreKind.Scheduled -> local.update { it.copy(visible = it.visible.copy(scheduled = it.visible.scheduled + PAGE_STEP)) }
            is ShowMoreKind.Project -> local.update {
                it.copy(visible = it.visible.copy(projects = it.visible.projects + (kind.id to it.visible.project(kind.id) + PAGE_STEP)))
            }
            ShowMoreKind.History -> viewModelScope.launch {
                if (!port.loadOlder()) actions.message(SessionsMessage.LoadMoreFailed)
            }
        }
    }

    /** §2.5 item 2: `loadOlderSessions()` until Tasks has the window's rows or history ends; a failure halts. */
    private fun fillTasks() {
        if (fillJob?.isActive == true) return
        fillJob = viewModelScope.launch {
            while (true) {
                val l = local.value
                if (l.halted) return@launch
                val snap = snapshot.value
                val inputs = DrawerInputs(snap.rows, snap.source, snap.load, sortMode.value, l.statusFilter, l.sourceFilter)
                if (DrawerBodies.taskRows(inputs).size >= l.visible.tasks || !snap.mayHaveMore) return@launch
                if (!port.loadOlder()) {
                    local.update { it.copy(halted = true) }
                    actions.message(SessionsMessage.LoadMoreFailed)
                    return@launch
                }
                // Let the snapshot collector take the new page before re-checking the window.
                yield()
            }
        }
    }

    companion object {
        const val PAGE_STEP = 30

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val prefs = graph.prefs
                SessionsDrawerViewModel(
                    port = graphPort(graph),
                    sortStore = object : SidebarSortStore {
                        override val mode: Flow<SidebarSortMode> get() = prefs.sidebarSort
                        override suspend fun set(mode: SidebarSortMode) {
                            prefs.setSidebarSort(mode)
                        }
                    },
                    account = prefs.account.map { (name, scope) ->
                        name?.trim()?.takeIf { it.isNotEmpty() }?.let { AccountFooter(it, scope?.trim()?.ifEmpty { null }) }
                    },
                )
            }
        }

        /** The production port: the graph's members resolve on `Dispatchers.Default` (docs/07 §8). */
        fun graphPort(graph: AppGraph): SessionsPort = GraphSessionsPort(
            repository = { graph.sessionsRepository },
            actions = { graph.sessionActions },
            api = { graph.sessionApi },
        )
    }
}
