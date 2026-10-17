package dev.cline.termux.data.providers

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.dashboard.DesktopCommandException
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.SettingsHelper
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.core.kernel.termux.readProviderPresence
import dev.cline.termux.data.catalog.FallbackProvider
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.catalog.fallbackModelIds
import dev.cline.termux.data.chat.CatalogAuthFacts
import dev.cline.termux.data.chat.ProviderAuthCatalog
import dev.cline.termux.data.prefs.CatalogCache
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the current catalog came from (S8.5 `source`). */
enum class CatalogSource {
    /** Fresh from the settings helper (`list_provider_catalog`). */
    Catalog,

    /** The persisted non-secret projection (`filesDir/catalog-cache.json`). */
    Cache,

    /** The bundled `fallback_providers.json` + `providers.json` presence (§0.4). */
    Fallback,
}

/**
 * The app-wide, non-secret catalog (docs/07 §4.5). [providers] are [ProviderListItem.redacted]
 * copies: no plaintext key, no secret config values. [modelsByProvider] holds every model of
 * each provider (S5 lists them all; S8 filters with [isChatModel]).
 */
@Immutable
data class CatalogState(
    val source: CatalogSource = CatalogSource.Fallback,
    val generation: Int = 0,
    val providers: List<ProviderListItem> = emptyList(),
    val modelsByProvider: Map<String, List<ProviderModel>> = emptyMap(),
    /** A non-silent catalog load is in flight. */
    val loading: Boolean = false,
    /** The last catalog load failed with this message (desktop `providerCatalogError`). */
    val error: String? = null,
    /** The redacted `providers.json` projection, when it has been read. */
    val presence: ConfiguredProviders? = null,
    /** Bumped by every failed save after the resync (desktop `detailResetToken`). */
    val detailResetToken: Int = 0,
    /** Bumped by [ProviderCatalogRepository.invalidate]; observers refetch on change. */
    val invalidations: Int = 0,
    /** False until the cache file and the bundle have been read once. */
    val initialized: Boolean = false,
    /**
     * The bundled `fallback_providers.json` (AppGraph's loader), [FallbackProviders.EMPTY] until
     * it has been read. In [CatalogSource.Fallback] the composer's reasoning chip asks it
     * ([FallbackProviders.reasoningSupport], decision A2); a live or cached catalog never does.
     */
    val bundle: FallbackProviders = FallbackProviders.EMPTY,
) {
    fun provider(id: String): ProviderListItem? = providers.firstOrNull { it.id == id }

    /** `configured(id)`: [isProviderConnected] on the catalog, or the presence rule in fallback. */
    fun configured(id: String): Boolean {
        provider(id)?.let { return isProviderConnected(it) }
        return presence?.isConnected(id) == true
    }

    /** Chat models of [providerId] (S8.2). */
    fun chatModels(providerId: String): List<ProviderModel> = filterChatModels(modelsByProvider[providerId])

    /** `enabledProviderIds`: enabled providers with at least one chat model, catalog order. */
    val enabledProviderIds: List<String>
        get() = providers.filter { it.enabled && chatModels(it.id).isNotEmpty() }.map { it.id }

    val anyConnected: Boolean get() = providers.any(::isProviderConnected) || presence?.anyConnected == true

    override fun toString(): String =
        "CatalogState(source=$source, generation=$generation, providers=${providers.size}, loading=$loading, error=${error != null})"
}

/** Outcome of [ProviderCatalogRepository.load]. [providers] may carry plaintext keys: screen memory only. */
sealed interface CatalogLoad {
    data class Loaded(val providers: List<ProviderListItem>, val source: CatalogSource) : CatalogLoad {
        override fun toString(): String = "Loaded(${providers.size} providers, $source)"
    }

    /** A newer mutation or load superseded this response; callers needing a resync retry. */
    data object Superseded : CatalogLoad
    data class Failed(val message: String) : CatalogLoad

    /** Fallback mode could not read `providers.json` because Termux is unavailable (§0.5). */
    data class Blocked(val error: TermuxUnavailable) : CatalogLoad
}

/** Outcome of [ProviderCatalogRepository.refreshModels]. */
sealed interface ModelsLoad {
    data class Loaded(val providerId: String, val models: List<ProviderModel>) : ModelsLoad
    data class Failed(val providerId: String, val message: String) : ModelsLoad

