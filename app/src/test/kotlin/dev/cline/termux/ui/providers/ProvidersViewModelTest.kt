@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.cline.termux.ui.providers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.catalog.FallbackProvider
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.providers.PresenceSource
import dev.cline.termux.data.providers.ProviderCatalogRepository
import dev.cline.termux.fakes.FakeCatalogHelper
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.InMemoryProjectionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** settings-providers.md S4–S6 state, the helper lease, drafts, fallback saves and secrets. */
class ProvidersViewModelTest {

    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeHelper(initial: HelperState) : ProvidersHelper {
        override val state = MutableStateFlow(initial)
        val leases = mutableListOf<String>()
        var retries = 0
        override fun acquire(owner: String) {
            leases += "+$owner"
        }
        override fun release(owner: String) {
            leases += "-$owner"
        }
        override fun retry() {
            retries += 1
        }
    }

    private class FakeSignIn : ProvidersSignIn {
        override val state = MutableStateFlow<SignInState>(SignInState.Idle)
        val started = mutableListOf<String>()
        var signOuts = 0
        var acknowledged = 0
        override fun start(providerId: String) {
            started += providerId
        }
        override fun cancel() = Unit
        override fun openTermux(): Boolean = true
        override suspend fun signOut(providerId: String): Result<Unit> {
            signOuts += 1
            return Result.success(Unit)
        }
        override fun acknowledge() {
            acknowledged += 1
            state.value = SignInState.Idle
        }
    }

    private class FakeFavorites : FavoriteModelsStore {
        override val favorites = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        override suspend fun setFavorite(providerId: String, modelId: String, favorite: Boolean) {
            val current = favorites.value[providerId].orEmpty()
            favorites.value = favorites.value + (providerId to if (favorite) current + modelId else current - modelId)
        }
    }

    private val catalogHelper = FakeCatalogHelper()
    private val helper = FakeHelper(HelperState.Ready)
    private val signIn = FakeSignIn()
    private val favorites = FakeFavorites()
    private val termux = FakeTermuxExecutor()
    private val savedState = SavedStateHandle()
    private var kernelStarts = 0

    private val anthropic = """{"id":"anthropic","name":"Anthropic","enabled":true,"configured":true,"apiKey":"sk-secret",
        "capabilities":["popular"],"configFields":[{"path":"apiKey","label":"API Key","type":"password"},
        {"path":"region","label":"Region","type":"text"}],"configValues":{"region":"us"}}"""
    private val openrouter = """{"id":"openrouter","name":"OpenRouter","enabled":false,"capabilities":["popular"],
        "configFields":[{"path":"apiKey","label":"API Key","type":"password"}]}"""
    private val cline = """{"id":"cline","name":"Cline","enabled":true,"oauthAccessTokenPresent":true,"capabilities":["oauth"]}"""

    private fun catalogJson(vararg providers: String) =
        Json.parseToJsonElement("""{"providers":[${providers.joinToString(",")}],"settingsPath":"/s"}""")

    private val bundle = FallbackProviders(
        listOf(
            FallbackProvider("anthropic", "Anthropic", popular = true, authKind = "api-key", defaultModelId = "claude-sonnet-5"),
            FallbackProvider("openai-compatible", "OpenAI Compatible", authKind = "api-key", supportsBaseUrl = true),
            FallbackProvider("cline", "Cline", popular = true, authKind = "oauth", defaultModelId = "anthropic/claude-sonnet-5"),
            FallbackProvider("openai-codex", "OpenAI ChatGPT Subscription", popular = true, authKind = "oauth"),
            FallbackProvider("claude-code", "Claude Code", authKind = "local"),
        ),
    )

    /** What `cat providers.json` returns (the redacted projection is built from it). */
    private var presence: ProviderPresence = ProviderPresence.Missing

