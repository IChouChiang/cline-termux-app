package dev.cline.termux.ui.customize

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.CustomizeCounts
import dev.cline.termux.data.customize.CustomizeRepository
import dev.cline.termux.data.customize.McpRepository
import dev.cline.termux.data.customize.McpServersResponse
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.ScopeRules
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.data.customize.customizeCounts
import dev.cline.termux.data.customize.toProblem
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.WorkspaceValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The line under a card after an uninstall (CR §1.4 "Item result"); cleared on the next action for that card. */
@Immutable
sealed interface ActionMessage {
    /** [message] is the helper's own line; null = "Uninstalled <name>.". */
    data class Uninstalled(val name: String, val message: String?) : ActionMessage

    data class Failed(val failure: RepoFailure) : ActionMessage
}

/** The MCP delete dialog opened from a card (CR §2.5 "Delete dialog"). */
@Immutable
data class McpDeleteState(val name: String, val deleting: Boolean = false, val error: RepoFailure? = null)

/** The workspace scope sheet's transient state (SP S9 in scope mode). */
@Immutable
data class WorkspacePickerState(
    val validating: String? = null,
    /** The path that failed validation ("Couldn't open …"). */
    val invalidPath: String? = null,
    val blocked: TermuxProblem? = null,
)

/** CR §1.8 `CustomizeUiState`, plus the page-level kernel and helper state of §0.4 / §1.4. */
@Immutable
data class CustomizeUiState(
    val tab: CustomizeTab = CustomizeTab.Tools,
    /** Null until the stored / default workspace has been read. */
    val workspaceRoot: String? = null,
    val gate: KernelGate = KernelGate.Ready,
    /** No lists yet and a load is running ("First load"). */
    val loading: Boolean = true,
    /** A load is running while cached cards show ("Loading with cache", pull to refresh). */
    val refreshing: Boolean = false,
    /** Lists have been shown at least once (cache or network). */
    val hasData: Boolean = false,
    /** settings.list data (live or cached) is present; with the Hub down and none, KernelStatePanel shows (CR §0.4). */
    val hubLoaded: Boolean = false,
    val loadError: RepoFailure? = null,
    val warnings: List<CustomizeRepository.SourceWarning> = emptyList(),
    /** Null until the first load finished (the tab row shows no counts). */
    val counts: CustomizeCounts? = null,
    val tools: ToolsState = ToolsState(),
    val plugins: List<PluginCard> = emptyList(),
    val commands: List<CommandCard> = emptyList(),
    val rules: List<RuleCard> = emptyList(),
    val mcp: McpTabState = McpTabState(),
    val mcpError: RepoFailure? = null,
    val hooks: List<HookCard> = emptyList(),
    val toolsBlocked: TermuxProblem? = null,
    val hooksBlocked: TermuxProblem? = null,
    val helper: HelperState = HelperState.Idle,
    val queries: Map<CustomizeTab, String> = emptyMap(),
    /** Item keys toggling or uninstalling. */
    val pending: Set<String> = emptySet(),
    /** Item keys whose pending action is an uninstall ("Uninstalling..."). */
    val uninstalling: Set<String> = emptySet(),
    val messages: Map<String, ActionMessage> = emptyMap(),
    /** Plugin cards and setup disclosures the user expanded. */
    val expanded: Set<String> = emptySet(),
    val sessionRunning: Boolean = false,
    val mcpDelete: McpDeleteState? = null,
    val workspacePicker: WorkspacePickerState = WorkspacePickerState(),
    val workspaceCandidates: List<String> = emptyList(),
) {
    /** Mutations (switches, uninstall) need the Hub (CR §0.4). */
    val mutationsAllowed: Boolean get() = gate is KernelGate.Ready || gate is KernelGate.DegradedNoCommands

    /** The `Busy(Reconnecting | Draining)` row: switches read "Unavailable while reconnecting". */
    val reconnecting: Boolean
        get() = gate is KernelGate.Busy && (gate.kind == KernelGate.BusyKind.Reconnecting || gate.kind == KernelGate.BusyKind.Draining)

    fun query(tab: CustomizeTab): String = queries[tab].orEmpty()
}

