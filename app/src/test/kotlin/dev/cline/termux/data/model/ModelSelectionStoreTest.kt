package dev.cline.termux.data.model

import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.data.catalog.FallbackProvider
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory `model.selection` / `model.reasoning`. */
class InMemoryModelSelectionPrefs : ModelSelectionPrefs {
    val selectionFlow = MutableStateFlow(ModelSelection())
    val reasoningFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    override val selection: Flow<ModelSelection> get() = selectionFlow
    override suspend fun selection(): ModelSelection = selectionFlow.value
    override suspend fun writeSelection(selection: ModelSelection) {
        selectionFlow.value = selection
    }
    override val reasoning: Flow<Map<String, String>> get() = reasoningFlow
    override suspend fun reasoning(): Map<String, String> = reasoningFlow.value
    override suspend fun writeReasoning(providerId: String, level: String?) {
        reasoningFlow.value = if (level == null) reasoningFlow.value - providerId else reasoningFlow.value + (providerId to level)
    }
}

/** SP S8.3 / S8.4 selection rules and persistence. */
class ModelSelectionStoreTest {

    private val prefs = InMemoryModelSelectionPrefs()
    private val store = ModelSelectionStore(prefs)

    @Test fun `explicit picks remember the provider and its model`() = runTest {
        store.recordPick("anthropic", "claude-sonnet-5")
        store.recordPick("openrouter", "x/y")
        val selection = store.selection.first()
        assertEquals("openrouter", selection.lastProvider)
        assertEquals(mapOf("anthropic" to "claude-sonnet-5", "openrouter" to "x/y"), selection.lastModelByProvider)
        store.recordPick(" ", "m")
        assertEquals("openrouter", store.current().lastProvider)
    }

    @Test fun `a provider pick without a model keeps the remembered models`() = runTest {
        store.recordPick("anthropic", "claude")
        store.recordProviderPick("gemini", null)
        assertEquals(ModelSelection("gemini", mapOf("anthropic" to "claude")), store.current())
        store.recordProviderPick("gemini", "gemini-3")
        assertEquals("gemini-3", store.current().modelFor("gemini"))
    }

    @Test fun `rememberProviderSelection keeps the map unless a default model is known`() = runTest {
        store.recordPick("anthropic", "claude")
        store.rememberProviderSelection("cline")
        assertEquals(ModelSelection("cline", mapOf("anthropic" to "claude")), store.current())
        store.rememberProviderSelection("cline", "anthropic/claude-sonnet-5")
        assertEquals("anthropic/claude-sonnet-5", store.current().modelFor("cline"))
    }

    @Test fun `reasoning is stored per provider and unknown levels are dropped`() = runTest {
        store.recordReasoning("anthropic", ReasoningEffort.High)
        prefs.reasoningFlow.value = prefs.reasoningFlow.value + ("bad" to "turbo")
        assertEquals(mapOf("anthropic" to ReasoningEffort.High), store.reasoning.first())
        assertEquals(ReasoningEffort.High, store.rememberedReasoning("anthropic"))
        assertNull(store.rememberedReasoning("bad"))
    }

    private fun catalog(
        source: CatalogSource = CatalogSource.Catalog,
        lastUsed: String? = null,
        providers: List<ProviderListItem>,
        models: Map<String, List<ProviderModel>>,
    ) = CatalogState(
        source = source,
        providers = providers,
        modelsByProvider = models,
        presence = ConfiguredProviders(lastUsedProvider = lastUsed, providers = emptyMap()),
    )

    private val anthropic = ProviderListItem("anthropic", "Anthropic", enabled = true, apiKey = "k", defaultModelId = "claude-default")
    private val openrouter = ProviderListItem("openrouter", "OpenRouter", enabled = true, apiKey = "k")
    private val gemini = ProviderListItem("gemini", "Gemini")
    private val models = mapOf(
        "anthropic" to listOf(ProviderModel("claude-a"), ProviderModel("claude-default")),
        "openrouter" to listOf(ProviderModel("or-1"), ProviderModel("or-tts", operation = "speech-generation")),
        "gemini" to listOf(ProviderModel("g-1")),
    )

    @Test fun `home defaults prefer the remembered configured provider and model`() {
        val state = catalog(providers = listOf(anthropic, openrouter, gemini), models = models)
        assertEquals(ModelChoice("openrouter", "x"), resolveHomeSelection(ModelSelection("openrouter", mapOf("openrouter" to "x")), state))
        // Remembered provider not configured → providers.json lastUsedProvider → first configured.
        assertEquals(ModelChoice("anthropic", "claude-default"), resolveHomeSelection(ModelSelection("gemini"), state))
        val withLastUsed = catalog(lastUsed = "openrouter", providers = listOf(anthropic, openrouter), models = models)
        assertEquals(ModelChoice("openrouter", "or-1"), resolveHomeSelection(ModelSelection(), withLastUsed))
        assertNull(resolveHomeSelection(ModelSelection(), catalog(providers = listOf(gemini), models = models)))
    }

    @Test fun `provider chip resolution ports handleProviderSelect`() {
        val ids = listOf("a", "b", "c")
        // Remembered and offered.
        assertEquals("b", resolveProviderPickModel("b", ids, setOf("a", "b")))
        // Remembered live-only id missing from the bundled list is preserved.
        assertEquals("live-only", resolveProviderPickModel("live-only", ids, setOf("a")))
        // Remembered but hidden by the offer → first offered.
        assertEquals("a", resolveProviderPickModel("c", ids, setOf("a", "b")))
        // Nothing offered → first provider model.
        assertEquals("a", resolveProviderPickModel(null, ids, emptySet()))
        assertNull(resolveProviderPickModel(null, emptyList(), emptySet()))
    }

