package dev.cline.termux.ui.model

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.model.InMemoryModelSelectionPrefs
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ModelsLoad
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.providers.ProviderModelFeatured
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Catalog and helper in memory; records refreshes and the lease. */
class FakePickerCatalog(state: CatalogState, helper: HelperState = HelperState.Ready) : PickerCatalogPort {
    override val state = MutableStateFlow(state)
    override val helperState = MutableStateFlow(helper)
    val refreshed = mutableListOf<String>()
    val leases = mutableListOf<String>()
    var acquireResult = true

    override suspend fun refreshModels(providerId: String): ModelsLoad {
        refreshed += providerId
        return ModelsLoad.Loaded(providerId, state.value.modelsByProvider[providerId].orEmpty())
    }

    override suspend fun acquireHelper(owner: String): Boolean {
        leases += "acquire:$owner"
        if (acquireResult) helperState.value = HelperState.Ready
        return acquireResult
    }

    override suspend fun releaseHelper(owner: String) {
        leases += "release:$owner"
    }
}

/** Counts the work [ModelPickerViewModel] hands to its compute dispatcher (docs/07 §8). */
class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatches = 0
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches += 1
        delegate.dispatch(context, block)
    }
}

/** Session snapshots and scripted `session.update_connection` replies. */
class FakePickerSessions : PickerSessionPort {
    val snapshots = HashMap<String, MutableStateFlow<PickerSessionSnapshot>>()
    val updates = mutableListOf<Pair<String, ConnectionUpdate>>()
    val notLive = mutableListOf<String>()
    var failures = ArrayDeque<HubCommandException>()
    var drainEnds = true

    fun session(id: String, providerId: String, modelId: String, live: Boolean = true, busy: Boolean = false) {
        snapshots.getOrPut(id) { MutableStateFlow(PickerSessionSnapshot(providerId, modelId, busy, live)) }.value =
            PickerSessionSnapshot(providerId, modelId, busy, live)
    }

    override fun observe(sessionId: String): Flow<PickerSessionSnapshot> = snapshots.getValue(sessionId)

    override fun isLive(sessionId: String): Boolean = snapshots.getValue(sessionId).value.live

    override suspend fun updateConnection(sessionId: String, update: ConnectionUpdate) {
        updates += sessionId to update
        failures.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun markNotLive(sessionId: String) {
        notLive += sessionId
        snapshots[sessionId]?.let { it.value = it.value.copy(live = false) }
    }

    override suspend fun awaitNotDraining(): Boolean = drainEnds
}

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val cline = ProviderListItem("cline", "Cline", enabled = true, oauthAccessTokenPresent = true)
    private val openrouter = ProviderListItem("openrouter", "OpenRouter", enabled = true, apiKey = "k")
    private val anthropic = ProviderListItem("anthropic", "Anthropic", enabled = true, apiKey = "k")
    private val gemini = ProviderListItem("gemini", "Gemini")
    private val clineModels = listOf(
        ProviderModel("anthropic/claude-sonnet-5", "Claude Sonnet 5", "Best for agents", ProviderModelFeatured("recommended", 1, listOf("NEW")), inputModalities = listOf("text", "image"), supportsReasoning = true),
        ProviderModel("openai/gpt-5.5", "GPT-5.5", featured = ProviderModelFeatured("recommended", 2), supportsVision = true),
        ProviderModel("moonshotai/kimi-k2.5:free", "Kimi K2.5 Free", featured = ProviderModelFeatured("free", 1), supportsReasoning = true),
        ProviderModel("x-ai/grok-4", "Grok 4"),
        ProviderModel("openai/tts-1", "TTS", operation = "speech-generation"),
    )
    private val catalog = CatalogState(
        source = CatalogSource.Catalog,
        providers = listOf(cline, openrouter, anthropic, gemini),
        modelsByProvider = mapOf(
            "cline" to clineModels,
            "openrouter" to listOf(ProviderModel("or/b", "Bravo"), ProviderModel("or/a", "Alpha")),
            "anthropic" to listOf(ProviderModel("claude-sonnet-4-6")),
            "gemini" to listOf(ProviderModel("g-1")),
        ),
        initialized = true,
    )