/** One-shot effects the screen turns into snackbars or navigation. */
sealed interface CustomizeEvent {
    /** A toggle failed: snackbar with the reply message (CR §1.5 "Errors"). */
    data class ToggleFailed(val failure: RepoFailure) : CustomizeEvent

    /** The scoped folder vanished; the scope reverted to Termux's home (CR §1.9). */
    data object WorkspaceMissing : CustomizeEvent

    /** A stale "Uninstalling..." was cleared on resume (CR §1.9). */
    data object UninstallMayHaveFinished : CustomizeEvent

    /** The scope sheet chose a folder: close it. */
    data object WorkspacePicked : CustomizeEvent
}

/**
 * Customize (CR C1). Loads the lists of the scoped workspace through [CustomizeRepository]
 * (60 s cache, `settings.changed`, reconnects), the MCP details through [McpRepository], and
 * the marketplace catalog only to match badges. Every mutation waits for its reply (no
 * optimistic flip, CR §1.6). The settings helper is leased only while the MCP tab has been
 * settled for 500 ms ([onSettledTab]); it is released when the tab changes and on [onCleared].
 */
class CustomizeViewModel(
    private val repo: CustomizeRepository,
    private val mcp: McpRepository,
    private val helper: HelperLease,
    private val gate: StateFlow<KernelGate>,
    private val workspace: CustomizeWorkspace,
    private val catalog: suspend () -> MarketplaceCatalog?,
    sessionRunning: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    routeTab: String?,
    private val clockMs: () -> Long,
    /** Outlives the ViewModel so the lease is released after [onCleared]. */
    private val leaseScope: CoroutineScope,
) : ViewModel() {
    private val owner = "customize@" + Integer.toHexString(System.identityHashCode(this))
    private val _state = MutableStateFlow(
        CustomizeUiState(
            tab = savedState.get<String>(KEY_TAB)?.let(CustomizeTab::parse) ?: CustomizeTab.parse(routeTab) ?: CustomizeTab.Tools,
            queries = CustomizeTab.entries.mapNotNull { tab -> savedState.get<String>(queryKey(tab))?.let { tab to it } }.toMap(),
            expanded = savedState.get<ArrayList<String>>(KEY_EXPANDED)?.toSet().orEmpty(),
            gate = gate.value,
            helper = helper.state.value,
        ),
    )
    val state: StateFlow<CustomizeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CustomizeEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CustomizeEvent> = _events.asSharedFlow()

    private var lists: CustomizeRepository.Lists? = null
    private var mcpResponse: McpServersResponse? = null
    private var marketplace: MarketplaceCatalog? = null
    private var loadJob: Job? = null
    private var leased = false

    /** Uninstall start times, for the 120 s stale rule on resume. */
    private val uninstallStarted = HashMap<String, Long>()

    /** Bumped per key so a late reply of a cleared action does not overwrite newer state. */
    private val generations = HashMap<String, Int>()

    init {
        viewModelScope.launch {
            val root = workspace.initialRoot()
            _state.update { it.copy(workspaceRoot = root) }
            load(force = false)
        }
        viewModelScope.launch {
            var previous = gate.value
            gate.collect { next ->
                _state.update { it.copy(gate = next) }
                // CR §1.9: back to Ready after an outage, re-run the reads if nothing (good) is shown.
                if (next is KernelGate.Ready && previous !is KernelGate.Ready) {
                    val s = _state.value
                    if (!s.hasData || s.loadError != null) load(force = true)
                }
                previous = next
            }
        }
        viewModelScope.launch {
            var previous = helper.state.value
            helper.state.collect { next ->
                _state.update { it.copy(helper = next) }
                // The helper route carries metadata the file route lacks: re-list when it comes up.
                if (next is HelperState.Ready && previous !is HelperState.Ready && leased) reloadMcp()
                previous = next
            }
        }
        viewModelScope.launch { repo.invalidations.collect { load(force = true) } }
        viewModelScope.launch { sessionRunning.distinctUntilChanged().collect { running -> _state.update { it.copy(sessionRunning = running) } } }
        viewModelScope.launch { workspace.candidates.collect { paths -> _state.update { it.copy(workspaceCandidates = paths) } } }
        viewModelScope.launch {
            marketplace = runCatching { catalog() }.getOrNull()
            if (marketplace != null) reproject()
        }
    }

    // ---- tabs, queries, disclosure -----------------------------------------------------------------

    fun selectTab(tab: CustomizeTab) {
        if (_state.value.tab == tab) return
        savedState[KEY_TAB] = tab.wire
        _state.update { it.copy(tab = tab) }
    }

    /** The pager has rested on [tab] for 500 ms (CR §1.6): lease the helper on MCP only. */
    fun onSettledTab(tab: CustomizeTab) {
        if (tab == CustomizeTab.Mcp && !leased) {
            leased = true
            viewModelScope.launch { helper.acquire(owner) }
        } else if (tab != CustomizeTab.Mcp && leased) {
            leased = false
            leaseScope.launch { helper.release(owner) }
        }
    }

    fun setQuery(tab: CustomizeTab, query: String) {
        savedState[queryKey(tab)] = query
        _state.update { it.copy(queries = it.queries + (tab to query)) }
    }

    fun toggleExpanded(key: String) {
        val next = _state.value.expanded.let { if (key in it) it - key else it + key }
        savedState[KEY_EXPANDED] = ArrayList(next)
        _state.update { it.copy(expanded = next) }
    }

    fun retryHelper() {
        viewModelScope.launch { helper.retry() }
    }

    // ---- loading -----------------------------------------------------------------------------------

    /** Pull to refresh and the app-bar action: bypass the 60 s cache (CR §1.6). */
    fun refresh() = load(force = true)

    /** ON_RESUME: clear stale uninstalls (CR §1.9), then reload if the cache expired. */
    fun onResume() {
        val now = clockMs()
        val stale = uninstallStarted.filterValues { now - it >= CustomizeRepository.UNINSTALL_STALE_MS }.keys
        if (stale.isNotEmpty()) {
            stale.forEach { key ->
                uninstallStarted.remove(key)
                bump(key)
            }
            _state.update { it.copy(pending = it.pending - stale, uninstalling = it.uninstalling - stale) }
            _events.tryEmit(CustomizeEvent.UninstallMayHaveFinished)
            load(force = true)
        } else {
            load(force = false)
        }
    }

    private fun load(force: Boolean) {
        val root = _state.value.workspaceRoot ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = repo.cached(root)
            if (cached != null && cached !== lists) {
                lists = cached
                reproject()
            }
            if (!force && cached != null && repo.isFresh(root) && mcpResponse != null) {
                _state.update { it.copy(loading = false, refreshing = false) }
                return@launch
            }
            _state.update { it.copy(loading = !it.hasData, refreshing = it.hasData) }
            val mcpLoad = async { mcp.list() }
            val loaded = repo.load(root, force = force)
            val mcpOut = mcpLoad.await()
            lists = loaded
            when (mcpOut) {
                is Outcome.Ok -> {
                    mcpResponse = mcpOut.value
                    _state.update { it.copy(mcpError = null) }
                }
                is Outcome.Failed -> _state.update { it.copy(mcpError = mcpOut.failure) }
            }
            reproject()
            _state.update { it.copy(loading = false, refreshing = false) }
            if (loaded.workspaceMissing && ScopeRules.normalizeRoot(root) != TERMUX_HOME) {
                _events.tryEmit(CustomizeEvent.WorkspaceMissing)
                workspace.store(TERMUX_HOME)
                _state.update { it.copy(workspaceRoot = TERMUX_HOME) }
                load(force = false)
            }
        }
    }

    private suspend fun reloadMcp() {
        when (val out = mcp.list()) {
            is Outcome.Ok -> {
                mcpResponse = out.value
                _state.update { it.copy(mcpError = null) }
            }
            is Outcome.Failed -> _state.update { it.copy(mcpError = out.failure) }
        }
        reproject()
    }

    /** Rebuilds every card from the current lists, MCP details and catalog. */
    private fun reproject() {
        val current = lists
        val root = ScopeRules.normalizeRoot(_state.value.workspaceRoot)
        val snapshot = current?.snapshot
        val catalog = marketplace
        val canUninstall: (SettingsItem) -> Boolean = repo::canUninstall
        val mcpState = CustomizeRules.mcp(mcpResponse, catalog)
        _state.update { s ->
            s.copy(
                hasData = s.hasData || current != null,
                hubLoaded = s.hubLoaded || snapshot != null,
                loadError = current?.loadError,
                warnings = current?.warnings.orEmpty(),
                counts = current?.let { customizeCounts(it.snapshot, it.builtinTools, it.hooks, mcpResponse?.servers?.size) },
                tools = ToolsState(CustomizeRules.builtinTools(current?.builtinTools.orEmpty()), CustomizeRules.pluginTools(snapshot)),
                plugins = CustomizeRules.plugins(snapshot, root, catalog, canUninstall),
                commands = CustomizeRules.commands(snapshot, root, catalog, canUninstall),
                rules = CustomizeRules.rules(snapshot, root),
                hooks = CustomizeRules.hooks(current?.hooks.orEmpty(), root),
                mcp = mcpState,
                toolsBlocked = current?.toolsBlocked,
                hooksBlocked = current?.hooksBlocked,
            )
        }
    }

    // ---- toggles (CR §1.6, §1.8) -------------------------------------------------------------------

    /** A tool switch; [card] is a built-in or a plugin tool. */
    fun toggleTool(card: ToolCard, enabled: Boolean) {
        val root = _state.value.workspaceRoot ?: return
        runMutation(setOf(card.key)) { repo.toggleTool(root, card.toggleNames, card.builtinId, enabled) }
    }

    /** "Enable all" / "Disable all" over the filtered tools of one section, one switch at a time. */
    fun setAllTools(shown: List<ToolCard>, enable: Boolean) {
        val root = _state.value.workspaceRoot ?: return
        val targets = CustomizeRules.bulkTargets(shown, enable)
        if (targets.isEmpty()) return
        runMutation(targets.map { it.key }.toSet()) { repo.toggleTools(root, targets.map { it.toggleNames to it.builtinId }, enable) }
    }

    /** A skill or plugin switch (workflows, rules and non-toggleable Agent Plugins never call this). */
    fun toggleItem(key: String, item: SettingsItem, enabled: Boolean) {
        val root = _state.value.workspaceRoot ?: return
        runMutation(setOf(key)) { repo.toggleItem(root, item, enabled) }
    }

    private fun runMutation(keys: Set<String>, block: suspend () -> Outcome<CustomizeRepository.Lists>) {
        val s = _state.value
        if (!s.mutationsAllowed || keys.any { it in s.pending }) return
        _state.update { it.copy(pending = it.pending + keys, messages = it.messages - keys) }
        viewModelScope.launch {
            try {
                when (val out = block()) {
                    is Outcome.Ok -> {
                        lists = out.value
                        reproject()
                    }
                    is Outcome.Failed -> _events.tryEmit(CustomizeEvent.ToggleFailed(out.failure))
                }
            } finally {
                _state.update { it.copy(pending = it.pending - keys) }
            }
        }
    }

    /** An MCP server switch: `settings.toggle {type:"mcp"}`, then the details are re-read (CR §1.8). */
    fun toggleMcp(card: McpServerCard, enabled: Boolean) {
        val s = _state.value
        if (!s.mutationsAllowed || card.key in s.pending) return
        _state.update { it.copy(pending = it.pending + card.key) }
        viewModelScope.launch {
            try {
                // Success re-reads everything through `inventoryChanged` → `invalidations`.
                val out = mcp.setEnabled(card.name, enabled)
                if (out is Outcome.Failed) _events.tryEmit(CustomizeEvent.ToggleFailed(out.failure))
            } finally {
                _state.update { it.copy(pending = it.pending - card.key) }
            }
        }
    }

    // ---- uninstall ---------------------------------------------------------------------------------

    /** After the confirm dialog (CR §1.6): helper route when Ready, CLI otherwise. */
    fun uninstall(key: String, item: SettingsItem) {
        val root = _state.value.workspaceRoot ?: return
        val s = _state.value
        if (!s.mutationsAllowed || key in s.pending) return
        val generation = bump(key)
        uninstallStarted[key] = clockMs()
        _state.update { it.copy(pending = it.pending + key, uninstalling = it.uninstalling + key, messages = it.messages - key) }
        viewModelScope.launch {
            val result = repo.uninstall(root, item)
            if (generations[key] != generation) return@launch
            uninstallStarted.remove(key)
            val message = when (result) {
                is CustomizeRepository.UninstallResult.Done -> ActionMessage.Uninstalled(item.name, result.message)
                is CustomizeRepository.UninstallResult.Failed -> ActionMessage.Failed(result.failure)
                CustomizeRepository.UninstallResult.NotSupported -> null
            }
            _state.update {
                it.copy(
                    pending = it.pending - key,
                    uninstalling = it.uninstalling - key,
                    messages = if (message != null) it.messages + (key to message) else it.messages,
                )
            }
        }
    }

    private fun bump(key: String): Int = (generations[key] ?: 0).plus(1).also { generations[key] = it }

    // ---- MCP delete (the card's Delete button, CR §2.5) ----------------------------------------------

    fun requestMcpDelete(name: String) = _state.update { it.copy(mcpDelete = McpDeleteState(name)) }

    fun dismissMcpDelete() = _state.update { s -> if (s.mcpDelete?.deleting == true) s else s.copy(mcpDelete = null) }

    fun confirmMcpDelete() {
        val target = _state.value.mcpDelete ?: return
        if (target.deleting || !_state.value.mutationsAllowed) return
        _state.update { it.copy(mcpDelete = target.copy(deleting = true, error = null)) }
        viewModelScope.launch {
            when (val result = mcp.delete(target.name)) {
                is McpRepository.WriteResult.Done -> {
                    result.servers?.let { mcpResponse = it } ?: reloadMcp()
                    reproject()
                    _state.update { it.copy(mcpDelete = null) }
                }
                is McpRepository.WriteResult.Failed -> _state.update { it.copy(mcpDelete = target.copy(deleting = false, error = result.failure)) }
                McpRepository.WriteResult.Unconfirmed, McpRepository.WriteResult.BlockedInFallback ->
                    _state.update { it.copy(mcpDelete = target.copy(deleting = false, error = RepoFailure.HelperLost)) }
            }
        }
    }

    // ---- workspace scope (CR §1.6) -----------------------------------------------------------------

    /** Validates [path] in Termux; a valid folder becomes the scope and every source re-runs. */
    fun pickWorkspace(path: String) {
        if (_state.value.workspacePicker.validating != null) return
        _state.update { it.copy(workspacePicker = WorkspacePickerState(validating = path)) }
        viewModelScope.launch {
            when (val result = workspace.validate(path)) {
                is WorkspaceValidation.Valid -> {
                    workspace.store(result.path)
                    _state.update { it.copy(workspaceRoot = result.path, workspacePicker = WorkspacePickerState()) }
                    _events.tryEmit(CustomizeEvent.WorkspacePicked)
                    lists = null
                    mcpResponse = null
                    _state.update { it.copy(hasData = false, hubLoaded = false) }
                    load(force = false)
                }
                is WorkspaceValidation.Invalid -> _state.update { it.copy(workspacePicker = WorkspacePickerState(invalidPath = result.path)) }
                is WorkspaceValidation.Blocked -> _state.update { it.copy(workspacePicker = WorkspacePickerState(blocked = result.error.toProblem())) }
            }
        }
    }

    fun clearWorkspaceError() = _state.update { it.copy(workspacePicker = WorkspacePickerState()) }

    override fun onCleared() {
        if (leased) {
            leased = false
            leaseScope.launch { withContext(NonCancellable) { helper.release(owner) } }
        }
    }

    companion object {
        const val KEY_TAB = "customize.tab"
        const val KEY_EXPANDED = "customize.expanded"

        /** CR §1.6: the helper is leased only after the MCP page has been settled this long. */
        const val HELPER_SETTLE_MS = 500L

        fun queryKey(tab: CustomizeTab) = "customize.query.${tab.wire}"
    }
}

