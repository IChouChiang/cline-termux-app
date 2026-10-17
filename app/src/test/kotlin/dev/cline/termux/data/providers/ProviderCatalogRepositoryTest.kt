@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.cline.termux.data.providers

import kotlinx.coroutines.ExperimentalCoroutinesApi

import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.catalog.FallbackProvider
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.fakes.FakeCatalogHelper
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.InMemoryProjectionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP §0.7: TTL, generation counter, silent resync, failed-save retry, projection, fallback, auth catalog. */
class ProviderCatalogRepositoryTest {

    private val clock = FakeClock()
    private val helper = FakeCatalogHelper()
    private val store = InMemoryProjectionStore()
    private var presence: ProviderPresence = ProviderPresence.Missing
    private var presenceError: TermuxUnavailable? = null
    private var presenceReads = 0

    private val bundle = FallbackProviders(
        listOf(
            FallbackProvider("cline", "Cline Usage-Billing", popular = true, authKind = "oauth", defaultModelId = "anthropic/claude-sonnet-5"),
            FallbackProvider("cline-pass", "ClinePass", popular = true, authKind = "oauth"),
            FallbackProvider("anthropic", "Anthropic", popular = true, authKind = "api-key", defaultModelId = "claude-sonnet-5"),
            FallbackProvider("claude-code", "Claude Code", authKind = "local"),
            FallbackProvider("openai-compatible", "OpenAI Compatible", authKind = "api-key", supportsBaseUrl = true),
        ),
    )

    private fun TestScope.repository() = ProviderCatalogRepository(
        helper = helper,
        projectionStore = store,
        presenceSource = PresenceSource {
            presenceReads += 1
            presenceError?.let { throw it }
            presence
        },
        fallbackLoader = { bundle },
        scope = backgroundScope,
        clock = clock,
    )

    private fun catalogJson(vararg providers: String) = Json.parseToJsonElement("""{"providers":[${providers.joinToString(",")}],"settingsPath":"/s"}""")

    private val anthropicWithKey = """{"id":"anthropic","name":"Anthropic","enabled":true,"configured":true,"apiKey":"sk-secret",
        "capabilities":["popular"],"modelList":[{"id":"claude-sonnet-5","name":"Claude Sonnet 5","contextWindow":200000,"supportsReasoning":true}],
        "auth":{"providerId":"anthropic","capabilities":["popular"]}}"""
    private val clineSignedIn = """{"id":"cline","name":"Cline","enabled":true,"oauthAccessTokenPresent":true,"capabilities":["oauth"],
        "modelList":[{"id":"anthropic/claude-sonnet-5","name":"Sonnet","featured":{"tier":"recommended","rank":0,"tags":["NEW"]}}]}"""
    private val claudeCode = """{"id":"claude-code","name":"Claude Code","enabled":false,"capabilities":["local-auth"],
        "auth":{"providerId":"claude-code","capabilities":["local-auth"],"localCli":{"command":"claude","docsUrl":"https://docs"}}}"""

    private fun presenceOf(json: String) = ProviderPresence.Present(ConfiguredProviders.parse(json))

    @Test fun `without a cache file the state starts as the bundled fallback`() = runTest {
        val repo = repository()
        repo.ensureInitialized()
        val state = repo.state.value
        assertTrue(state.initialized)
        assertEquals(CatalogSource.Fallback, state.source)
        assertEquals(listOf("cline", "cline-pass", "anthropic", "claude-code", "openai-compatible"), state.providers.map { it.id })
        assertEquals(listOf("anthropic/claude-sonnet-5"), state.modelsByProvider["cline"]?.map { it.id })
        assertFalse(state.anyConnected)
        assertEquals(0, presenceReads)
    }

    @Test fun `a cache file opens the picker instantly without the helper`() = runTest {
        store.value = CatalogProjection(
            providers = listOf(ProjectedProvider("anthropic", "Anthropic", enabled = true, configured = true, apiKeyPresent = true, capabilities = listOf("popular"))),
            models = mapOf("anthropic" to listOf(ProviderModel("claude-sonnet-5"))),
        )
        val repo = repository()
        repo.ensureInitialized()
        assertEquals(CatalogSource.Cache, repo.state.value.source)
        assertTrue(repo.configured("anthropic"))
        assertEquals(listOf("anthropic"), repo.state.value.enabledProviderIds)
        assertNull(repo.liveProvider("anthropic"))
        assertEquals(listOf("popular"), repo.cachedProvider("anthropic")?.capabilities)
    }