    private val prefs = InMemoryModelSelectionPrefs()
    private val sessions = FakePickerSessions()

    private class Harness(val vm: ModelPickerViewModel, val effects: MutableList<ModelPickerEffect>) {
        val state get() = vm.uiState.value
    }

    private fun TestScope.harness(
        catalogPort: FakePickerCatalog = FakePickerCatalog(catalog),
        selection: ModelSelection = ModelSelection("cline", mapOf("cline" to "anthropic/claude-sonnet-5")),
        compute: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): Harness {
        prefs.selectionFlow.value = selection
        val vm = ModelPickerViewModel(catalogPort, ModelSelectionStore(prefs), sessions, compute)
        val effects = mutableListOf<ModelPickerEffect>()
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        return Harness(vm, effects)
    }

    @Test fun `home lists configured providers with chat models and the cline sections`() = runTest {
        val h = harness()
        val s = h.state
        assertEquals(PickerHost.Home, s.host)
        assertEquals(listOf("cline", "openrouter", "anthropic"), s.providers.map { it.id })
        assertTrue(s.providers.first().selected)
        assertEquals(listOf("recommended", "free", "all"), s.sections.map { it.id })
        val recommended = s.sections.first().rows
        assertEquals(listOf("anthropic/claude-sonnet-5", "openai/gpt-5.5"), recommended.map { it.id })
        assertEquals("NEW", recommended.first().tierBadge)
        assertTrue(recommended.first().images && recommended.first().reasoning)
        assertTrue(recommended[1].images)
        // The speech model is not a chat model.
        assertEquals(listOf("x-ai/grok-4"), s.sections.last().rows.map { it.id })
        assertEquals("anthropic/claude-sonnet-5", s.selectedModelId)
        assertFalse(s.canRefresh)
    }

    @Test fun `home model pick is remembered and closes the sheet`() = runTest {
        val h = harness()
        h.vm.pickModel("moonshotai/kimi-k2.5:free")
        settle()
        assertEquals("moonshotai/kimi-k2.5:free", prefs.selectionFlow.value.modelFor("cline"))
        assertEquals(
            listOf(ModelPickerEffect.Result(ModelPickerResult.Selected(PickerHost.Home, "cline", "moonshotai/kimi-k2.5:free")), ModelPickerEffect.Close),
            h.effects,
        )
        assertEquals("moonshotai/kimi-k2.5:free", h.state.selectedModelId)
    }

    @Test fun `home provider chip resolves the remembered model else the first offered and stays open`() = runTest {
        val h = harness(selection = ModelSelection("cline", mapOf("anthropic" to "claude-sonnet-4-6")))
        h.vm.pickProvider("openrouter")
        settle()
        // handleProviderSelect: the first provider model (catalog order) the picker offers.
        assertEquals("or/b", prefs.selectionFlow.value.modelFor("openrouter"))
        assertEquals("openrouter", prefs.selectionFlow.value.lastProvider)
        assertEquals(listOf(ModelPickerEffect.Result(ModelPickerResult.Selected(PickerHost.Home, "openrouter", "or/b"))), h.effects)
        assertEquals(listOf("Alpha", "Bravo"), h.state.sections.single().rows.map { it.label })
        h.vm.pickProvider("anthropic")
        settle()
        assertEquals("claude-sonnet-4-6", prefs.selectionFlow.value.modelFor("anthropic"))
    }