    private fun TestScope.newViewModel(compute: CoroutineDispatcher = main): ProvidersViewModel {
        val repository = ProviderCatalogRepository(
            helper = catalogHelper,
            projectionStore = InMemoryProjectionStore(),
            presenceSource = PresenceSource { presence },
            fallbackLoader = { bundle },
            scope = backgroundScope,
            clock = FakeClock(),
        )
        val vm = ProvidersViewModel(
            savedState = savedState,
            catalog = repository,
            helper = helper,
            signIn = signIn,
            favoritesStore = favorites,
            termux = termux,
            kernelStarter = KernelStarter { kernelStarts += 1 },
            workScope = backgroundScope,
            compute = compute,
        )
        backgroundScope.launch { vm.listState.collect {} }
        backgroundScope.launch { vm.addState.collect {} }
        return vm
    }

    private fun <T> TestScope.watch(flow: StateFlow<T>): StateFlow<T> {
        backgroundScope.launch { flow.collect {} }
        return flow
    }

    private fun TestScope.events(vm: ProvidersViewModel): MutableList<ProvidersEvent> {
        val list = mutableListOf<ProvidersEvent>()
        backgroundScope.launch { vm.events.collect { list += it } }
        return list
    }

    @Test fun `the lease is held for the VM's life and the list loads from the helper`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, openrouter, cline))
        val vm = newViewModel()
        runCurrent()
        assertEquals(listOf("+providers"), helper.leases)
        val list = vm.listState.value
        assertFalse(list.loading)
        assertEquals(2, list.configuredCount)
        assertEquals(3, list.totalCount)
        assertEquals(listOf("anthropic", "cline"), list.rows.configured.map { it.id })
        assertEquals(listOf("openrouter"), list.rows.popular.map { it.id })
        assertTrue(list.showFab)

        val store = ViewModelStore()
        store.put("providers", vm)
        store.clear()
        assertEquals(listOf("+providers", "-providers"), helper.leases)
        assertEquals("", vm.storedSecret("anthropic", "apiKey"))
    }

    @Test fun `the detail seeds with the pending placeholder and builds on the compute dispatcher`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        val compute = StandardTestDispatcher(testScheduler)
        val vm = newViewModel(compute)
        val detail = vm.detail("anthropic")
        // The seed is the cheap placeholder: nothing was built in composition.
        assertEquals(ProviderDetailUiState.pending("anthropic"), detail.value)
        assertTrue(detail.value.pending)
        assertTrue(detail.value.loading)
        assertTrue(detail.value.fields.isEmpty())
        watch(detail)
        // Subscribed, but the build is queued on compute rather than run on Main.
        assertTrue(detail.value.pending)
        runCurrent()
        assertFalse(detail.value.pending)
        assertEquals("Anthropic", detail.value.name)
        assertEquals(listOf("apiKey", "region"), detail.value.fields.map { it.path })
        // The same flow is handed back, so a re-entry keeps the last built value.
        assertTrue(vm.detail("anthropic") === detail)
        assertFalse(vm.detail("anthropic").value.pending)
    }

    @Test fun `the list builds on the compute dispatcher`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, openrouter))
        val compute = StandardTestDispatcher(testScheduler)
        val vm = newViewModel(compute)
        assertTrue(vm.listState.value.loading)
        runCurrent()
        assertFalse(vm.listState.value.loading)
        assertEquals(2, vm.listState.value.totalCount)
    }

    @Test fun `secrets stay out of every UiState and the SavedStateHandle`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        val vm = newViewModel()
        val detail = watch(vm.detail("anthropic"))
        runCurrent()
        assertEquals("sk-secret", vm.storedSecret("anthropic", "apiKey"))
        assertTrue(detail.value.apiKeyPresent)
        assertEquals("", detail.value.fields.first { it.path == "apiKey" }.storedText)
        assertEquals("us", detail.value.fields.first { it.path == "region" }.storedText)
        assertFalse(detail.value.toString().contains("sk-secret"))
        assertFalse(vm.listState.value.toString().contains("sk-secret"))
        vm.updateAddForm { it.copy(name = "x") }
        assertFalse(savedState.keys().any { savedState.get<Any>(it).toString().contains("sk-secret") })
    }

    @Test fun `a blur saves only a changed draft`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val vm = newViewModel()
        runCurrent()
        vm.commitField("anthropic", "region", " us ")
        runCurrent()
        assertTrue(catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).isEmpty())

        vm.commitField("anthropic", "apiKey", "sk-new")
        runCurrent()
        val save = catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single()
        assertEquals("anthropic", save.args["provider"]?.jsonPrimitive?.content)
        assertEquals("sk-new", vm.storedSecret("anthropic", "apiKey"))
    }

    @Test fun `leaving the detail commits each dirty draft once`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val vm = newViewModel()
        runCurrent()
        vm.commitPendingDrafts("anthropic", mapOf("region" to "eu", "apiKey" to "sk-secret"))
        runCurrent()
        val saves = catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS)
        assertEquals(1, saves.size)
        assertTrue(saves.single().args.toString().contains("eu"))
    }

    @Test fun `a failed save reports, reloads and remounts the drafts`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.fail(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, "disk full")
        val vm = newViewModel()
        val events = events(vm)
        val detail = watch(vm.detail("anthropic"))
        runCurrent()
        val before = detail.value.resetKey
        vm.commitField("anthropic", "region", "eu")
        runCurrent()
        val failure = events.filterIsInstance<ProvidersEvent.SaveFailed>().single()
        assertEquals("anthropic", failure.providerId)
        assertTrue(failure.message.contains("disk full"))
        assertEquals(before + 1, detail.value.resetKey)
        assertFalse(detail.value.fields.single { it.path == "region" }.saving)
    }

    @Test fun `search clears the collapsed sections and both survive in saved state`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, openrouter))
        val vm = newViewModel()
        runCurrent()
        vm.toggleSection(ProviderSection.All)
        assertEquals(setOf(ProviderSection.All), vm.listState.value.collapsed)
        assertEquals(arrayListOf("All"), savedState.get<ArrayList<String>>(ProvidersViewModel.KEY_COLLAPSED))
        vm.setQuery("open")
        assertEquals(emptySet<ProviderSection>(), vm.listState.value.collapsed)
        assertEquals(listOf("openrouter"), vm.listState.value.rows.popular.map { it.id })
        vm.setQuery("zzz")
        assertTrue(vm.listState.value.emptySearch)
    }

    @Test fun `without the helper the detail is the fallback form and cline auth saves it`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.StartTimeout, "boom")
        termux.on("cline auth openai-compatible", TermuxResult.success("ok"))
        val vm = newViewModel()
        val events = events(vm)
        val detail = watch(vm.detail("openai-compatible"))
        runCurrent()
        assertTrue(detail.value.fallback)
        assertTrue(detail.value.fallbackForm!!.showBaseUrl)
        assertTrue(detail.value.fallbackForm!!.keylessHint)
        assertTrue(vm.listState.value.fallback)
        assertTrue(vm.listState.value.helperCard != null)

        vm.saveFallback("openai-compatible", " none ", "gpt-4o", "http://localhost:8080/v1")
        runCurrent()
        val command = termux.commandsLabelled("cline auth openai-compatible").single()
        assertEquals(
            listOf("auth", "--provider", "openai-compatible", "--apikey", "none", "--modelid", "gpt-4o", "--baseurl", "http://localhost:8080/v1"),
            command.args,
        )
        assertFalse(command.toString().contains("none"))
        assertEquals(ProvidersEvent.ProviderConfigured("openai-compatible", "gpt-4o"), events.single())
        assertEquals(FallbackSaveState.Done, detail.value.fallbackSave)
    }

    @Test fun `in fallback OAuth providers keep browser sign-in and only key providers get the form alone`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.BuildUnsupported, "")
        catalogHelper.state.value = HelperState.Unavailable(HelperFailure.BuildUnsupported, "")
        val vm = newViewModel()
        val clineDetail = watch(vm.detail("cline"))
        val codex = watch(vm.detail("openai-codex"))
        val local = watch(vm.detail("claude-code"))
        val anthropicDetail = watch(vm.detail("anthropic"))
        runCurrent()

        assertEquals(ConnectionVariant.OAuthSignIn, clineDetail.value.connection)
        assertTrue(clineDetail.value.canSignIn)
        assertTrue(clineDetail.value.fallbackForm != null)
        assertFalse(clineDetail.value.fallbackForm!!.showBaseUrl)
        vm.startSignIn("cline")
        assertEquals(listOf("cline"), signIn.started)

        assertEquals(ConnectionVariant.OAuthSignIn, codex.value.connection)
        assertTrue(codex.value.canSignIn)
        assertNull(codex.value.fallbackForm)

        assertEquals(ConnectionVariant.Local, local.value.connection)
        assertNull(local.value.fallbackForm)
        assertFalse(local.value.showConnect)

        assertEquals(ConnectionVariant.ApiKey, anthropicDetail.value.connection)
        assertTrue(anthropicDetail.value.fallbackForm != null)
        assertFalse(anthropicDetail.value.canDisconnect)
    }

    @Test fun `in fallback the browser-vs-key variant comes from providers json`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.KernelStopped, "")
        catalogHelper.state.value = HelperState.Unavailable(HelperFailure.KernelStopped, "")
        presence = ProviderPresence.Present(
            ConfiguredProviders.parse(
                """{"providers":{
                    "cline":{"settings":{"provider":"cline","apiKey":"sk-cline","model":"anthropic/claude-sonnet-5"},"tokenSource":"manual"},
                    "openai-codex":{"settings":{"provider":"openai-codex","auth":{"accessToken":"tok"}},"tokenSource":"oauth"}
                }}""",
            ),
        )
        val vm = newViewModel()
        val clineDetail = watch(vm.detail("cline"))
        val codex = watch(vm.detail("openai-codex"))
        runCurrent()

        assertTrue(clineDetail.value.connected)
        assertEquals(ConnectionVariant.OAuthWithApiKey, clineDetail.value.connection)
        assertTrue(clineDetail.value.fallbackForm!!.apiKeySaved)
        assertFalse(clineDetail.value.signOutAction)

        assertTrue(codex.value.connected)
        assertTrue(codex.value.oauthSignedIn)
        assertEquals(ConnectionVariant.OAuthSignedIn, codex.value.connection)
        assertNull(codex.value.fallbackForm)
    }

    @Test fun `a failed cline auth shows the last stderr line, or the exit code`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.StartTimeout, "boom")
        termux.on("cline auth anthropic", TermuxResult.failure(1, "warning\n\u001B[31mError: invalid key\u001B[0m\n"))
        val vm = newViewModel()
        val detail = watch(vm.detail("anthropic"))
        runCurrent()
        vm.saveFallback("anthropic", "sk", "claude-sonnet-5", null)
        runCurrent()
        assertEquals(FallbackSaveState.Error(FallbackError.Message("Error: invalid key")), detail.value.fallbackSave)

        termux.on("cline auth anthropic", TermuxResult.failure(3, ""))
        vm.saveFallback("anthropic", "sk", "claude-sonnet-5", null)
        runCurrent()
        assertEquals(FallbackSaveState.Error(FallbackError.ExitCode(3)), detail.value.fallbackSave)
    }

    @Test fun `adding a provider sends the key once, resets the form and selects the new id`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_ADD_PROVIDER, JsonObject(emptyMap()))
        val vm = newViewModel()
        val events = events(vm)
        runCurrent()
        vm.updateAddForm { it.copy(providerId = "Anthropic") }
        assertTrue(vm.addState.value.duplicate)
        assertFalse(vm.addState.value.canSave)
        vm.updateAddForm { it.copy(providerId = "My Gateway", name = "Gateway", baseUrl = "https://gw/v1").typeModelInput("m1,") }
        assertTrue(vm.addState.value.canSave)
        val stored = savedState.get<String>(ProvidersViewModel.KEY_ADD_FORM)!!
        assertTrue(stored.contains("Gateway"))

        vm.saveNewProvider("sk-gw")
        runCurrent()
        val call = catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_ADD_PROVIDER).single()
        assertTrue(call.args.toString().contains("my-gateway"))
        assertTrue(call.args.toString().contains("sk-gw"))
        assertEquals(ProvidersEvent.ProviderAdded("my-gateway"), events.single())
        assertEquals(AddProviderForm.EMPTY, vm.addState.value.form)
        assertEquals("my-gateway", savedState.get<String>(ProvidersViewModel.KEY_SELECTED))
    }

    @Test fun `an add failure stays on the form with the message`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.fail(ProviderCatalogRepository.COMMAND_ADD_PROVIDER, "bad url")
        val vm = newViewModel()
        runCurrent()
        vm.updateAddForm { it.copy(providerId = "gw", name = "Gateway", baseUrl = "x", modelsSourceUrl = "y") }
        vm.saveNewProvider("")
        runCurrent()
        assertFalse(vm.addState.value.saving)
        assertTrue(vm.addState.value.error!!.contains("bad url"))
        assertEquals("gw", vm.addState.value.form.providerId)
        vm.updateAddForm { it.copy(name = "Gateway 2") }
        assertNull(vm.addState.value.error)
    }

    @Test fun `sign in from the detail waits, then reports success once`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, cline))
        val vm = newViewModel()
        val events = events(vm)
        val detail = watch(vm.detail("cline-pass"))
        runCurrent()
        assertTrue(detail.value.canSignIn)
        vm.startSignIn("cline-pass")
        assertEquals(listOf("cline-pass"), signIn.started)
        signIn.state.value = SignInState.WaitingForTermux("cline", 0L)
        assertEquals(DetailSignIn.Waiting, detail.value.signIn)
        signIn.state.value = SignInState.Succeeded("cline", SignInOrigin.ProviderDetail, viaApiKey = false, verified = true)
        runCurrent()
        assertEquals(listOf(ProvidersEvent.SignedIn("cline")), events)
        assertEquals(1, signIn.acknowledged)
        assertEquals(DetailSignIn.Idle, detail.value.signIn)
    }

    @Test fun `providers without a CLI sign-in cannot start one`() {
        assertNull(ProvidersViewModel.signInId("anthropic"))
        assertEquals("cline", ProvidersViewModel.signInId("cline-pass"))
        assertEquals("openai-codex", ProvidersViewModel.signInId("openai-codex"))
    }

    @Test fun `signing out of cline goes through the coordinator, others save enabled false`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, cline))
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val vm = newViewModel()
        val detail = watch(vm.detail("anthropic"))
        runCurrent()
        vm.requestDisconnect("cline")
        vm.confirmDisconnect("cline")
        runCurrent()
        assertEquals(1, signIn.signOuts)
        assertTrue(catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).isEmpty())

        val before = detail.value.resetKey
        vm.requestDisconnect("anthropic")
        assertTrue(detail.value.confirmDisconnect)
        vm.confirmDisconnect("anthropic")
        runCurrent()
        val save = catalogHelper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single()
        assertEquals(JsonPrimitive(false), save.args["enabled"])
        assertFalse(detail.value.confirmDisconnect)
        assertEquals(before + 1, detail.value.resetKey)
    }

    @Test fun `an open detail whose provider disappears reports it`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic, openrouter))
        val vm = newViewModel()
        val events = events(vm)
        runCurrent()
        vm.openDetail("openrouter")
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        vm.retryLoad()
        runCurrent()
        assertEquals(listOf(ProvidersEvent.ProviderVanished("openrouter")), events)
    }

    @Test fun `models load once the detail opens, favourites sort first and copied clears`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        catalogHelper.respond(
            ProviderCatalogRepository.COMMAND_LIST_PROVIDER_MODELS,
            Json.parseToJsonElement("""{"providerId":"anthropic","models":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}"""),
        )
        val vm = newViewModel()
        val detail = watch(vm.detail("anthropic"))
        runCurrent()
        vm.openDetail("anthropic")
        runCurrent()
        assertEquals(listOf("a", "b"), detail.value.models.rows.map { it.id })
        assertTrue(detail.value.models.searchVisible)
        vm.toggleFavorite("anthropic", "b")
        runCurrent()
        assertEquals(listOf("b", "a"), detail.value.models.rows.map { it.id })
        vm.markCopied("anthropic", "a")
        assertEquals("a", detail.value.models.copiedId)
        advanceTimeBy(COPIED_MODEL_MS + 1)
        assertNull(detail.value.models.copiedId)
    }

    @Test fun `the stopped-kernel card starts the kernel and retries the helper`() = runTest {
        catalogHelper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropic))
        val vm = newViewModel()
        runCurrent()
        vm.startKernel()
        runCurrent()
        assertEquals(1, kernelStarts)
        assertEquals(1, helper.retries)
    }
}
