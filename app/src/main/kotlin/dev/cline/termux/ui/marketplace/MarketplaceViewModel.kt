package dev.cline.termux.ui.marketplace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.DashboardTimeouts
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceRepository
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.data.workspace.TERMUX_HOME
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the marketplace screen needs from the app (customize-and-rest.md §3.8, §0.3): the
 * catalog and install routes of [MarketplaceRepository], the settings-helper lease (SP §0.3:
 * acquire on enter, release on leave), the kernel gate, the workspace the fallback
 * `settings.list` reads, and a clock. [of] adapts the [AppGraph]; tests pass a fake.
 */
interface MarketplaceBackend {
    val helperState: StateFlow<HelperState>
    val kernelGate: StateFlow<KernelGate>

    /** Where install and uninstall run, so a result still lands (and `inventoryChanged` fires) after the screen closes. */
    val actionScope: CoroutineScope

    suspend fun acquireHelper(owner: String)

    /** Called from `onCleared()`, when the ViewModel's scope is already gone. */
    fun releaseHelper(owner: String)

    suspend fun retryHelper()

    /** The helper card's "Start kernel" (KS §0.4: `ensureConnected(startIfDown = true, userInitiated = true)`). */
    suspend fun startKernel()

    suspend fun workspaceRoot(): String

    suspend fun catalog(force: Boolean): MarketplaceRepository.CatalogLoad

    suspend fun installedKeys(catalog: MarketplaceCatalog, workspaceRoot: String): Outcome<MarketplaceRepository.Installed>

    suspend fun install(entry: MarketplaceEntry, workspaceRoot: String): MarketplaceRepository.ActionResult

    suspend fun uninstall(entry: MarketplaceEntry, workspaceRoot: String): MarketplaceRepository.ActionResult

    fun nowMs(): Long

    companion object {
        fun of(graph: AppGraph): MarketplaceBackend = object : MarketplaceBackend {
            private val repository get() = graph.marketplace
            override val helperState: StateFlow<HelperState> get() = graph.settingsHelper.state
            override val kernelGate: StateFlow<KernelGate> get() = graph.kernel.gate
            override val actionScope: CoroutineScope get() = graph.appScope
            override suspend fun acquireHelper(owner: String) = graph.settingsHelper.acquire(owner)
            override fun releaseHelper(owner: String) {
                graph.appScope.launch { graph.settingsHelper.release(owner) }
            }
            override suspend fun retryHelper() = graph.settingsHelper.retry()
            override suspend fun startKernel() {
                graph.kernel.ensureConnected(startIfDown = true, userInitiated = true)
            }
            override suspend fun workspaceRoot(): String = graph.prefs.lastWorkspace() ?: TERMUX_HOME
            override suspend fun catalog(force: Boolean) = repository.catalog(force)
            override suspend fun installedKeys(catalog: MarketplaceCatalog, workspaceRoot: String) = repository.installedKeys(catalog, workspaceRoot)
            override suspend fun install(entry: MarketplaceEntry, workspaceRoot: String) = repository.install(entry, workspaceRoot)
            override suspend fun uninstall(entry: MarketplaceEntry, workspaceRoot: String) = repository.uninstall(entry, workspaceRoot)
            override fun nowMs(): Long = graph.clock.millis()
        }
    }
}

/**
 * C3 / C4 (customize-and-rest.md §3): the catalog, filters, installed keys and the per-entry
 * install/uninstall actions, as one [state]. The list route owns it; on compact and medium the
 * pushed detail shares the list entry's instance, on expanded the detail pane reads
 * [MarketplaceUiState.selectedKey]. Filters and the selection live in [savedState].
 */