    /** The helper is not running; the caller shows the cached or bundled list. */
    data object Unavailable : ModelsLoad

    /** A newer request for the same provider, or an invalidation, made this reply stale. */
    data object Superseded : ModelsLoad
}

/** Outcome of a provider settings write. */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/** `ProviderSettingsUpdate`. Keys are secrets: they pass through, never stored. */
data class ProviderSettingsUpdate(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val configValues: Map<String, JsonElement?>? = null,
) {
    override fun toString(): String =
        "ProviderSettingsUpdate(apiKey=${if (apiKey == null) "null" else "<redacted>"}, baseUrl=${baseUrl != null}, configValues=${configValues?.keys})"
}

/** `AddProviderPayload` (S6) → `desktopCommand add_provider`. */
data class AddProviderRequest(
    val providerId: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val headers: Map<String, String>? = null,
    val timeoutMs: Long? = null,
    val models: List<String>? = null,
    val defaultModelId: String? = null,
    val modelsSourceUrl: String? = null,
    val capabilities: List<String>? = null,
) {
    fun toArgs(): JsonObject = buildJsonObject {
        put("provider_id", providerId)
        put("name", name)
        put("base_url", baseUrl)
        apiKey?.let { put("api_key", it) }
        headers?.let { h -> put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } }) }
        timeoutMs?.let { put("timeout_ms", it) }
        models?.let { list -> put("models", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
        defaultModelId?.let { put("default_model_id", it) }
        modelsSourceUrl?.let { put("models_source_url", it) }
        capabilities?.let { list -> put("capabilities", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
    }

    override fun toString(): String = "AddProviderRequest(providerId=$providerId, apiKey=${if (apiKey == null) "null" else "<redacted>"})"
}

/** The slice of [SettingsHelper] the repository uses; it never starts the helper itself. */
interface CatalogHelper {
    val state: StateFlow<HelperState>

    /** Waits for a start sequence in flight and returns the state it settled in. */
    suspend fun awaitSettled(): HelperState

    suspend fun desktopCommand(command: String, args: JsonObject): JsonElement
}

fun SettingsHelper.asCatalogHelper(): CatalogHelper = object : CatalogHelper {
    override val state: StateFlow<HelperState> get() = this@asCatalogHelper.state
    override suspend fun awaitSettled(): HelperState = this@asCatalogHelper.awaitSettled()
    override suspend fun desktopCommand(command: String, args: JsonObject): JsonElement =
        this@asCatalogHelper.desktopCommand(command, args)
}

/** `providers.json` through RUN_COMMAND, reduced in the bridge (§0.4). */
fun interface PresenceSource {
    /** @throws TermuxUnavailable when Termux cannot run the command. */
    suspend fun read(): ProviderPresence

    companion object {
        fun of(termux: TermuxExecutor) = PresenceSource { termux.readProviderPresence() }
    }
}

/** The persisted projection's storage (`filesDir/catalog-cache.json`). */
interface CatalogProjectionStore {
    suspend fun read(): CatalogProjection?
    suspend fun write(projection: CatalogProjection)

    companion object {
        fun of(cache: CatalogCache<CatalogProjection>) = object : CatalogProjectionStore {
            override suspend fun read(): CatalogProjection? = cache.read()?.value
            override suspend fun write(projection: CatalogProjection) = cache.write(projection)
        }
    }
}

/** One provider of the persisted projection: id, name, flags, capabilities, auth kind, default model. */
@Serializable
data class ProjectedProvider(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val configured: Boolean? = null,
    val apiKeyPresent: Boolean = false,
    val oauthAccessTokenPresent: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val authKind: String = AuthKind.ApiKey.wire,
    val defaultModelId: String? = null,
    val localCliCommand: String? = null,
    val localCliDocsUrl: String? = null,
) {
    fun toListItem(): ProviderListItem = ProviderListItem(
        id = id,
        name = name,
        enabled = enabled,
        configured = configured,
        apiKeyPresent = apiKeyPresent,
        oauthAccessTokenPresent = oauthAccessTokenPresent,
        defaultModelId = defaultModelId,
        capabilities = capabilities,
        auth = ProviderAuthFacts(id, capabilities, localCliCommand?.let { ProviderLocalCliInfo(it, localCliDocsUrl) }),
    )

    fun authFacts(): CatalogAuthFacts = CatalogAuthFacts(capabilities, localCliCommand, localCliDocsUrl)

    companion object {
        fun from(item: ProviderListItem): ProjectedProvider = ProjectedProvider(
            id = item.id,
            name = item.name,
            enabled = item.enabled,
            configured = item.configured,
            apiKeyPresent = item.hasApiKey,
            oauthAccessTokenPresent = item.oauthAccessTokenPresent == true,
            capabilities = item.auth?.capabilities?.takeIf { it.isNotEmpty() } ?: item.capabilities.orEmpty(),
            authKind = item.authKind().wire,
            defaultModelId = item.defaultModelId,
            localCliCommand = item.auth?.localCli?.command,
            localCliDocsUrl = item.auth?.localCli?.docsUrl,
        )
    }
}

/** `catalog-cache.json` payload (SP §0.7): never an `apiKey`, `configValues` or `baseUrl`. */
@Serializable
data class CatalogProjection(
    val providers: List<ProjectedProvider> = emptyList(),
    val models: Map<String, List<ProviderModel>> = emptyMap(),
)

/**
 * SP §0.7 in code: the desktop's `providerCatalogCache` + `catalogGenerationRef`
 * (settings-view.tsx) over the settings helper, with the persisted projection and the §0.4
 * fallback behind it. App-scoped; screens own the helper lease and call [load]; S8, the
 * welcome setup notice and S1 observe [state].
 *
 * Secrets: a fresh catalog's plaintext keys stay in [secretCatalog] for the 60 s TTL only
 * (so a revisit inside the TTL is instant, as on the desktop) and are dropped by
 * [releaseSecrets] (the providers ViewModel's `onCleared()`), [invalidate] and expiry.
 * [state] and the cache file never hold them.
 */
class ProviderCatalogRepository(
    private val helper: CatalogHelper,
    private val projectionStore: CatalogProjectionStore,
    private val presenceSource: PresenceSource,
    private val fallbackLoader: suspend () -> FallbackProviders,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val ttlMs: Long = PROVIDER_CATALOG_CACHE_TTL_MS,
) : ProviderAuthCatalog {

    private class TimedCatalog(val providers: List<ProviderListItem>, val fetchedAtMs: Long)

    private val generationCounter = AtomicInteger(0)
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    @Volatile private var secretCatalog: TimedCatalog? = null

    @Volatile private var fallback: FallbackProviders? = null

    @Volatile private var cachedProjection: CatalogProjection? = null

    /** Providers whose model list came from `list_provider_models`; a catalog load does not overwrite them. */
    private val explicitModels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val modelRequestSeq = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val modelsRevision = AtomicInteger(0)

    private val initialized = CompletableDeferred<Unit>()
    private val initMutex = Mutex()
    private val writeMutex = Mutex()

    /** The current generation (desktop `catalogGenerationRef.current`). */
    val generation: Int get() = generationCounter.get()

    /** Reads the bundle and the cache file once; [state] then shows the cache or the fallback. */
    fun start() {
        scope.launch { ensureInitialized() }
    }

    suspend fun ensureInitialized() {
        if (initialized.isCompleted) return
        initMutex.withLock {
            if (initialized.isCompleted) return
            val bundle = runCatching { fallbackLoader() }.getOrElse { if (it is CancellationException) throw it; FallbackProviders.EMPTY }
            fallback = bundle
            val projection = runCatching { projectionStore.read() }.getOrElse { if (it is CancellationException) throw it; null }
            cachedProjection = projection
            _state.update { current ->
                if (current.source == CatalogSource.Catalog || current.providers.isNotEmpty()) {
                    current.copy(initialized = true, bundle = bundle)
                } else if (projection != null && projection.providers.isNotEmpty()) {
                    current.copy(
                        source = CatalogSource.Cache,
                        providers = projection.providers.map { it.toListItem() },
                        modelsByProvider = projection.models,
                        initialized = true,
                        bundle = bundle,
                    )
                } else {
                    val built = buildFallback(bundle, current.presence)
                    current.copy(
                        source = CatalogSource.Fallback,
                        providers = built.first,
                        modelsByProvider = built.second,
                        initialized = true,
                        bundle = bundle,
                    )
                }
            }
            initialized.complete(Unit)
        }
    }

    /** The bundled fallback row for [providerId] (S5.2 fallback fields: `supportsBaseUrl`). */
    fun fallbackProvider(providerId: String): FallbackProvider? = fallback?.get(providerId)

    /** In-memory models of [providerId] (all of them; S8 filters with [isChatModel]). */
    fun models(providerId: String): List<ProviderModel> = _state.value.modelsByProvider[providerId].orEmpty()

    /** docs/07 §4.5 `configured(id)`. */
    fun configured(providerId: String): Boolean = _state.value.configured(providerId)

    /** Claims a generation for an optimistic mutation; responses of older loads are then discarded. */
    fun claimGeneration(): Int = generationCounter.incrementAndGet().also { gen -> _state.update { it.copy(generation = gen) } }

    /**
     * `loadProviderCatalog`: within the TTL returns the screen-memory catalog at once;
     * otherwise claims a generation and asks the helper. With the helper not Ready (after a
     * start in flight settles), builds the §0.4 fallback list from the bundle and
     * `providers.json`.
     */
    suspend fun load(force: Boolean = false): CatalogLoad {
        ensureInitialized()
        val helperState = helper.state.value.let { if (it is HelperState.Starting) helper.awaitSettled() else it }
        if (helperState !is HelperState.Ready) return loadFallback()
        val cached = secretCatalog
        if (!force && cached != null && clock.millis() - cached.fetchedAtMs < ttlMs) {
            _state.update { it.copy(loading = false, error = null) }
            return CatalogLoad.Loaded(cached.providers, CatalogSource.Catalog)
        }
        val gen = claimGeneration()
        _state.update { it.copy(loading = true, error = null) }
        try {
            val payload = fetchCatalog()
            if (gen != generationCounter.get()) return CatalogLoad.Superseded
            applyCatalog(payload.providers)
            return CatalogLoad.Loaded(payload.providers, CatalogSource.Catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generationCounter.get()) return CatalogLoad.Superseded
            val message = errorMessage(e)
            _state.update { it.copy(error = message) }
            return CatalogLoad.Failed(message)
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * `resyncProviderCatalog`: a silent refresh after a successful save (no loading state);
     * claims a generation like [load], so the newest snapshot always wins. Errors are ignored.
     */
    suspend fun resync() {
        if (helper.state.value !is HelperState.Ready) return
        val gen = claimGeneration()
        try {
            val payload = fetchCatalog()
            if (gen == generationCounter.get()) applyCatalog(payload.providers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Background refresh only; the optimistic state stays until the next full load.
        }
    }

    /**
     * `setProvidersWithCache` for an optimistic edit: claims a generation, applies [transform]
     * to the screen-memory catalog (restarting its TTL) and to [state].
     */
    fun applyOptimistic(transform: (List<ProviderListItem>) -> List<ProviderListItem>) {
        claimGeneration()
        val base = secretCatalog?.providers ?: _state.value.providers
        val next = transform(base)
        secretCatalog = TimedCatalog(next, clock.millis())
        _state.update { it.copy(providers = next.map(ProviderListItem::redacted)) }
    }

    /**
     * `persistProviderSettings`: `save_provider_settings {provider, enabled?, api_key?, base_url?,
     * settings?}`. Success → silent resync. Failure → reload with up to 3 attempts while a newer
     * generation supersedes, then bump `detailResetToken` so field drafts remount.
     * [invalidate] runs either way (the shared short-lived caches follow the save).
     */
    suspend fun saveProviderSettings(
        providerId: String,
        enabled: Boolean? = null,
        apiKey: String? = null,
        baseUrl: String? = null,
        configValues: Map<String, JsonElement?>? = null,
    ): SaveOutcome {
        val args = buildJsonObject {
            put("provider", providerId)
            enabled?.let { put("enabled", it) }
            apiKey?.let { put("api_key", it) }
            baseUrl?.let { put("base_url", it) }
            configValues?.let { put("settings", toSettingsPatch(it)) }
        }
        return try {
            helper.desktopCommand(COMMAND_SAVE_PROVIDER_SETTINGS, args)
            scope.launch { resync() }
            SaveOutcome.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = errorMessage(e)
            for (attempt in 0 until SAVE_FAILURE_RELOAD_ATTEMPTS) {
                secretCatalog = null
                if (load(force = true) != CatalogLoad.Superseded) break
            }
            _state.update { it.copy(detailResetToken = it.detailResetToken + 1) }
            SaveOutcome.Failed(message)
        } finally {
            // The desktop's `invalidateProviderCatalogCache()`: the shared consumers (picker,
            // setup notice, S1) refetch; the screen's own catalog keeps its optimistic state.
            signalInvalidation()
        }
    }

    /** `connectProvider`: optimistic `enabled = true`, then save `{enabled:true}`. */
    suspend fun connectProvider(providerId: String): SaveOutcome {
        applyOptimistic { list -> list.map { if (it.id == providerId) it.copy(enabled = true) else it } }
        return saveProviderSettings(providerId, enabled = true)
    }

    /**
     * `disconnectProvider`: optimistic disable (no key, no OAuth), save `{enabled:false}`, and on
     * success reload from disk (the entry is deleted) and [invalidate].
     */
    suspend fun disconnectProvider(providerId: String): SaveOutcome {
        applyOptimistic { list ->
            list.map {
                if (it.id == providerId) it.copy(enabled = false, apiKey = null, apiKeyPresent = false, oauthAccessTokenPresent = false) else it
            }
        }
        val outcome = saveProviderSettings(providerId, enabled = false)
        if (outcome == SaveOutcome.Saved) {
            invalidate(refresh = false)
            load(force = true)
        }
        return outcome
    }

    /** `updateProvider`: optimistic merge (`enabled = true`, merged `configValues`), then save. */
    suspend fun updateProvider(providerId: String, updates: ProviderSettingsUpdate): SaveOutcome {
        applyOptimistic { list ->
            list.map { p ->
                if (p.id != providerId) {
                    p
                } else {
                    p.copy(
                        apiKey = updates.apiKey ?: p.apiKey,
                        apiKeyPresent = if (updates.apiKey != null) updates.apiKey.isNotBlank() else p.apiKeyPresent,
                        baseUrl = updates.baseUrl ?: p.baseUrl,
                        enabled = true,
                        configValues = updates.configValues?.let { values ->
                            p.configValues.orEmpty() + values.mapValues { (_, v) -> v ?: JsonNull }
                        } ?: p.configValues,
                    )
                }
            }
        }
        return saveProviderSettings(providerId, apiKey = updates.apiKey, baseUrl = updates.baseUrl, configValues = updates.configValues)
    }

    /** `saveNewProvider` (S6): `add_provider` (60 s), then invalidate and reload. */
    suspend fun addProvider(request: AddProviderRequest): SaveOutcome = try {
        helper.desktopCommand(COMMAND_ADD_PROVIDER, request.toArgs())
        invalidate(refresh = false)
        load(force = true)
        SaveOutcome.Saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SaveOutcome.Failed(errorMessage(e))
    }

    /**
     * `loadProviderModels`: `list_provider_models {provider}` → `{providerId, models}`, scoped to
     * the provider and the request (a newer request or an [invalidate] makes it stale). A
     * success replaces that provider's list (an explicit empty list included) and is persisted.
     */
    suspend fun refreshModels(providerId: String): ModelsLoad {
        val id = providerId.trim()
        if (helper.state.value.let { if (it is HelperState.Starting) helper.awaitSettled() else it } !is HelperState.Ready) {
            return ModelsLoad.Unavailable
        }
        val seq = modelRequestSeq.merge(id, 1, Int::plus) ?: 1
        val revision = modelsRevision.get()
        return try {
            val element = helper.desktopCommand(COMMAND_LIST_PROVIDER_MODELS, buildJsonObject { put("provider", id) })
            val payload = ProviderJson.decodeFromJsonElement(ProviderModelsResponse.serializer(), element)
            if (modelRequestSeq[id] != seq || modelsRevision.get() != revision) return ModelsLoad.Superseded
            publishModels(id, payload.models)
            ModelsLoad.Loaded(id, payload.models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (modelRequestSeq[id] != seq) ModelsLoad.Superseded else ModelsLoad.Failed(id, errorMessage(e))
        }
    }

    /**
     * Marks every cache stale after a provider save, add, disconnect, OAuth sign-in or `cline auth`
     * success (§0.7): the screen-memory catalog is dropped, observers see [CatalogState.invalidations]
     * change, and, with [refresh], the state is refreshed in the background (catalog when the
     * helper is Ready, else `providers.json` presence for the fallback list).
     */
    fun invalidate(refresh: Boolean = true) {
        secretCatalog = null
        modelsRevision.incrementAndGet()
        explicitModels.clear()
        signalInvalidation()
        if (refresh) {
            scope.launch {
                if (helper.state.value is HelperState.Ready) resync() else refreshPresence()
            }
        }
    }

    private fun signalInvalidation() {
        _state.update { it.copy(invalidations = it.invalidations + 1) }
    }

    /** Drops the screen-memory catalog with its plaintext keys (the providers ViewModel's `onCleared()`). */
    fun releaseSecrets() {
        secretCatalog = null
    }

    /**
     * Home's provider/model choice reads [state] (S8.3 `resolveHomeSelection`), so a cold start
     * needs the catalog or, without the helper, the `providers.json` presence before the first
     * send (the desktop loads its catalog when Home mounts). Runs [load] once per process until
     * either is known; later calls are free. Failures leave the state for the next attempt.
     */
    suspend fun primeForHome() {
        ensureInitialized()
        val current = _state.value
        if (current.presence != null || secretCatalog != null || current.loading) return
        load()
    }

    /**
     * Re-reads `providers.json` (redacted in the bridge) and folds it into [state]; in fallback
     * the provider list is rebuilt from it. Termux failures leave the state unchanged.
     */
    suspend fun refreshPresence(): ProviderPresence? {
        ensureInitialized()
        val presence = try {
            presenceSource.read()
        } catch (e: TermuxUnavailable) {
            return null
        }
        applyPresence(presence)
        return presence
    }

    // ---- ProviderAuthCatalog (home-chat.md §1.3.5) -------------------------------------

    override fun liveProvider(providerId: String): CatalogAuthFacts? {
        val current = _state.value
        if (current.source != CatalogSource.Catalog) return null
        val item = current.provider(providerId) ?: return null
        return CatalogAuthFacts(
            capabilities = item.auth?.capabilities?.takeIf { it.isNotEmpty() } ?: item.capabilities.orEmpty(),
            localCliCommand = item.auth?.localCli?.command,
            docsUrl = item.auth?.localCli?.docsUrl,
        )
    }

    override fun cachedProvider(providerId: String): CatalogAuthFacts? =
        cachedProjection?.providers?.firstOrNull { it.id == providerId }?.authFacts()

    override fun fallbackAuthKind(providerId: String): String? = fallback?.authKind(providerId)

    override fun isConfigured(providerId: String): Boolean =
        _state.value.presence?.configuredIds?.contains(providerId) == true

    // ---- internals ---------------------------------------------------------------------

    private suspend fun fetchCatalog(): ProviderCatalogResponse {
        val element = helper.desktopCommand(COMMAND_LIST_PROVIDER_CATALOG, JsonObject(emptyMap()))
        return ProviderJson.decodeFromJsonElement(ProviderCatalogResponse.serializer(), element)
    }

    private fun applyCatalog(providers: List<ProviderListItem>) {
        secretCatalog = TimedCatalog(providers, clock.millis())
        _state.update { current ->
            val models = LinkedHashMap<String, List<ProviderModel>>()
            for (provider in providers) {
                val explicit = provider.id in explicitModels
                val list = if (explicit) current.modelsByProvider[provider.id] else provider.modelList
                if (list != null) models[provider.id] = list
            }
            current.copy(
                source = CatalogSource.Catalog,
                providers = providers.map(ProviderListItem::redacted),
                modelsByProvider = models,
                error = null,
            )
        }
        persist()
    }

    private fun publishModels(providerId: String, models: List<ProviderModel>) {
        explicitModels += providerId
        secretCatalog?.let { cached ->
            secretCatalog = TimedCatalog(
                cached.providers.map { if (it.id == providerId) it.copy(modelList = models, models = models.size) else it },
                cached.fetchedAtMs,
            )
        }
        _state.update { current ->
            current.copy(
                modelsByProvider = current.modelsByProvider + (providerId to models),
                providers = current.providers.map { if (it.id == providerId) it.copy(models = models.size) else it },
            )
        }
        persist()
    }

    private suspend fun loadFallback(): CatalogLoad {
        val gen = claimGeneration()
        val presence = try {
            presenceSource.read()
        } catch (e: TermuxUnavailable) {
            return CatalogLoad.Blocked(e)
        }
        if (gen != generationCounter.get()) return CatalogLoad.Superseded
        val bundle = fallback ?: FallbackProviders.EMPTY
        val projection = presence.providersOrEmpty
        val (providers, models) = buildFallback(bundle, projection)
        _state.update {
            it.copy(
                source = CatalogSource.Fallback,
                providers = providers,
                modelsByProvider = models,
                presence = projection,
                loading = false,
                error = (presence as? ProviderPresence.Failed)?.detail,
            )
        }
        return CatalogLoad.Loaded(providers, CatalogSource.Fallback)
    }

    private fun applyPresence(presence: ProviderPresence) {
        val projection = presence.providersOrEmpty
        _state.update { current ->
            if (current.source == CatalogSource.Fallback) {
                val (providers, models) = buildFallback(fallback ?: FallbackProviders.EMPTY, projection)
                current.copy(presence = projection, providers = providers, modelsByProvider = models)
            } else {
                current.copy(presence = projection)
            }
        }
    }

    private fun persist() {
        val snapshot = _state.value
        val projection = CatalogProjection(
            providers = snapshot.providers.map(ProjectedProvider::from),
            models = snapshot.modelsByProvider,
        )
        cachedProjection = projection
        scope.launch {
            writeMutex.withLock { runCatching { projectionStore.write(projection) } }
        }
    }

    companion object {
        /** `PROVIDER_CATALOG_CACHE_TTL_MS` (settings-view.tsx). */
        const val PROVIDER_CATALOG_CACHE_TTL_MS = 60_000L
        const val SAVE_FAILURE_RELOAD_ATTEMPTS = 3

        const val COMMAND_LIST_PROVIDER_CATALOG = "list_provider_catalog"
        const val COMMAND_LIST_PROVIDER_MODELS = "list_provider_models"
        const val COMMAND_SAVE_PROVIDER_SETTINGS = "save_provider_settings"
        const val COMMAND_ADD_PROVIDER = "add_provider"

        /** The desktop shows `error.message`; a dashboard error is its `error` text. */
        fun errorMessage(e: Throwable): String = when (e) {
            is DesktopCommandException -> e.error
            else -> e.message ?: e.javaClass.simpleName
        }

        /**
         * The §0.4 fallback list: every bundled provider plus any configured id the bundle does
         * not know (a custom provider), with the redacted `providers.json` flags. ClinePass is
         * enabled whenever Cline is (it stores its credentials under `cline`). Each model's
         * `supportsReasoning` is the bundle's A2 rule ([FallbackProviders.reasoningSupport]).
         */
        fun buildFallback(
            bundle: FallbackProviders,
            presence: ConfiguredProviders?,
        ): Pair<List<ProviderListItem>, Map<String, List<ProviderModel>>> {
            val entries = presence?.providers.orEmpty()
            val items = ArrayList<ProviderListItem>()
            val models = LinkedHashMap<String, List<ProviderModel>>()
            fun add(id: String, name: String, capabilities: List<String>, defaultModelId: String?) {
                val entry = entries[id] ?: if (id == CLINE_PASS_PROVIDER_ID) entries[CLINE_PROVIDER_ID] else null
                items += ProviderListItem(
                    id = id,
                    name = name,
                    enabled = entry != null,
                    configured = presence?.isConnected(id) == true ||
                        (id == CLINE_PASS_PROVIDER_ID && presence?.isConnected(CLINE_PROVIDER_ID) == true),
                    apiKeyPresent = entry?.hasApiKey == true,
                    oauthAccessTokenPresent = entry?.hasOAuth == true,
                    defaultModelId = defaultModelId,
                    capabilities = capabilities,
                    auth = ProviderAuthFacts(id, capabilities),
                )
                models[id] = fallbackModelIds(id, entry?.model).map { modelId ->
                    ProviderModel(id = modelId, name = modelId, supportsReasoning = bundle.reasoningSupport(id, modelId))
                }
            }
            for (row in bundle.providers) {
                val capabilities = buildList {
                    if (row.popular) add("popular")
                    when (row.authKind) {
                        FallbackProvider.AUTH_KIND_OAUTH -> add("oauth")
                        FallbackProvider.AUTH_KIND_LOCAL -> add("local-auth")
                    }
                }
                add(row.id, row.name, capabilities, row.defaultModelId)
            }
            val known = bundle.providers.map { it.id }.toSet()
            for (id in entries.keys) if (id !in known) add(id, id, emptyList(), entries[id]?.model)
            return items to models
        }

        const val CLINE_PROVIDER_ID = "cline"
        const val CLINE_PASS_PROVIDER_ID = "cline-pass"
    }
}
