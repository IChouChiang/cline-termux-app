package dev.cline.termux.ui.composer

import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.model.ModelChoice
import dev.cline.termux.data.model.ReasoningChipLabel
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision A2 end to end on the composer's reasoning chip: in fallback the bundled registry
 * capabilities enable it for a reasoning-capable provider's models; a live catalog row always
 * decides; unknown stays disabled.
 */
class ReasoningChipSourceTest {

    private val bundle: FallbackProviders by lazy {
        val file = listOf("src/main/res/raw/fallback_providers.json", "app/src/main/res/raw/fallback_providers.json")
            .map(::File).first { it.isFile }
        FallbackProviders.parse(file.readText())
    }

    private fun chip(catalog: CatalogState, providerId: String, modelId: String): ReasoningChipState {
        val model = ComposerProjection.modelChip(ModelChoice(providerId, modelId), catalog, busy = false)
        return ComposerProjection.reasoningChip(explicit = null, supportsReasoning = model.supportsReasoning)
    }

    @Test fun fallbackEnablesTheChipForReasoningProviders() {
        val fallback = CatalogState(source = CatalogSource.Fallback, bundle = bundle, initialized = true)
        // The owner's Astra (openai-codex) and DeepSeek, whatever the model id.
        for ((provider, model) in listOf("openai-codex" to "gpt-5.6-terra", "deepseek" to "deepseek-chat", "anthropic" to "claude-sonnet-4-6")) {
            val state = chip(fallback, provider, model)
            assertTrue("$provider/$model", state.enabled)
            assertEquals(ReasoningChipLabel.Level(ReasoningEffort.DEFAULT), state.label)
        }
    }

    @Test fun fallbackKeepsTheMockAndUnknownProvidersDisabled() {
        val fallback = CatalogState(source = CatalogSource.Fallback, bundle = bundle, initialized = true)
        for ((provider, model) in listOf("openai-compatible" to "mock-model", "my-proxy" to "llama")) {
            val state = chip(fallback, provider, model)
            assertFalse("$provider/$model", state.enabled)
            assertTrue(state.unsupportedReason)
            assertEquals(ReasoningChipLabel.Generic, state.label)
        }
    }

    @Test fun aLiveRowWithoutReasoningKeepsTheChipOff() {
        val live = CatalogState(
            source = CatalogSource.Catalog,
            providers = listOf(ProviderListItem("deepseek", "DeepSeek", enabled = true, apiKey = "k")),
            modelsByProvider = mapOf(
                "deepseek" to listOf(ProviderModel("deepseek-chat", supportsReasoning = false), ProviderModel("deepseek-reasoner", supportsReasoning = true)),
            ),
            initialized = true,
            bundle = bundle,
        )
        // The bundle says DeepSeek reasons; the live row overrides it.
        assertTrue(bundle["deepseek"]!!.supportsReasoning)
        assertFalse(chip(live, "deepseek", "deepseek-chat").enabled)
        assertEquals(ReasoningChipLabel.Level(ReasoningEffort.None), chip(live, "deepseek", "deepseek-chat").label)
        assertTrue(chip(live, "deepseek", "deepseek-reasoner").enabled)
    }
}