class MarketplaceViewModel(
    private val backend: MarketplaceBackend,
    private val savedState: SavedStateHandle,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** One lease holder per instance (two marketplace entries on the back stack hold two). */
    private val owner = "marketplace-" + UUID.randomUUID()

    private val core = MutableStateFlow(
        MarketplaceUiState(
            query = savedState[KEY_QUERY] ?: "",
            type = MarketplaceType.parse(savedState[KEY_TYPE]),
            tag = savedState[KEY_TAG],
            selectedKey = savedState[KEY_SELECTED],
        ),
    )

    /**
     * Installed state applied over an approximate (`settings.list`) read: the result of each
     * install/uninstall this screen ran, so the fallback's name matching cannot hide a check
     * the route just confirmed. Cleared by an exact (helper) read.
     */
    private val overrides = HashMap<String, Boolean>()

    private var loadJob: Job? = null
    private var installedJob: Job? = null
    private val actionJobs = HashMap<String, Job>()

    val state: StateFlow<MarketplaceUiState> =
        combine(core, backend.helperState, backend.kernelGate) { core, helper, kernel ->
            MarketplaceUiState.derive(core.copy(helper = helper, kernel = kernel))
        }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketplaceUiState.derive(core.value))

    init {
        viewModelScope.launch { backend.acquireHelper(owner) }
        load(force = false)
        // An approximate or failed installed read is redone once the helper becomes Ready.
        viewModelScope.launch {
            backend.helperState.map { it is HelperState.Ready }.distinctUntilChanged().drop(1).filter { it }.collect {
                val current = core.value
                if (current.catalog != null && (current.installedApproximate || !current.installedReady)) refreshInstalled()
            }
        }
    }

    // ---- catalog -----------------------------------------------------------------------------

    /** First load, "Retry" ([force] = true) and pull to refresh (CR §3.6: bypasses the in-memory cache). */
    fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            core.update {
                if (it.catalog == null || it.catalogState is MarketplaceCatalogState.Error) it.copy(catalogState = MarketplaceCatalogState.Loading, refreshing = false)
                else it.copy(refreshing = true)
            }
            when (val result = backend.catalog(force)) {
                is MarketplaceRepository.CatalogLoad.Loaded -> {
                    core.update {
                        it.copy(
                            catalogState = MarketplaceCatalogState.Loaded(result.stale),
                            catalog = result.catalog,
                            retained = it.retained + result.catalog.entries.associateBy { entry -> entry.key },
                            refreshing = false,
                        )
                    }
                    refreshInstalled()
                }
                is MarketplaceRepository.CatalogLoad.Error -> core.update {
                    // Desktop: a failed catalog ends the installed check too.
                    it.copy(catalogState = MarketplaceCatalogState.Error(result.httpStatus), refreshing = false, installedReady = true)
                }
            }
        }
    }

    /** Pull to refresh: the catalog (network first) and then the installed keys. */
    fun refresh() = load(force = true)

    /** Installed keys after the catalog loads, after every install/uninstall and when the helper becomes Ready (CR §3.8). */
    private fun refreshInstalled() {
        val catalog = core.value.catalog ?: return
        installedJob?.cancel()
        installedJob = viewModelScope.launch {
            val read = readInstalled(catalog)
            core.update { current ->
                when (read) {
                    null -> current.copy(installedReady = true)
                    else -> current.copy(installedKeys = read.keys, installedReady = true, installedApproximate = read.approximate)
                }
            }
        }
    }

    /** One installed-keys read with the overrides applied; null when it failed (desktop keeps the current keys). */
    private suspend fun readInstalled(catalog: MarketplaceCatalog): MarketplaceRepository.Installed? {
        awaitHelperSettled()
        val result = backend.installedKeys(catalog, backend.workspaceRoot())
        val installed = (result as? Outcome.Ok)?.value ?: return null
        return synchronized(overrides) {
            if (!installed.approximate) {
                overrides.clear()
                installed
            } else {
                val keys = installed.keys.toMutableSet()
                overrides.forEach { (key, on) -> if (on) keys += key else keys -= key }
                installed.copy(keys = keys)
            }
        }
    }

    /** The helper route needs the helper; while it starts (which can also boot the Hub), wait for it to settle. */
    private suspend fun awaitHelperSettled() {
        if (backend.helperState.value is HelperState.Starting) backend.helperState.first { it !is HelperState.Starting }
    }

    // ---- filters and selection ---------------------------------------------------------------

    /**
     * The search text as snapshot state. [state] is derived off the main thread, so a field bound
     * to `state.query` would get each keystroke back a frame late and drop or reorder characters.
     */
    var queryText by mutableStateOf(savedState[KEY_QUERY] ?: "")
        private set

    fun setQuery(query: String) {
        queryText = query
        savedState[KEY_QUERY] = query
        core.update { it.copy(query = query) }
    }

    /** "All". */
    fun showAllTypes() = setType(null)

    /** A type chip: selects it, or returns to All when it is already selected (desktop toggle). The tag is kept. */
    fun toggleType(type: MarketplaceType) = setType(if (core.value.type == type) null else type)

    private fun setType(type: MarketplaceType?) {
        savedState[KEY_TYPE] = type?.wire
        core.update { it.copy(type = type) }
    }

    /** The category sheet's choice (null = "All categories"), also the detail's tag chips. */
    fun setTag(tag: String?) {
        savedState[KEY_TAG] = tag
        core.update { it.copy(tag = tag) }
    }

    /** The expanded detail pane's selection; null shows the "Select an entry" placeholder. */
    fun select(key: String?) {
        savedState[KEY_SELECTED] = key
        core.update { it.copy(selectedKey = key) }
    }

    // ---- install / uninstall -----------------------------------------------------------------

    fun install(key: String) = act(key, install = true)

    /** After the confirm dialog (CR §3.6). */
    fun uninstall(key: String) = act(key, install = false)

    private fun act(key: String, install: Boolean) {
        val current = core.value.copy(kernel = backend.kernelGate.value)
        if (current.kernelBlocksActions || !current.installedReady) return
        val entry = current.catalog?.find(key) ?: return
        if (current.action(key).busy) return
        val started = backend.nowMs()
        setAction(key, if (install) EntryAction.Installing(started) else EntryAction.Uninstalling(started))
        actionJobs[key] = backend.actionScope.launch {
            awaitHelperSettled()
            val workspace = backend.workspaceRoot()
            val result = if (install) backend.install(entry, workspace) else backend.uninstall(entry, workspace)
            when (result) {
                is MarketplaceRepository.ActionResult.Done -> finishDone(entry, install, result.done)
                is MarketplaceRepository.ActionResult.Failed -> setAction(key, EntryAction.Failed(result.failure, install))
                MarketplaceRepository.ActionResult.Unknown -> reconcile(entry, install)
            }
        }
    }

    private fun finishDone(entry: MarketplaceEntry, install: Boolean, done: MarketplaceDone) {
        synchronized(overrides) { overrides[entry.key] = install }
        core.update {
            it.copy(
                actions = it.actions + (entry.key to EntryAction.Done(done, install)),
                installedKeys = if (install) it.installedKeys + entry.key else it.installedKeys - entry.key,
            )
        }
        refreshInstalled()
    }

    /**
     * CR §3.9: the helper socket dropped or timed out (or the app came back after the timeout):
     * re-read the installed keys; if the key flipped, show the normal success message,
     * otherwise "Check the result: the install may have finished in Termux."
     */
    private suspend fun reconcile(entry: MarketplaceEntry, install: Boolean) {
        val catalog = core.value.catalog
        val read = catalog?.let { readInstalled(it) }
        val flipped = read != null && (entry.key in read.keys) == install
        if (flipped) {
            finishDone(entry, install, MarketplaceDone.of(entry, install))
        } else {
            setAction(entry.key, EntryAction.Interrupted)
            if (read != null) core.update { it.copy(installedKeys = read.keys, installedApproximate = read.approximate) }
        }
    }

    /**
     * The screen resumed (CR §3.9 "App backgrounded during an install"): an action older than
     * its 300 s timeout is resolved by re-reading the installed keys instead of waiting on a
     * socket the system froze.
     */
    fun onResume() {
        val now = backend.nowMs()
        val catalog = core.value.catalog ?: return
        core.value.actions.forEach { (key, action) ->
            val started = when (action) {
                is EntryAction.Installing -> action.startedAtMs
                is EntryAction.Uninstalling -> action.startedAtMs
                else -> return@forEach
            }
            if (now - started < INSTALL_TIMEOUT_MS) return@forEach
            val entry = catalog.find(key) ?: return@forEach
            actionJobs.remove(key)?.cancel()
            actionJobs[key] = backend.actionScope.launch { reconcile(entry, action is EntryAction.Installing) }
        }
    }

    private fun setAction(key: String, action: EntryAction) {
        core.update { it.copy(actions = it.actions + (key to action)) }
    }

    // ---- helper and kernel -------------------------------------------------------------------

    /** The helper card's "Retry". */
    fun retryHelper() {
        viewModelScope.launch { backend.retryHelper() }
    }

    /** The kernel-stopped helper card's "Start kernel": start the Hub, then try the helper again. */
    fun startKernel() {
        viewModelScope.launch {
            backend.startKernel()
            backend.retryHelper()
        }
    }

    override fun onCleared() {
        backend.releaseHelper(owner)
    }

    companion object {
        /** Desktop `INSTALL_TIMEOUT_MS` (the helper's client timer for install/uninstall). */
        const val INSTALL_TIMEOUT_MS: Long = DashboardTimeouts.MARKETPLACE_MS

        /** `SavedStateHandle` keys; `type` and `tag` are also the `marketplace?type=&tag=` route arguments. */
        const val KEY_QUERY = "query"
        const val KEY_TYPE = "type"
        const val KEY_TAG = "tag"
        const val KEY_SELECTED = "selectedKey"

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { MarketplaceViewModel(MarketplaceBackend.of(graph), createSavedStateHandle()) }
        }
    }
}