    @Test fun `reasoning chip label`() {
        assertEquals(ReasoningChipLabel.Generic, reasoningChipLabel(null, null))
        assertEquals(ReasoningChipLabel.Level(ReasoningEffort.None), reasoningChipLabel(null, false))
        assertEquals(ReasoningChipLabel.Level(ReasoningEffort.High), reasoningChipLabel(ReasoningEffort.High, false))
        assertEquals(ReasoningChipLabel.Level(ReasoningEffort.Low), reasoningChipLabel(null, true))
    }

    @Test fun `thinking config follows the effort`() {
        assertEquals(ThinkingConfig(false, null), thinkingConfig(ReasoningEffort.None))
        assertEquals(ThinkingConfig(true, "xhigh"), thinkingConfig(ReasoningEffort.XHigh))
    }

    @Test fun `capabilities come from the catalog, or the fallback tables`() {
        val rich = mapOf(
            "anthropic" to listOf(
                ProviderModel("vision", inputModalities = listOf("text", "image"), supportsReasoning = true, contextWindow = 200_000),
                ProviderModel("plain", supportsVision = false),
            ),
        )
        val state = catalog(providers = listOf(anthropic), models = rich)
        assertEquals(ModelCapabilities(true, true, 200_000), modelCapabilities(state, "anthropic", "vision"))
        assertEquals(ModelCapabilities(false, false, null), modelCapabilities(state, "anthropic", "plain"))
        assertEquals(ModelCapabilities(null, false, null), modelCapabilities(state, "anthropic", "unknown"))
        val fallback = state.copy(source = CatalogSource.Fallback)
        assertEquals(true, modelCapabilities(fallback, "anthropic", "claude-sonnet-4-6").supportsReasoning)
        assertNull(modelCapabilities(fallback, "anthropic", "claude-opus-9").supportsReasoning)
        assertEquals(ModelCapabilities(null, null, null), modelCapabilities(state, null, "x"))
    }

    /** Four registry rows as the exporter writes them (schema 2). */
    private val bundle = FallbackProviders(
        listOf(
            FallbackProvider("openai-codex", capabilities = listOf("oauth", "popular", "reasoning")),
            FallbackProvider("deepseek", capabilities = listOf("popular", "prompt-cache", "reasoning")),
            FallbackProvider("anthropic", capabilities = listOf("popular", "prompt-cache", "reasoning")),
            FallbackProvider("openai-compatible", capabilities = listOf("popular", "tools")),
        ),
    )

    @Test fun `the fallback branch uses the bundle's reasoning rule`() {
        val fallback = CatalogState(source = CatalogSource.Fallback, bundle = bundle)
        assertEquals(true, modelCapabilities(fallback, "openai-codex", "gpt-5.6-terra").supportsReasoning)
        assertEquals(true, modelCapabilities(fallback, "deepseek", "deepseek-chat").supportsReasoning)
        assertEquals(true, modelCapabilities(fallback, "anthropic", "claude-sonnet-4-6").supportsReasoning)
        assertEquals(true, modelCapabilities(fallback, "anthropic", "claude-opus-9").supportsReasoning)
        assertNull(modelCapabilities(fallback, "openai-compatible", "mock-model").supportsReasoning)
        // A custom provider is unknown in fallback: its models.json facts need the live catalog.
        assertNull(modelCapabilities(fallback, "my-proxy", "llama").supportsReasoning)
        // Fallback knows nothing about images or the context window.
        assertEquals(ModelCapabilities(null, true, null), modelCapabilities(fallback, "deepseek", "deepseek-chat"))
    }

    @Test fun `a live row decides even when the fallback provider reasons`() {
        val deepseek = ProviderListItem("deepseek", "DeepSeek", enabled = true, apiKey = "k")
        val proxy = ProviderListItem("my-proxy", "My proxy", enabled = true, apiKey = "k")
        val live = CatalogState(
            source = CatalogSource.Catalog,
            providers = listOf(deepseek, proxy),
            modelsByProvider = mapOf(
                "deepseek" to listOf(
                    ProviderModel("deepseek-chat", supportsReasoning = false),
                    ProviderModel("deepseek-reasoner", supportsReasoning = true),
                    ProviderModel("deepseek-undeclared"),
                ),
                // models.json `supportsReasoning` arrives as the row's reasoning flag.
                "my-proxy" to listOf(ProviderModel("llama-think", supportsReasoning = true), ProviderModel("llama")),
            ),
            bundle = bundle,
        )
        assertEquals(false, modelCapabilities(live, "deepseek", "deepseek-chat").supportsReasoning)
        assertEquals(true, modelCapabilities(live, "deepseek", "deepseek-reasoner").supportsReasoning)
        assertEquals(false, modelCapabilities(live, "deepseek", "deepseek-undeclared").supportsReasoning)
        assertEquals(false, modelCapabilities(live, "deepseek", "not-in-the-catalog").supportsReasoning)
        assertEquals(true, modelCapabilities(live, "my-proxy", "llama-think").supportsReasoning)
        assertEquals(false, modelCapabilities(live, "my-proxy", "llama").supportsReasoning)
        // The cached projection of a live catalog decides the same way.
        assertEquals(false, modelCapabilities(live.copy(source = CatalogSource.Cache), "deepseek", "deepseek-chat").supportsReasoning)
    }
}
