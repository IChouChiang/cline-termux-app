package dev.cline.termux.ui.providers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.SettingsHelper
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.core.kernel.termux.stripAnsi
import dev.cline.termux.data.account.ClineSignInCoordinator
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.catalog.fallbackModelIds
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.data.providers.CatalogLoad
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ConfigField
import dev.cline.termux.data.providers.ModelsLoad
import dev.cline.termux.data.providers.ProviderCatalogRepository
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderSettingsUpdate
import dev.cline.termux.data.providers.SaveOutcome
import dev.cline.termux.data.providers.authKind
import dev.cline.termux.data.providers.getProviderApiKeyUrl
import dev.cline.termux.data.providers.isProviderConnected
import dev.cline.termux.ui.components.KernelBlock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/*
 * settings-providers.md S4–S6: one ViewModel for the providers list, the provider detail and
 * the add-provider form, scoped to the `settings/providers` navigation graph so a save started
 * on S5 survives the pop back to S4 (S5.2 "Leaving with a dirty draft"). It holds the settings
 * helper lease (`acquire` in init, `release` in onCleared, SP §0.3) and the only plaintext
 * provider keys the app ever has: the catalog's `apiKey` / secret config values, in
 * [secrets], wiped in [onCleared] (SP §0.6, docs/07 §3.7). Nothing secret reaches a UiState,
 * the SavedStateHandle or a log.
 */

/** The helper lease as the providers screens use it. */
interface ProvidersHelper {
    val state: StateFlow<HelperState>
    fun acquire(owner: String)
    fun release(owner: String)

    /** The unavailable card's "Retry". */
    fun retry()

    companion object {
        /** The lease calls are suspend; [scope] (the app scope) outlives `onCleared`. */
        fun of(helper: SettingsHelper, scope: CoroutineScope): ProvidersHelper = object : ProvidersHelper {
            override val state: StateFlow<HelperState> get() = helper.state
            override fun acquire(owner: String) {
                scope.launch { helper.acquire(owner) }
            }
            override fun release(owner: String) {
                scope.launch { helper.release(owner) }
            }
            override fun retry() {
                scope.launch { helper.retry() }
            }
        }
    }
}

/** The S7 coordinator as S5 uses it. */
interface ProvidersSignIn {
    val state: StateFlow<SignInState>
    fun start(providerId: String)
    fun cancel()
    fun openTermux(): Boolean
    suspend fun signOut(providerId: String): Result<Unit>
    fun acknowledge()

    companion object {
        fun of(coordinator: ClineSignInCoordinator): ProvidersSignIn = object : ProvidersSignIn {
            override val state: StateFlow<SignInState> get() = coordinator.state
            override fun start(providerId: String) = coordinator.start(providerId, SignInOrigin.ProviderDetail)
            override fun cancel() = coordinator.cancel()
            override fun openTermux(): Boolean = coordinator.openTermux()
            override suspend fun signOut(providerId: String): Result<Unit> = coordinator.signOut(providerId)
            override fun acknowledge() = coordinator.acknowledge()
        }
    }
}

/** DataStore `providers.favoriteModels` (S5.4). */
interface FavoriteModelsStore {
    val favorites: Flow<Map<String, List<String>>>
    suspend fun setFavorite(providerId: String, modelId: String, favorite: Boolean)

    companion object {
        fun of(prefs: AppPreferences): FavoriteModelsStore = object : FavoriteModelsStore {
            override val favorites: Flow<Map<String, List<String>>> get() = prefs.favoriteModels
            override suspend fun setFavorite(providerId: String, modelId: String, favorite: Boolean) {
                prefs.setFavoriteModel(providerId, modelId, favorite)
            }
        }
    }
}

/** "Start kernel" on the KernelStopped card: the coordinator starts the Hub and clears `userStopped`. */
fun interface KernelStarter {
    suspend fun start()
}

