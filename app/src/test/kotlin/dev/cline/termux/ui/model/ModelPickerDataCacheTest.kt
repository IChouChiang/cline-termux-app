package dev.cline.termux.ui.model

import dev.cline.termux.data.model.CURRENT_MODEL_SECTION_ID
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.providers.ProviderModelFeatured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** docs/07 §8: a keystroke re-filters a cached `buildModelPickerData`, it never rebuilds it. */
class ModelPickerDataCacheTest {
    private val openrouterModels = listOf(ProviderModel("or/b", "Bravo"), ProviderModel("or/a", "Alpha"))
    private val anthropicModels = listOf(ProviderModel("claude-sonnet-4-6"))
    private val catalog = CatalogState(
        source = CatalogSource.Catalog,
        generation = 3,
        providers = listOf(
            ProviderListItem("openrouter", "OpenRouter", enabled = true, apiKey = "k"),
            ProviderListItem("anthropic", "Anthropic", enabled = true, apiKey = "k"),
        ),
        modelsByProvider = mapOf("openrouter" to openrouterModels, "anthropic" to anthropicModels),
        initialized = true,
    )

    @Test fun `the same provider list is prepared once across state copies`() {
        val cache = ModelPickerDataCache()
        val first = cache.get(catalog, "openrouter")
        assertSame(first, cache.get(catalog, "openrouter"))
        // Loading flags and generation bumps keep the list instance.
        assertSame(first, cache.get(catalog.copy(loading = true, generation = 4), "openrouter"))
    }

    @Test fun `a replaced model list or another provider is prepared again`() {
        val cache = ModelPickerDataCache()
        val first = cache.get(catalog, "openrouter")
        val refreshed = catalog.copy(modelsByProvider = catalog.modelsByProvider + ("openrouter" to openrouterModels + ProviderModel("or/c", "Charlie")))
        val second = cache.get(refreshed, "openrouter")
        assertNotSame(first, second)
        assertEquals(listOf("or/a", "or/b", "or/c"), ModelPickerSections.build(second, null, "").sections.single().rows.map { it.id })
        val other = cache.get(refreshed, "anthropic")
        assertEquals("anthropic", other.providerId)
        assertNotSame(other, cache.get(refreshed, "openrouter"))
    }

    @Test fun `a model hidden from the ClinePass offer appears under Current model with its name`() {
        val clinePass = catalog.copy(
            modelsByProvider = catalog.modelsByProvider + (
                "cline-pass" to listOf(
                    ProviderModel("cline-pass/fast", "Fast", featured = ProviderModelFeatured("subscribed", 1)),
                    ProviderModel("legacy/model", "Legacy Model"),
                )
            ),
        )
        val sections = ModelPickerSections.build(clinePass, "cline-pass", "legacy/model", "").sections
        assertEquals(listOf("subscribed", CURRENT_MODEL_SECTION_ID), sections.map { it.id })
        assertEquals(PickerRow("legacy/model", "Legacy Model", null, null, false, false, CURRENT_MODEL_SECTION_ID), sections.last().rows.single())
    }

    @Test fun `prepared rows match the uncached build for every query`() {
        val cache = ModelPickerDataCache()
        for (query in listOf("", "alpha", "or/", "zzz", "custom-id")) {
            for (selected in listOf(null, "or/a", "or/legacy")) {
                assertEquals(
                    ModelPickerSections.build(catalog, "openrouter", selected, query),
                    ModelPickerSections.build(cache.get(catalog, "openrouter"), selected, query),
                )
            }
        }
    }
}
