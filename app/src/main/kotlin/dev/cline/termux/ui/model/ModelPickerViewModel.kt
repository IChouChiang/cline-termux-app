package dev.cline.termux.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.isDraining
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.model.buildModelPickerData
import dev.cline.termux.data.model.resolveHomeSelection
import dev.cline.termux.data.model.resolveProviderPickModel
import dev.cline.termux.data.model.thinkingConfig
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ModelsLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SP S8 model picker + reasoning effort (desktop `ModelSelector`, `handleProviderSelect`,
 * `refreshActiveProviderModels`, `EFFORT_LEVELS`). Scoped to the hosting screen; the host
 * calls [setHost] whenever its session changes and collects [effects].
 *
 * Rules kept from the spec: remember only explicit picks (ModelSelectionStore); liveness is
 * read before sending (`session.update_connection` only on a live session, otherwise the pick
 * becomes a pending connection for the resume); refresh on open only when the helper is
 * already Ready, never starting it; refresh failures are silent; "Refresh models" leases the
 * helper on demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerViewModel(
    private val catalog: PickerCatalogPort,
    private val selection: ModelSelectionStore,
    private val sessions: PickerSessionPort,
    /** Where the picker state is built (docs/07 §8: catalog filtering runs on `Dispatchers.Default`). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private sealed interface HostRequest {
        data object Home : HostRequest
        data class Session(val sessionId: String) : HostRequest
    }

    /** The host's current provider/model plus its flags, overlay applied. */
    private data class Current(
        val host: PickerHost,
        val providerId: String?,
        val modelId: String?,
        val busy: Boolean,
    )

    private data class Transient(
        val query: String = "",
        val providerQuery: String = "",
        val refreshing: Boolean = false,
        val pendingModelId: String? = null,
        val pendingProviderId: String? = null,
    )

    private val hostRequest = MutableStateFlow<HostRequest>(HostRequest.Home)
    private val transient = MutableStateFlow(Transient())

    /** Picks made on a session that its record does not show yet (pending or just applied). */
    private val overlays = MutableStateFlow<Map<String, Pair<String, String?>>>(emptyMap())

    private val _effects = Channel<ModelPickerEffect>(Channel.BUFFERED)
    val effects: Flow<ModelPickerEffect> = _effects.receiveAsFlow()

    private var refreshJob: Job? = null
    private var leaseJob: Job? = null

    private val current: Flow<Current> = hostRequest.flatMapLatest { request ->
        when (request) {
            HostRequest.Home -> combine(selection.selection, catalog.state) { stored, state -> homeCurrent(stored, state) }
            is HostRequest.Session -> combine(sessions.observe(request.sessionId), overlays) { snapshot, map ->
                val overlay = map[request.sessionId]
                if (overlay != null && overlay.first == snapshot.providerId && overlay.second == snapshot.modelId) {
                    overlays.update { it - request.sessionId }
                }
                Current(
                    host = PickerHost.Session(request.sessionId, snapshot.live),
                    providerId = overlay?.first ?: snapshot.providerId,
                    modelId = if (overlay != null) overlay.second else snapshot.modelId,
                    busy = snapshot.busy,
                )
            }
        }
    }.distinctUntilChanged()

    /** The provider's `buildModelPickerData`, reused across keystrokes; touched only by [uiState]'s pipeline on [compute]. */
    private val pickerData = ModelPickerDataCache()

    val uiState: StateFlow<ModelPickerUiState> =
        combine(current, catalog.state, catalog.helperState, transient) { cur, state, helper, t -> buildState(cur, state, helper, t, pickerData) }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelPickerUiState())

    /** The screen's host: Home, or the open session (its liveness is read from the store). */
    fun setHost(host: PickerHost) {
        hostRequest.value = when (host) {
            PickerHost.Home -> HostRequest.Home
            is PickerHost.Session -> HostRequest.Session(host.sessionId)
        }
    }

    fun setHomeHost() = setHost(PickerHost.Home)

    fun setSessionHost(sessionId: String) {
        hostRequest.value = HostRequest.Session(sessionId)
    }

    /** The sheet opened: fresh search, and refresh the active provider when the helper already runs (S8.2). */
    fun onOpened() {
        transient.update { it.copy(query = "", providerQuery = "") }
        viewModelScope.launch {
            val providerId = current.first().providerId ?: return@launch
            if (catalog.helperState.value is HelperState.Ready) refresh(providerId)
        }
    }

    fun setQuery(query: String) = transient.update { it.copy(query = query) }

    fun setProviderQuery(query: String) = transient.update { it.copy(providerQuery = query) }

    /** "Refresh models" (Android): leases the helper on demand, then refreshes the active provider. */
    fun refreshModels() {
        if (leaseJob?.isActive == true) return
        leaseJob = viewModelScope.launch {
            transient.update { it.copy(refreshing = true) }
            try {
                val ready = catalog.acquireHelper(LEASE_OWNER)
                val providerId = current.first().providerId
                if (ready && providerId != null) catalog.refreshModels(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Refresh failures are silent (desktop); the cached or bundled list stays.
            } finally {
                transient.update { it.copy(refreshing = false) }
                withContext(NonCancellable) { runCatching { catalog.releaseHelper(LEASE_OWNER) } }
            }
        }
    }

    /** A model row or the free-text row (S8.3 "Pick a model"). */
    fun pickModel(modelId: String) {
        val id = modelId.trim()
        if (id.isEmpty()) return
        viewModelScope.launch {
            val cur = current.first()
            if (cur.busy || !interactive()) return@launch
            val providerId = cur.providerId ?: return@launch
            when (val host = cur.host) {
                PickerHost.Home -> {
                    selection.recordPick(providerId, id)
                    emit(ModelPickerEffect.Result(ModelPickerResult.Selected(host, providerId, id)))
                    emit(ModelPickerEffect.Close)
                }
                is PickerHost.Session -> {
                    val update = ConnectionUpdate(providerId = providerId, modelId = id)
                    if (!sessions.isLive(host.sessionId)) {
                        pending(host.sessionId, providerId, id, update)
                        emit(ModelPickerEffect.Close)
                        return@launch
                    }
                    transient.update { it.copy(pendingModelId = id) }
                    val outcome = applyLive(host.sessionId, update)
                    transient.update { it.copy(pendingModelId = null) }
                    when (outcome) {
                        LiveOutcome.Applied -> {
                            selection.recordPick(providerId, id)
                            overlays.update { it + (host.sessionId to (providerId to id)) }
                            emit(ModelPickerEffect.Result(ModelPickerResult.Selected(host.copy(live = true), providerId, id)))
                            emit(ModelPickerEffect.Close)
                        }
                        LiveOutcome.Gone -> {
                            pending(host.sessionId, providerId, id, update)
                            emit(ModelPickerEffect.Close)
                        }
                        is LiveOutcome.Failed -> emit(ModelPickerEffect.SwitchFailed(outcome.message))
                    }
                }
            }
        }
    }

    /** A provider chip (S8.3 "Pick a provider chip"): resolve the model, update both, sheet stays open. */
    fun pickProvider(providerId: String) {
        viewModelScope.launch {
            val cur = current.first()
            if (cur.busy || !interactive()) return@launch
            if (providerId == cur.providerId) return@launch
            val state = catalog.state.value
            val stored = selection.current()
            val modelId = withContext(compute) {
                val providerModels = state.chatModels(providerId).map { it.toPickerModel() }
                val optionIds = buildModelPickerData(providerId, providerModels).options.map { it.value }.toSet()
                resolveProviderPickModel(stored.modelFor(providerId), providerModels.map { it.id }, optionIds)
            }
            when (val host = cur.host) {
                PickerHost.Home -> {
                    selection.recordProviderPick(providerId, modelId)
                    emit(ModelPickerEffect.Result(ModelPickerResult.Selected(host, providerId, modelId)))
                }
                is PickerHost.Session -> {
                    val update = ConnectionUpdate(providerId = providerId, modelId = modelId)
                    if (!sessions.isLive(host.sessionId)) {
                        pending(host.sessionId, providerId, modelId, update, provider = true)
                    } else {
                        transient.update { it.copy(pendingProviderId = providerId) }
                        val outcome = applyLive(host.sessionId, update)
                        transient.update { it.copy(pendingProviderId = null) }
                        when (outcome) {
                            LiveOutcome.Applied -> {
                                selection.recordProviderPick(providerId, modelId)
                                overlays.update { it + (host.sessionId to (providerId to modelId)) }
                                emit(ModelPickerEffect.Result(ModelPickerResult.Selected(host.copy(live = true), providerId, modelId)))
                            }
                            LiveOutcome.Gone -> pending(host.sessionId, providerId, modelId, update, provider = true)
                            is LiveOutcome.Failed -> emit(ModelPickerEffect.SwitchFailed(outcome.message))
                        }
                    }
                }
            }
            transient.update { it.copy(query = "") }
            if (catalog.helperState.value is HelperState.Ready) refresh(providerId)
        }
    }

    /**
     * S8.4 thinking level from the composer's ReasoningMenu: stored per provider; Home → sent at
     * `session.create`; live session → `update_connection {thinking, reasoningEffort}` (None sends
     * `thinking:false` only); not live → a pending connection.
     */
    fun pickReasoning(effort: ReasoningEffort) {
        viewModelScope.launch {
            val cur = current.first()
            val providerId = cur.providerId ?: return@launch
            selection.recordReasoning(providerId, effort)
            val config = thinkingConfig(effort)
            val update = ConnectionUpdate(thinking = config.thinking, reasoningEffort = config.reasoningEffort)
            when (val host = cur.host) {
                PickerHost.Home -> emit(ModelPickerEffect.Result(ModelPickerResult.ReasoningSelected(host, providerId, effort)))
                is PickerHost.Session -> {
                    if (!sessions.isLive(host.sessionId)) {
                        emit(ModelPickerEffect.Result(ModelPickerResult.PendingConnection(host.sessionId, update)))
                        return@launch
                    }
                    when (val outcome = applyLive(host.sessionId, update)) {
                        LiveOutcome.Applied -> emit(ModelPickerEffect.Result(ModelPickerResult.ReasoningSelected(host.copy(live = true), providerId, effort)))
                        LiveOutcome.Gone -> emit(ModelPickerEffect.Result(ModelPickerResult.PendingConnection(host.sessionId, update)))
                        is LiveOutcome.Failed -> emit(ModelPickerEffect.ReasoningFailed(outcome.message))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------- internals

    private sealed interface LiveOutcome {
        data object Applied : LiveOutcome
        data object Gone : LiveOutcome
        data class Failed(val message: String) : LiveOutcome
    }

    /**
     * One `session.update_connection` with the S8.3 error rules: session gone (`session_not_found`,
     * or `command_failed` "session not found: …") → not live, pending, no error; `hub_draining` →
     * keep the spinner and send again after `hub.drain_changed {draining:false}`; anything else fails.
     */
    private suspend fun applyLive(sessionId: String, update: ConnectionUpdate): LiveOutcome {
        var attempts = 0
        while (true) {
            attempts += 1
            try {
                sessions.updateConnection(sessionId, update)
                return LiveOutcome.Applied
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubCommandException) {
                when {
                    e.isSessionGone() -> {
                        sessions.markNotLive(sessionId)
                        return LiveOutcome.Gone
                    }
                    e.isDraining() && attempts < MAX_DRAIN_RETRIES -> {
                        if (!sessions.awaitNotDraining()) return LiveOutcome.Failed(e.hubMessage)
                    }
                    else -> return LiveOutcome.Failed(e.hubMessage)
                }
            } catch (e: HubConnectionClosedException) {
                return LiveOutcome.Failed(e.message.orEmpty())
            }
        }
    }

    private suspend fun pending(sessionId: String, providerId: String, modelId: String?, update: ConnectionUpdate, provider: Boolean = false) {
        if (provider) selection.recordProviderPick(providerId, modelId) else if (modelId != null) selection.recordPick(providerId, modelId)
        overlays.update { it + (sessionId to (providerId to modelId)) }
        emit(ModelPickerEffect.Result(ModelPickerResult.PendingConnection(sessionId, update)))
    }

    private fun refresh(providerId: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            transient.update { it.copy(refreshing = true) }
            try {
                when (catalog.refreshModels(providerId)) {
                    is ModelsLoad.Loaded, is ModelsLoad.Failed, ModelsLoad.Unavailable, ModelsLoad.Superseded -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent (desktop): keep the current list.
            } finally {
                transient.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun interactive(): Boolean = transient.value.let { it.pendingModelId == null && it.pendingProviderId == null }

    private fun emit(effect: ModelPickerEffect) {
        _effects.trySend(effect)
    }

    override fun onCleared() {
        refreshJob?.cancel()
        leaseJob?.cancel()
    }

    companion object {
        const val LEASE_OWNER = "model-picker"
        private const val MAX_DRAIN_RETRIES = 3

        private fun homeCurrent(stored: ModelSelection, state: CatalogState): Current {
            val choice = resolveHomeSelection(stored, state)
            return Current(PickerHost.Home, choice?.providerId, choice?.modelId, busy = false)
        }

        /** The S8.5 state from the host's selection and the catalog; pure. */
        internal fun buildStateFor(
            host: PickerHost,
            providerId: String?,
            modelId: String?,
            busy: Boolean,
            state: CatalogState,
            helperReady: Boolean,
            query: String = "",
            providerQuery: String = "",
            refreshing: Boolean = false,
            pendingModelId: String? = null,
            pendingProviderId: String? = null,
            cache: ModelPickerDataCache? = null,
        ): ModelPickerUiState {
            val enabled = state.enabledProviderIds.toSet()
            val ordered = state.providers.filter { it.id in enabled || it.id == providerId }
            val providers = buildList {
                ordered.forEach { add(PickerProvider(it.id, it.name.ifBlank { it.id }, it.id == providerId)) }
                if (providerId != null && ordered.none { it.id == providerId }) add(PickerProvider(providerId, providerId, true))
            }
            val sections = when {
                providerId.isNullOrBlank() || cache == null -> ModelPickerSections.build(state, providerId, modelId, query)
                else -> ModelPickerSections.build(cache.get(state, providerId), modelId, query)
            }
            return ModelPickerUiState(
                host = host,
                busy = busy,
                providers = providers,
                activeProviderId = providerId,
                query = query,
                providerQuery = providerQuery,
                sections = sections.sections,
                freeTextRow = sections.freeTextRow,
                selectedModelId = modelId,
                loading = refreshing || state.loading,
                source = state.source,
                pendingModelId = pendingModelId,
                pendingProviderId = pendingProviderId,
                canRefresh = !helperReady && providers.isNotEmpty(),
                noProviders = providers.isEmpty(),
                noModels = providers.isNotEmpty() && sections.noModels,
            )
        }

        private fun buildState(cur: Current, state: CatalogState, helper: HelperState, t: Transient, cache: ModelPickerDataCache): ModelPickerUiState =
            buildStateFor(
                host = cur.host,
                providerId = cur.providerId,
                modelId = cur.modelId,
                busy = cur.busy,
                state = state,
                helperReady = helper is HelperState.Ready,
                query = t.query,
                providerQuery = t.providerQuery,
                refreshing = t.refreshing,
                pendingModelId = t.pendingModelId,
                pendingProviderId = t.pendingProviderId,
                cache = cache,
            )

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ModelPickerViewModel(
                    catalog = PickerCatalogPort.of(graph.providerCatalog, graph.settingsHelper),
                    selection = graph.modelSelection,
                    sessions = PickerSessionPort.of({ graph.sessionStore }, { graph.sessionApi }, { graph.hubClient }),
                )
            }
        }
    }
}
