package dev.cline.termux.data.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shape of upstream's built-in provider + model catalog (`@cline/llms`), as
 * `tools/catalog/export-catalog.ts` exports it: what the desktop's
 * `list_provider_catalog` / `list_provider_models` would return minus the per-user
 * configured state. The app does not bundle the export; provider and model facts come
 * from `ProviderCatalogRepository` (helper catalog, `catalog-cache.json`, the SP §0.4
 * fallback), and [parse] reads an export for tooling and tests.
 */
@Serializable
data class ModelCatalog(
    val schema: Int = 1,
    val generatedAt: String = "",
    val featured: FeaturedModels? = null,
    val providers: List<CatalogProvider> = emptyList(),
) {
    private val byId: Map<String, CatalogProvider> by lazy { providers.associateBy { it.id } }

    fun provider(id: String): CatalogProvider? = byId[id]

    fun model(providerId: String, modelId: String): CatalogModel? =
        byId[providerId]?.models?.firstOrNull { it.id == modelId || it.id == modelId.substringAfter('/') }

    /** Providers upstream ranks as popular, in their order; the rest alphabetical. */
    val popular: List<CatalogProvider> get() = providers.filter { it.popularRank != null }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
        fun parse(text: String): ModelCatalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CatalogProvider(
    val id: String,
    val name: String,
    val description: String? = null,
    val docsUrl: String? = null,
    val baseUrl: String? = null,
    val defaultModelId: String? = null,
    val protocol: String? = null,
    val client: String? = null,
    val capabilities: List<String> = emptyList(),
    val env: List<String> = emptyList(),
    val popularRank: Int? = null,
    val configFields: List<ProviderConfigField>? = null,
    val models: List<CatalogModel> = emptyList(),
) {
    val supportsOAuth: Boolean get() = "oauth" in capabilities
    /** Keyless local endpoints (Ollama, LM Studio…) have no secret field. */
    val needsApiKey: Boolean get() = configFields?.any { it.path == "apiKey" } ?: true
    val needsBaseUrl: Boolean get() = configFields?.any { it.path == "baseUrl" && it.required == true } ?: false
}

@Serializable
data class ProviderConfigField(
    val path: String,
    val label: String,
    /** text | password | url | number | select | boolean */
    val type: String,
    val placeholder: String? = null,
    val description: String? = null,
    val secret: Boolean? = null,
    val required: Boolean? = null,
    val options: List<JsonElement>? = null,
    val default: JsonElement? = null,
)

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val maxInputTokens: Long? = null,
    val capabilities: List<String> = emptyList(),
    val reasoningOptions: List<ReasoningOption> = emptyList(),
    val pricing: ModelPricing? = null,
    val family: String? = null,
    val releaseDate: String? = null,
    val deprecated: Boolean = false,
) {
    val supportsReasoning: Boolean get() = "reasoning" in capabilities || reasoningOptions.isNotEmpty()
    val supportsImages: Boolean get() = "images" in capabilities || "vision" in capabilities
    val supportsTools: Boolean get() = "tools" in capabilities
    val isFree: Boolean get() = pricing?.let { it.input == 0.0 && it.output == 0.0 } ?: false

    /** Effort values the model accepts (`low|medium|high|xhigh|max`), if it has an effort option. */
    val effortLevels: List<String>
        get() = reasoningOptions.firstOrNull { it.type == "effort" }?.values
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}

@Serializable
data class ReasoningOption(
    val type: String,
    val values: List<JsonElement>? = null,
    val min: Long? = null,
    val max: Long? = null,
    val default: JsonElement? = null,
)

@Serializable
data class ModelPricing(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)

/** Upstream's featured tiers (`ClineRecommendedModelsPayload`); ids are `provider/model`. */
@Serializable
data class FeaturedModels(
    val recommended: List<FeaturedModel> = emptyList(),
    val clinePass: List<FeaturedModel> = emptyList(),
    val free: List<FeaturedModel> = emptyList(),
    val clineCloud: List<FeaturedModel> = emptyList(),
)

@Serializable
data class FeaturedModel(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)