    @Test fun `a helper load publishes a redacted state and persists a projection without secrets`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey, clineSignedIn))
        val repo = repository()
        val load = repo.load()
        runCurrent()
        load as CatalogLoad.Loaded
        assertEquals("sk-secret", load.providers.first { it.id == "anthropic" }.apiKey)
        val state = repo.state.value
        assertEquals(CatalogSource.Catalog, state.source)
        assertNull(state.provider("anthropic")?.apiKey)
        assertTrue(state.configured("anthropic"))
        assertTrue(state.configured("cline"))
        assertEquals(200_000L, state.modelsByProvider["anthropic"]?.single()?.contextWindow)
        val written = Json.encodeToString(CatalogProjection.serializer(), store.value!!)
        assertFalse(written.contains("sk-secret"))
        assertFalse(written.contains("apiKey\""))
        assertFalse(written.contains("baseUrl"))
        assertTrue(store.value!!.providers.first { it.id == "anthropic" }.apiKeyPresent)
        assertFalse(state.toString().contains("sk-secret"))
    }

    @Test fun `loads inside the 60 s TTL reuse the catalog, later ones refetch`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey))
        val repo = repository()
        repo.load()
        clock.advanceMillis(59_000)
        assertTrue(repo.load() is CatalogLoad.Loaded)
        assertEquals(1, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
        clock.advanceMillis(1_000)
        repo.load()
        assertEquals(2, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
        repo.load(force = true)
        assertEquals(3, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
        repo.releaseSecrets()
        repo.load()
        assertEquals(4, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
    }

    @Test fun `an older response never overwrites a newer optimistic edit`() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG) { gate.await() }
        val repo = repository()
        repo.ensureInitialized()
        val pending = async { repo.load(force = true) }
        runCurrent()
        assertTrue(repo.state.value.loading)
        repo.applyOptimistic { list -> list.map { if (it.id == "anthropic") it.copy(enabled = true, configured = true) else it } }
        gate.complete(catalogJson(claudeCode))
        assertEquals(CatalogLoad.Superseded, pending.await())
        assertFalse(repo.state.value.loading)
        assertTrue(repo.configured("anthropic"))
        assertEquals(CatalogSource.Fallback, repo.state.value.source)
    }

    @Test fun `a successful save resyncs silently`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(claudeCode))
        helper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val repo = repository()
        repo.load()
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(claudeCode.replace("\"enabled\":false", "\"enabled\":true,\"configured\":true")))
        val invalidationsBefore = repo.state.value.invalidations
        val outcome = repo.connectProvider("claude-code")
        assertEquals(SaveOutcome.Saved, outcome)
        assertTrue(repo.state.value.provider("claude-code")!!.enabled)
        runCurrent()
        assertFalse(repo.state.value.loading)
        assertTrue(repo.configured("claude-code"))
        assertEquals(invalidationsBefore + 1, repo.state.value.invalidations)
        val save = helper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single().args
        assertEquals("claude-code", save["provider"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), save["enabled"])
        assertNull(save["api_key"])
    }

    @Test fun `a failed save reloads, bumps the reset token and reports the dashboard error`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey))
        helper.fail(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, "disk full")
        val repo = repository()
        repo.load()
        val outcome = repo.updateProvider("anthropic", ProviderSettingsUpdate(baseUrl = "https://proxy"))
        assertEquals(SaveOutcome.Failed("disk full"), outcome)
        assertEquals(1, repo.state.value.detailResetToken)
        assertEquals(2, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
        assertNull(repo.state.value.provider("anthropic")?.baseUrl)
        assertEquals(JsonPrimitive("https://proxy"), helper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single().args["base_url"])
    }

    @Test fun `config values are sent as a nested settings patch`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey))
        helper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val repo = repository()
        repo.load()
        repo.updateProvider("anthropic", ProviderSettingsUpdate(configValues = mapOf("aws.region" to JsonPrimitive("eu-west-1"))))
        val args = helper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single().args
        assertEquals(Json.parseToJsonElement("""{"aws":{"region":"eu-west-1"}}"""), args["settings"])
    }

    @Test fun `disconnect saves enabled false, reloads and invalidates`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey))
        helper.respond(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS, JsonObject(emptyMap()))
        val repo = repository()
        repo.load()
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey.replace("\"apiKey\":\"sk-secret\",", "").replace("\"enabled\":true,\"configured\":true", "\"enabled\":false")))
        assertEquals(SaveOutcome.Saved, repo.disconnectProvider("anthropic"))
        runCurrent()
        assertFalse(repo.configured("anthropic"))
        assertEquals(JsonPrimitive(false), helper.callsFor(ProviderCatalogRepository.COMMAND_SAVE_PROVIDER_SETTINGS).single().args["enabled"])
    }

    @Test fun `model lists come from list_provider_models and the newest request wins`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(clineSignedIn))
        val repo = repository()
        repo.load()
        val first = CompletableDeferred<JsonElement>()
        var calls = 0
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_MODELS) {
            calls += 1
            if (calls == 1) first.await() else Json.parseToJsonElement("""{"providerId":"cline","models":[{"id":"b","name":"B"}]}""")
        }
        val older = async { repo.refreshModels("cline") }
        runCurrent()
        val newer = repo.refreshModels("cline")
        first.complete(Json.parseToJsonElement("""{"providerId":"cline","models":[{"id":"a","name":"A"}]}"""))
        assertEquals(ModelsLoad.Superseded, older.await())
        assertEquals(listOf("b"), (newer as ModelsLoad.Loaded).models.map { it.id })
        runCurrent()
        assertEquals(listOf("b"), repo.models("cline").map { it.id })
        assertEquals(listOf("b"), store.value!!.models["cline"]?.map { it.id })
        // A later catalog load keeps the explicit list.
        repo.load(force = true)
        assertEquals(listOf("b"), repo.models("cline").map { it.id })
    }

    @Test fun `an explicit empty model list replaces the cached one`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(clineSignedIn))
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_MODELS, Json.parseToJsonElement("""{"providerId":"cline","models":[]}"""))
        val repo = repository()
        repo.load()
        assertEquals(ModelsLoad.Loaded("cline", emptyList()), repo.refreshModels("cline"))
        assertTrue(repo.models("cline").isEmpty())
    }

    @Test fun `model refresh failures keep the list and helper-less refreshes are unavailable`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(clineSignedIn))
        helper.fail(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_MODELS, "network down")
        val repo = repository()
        repo.load()
        assertEquals(ModelsLoad.Failed("cline", "network down"), repo.refreshModels("cline"))
        assertEquals(listOf("anthropic/claude-sonnet-5"), repo.models("cline").map { it.id })
        helper.state.value = HelperState.Idle
        assertEquals(ModelsLoad.Unavailable, repo.refreshModels("cline"))
    }

    @Test fun `fallback mode builds the list from the bundle and providers json`() = runTest {
        helper.state.value = HelperState.Unavailable(HelperFailure.BuildUnsupported, null)
        presence = presenceOf(
            """{"lastUsedProvider":"cline","providers":{
                "cline":{"settings":{"auth":{"accessToken":"t"}},"updatedAt":"2026-10-03T10:00:00Z"},
                "anthropic":{"settings":{"apiKey":"k","model":"claude-opus-9"}},
                "my-proxy":{"settings":{"apiKey":"k2","model":"llama"}}}}""",
        )
        val repo = repository()
        val load = repo.load()
        assertTrue(load is CatalogLoad.Loaded)
        val state = repo.state.value
        assertEquals(CatalogSource.Fallback, state.source)
        assertTrue(state.configured("cline"))
        assertTrue(state.configured("cline-pass"))
        assertTrue(state.configured("anthropic"))
        assertTrue(state.configured("my-proxy"))
        assertFalse(state.configured("claude-code"))
        assertEquals(listOf("claude-sonnet-4-6", "claude-opus-9"), state.modelsByProvider["anthropic"]?.map { it.id })
        assertNull(state.modelsByProvider["anthropic"]?.first { it.id == "claude-opus-9" }?.supportsReasoning)
        assertEquals(true, state.modelsByProvider["anthropic"]?.first { it.id == "claude-sonnet-4-6" }?.supportsReasoning)
        assertEquals(listOf("llama"), state.modelsByProvider["my-proxy"]?.map { it.id })
        assertEquals("cline", state.presence?.lastUsedProvider)
        assertEquals(listOf("oauth", "popular").toSet(), state.provider("cline")?.capabilities?.toSet())
        assertEquals(listOf("local-auth"), state.provider("claude-code")?.capabilities)
        assertTrue(repo.fallbackProvider("openai-compatible")!!.supportsBaseUrl)
    }

    @Test fun `fallback models take reasoning from the bundled provider capabilities`() = runTest {
        val capable = FallbackProviders(
            listOf(
                FallbackProvider("deepseek", "DeepSeek", popular = true, capabilities = listOf("popular", "prompt-cache", "reasoning")),
                FallbackProvider("openai-compatible", "OpenAI Compatible", supportsBaseUrl = true, capabilities = listOf("popular", "tools")),
            ),
        )
        val projection = ConfiguredProviders.parse(
            """{"providers":{
                "deepseek":{"settings":{"apiKey":"k","model":"deepseek-chat"}},
                "openai-compatible":{"settings":{"apiKey":"k","model":"mock-model"}},
                "my-proxy":{"settings":{"apiKey":"k2","model":"llama"}}}}""",
        )
        val (_, models) = ProviderCatalogRepository.buildFallback(capable, projection)
        assertEquals(true, models.getValue("deepseek").single { it.id == "deepseek-chat" }.supportsReasoning)
        assertNull(models.getValue("openai-compatible").single { it.id == "mock-model" }.supportsReasoning)
        assertNull(models.getValue("my-proxy").single { it.id == "llama" }.supportsReasoning)
        // The repository hands the loaded bundle to the catalog state (the composer's fallback rule).
        helper.state.value = HelperState.Unavailable(HelperFailure.BuildUnsupported, null)
        val repo = repository()
        assertSame(FallbackProviders.EMPTY, repo.state.value.bundle)
        repo.ensureInitialized()
        assertSame(bundle, repo.state.value.bundle)
    }

    @Test fun `fallback mode without Termux is blocked`() = runTest {
        helper.state.value = HelperState.Idle
        presenceError = TermuxUnavailable.NotInstalled()
        val repo = repository()
        val load = repo.load()
        assertTrue(load is CatalogLoad.Blocked)
    }

    @Test fun `priming for Home reads presence once and retries after a Termux failure`() = runTest {
        helper.state.value = HelperState.Idle
        presenceError = TermuxUnavailable.NotInstalled()
        val repo = repository()
        repo.primeForHome()
        assertFalse(repo.configured("anthropic"))
        assertEquals(1, presenceReads)

        presenceError = null
        presence = presenceOf("""{"lastUsedProvider":"anthropic","providers":{"anthropic":{"settings":{"apiKey":"k","model":"claude-sonnet-5"}}}}""")
        repo.primeForHome()
        assertTrue(repo.configured("anthropic"))
        assertEquals("claude-sonnet-5", repo.state.value.provider("anthropic")?.defaultModelId)
        assertEquals(2, presenceReads)

        repo.primeForHome()
        assertEquals(2, presenceReads)
    }

    @Test fun `priming for Home with the helper ready loads the catalog once`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(anthropicWithKey))
        val repo = repository()
        repo.primeForHome()
        repo.primeForHome()
        assertTrue(repo.configured("anthropic"))
        assertEquals(1, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
    }

    @Test fun `invalidate signals observers and refreshes presence without the helper`() = runTest {
        helper.state.value = HelperState.Idle
        val repo = repository()
        repo.ensureInitialized()
        presence = presenceOf("""{"providers":{"anthropic":{"settings":{"apiKey":"k"}}}}""")
        repo.invalidate()
        assertEquals(1, repo.state.value.invalidations)
        runCurrent()
        assertTrue(repo.configured("anthropic"))
        assertTrue(repo.isConfigured("anthropic"))
        assertFalse(repo.isConfigured("cline"))
    }

    @Test fun `invalidate with the helper ready resyncs the catalog`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(claudeCode))
        val repo = repository()
        repo.load()
        repo.invalidate()
        runCurrent()
        assertEquals(2, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
    }

    @Test fun `add_provider sends the desktop payload and reloads`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(claudeCode))
        helper.respond(ProviderCatalogRepository.COMMAND_ADD_PROVIDER, JsonObject(emptyMap()))
        val repo = repository()
        val request = AddProviderRequest(providerId = "lab", name = "Lab", baseUrl = "https://lab/v1", apiKey = "sk-lab", models = listOf("m1"), defaultModelId = "m1")
        assertFalse(request.toString().contains("sk-lab"))
        assertEquals(SaveOutcome.Saved, repo.addProvider(request))
        val args = helper.callsFor(ProviderCatalogRepository.COMMAND_ADD_PROVIDER).single().args
        assertEquals(Json.parseToJsonElement("""{"provider_id":"lab","name":"Lab","base_url":"https://lab/v1","api_key":"sk-lab","models":["m1"],"default_model_id":"m1"}"""), args)
        assertEquals(1, helper.callsFor(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG).size)
    }

    @Test fun `the auth catalog feeds ProviderAuthResolver`() = runTest {
        helper.respond(ProviderCatalogRepository.COMMAND_LIST_PROVIDER_CATALOG, catalogJson(claudeCode, anthropicWithKey))
        val repo = repository()
        repo.load()
        val resolver = ProviderAuthResolver(repo)
        val local = resolver.resolve("claude-code")
        assertNotNull(local)
        assertEquals("claude", local!!.localCli?.command)
        assertEquals(ProviderAuthInfo("anthropic", listOf("popular")), resolver.resolve("anthropic"))
        assertEquals(ProviderAuthInfo("cline"), resolver.resolve("cline"))
        // Not in the live catalog: the bundled fallback answers by auth kind.
        assertEquals(ProviderAuthInfo("openai-compatible", listOf("api-key")), resolver.resolve("openai-compatible"))
        assertNull(resolver.resolve("nobody"))
    }
}