class ProvidersViewModel(
    private val savedState: SavedStateHandle,
    private val catalog: ProviderCatalogRepository,
    private val helper: ProvidersHelper,
    private val signIn: ProvidersSignIn,
    private val favoritesStore: FavoriteModelsStore,
    private val termux: TermuxExecutor,
    private val kernelStarter: KernelStarter,
    /** Saves run here (the app scope) so a draft committed on leave completes after a pop (S5.2). */
    private val workScope: CoroutineScope,
    /** Where the list and detail states are built (docs/07 §8: catalog filtering runs on `Dispatchers.Default`). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Transient, non-secret screen state. */
    private data class Local(
        val loadedOnce: Boolean = false,
        val loadError: String? = null,
        val blocked: KernelBlock? = null,
        val refreshing: Boolean = false,
        /** providerId → config paths with a save in flight. */
        val saving: Map<String, Set<String>> = emptyMap(),
        val manualKeyExpanded: Set<String> = emptySet(),
        val modelsLoading: Set<String> = emptySet(),
        val modelsError: Map<String, String> = emptyMap(),
        val modelQuery: Map<String, String> = emptyMap(),
        val copied: Pair<String, String>? = null,
        val confirmDisconnect: String? = null,
        val disconnecting: Set<String> = emptySet(),
        val connecting: Set<String> = emptySet(),
        val fallbackSave: Map<String, FallbackSaveState> = emptyMap(),
        val resetTokens: Map<String, Int> = emptyMap(),
        /** Bumped whenever a load hands the VM fresh values (field drafts remount). */
        val valuesRevision: Int = 0,
        /** The provider whose sign-in this screen started (`cline-pass` → `cline`). */
        val signInProvider: String? = null,
        val addSaving: Boolean = false,
        val addError: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The plaintext stored keys and secret config values: providerId → path → value. Memory only. */
    private val secrets = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * The Add provider API-key draft (S6.2): memory only, never [savedState], so a rotation
     * or a shell relayout keeps it while process death drops it. Cleared on save, discard
     * and [onCleared].
     */
    private val _addKeyDraft = MutableStateFlow("")
    val addKeyDraft: StateFlow<String> = _addKeyDraft.asStateFlow()

    fun setAddKeyDraft(value: String) {
        _addKeyDraft.value = value
    }

    /** Details on screen (their models are fetched once the helper is Ready; vanish is checked for them). */
    private val openDetails = ConcurrentHashMap.newKeySet<String>()
    private val modelsFetched = ConcurrentHashMap.newKeySet<String>()

    private val _events = Channel<ProvidersEvent>(Channel.BUFFERED)
    val events: Flow<ProvidersEvent> = _events.receiveAsFlow()

    @Volatile private var cleared = false
    private var loadJob: Job? = null
    private var copiedJob: Job? = null

    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val collapsed = savedState.getStateFlow(KEY_COLLAPSED, ArrayList<String>())
    private val selected = savedState.getStateFlow<String?>(KEY_SELECTED, null)
    /** The S6 form as JSON (no secret in it: the API key stays in the composable). */
    private val addFormJson = savedState.getStateFlow(KEY_ADD_FORM, "")
    private val addForm: Flow<AddProviderForm> = addFormJson.map(::decodeForm)

    private fun currentForm(): AddProviderForm = decodeForm(addFormJson.value)

    private fun storeForm(form: AddProviderForm) {
        savedState[KEY_ADD_FORM] = if (form == AddProviderForm.EMPTY) "" else FORM_JSON.encodeToString(AddProviderForm.serializer(), form)
    }

    private val favorites: StateFlow<Map<String, List<String>>> =
        favoritesStore.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val listInputs = combine(query, collapsed, selected) { q, c, s -> Triple(q, c, s) }

    val listState: StateFlow<ProvidersListUiState> =
        combine(catalog.state, helper.state, local, listInputs) { cat, helperState, l, (q, c, s) ->
            buildList(cat, helperState, l, q, c.mapNotNull { name -> ProviderSection.entries.firstOrNull { it.name == name } }.toSet(), s)
        }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProvidersListUiState())

    val addState: StateFlow<AddProviderUiState> =
        combine(addForm, catalog.state, helper.state, local) { form, cat, helperState, l ->
            val ids = cat.providers.map { it.id }
            AddProviderUiState(
                form = form,
                duplicate = form.duplicateOf(ids),
                canSave = form.canSave(ids),
                saving = l.addSaving,
                error = l.addError,
                helperCard = helperState.helperCard(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddProviderUiState())

    private val detailStates = ConcurrentHashMap<String, StateFlow<ProviderDetailUiState>>()

    init {
        helper.acquire(LEASE_OWNER)
        viewModelScope.launch {
            helper.state.collect { state ->
                when (state) {
                    HelperState.Idle, is HelperState.Starting -> Unit
                    HelperState.Ready -> {
                        reload(force = false)
                        openDetails.filter { it !in modelsFetched }.forEach(::fetchModels)
                    }
                    is HelperState.Unavailable -> reload(force = false)
                }
            }
        }
        viewModelScope.launch { signIn.state.collect(::onSignInState) }
        viewModelScope.launch {
            catalog.state.collect { cat -> checkVanished(cat) }
        }
    }

    // ---- S4 list ---------------------------------------------------------------------------

    fun setQuery(text: String) {
        savedState[KEY_QUERY] = text
        // Typing in search clears the collapsed set (desktop).
        if (collapsed.value.isNotEmpty()) savedState[KEY_COLLAPSED] = ArrayList<String>()
    }

    fun toggleSection(section: ProviderSection) {
        val current = collapsed.value
        savedState[KEY_COLLAPSED] = ArrayList(if (section.name in current) current - section.name else current + section.name)
    }

    /** Expanded windows: the detail pane's provider. */
    fun select(providerId: String?) {
        savedState[KEY_SELECTED] = providerId
    }

    /** Pull to refresh: a forced reload (cache cleared). */
    fun refresh() {
        local.update { it.copy(refreshing = true) }
        reload(force = true) { local.update { it.copy(refreshing = false) } }
    }

    /** The error's "Retry": clears the cache and reloads. */
    fun retryLoad() = reload(force = true)

    /** The unavailable card's "Retry". */
    fun retryHelper() = helper.retry()

    /** The KernelStopped card's "Start kernel": the coordinator starts the Hub, then the helper is tried again. */
    fun startKernel() {
        viewModelScope.launch {
            runCatching { kernelStarter.start() }.onFailure { if (it is CancellationException) throw it }
            helper.retry()
        }
    }

    // ---- S5 detail -------------------------------------------------------------------------

    /** The S5 state for [providerId]. */
    fun detail(providerId: String): StateFlow<ProviderDetailUiState> = detailStates.getOrPut(providerId) {
        combine(catalog.state, helper.state, signIn.state, favorites, local) { cat, helperState, sign, favs, l ->
            buildDetail(providerId, cat, helperState, sign, favs, l)
        }.flowOn(compute).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            // A cheap seed: the model rows (hundreds on OpenRouter) are never built in composition.
            ProviderDetailUiState.pending(providerId),
        )
    }

    /** S5 entered composition: fetch its models once the helper is Ready and watch for it vanishing. */
    fun openDetail(providerId: String) {
        openDetails += providerId
        if (helper.state.value is HelperState.Ready && providerId !in modelsFetched) fetchModels(providerId)
    }

    fun closeDetail(providerId: String) {
        openDetails -= providerId
    }

    /**
     * The stored plaintext of a secret field (the catalog's `apiKey` or a secret config value),
     * for the field composable's `remember` draft. Never put into a UiState.
     */
    fun storedSecret(providerId: String, path: String): String = secrets[providerId]?.get(path).orEmpty()

    /** A text-like field lost focus or got IME Done. */
    fun commitField(providerId: String, path: String, draft: String) {
        val field = fieldOf(providerId, path) ?: return
        if (!draftDiffers(field, draft, storedValue(providerId, field))) return
        commit(providerId, field, coerceFieldValue(field, draft))
    }

    /** A `select` pick ("Not set" = empty text). */
    fun commitSelect(providerId: String, path: String, valueText: String) {
        val field = fieldOf(providerId, path) ?: return
        if (!draftDiffers(field, valueText, storedValue(providerId, field))) return
        commit(providerId, field, coerceFieldValue(field, valueText))
    }

    fun commitBoolean(providerId: String, path: String, checked: Boolean) {
        val field = fieldOf(providerId, path) ?: return
        commit(providerId, field, coerceBooleanValue(checked))
    }

    /**
     * The detail left composition (S5.2 "Leaving with a dirty draft"): commits every draft that
     * differs from its stored value, exactly as a blur would. Fallback mode saves only explicitly.
     */
    fun commitPendingDrafts(providerId: String, drafts: Map<String, String>) {
        if (isFallback(helper.state.value)) return
        for ((path, draft) in drafts) commitField(providerId, path, draft)
    }

    fun toggleManualKey(providerId: String) {
        local.update { l ->
            l.copy(manualKeyExpanded = if (providerId in l.manualKeyExpanded) l.manualKeyExpanded - providerId else l.manualKeyExpanded + providerId)
        }
    }

    /** "Connect" (local-auth, or the api-key footer): optimistic `enabled = true` + `save_provider_settings {enabled:true}`. */
    fun connect(providerId: String) {
        local.update { it.copy(connecting = it.connecting + providerId) }
        workScope.launch {
            val outcome = catalog.connectProvider(providerId)
            if (outcome is SaveOutcome.Failed) onSaveFailed(providerId, outcome.message)
            local.update { it.copy(connecting = it.connecting - providerId) }
        }
    }

    fun requestDisconnect(providerId: String) = local.update { it.copy(confirmDisconnect = providerId) }

    fun dismissDisconnect() = local.update { if (it.disconnecting.isEmpty()) it.copy(confirmDisconnect = null) else it }

    /**
     * Disconnect / Sign out (S5.3): `save_provider_settings {provider, enabled:false}` → reload
     * (cache cleared) → invalidate. Cline goes through the S7 coordinator, which also clears the
     * cached account summary (S7.3 "Sign out"). Drafts and shown secrets reset on success.
     */
    fun confirmDisconnect(providerId: String) {
        if (providerId in local.value.disconnecting) return
        local.update { it.copy(disconnecting = it.disconnecting + providerId) }
        viewModelScope.launch {
            val failure: String? = if (providerId == ClineSignInCoordinator.CLINE) {
                signIn.signOut(providerId).fold(
                    onSuccess = {
                        handleLoad(catalog.load(force = true))
                        null
                    },
                    onFailure = { ProviderCatalogRepository.errorMessage(it) },
                )
            } else {
                (catalog.disconnectProvider(providerId) as? SaveOutcome.Failed)?.message
            }
            if (failure == null) {
                secrets.remove(providerId)
                local.update { l ->
                    l.copy(
                        manualKeyExpanded = l.manualKeyExpanded - providerId,
                        resetTokens = l.resetTokens + (providerId to (l.resetTokens[providerId] ?: 0) + 1),
                    )
                }
            } else {
                _events.trySend(ProvidersEvent.SaveFailed(providerId, failure))
            }
            local.update { it.copy(disconnecting = it.disconnecting - providerId, confirmDisconnect = null) }
        }
    }

    /** "Sign in with browser" → S7 (`cline-pass` signs in as `cline`). */
    fun startSignIn(providerId: String) {
        val id = signInId(providerId) ?: return
        local.update { it.copy(signInProvider = id) }
        signIn.start(providerId)
    }

    fun cancelSignIn() = signIn.cancel()

    fun openTermux() {
        signIn.openTermux()
    }

    /** Dismisses a sign-in failure shown in the card. */
    fun dismissSignInError() {
        val state = signIn.state.value
        if (state is SignInState.Failed || state is SignInState.Blocked) signIn.acknowledge()
    }

    // ---- models card -------------------------------------------------------------------------

    fun refreshModels(providerId: String) = fetchModels(providerId)

    fun setModelQuery(providerId: String, text: String) = local.update { it.copy(modelQuery = it.modelQuery + (providerId to text)) }

    fun toggleFavorite(providerId: String, modelId: String) {
        val favorite = modelId !in favorites.value[providerId].orEmpty()
        viewModelScope.launch { favoritesStore.setFavorite(providerId, modelId, favorite) }
    }

    /** The copy button was used (the composable already put the id on the clipboard): "Copied" for 1.6 s. */
    fun markCopied(providerId: String, modelId: String) {
        copiedJob?.cancel()
        local.update { it.copy(copied = providerId to modelId) }
        copiedJob = viewModelScope.launch {
            delay(COPIED_MODEL_MS)
            local.update { it.copy(copied = null) }
        }
    }

    // ---- fallback save (§0.4) ------------------------------------------------------------------

    /**
     * `cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]`. Success =
     * exit code 0 (never a stdout match); the snackbar is rebuilt from the arguments.
     */
    fun saveFallback(providerId: String, apiKey: String, modelId: String, baseUrl: String?) {
        val key = apiKey.trim()
        val model = modelId.trim()
        if (key.isEmpty() || model.isEmpty()) return
        if (local.value.fallbackSave[providerId] == FallbackSaveState.Saving) return
        setFallbackSave(providerId, FallbackSaveState.Saving)
        viewModelScope.launch {
            val result = try {
                termux.run(ClineCommands.authProvider(providerId, key, model, baseUrl?.trim()?.ifEmpty { null }))
            } catch (e: TermuxUnavailable.TimedOut) {
                setFallbackSave(providerId, FallbackSaveState.Error(FallbackError.TimedOut))
                return@launch
            } catch (e: TermuxUnavailable) {
                val block = KernelBlock.of(e)
                if (block != null) {
                    setFallbackSave(providerId, FallbackSaveState.Idle)
                    local.update { it.copy(blocked = block) }
                } else {
                    setFallbackSave(providerId, FallbackSaveState.Error(FallbackError.Message(e.message.orEmpty())))
                }
                return@launch
            }
            if (result.ok) {
                setFallbackSave(providerId, FallbackSaveState.Done)
                _events.trySend(ProvidersEvent.ProviderConfigured(providerId, model))
                catalog.invalidate(refresh = false)
                handleLoad(catalog.load(force = true))
            } else {
                val detail = (result.lastStderrLine ?: result.errmsg)?.stripAnsi()?.trim()?.takeIf { it.isNotEmpty() }
                setFallbackSave(
                    providerId,
                    FallbackSaveState.Error(detail?.let(FallbackError::Message) ?: FallbackError.ExitCode(result.exitCode)),
                )
            }
        }
    }

    // ---- S6 add provider -------------------------------------------------------------------

    fun updateAddForm(transform: (AddProviderForm) -> AddProviderForm) {
        storeForm(transform(currentForm()))
        if (local.value.addError != null) local.update { it.copy(addError = null) }
    }

    /** `saveNewProvider`: `add_provider`, then invalidate and reload; S5 opens on the new id. [apiKey] never leaves this call. */
    fun saveNewProvider(apiKey: String) {
        val form = currentForm()
        if (local.value.addSaving || !form.canSave(catalog.state.value.providers.map { it.id })) return
        local.update { it.copy(addSaving = true, addError = null) }
        viewModelScope.launch {
            val request = form.toRequest(apiKey)
            when (val outcome = catalog.addProvider(request)) {
                SaveOutcome.Saved -> {
                    storeForm(AddProviderForm.EMPTY)
                    _addKeyDraft.value = ""
                    savedState[KEY_SELECTED] = request.providerId
                    local.update { it.copy(addSaving = false) }
                    _events.trySend(ProvidersEvent.ProviderAdded(request.providerId))
                }
                is SaveOutcome.Failed -> local.update { it.copy(addSaving = false, addError = outcome.message) }
            }
        }
    }

    /** The editor closed without saving (Discard, or a clean close). */
    fun resetAddForm() {
        storeForm(AddProviderForm.EMPTY)
        _addKeyDraft.value = ""
        local.update { it.copy(addError = null) }
    }

    override fun onCleared() {
        cleared = true
        helper.release(LEASE_OWNER)
        secrets.clear()
        _addKeyDraft.value = ""
        catalog.releaseSecrets()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun reload(force: Boolean, onDone: () -> Unit = {}) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                var attempts = 0
                var result = catalog.load(force)
                while (result == CatalogLoad.Superseded && attempts < SUPERSEDED_RETRIES) {
                    attempts += 1
                    result = catalog.load(force = false)
                }
                handleLoad(result)
            } finally {
                onDone()
            }
        }
    }

    private fun handleLoad(result: CatalogLoad) {
        when (result) {
            is CatalogLoad.Loaded -> {
                if (result.source == CatalogSource.Catalog) absorbSecrets(result.providers)
                local.update { it.copy(loadedOnce = true, loadError = null, blocked = null, valuesRevision = it.valuesRevision + 1) }
                checkVanished(catalog.state.value)
            }
            CatalogLoad.Superseded -> local.update { it.copy(loadedOnce = true) }
            is CatalogLoad.Failed -> local.update { it.copy(loadedOnce = true, loadError = result.message) }
            is CatalogLoad.Blocked -> local.update {
                it.copy(loadedOnce = true, blocked = KernelBlock.of(result.error), loadError = if (KernelBlock.of(result.error) == null) result.error.message else null)
            }
        }
    }

    private fun absorbSecrets(providers: List<ProviderListItem>) {
        if (cleared) return
        for (provider in providers) {
            val values = LinkedHashMap<String, String>()
            provider.apiKey?.takeIf { it.isNotEmpty() }?.let { values[ProviderListItem.API_KEY_PATH] = it }
            for (field in provider.configFields.orEmpty()) {
                if (!field.isSecret) continue
                provider.configValues?.get(field.path)?.let { fieldValueText(it) }?.takeIf { it.isNotEmpty() }?.let { values[field.path] = it }
            }
            if (values.isEmpty()) secrets.remove(provider.id) else secrets[provider.id] = values
        }
    }

    private fun checkVanished(cat: CatalogState) {
        if (!local.value.loadedOnce || cat.loading || cat.source != CatalogSource.Catalog || cat.providers.isEmpty()) return
        for (id in openDetails.toList()) {
            if (cat.provider(id) == null) {
                openDetails -= id
                if (selected.value == id) savedState[KEY_SELECTED] = null
                _events.trySend(ProvidersEvent.ProviderVanished(id))
            }
        }
    }

    private fun fetchModels(providerId: String) {
        if (providerId in local.value.modelsLoading) return
        local.update { it.copy(modelsLoading = it.modelsLoading + providerId, modelsError = it.modelsError - providerId) }
        viewModelScope.launch {
            try {
                when (val result = catalog.refreshModels(providerId)) {
                    is ModelsLoad.Loaded -> modelsFetched += providerId
                    is ModelsLoad.Failed -> local.update { it.copy(modelsError = it.modelsError + (providerId to result.message)) }
                    ModelsLoad.Unavailable, ModelsLoad.Superseded -> Unit
                }
            } finally {
                local.update { it.copy(modelsLoading = it.modelsLoading - providerId) }
            }
        }
    }

    private fun fieldOf(providerId: String, path: String): ConfigField? =
        catalog.state.value.provider(providerId)?.configFields?.firstOrNull { it.path == path }

    private fun storedValue(providerId: String, field: ConfigField): JsonElement? {
        val provider = catalog.state.value.provider(providerId) ?: return null
        return initialFieldValue(provider, field, secrets[providerId]?.get(field.path))
    }

    private fun commit(providerId: String, field: ConfigField, value: JsonElement) {
        if (isFallback(helper.state.value)) return
        val text = fieldValueText(value)
        val updates = ProviderSettingsUpdate(
            apiKey = if (field.path == ProviderListItem.API_KEY_PATH) text else null,
            baseUrl = if (field.path == ProviderListItem.BASE_URL_PATH) text else null,
            configValues = mapOf(field.path to value),
        )
        setSaving(providerId, field.path, true)
        if (field.isSecret || field.path == ProviderListItem.API_KEY_PATH) rememberSecret(providerId, field.path, text)
        workScope.launch {
            try {
                when (val outcome = catalog.updateProvider(providerId, updates)) {
                    SaveOutcome.Saved -> Unit
                    is SaveOutcome.Failed -> onSaveFailed(providerId, outcome.message)
                }
            } finally {
                setSaving(providerId, field.path, false)
                if (cleared) catalog.releaseSecrets()
            }
        }
    }

    /** Failure: the snackbar, fresh stored values (the repository already reloaded), drafts remount. */
    private suspend fun onSaveFailed(providerId: String, message: String) {
        _events.trySend(ProvidersEvent.SaveFailed(providerId, message))
        if (cleared) return
        val reloaded = catalog.load(force = false)
        if (reloaded is CatalogLoad.Loaded && reloaded.source == CatalogSource.Catalog) absorbSecrets(reloaded.providers)
        local.update { l ->
            l.copy(
                resetTokens = l.resetTokens + (providerId to (l.resetTokens[providerId] ?: 0) + 1),
                valuesRevision = l.valuesRevision + 1,
            )
        }
    }

    private fun rememberSecret(providerId: String, path: String, value: String) {
        val current = secrets[providerId].orEmpty()
        val next = if (value.isEmpty()) current - path else current + (path to value)
        if (next.isEmpty()) secrets.remove(providerId) else secrets[providerId] = next
    }

    private fun setSaving(providerId: String, path: String, saving: Boolean) = local.update { l ->
        val paths = l.saving[providerId].orEmpty().let { if (saving) it + path else it - path }
        l.copy(saving = if (paths.isEmpty()) l.saving - providerId else l.saving + (providerId to paths))
    }

    private fun setFallbackSave(providerId: String, state: FallbackSaveState) =
        local.update { it.copy(fallbackSave = it.fallbackSave + (providerId to state)) }

    private fun onSignInState(state: SignInState) {
        when (state) {
            is SignInState.Succeeded -> {
                if (state.origin == SignInOrigin.ProviderDetail) {
                    _events.trySend(ProvidersEvent.SignedIn(state.providerId))
                    signIn.acknowledge()
                    local.update { it.copy(signInProvider = null) }
                }
                reload(force = true)
            }
            is SignInState.AlreadySignedIn -> if (local.value.signInProvider == state.providerId) {
                signIn.acknowledge()
                local.update { it.copy(signInProvider = null) }
                _events.trySend(ProvidersEvent.SignedIn(state.providerId))
                reload(force = true)
            }
            else -> Unit
        }
    }

    // ---- state builders ------------------------------------------------------------------------

    private fun isFallback(helperState: HelperState): Boolean = helperState is HelperState.Unavailable

    private fun buildList(
        cat: CatalogState,
        helperState: HelperState,
        l: Local,
        query: String,
        collapsedSections: Set<ProviderSection>,
        selectedId: String?,
    ): ProvidersListUiState {
        val starting = helperState is HelperState.Starting || (helperState is HelperState.Idle && !l.loadedOnce)
        return ProvidersListUiState(
            helper = helperState,
            fallback = isFallback(helperState),
            helperStarting = starting,
            loading = !l.loadedOnce || (cat.loading && !l.refreshing && cat.providers.isEmpty()),
            refreshing = l.refreshing,
            error = l.loadError,
            query = query,
            collapsed = collapsedSections,
            rows = providerRows(cat.providers, query, l.saving.keys + l.connecting + l.disconnecting),
            configuredCount = cat.providers.count(::isProviderConnected),
            totalCount = cat.providers.size,
            selectedId = effectiveSelectedProviderId(selectedId, cat.providers),
            blocked = helperState.kernelBlock() ?: l.blocked,
            helperCard = helperState.helperCard(),
        )
    }

    private fun buildDetail(
        id: String,
        cat: CatalogState,
        helperState: HelperState,
        sign: SignInState,
        favs: Map<String, List<String>>,
        l: Local,
    ): ProviderDetailUiState {
        val provider = cat.provider(id)
        val fallback = isFallback(helperState)
        val authKind = provider?.authKind() ?: dev.cline.termux.data.providers.providerAuthKind(id, null)
        // Fallback: the redacted providers.json projection decides (S5.1), when it has been read.
        val presence = if (fallback) cat.presence else null
        val presenceEntry = cat.presence?.providers?.let { it[id] ?: if (id == CLINE_PASS_ID) it[ClineSignInCoordinator.CLINE] else null }
        val connected = if (presence != null) {
            presence.isConnected(id) || (id == CLINE_PASS_ID && presence.isConnected(ClineSignInCoordinator.CLINE))
        } else {
            provider?.let(::isProviderConnected) == true
        }
        val oauthSignedIn = if (presence != null) fallbackOAuthSignedIn(presenceEntry) else provider?.oauthAccessTokenPresent == true
        val apiKeyPresent = if (presence != null) {
            presenceEntry?.hasApiKey == true
        } else {
            provider?.hasApiKey == true || secrets[id]?.containsKey(ProviderListItem.API_KEY_PATH) == true
        }
        val connection = connectionVariant(authKind, oauthSignedIn, connected, apiKeyPresent)
        val savingPaths = l.saving[id].orEmpty()
        val fields = if (fallback || provider == null) emptyList() else provider.configFields.orEmpty().map { fieldUi(provider, it, savingPaths) }
        val apiKeyField = fields.firstOrNull { it.path == ProviderListItem.API_KEY_PATH }
        val signInId = signInId(id)
        val detailSignIn = when {
            signInId == null -> DetailSignIn.Idle
            sign is SignInState.WaitingForTermux && sign.providerId == signInId -> DetailSignIn.Waiting
            sign is SignInState.Failed && sign.kind == SignInState.Failed.Kind.SignIn && l.signInProvider == signInId -> DetailSignIn.Failed(sign.failure)
            sign is SignInState.Blocked && l.signInProvider == signInId -> DetailSignIn.Blocked(KernelBlock.of(sign.error) ?: KernelBlock.TimedOut)
            else -> DetailSignIn.Idle
        }
        val models = cat.modelsByProvider[id].orEmpty()
        val query = l.modelQuery[id].orEmpty()
        val searchVisible = showsModelSearch(models.size, fallback)
        val modelsError = if (fallback) null else l.modelsError[id]
        val ready = helperState is HelperState.Ready
        val fallbackRow = catalog.fallbackProvider(id)
        return ProviderDetailUiState(
            providerId = id,
            name = provider?.name ?: id,
            known = provider != null,
            loading = !l.loadedOnce,
            connected = connected,
            authKind = authKind,
            oauthSignedIn = oauthSignedIn,
            apiKeyPresent = apiKeyPresent,
            connection = connection,
            fields = fields,
            apiKeyField = apiKeyField,
            manualKeyExpanded = id in l.manualKeyExpanded,
            canSignIn = signInId != null,
            signIn = detailSignIn,
            models = ModelsCardUi(
                loading = id in l.modelsLoading,
                error = modelsError,
                query = query,
                rows = modelRows(models, if (searchVisible) query else "", favs[id].orEmpty()),
                totalCount = models.size,
                refreshAvailable = !fallback && ready,
                searchVisible = searchVisible,
                copiedId = l.copied?.takeIf { it.first == id }?.second,
            ),
            fallback = fallback,
            fallbackForm = if (!fallbackFormShown(fallback, connection, id)) null else FallbackFormUi(
                showBaseUrl = fallbackRow?.supportsBaseUrl ?: (id in BASE_URL_PROVIDER_IDS),
                initialModelId = cat.presence?.providers?.get(id)?.model?.takeIf { it.isNotBlank() }
                    ?: fallbackModelIds(id).firstOrNull()
                    ?: provider?.defaultModelId
                    ?: fallbackRow?.defaultModelId
                    ?: "",
                apiKeySaved = apiKeyPresent,
                keylessHint = id == OPENAI_COMPATIBLE_ID,
            ),
            fallbackSave = l.fallbackSave[id] ?: FallbackSaveState.Idle,
            autoSaveNote = showsAutoSaveNote(connected, authKind, fallback),
            signOutAction = isSignOutAction(authKind, oauthSignedIn),
            canDisconnect = connected && !fallback && ready && detailSignIn != DetailSignIn.Waiting,
            confirmDisconnect = l.confirmDisconnect == id,
            disconnecting = id in l.disconnecting,
            showConnect = !connected && !fallback && (authKind == AuthKind.Local || authKind == AuthKind.ApiKey),
            connecting = id in l.connecting,
            helperCard = helperState.helperCard(),
            blocked = helperState.kernelBlock() ?: l.blocked,
            resetKey = l.resetTokens[id] ?: 0,
            valuesRevision = l.valuesRevision,
        )
    }

    private fun fieldUi(provider: ProviderListItem, field: ConfigField, savingPaths: Set<String>): FieldUi {
        val stored = if (field.isSecret) null else initialFieldValue(provider, field)
        return FieldUi(
            path = field.path,
            label = field.label,
            type = field.type,
            description = field.description?.takeIf { it.isNotBlank() },
            placeholder = field.placeholder?.takeIf { it.isNotBlank() },
            secret = field.isSecret,
            options = field.options.orEmpty().map { FieldOption(it.label, fieldValueText(it.value)) },
            storedText = fieldValueText(stored),
            checked = fieldChecked(stored),
            keyUrl = if (field.path == ProviderListItem.API_KEY_PATH) getProviderApiKeyUrl(provider.id) else null,
            saving = field.path in savingPaths,
        )
    }

    companion object {
        const val LEASE_OWNER = "providers"
        const val STOP_TIMEOUT_MS = 5_000L
        const val SUPERSEDED_RETRIES = 3

        const val KEY_QUERY = "providers.query"
        const val KEY_COLLAPSED = "providers.collapsed"
        const val KEY_SELECTED = "providers.selected"
        const val KEY_ADD_FORM = "providers.addForm"

        const val OPENAI_COMPATIBLE_ID = "openai-compatible"
        const val CLINE_PASS_ID = "cline-pass"

        private val FORM_JSON = Json { ignoreUnknownKeys = true }

        /** A stored form, or an empty one when there is none or it no longer decodes. */
        internal fun decodeForm(json: String): AddProviderForm =
            if (json.isEmpty()) AddProviderForm.EMPTY else runCatching { FORM_JSON.decodeFromString(AddProviderForm.serializer(), json) }.getOrDefault(AddProviderForm.EMPTY)

        /** Where `cline auth --baseurl` is accepted when the bundle does not say (§0.4). */
        val BASE_URL_PROVIDER_IDS = setOf("openai-native", OPENAI_COMPATIBLE_ID)

        /** The Termux sign-in id for [providerId] (`cline-pass` → `cline`), or null when `cline auth` has none. */
        fun signInId(providerId: String): String? {
            val id = if (providerId.trim() == "cline-pass") ClineSignInCoordinator.CLINE else providerId.trim()
            return id.takeIf { it in ClineCommands.OAUTH_PROVIDER_IDS }
        }
    }
}