    @Test fun `live session pick sends update_connection and shows the new model`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5")
        val h = harness()
        h.vm.setSessionHost("s1")
        h.vm.pickModel("openai/gpt-5.5")
        settle()
        assertEquals(listOf("s1" to ConnectionUpdate(providerId = "cline", modelId = "openai/gpt-5.5")), sessions.updates)
        assertEquals(ModelPickerEffect.Close, h.effects.last())
        assertEquals(ModelPickerResult.Selected(PickerHost.Session("s1", true), "cline", "openai/gpt-5.5"), (h.effects.first() as ModelPickerEffect.Result).result)
        assertEquals("openai/gpt-5.5", h.state.selectedModelId)
        assertNull(h.state.pendingModelId)
    }

    @Test fun `a not-live session stores a pending connection without sending`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5", live = false)
        val h = harness()
        h.vm.setSessionHost("s1")
        assertEquals(PickerHost.Session("s1", live = false), h.state.host)
        h.vm.pickModel("x-ai/grok-4")
        settle()
        assertTrue(sessions.updates.isEmpty())
        assertEquals(
            listOf(
                ModelPickerEffect.Result(ModelPickerResult.PendingConnection("s1", ConnectionUpdate(providerId = "cline", modelId = "x-ai/grok-4"))),
                ModelPickerEffect.Close,
            ),
            h.effects,
        )
        assertEquals("x-ai/grok-4", h.state.selectedModelId)
    }

    @Test fun `command_failed session not found marks not live and becomes pending with no error`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5")
        sessions.failures.addLast(HubCommandException("session.update_connection", HubErrorCodes.COMMAND_FAILED, "session not found: s1"))
        val h = harness()
        h.vm.setSessionHost("s1")
        h.vm.pickModel("x-ai/grok-4")
        settle()
        assertEquals(listOf("s1"), sessions.notLive)
        assertTrue(h.effects.none { it is ModelPickerEffect.SwitchFailed })
        assertTrue(h.effects.any { it is ModelPickerEffect.Result && it.result is ModelPickerResult.PendingConnection })
        assertEquals(ModelPickerEffect.Close, h.effects.last())
    }

    @Test fun `hub_draining waits for the drain to end and sends again`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5")
        sessions.failures.addLast(HubCommandException("session.update_connection", HubErrorCodes.HUB_DRAINING, "draining"))
        val h = harness()
        h.vm.setSessionHost("s1")
        h.vm.pickModel("x-ai/grok-4")
        settle()
        assertEquals(2, sessions.updates.size)
        assertEquals(ModelPickerEffect.Close, h.effects.last())
    }

    @Test fun `other errors keep the sheet open with the switch failure`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5")
        sessions.failures.addLast(HubCommandException("session.update_connection", "invalid_model", "model unavailable"))
        val h = harness()
        h.vm.setSessionHost("s1")
        h.vm.pickModel("x-ai/grok-4")
        settle()
        assertEquals(listOf(ModelPickerEffect.SwitchFailed("model unavailable")), h.effects)
        assertEquals("anthropic/claude-sonnet-5", h.state.selectedModelId)
        assertNull(h.state.pendingModelId)
        assertTrue(h.state.interactive)
    }

    @Test fun `a running session disables the picker and ignores picks`() = runTest {
        sessions.session("s1", "cline", "anthropic/claude-sonnet-5", busy = true)
        val h = harness()
        h.vm.setSessionHost("s1")
        assertTrue(h.state.busy)
        assertFalse(h.state.interactive)
        h.vm.pickModel("x-ai/grok-4")
        h.vm.pickProvider("openrouter")
        settle()
        assertTrue(sessions.updates.isEmpty())
        assertTrue(h.effects.isEmpty())
    }

    @Test fun `search filters rows keeps headers and offers a free-text id`() = runTest {
        val h = harness()
        h.vm.setQuery("grok")
        assertEquals(listOf("all"), h.state.sections.map { it.id })
        assertNull(h.state.freeTextRow)
        h.vm.setQuery("my-custom-model")
        assertTrue(h.state.sections.isEmpty())
        assertEquals("my-custom-model", h.state.freeTextRow)
        assertFalse(h.state.noModels)
        h.vm.setQuery("no such model")
        assertNull(h.state.freeTextRow)
        assertTrue(h.state.noModels)
        h.vm.pickModel("my-custom-model")
        settle()
        assertEquals("my-custom-model", prefs.selectionFlow.value.modelFor("cline"))
    }

    @Test fun `a selected model outside a flat list is appended as an ordinary row`() = runTest {
        val h = harness(selection = ModelSelection("openrouter", mapOf("openrouter" to "or/legacy")))
        val sections = h.state.sections
        assertEquals(listOf(null), sections.map { it.id })
        assertEquals(listOf("or/a", "or/b", "or/legacy"), sections.single().rows.map { it.id })
        assertNull(sections.single().rows.last().section)
    }

    @Test fun `open refreshes the active provider only when the helper already runs`() = runTest {
        val idle = FakePickerCatalog(catalog, HelperState.Idle)
        val h = harness(idle)
        h.vm.onOpened()
        settle()
        assertTrue(idle.refreshed.isEmpty())
        assertTrue(h.state.canRefresh)
        assertTrue(idle.leases.isEmpty())

        val ready = FakePickerCatalog(catalog)
        val h2 = harness(ready)
        h2.vm.onOpened()
        settle()
        assertEquals(listOf("cline"), ready.refreshed)
    }

    @Test fun `refresh models leases the helper then releases it`() = runTest {
        val port = FakePickerCatalog(catalog.copy(source = CatalogSource.Fallback), HelperState.Idle)
        val h = harness(port)
        assertTrue(h.state.showFallbackNotice)
        h.vm.refreshModels()
        settle()
        assertEquals(listOf("acquire:${ModelPickerViewModel.LEASE_OWNER}", "release:${ModelPickerViewModel.LEASE_OWNER}"), port.leases)
        assertEquals(listOf("cline"), port.refreshed)
        assertFalse(h.state.loading)
    }

    @Test fun `first run shows no providers`() = runTest {
        val h = harness(FakePickerCatalog(CatalogState(source = CatalogSource.Fallback, providers = listOf(gemini), initialized = true)), ModelSelection())
        assertTrue(h.state.noProviders)
        assertFalse(h.state.noModels)
        assertFalse(h.state.showFallbackNotice)
    }

    @Test fun `reasoning picks are stored and sent per host`() = runTest {
        val h = harness()
        h.vm.pickReasoning(ReasoningEffort.High)
        settle()
        assertEquals("high", prefs.reasoningFlow.value["cline"])
        assertEquals(ModelPickerEffect.Result(ModelPickerResult.ReasoningSelected(PickerHost.Home, "cline", ReasoningEffort.High)), h.effects.single())

        sessions.session("s1", "cline", "anthropic/claude-sonnet-5")
        h.vm.setSessionHost("s1")
        h.vm.pickReasoning(ReasoningEffort.None)
        settle()
        assertEquals(ConnectionUpdate(thinking = false, reasoningEffort = null), sessions.updates.single().second)

        sessions.session("s2", "cline", "anthropic/claude-sonnet-5", live = false)
        h.vm.setSessionHost("s2")
        h.vm.pickReasoning(ReasoningEffort.XHigh)
        settle()
        assertEquals(
            ModelPickerResult.PendingConnection("s2", ConnectionUpdate(thinking = true, reasoningEffort = "xhigh")),
            (h.effects.last() as ModelPickerEffect.Result).result,
        )
        assertEquals(1, sessions.updates.size)
    }

    @Test fun `picker state and provider resolution are built on the compute dispatcher`() = runTest {
        val compute = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val h = harness(compute = compute)
        assertEquals(listOf("recommended", "free", "all"), h.state.sections.map { it.id })
        val afterStart = compute.dispatches
        assertTrue(afterStart > 0)

        h.vm.setQuery("grok")
        settle()
        assertEquals(listOf("all"), h.state.sections.map { it.id })
        assertTrue(compute.dispatches > afterStart)

        val beforePick = compute.dispatches
        h.vm.pickProvider("openrouter")
        settle()
        assertEquals("or/b", prefs.selectionFlow.value.modelFor("openrouter"))
        assertTrue(compute.dispatches > beforePick)
    }

    @Test fun `a models refresh rebuilds the rows while keystrokes reuse them`() = runTest {
        val port = FakePickerCatalog(catalog)
        val h = harness(port)
        h.vm.setQuery("grok")
        assertEquals(listOf("x-ai/grok-4"), h.state.sections.single().rows.map { it.id })
        // An unrelated catalog change keeps the list instance, so the same rows come back.
        port.state.update { it.copy(loading = true) }
        assertEquals(listOf("x-ai/grok-4"), h.state.sections.single().rows.map { it.id })
        // `list_provider_models` replaced the provider's list: the new model is offered.
        port.state.update { it.copy(loading = false, modelsByProvider = it.modelsByProvider + ("cline" to clineModels + ProviderModel("x-ai/grok-5", "Grok 5"))) }
        assertEquals(listOf("x-ai/grok-4", "x-ai/grok-5"), h.state.sections.single().rows.map { it.id })
    }
}
