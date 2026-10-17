package dev.cline.termux.data.catalog

import android.content.Context
import dev.cline.termux.R
import dev.cline.termux.data.chat.normalizeProviderId
import dev.cline.termux.data.schedules.CLINE_DEFAULT_MODEL_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Fallback mode (settings-providers.md §0.4): the provider list and model tables the app
 * uses when the settings helper is not running. `res/raw/fallback_providers.json` is
 * generated from the kernel's provider registry by tools/catalog/export-fallback-providers.ts
 * (one row per provider id the CLI accepts, in the dashboard's catalog order, with the
 * registry's provider capabilities since schema 2) and committed; the model tables are the
 * desktop's `FALLBACK_PROVIDER_MODELS` and `FALLBACK_PROVIDER_REASONING_MODELS`
 * (components/views/chat/chat-input-bar.tsx), ported 1:1. Decision A2 (docs/decided.md) adds
 * the provider's `reasoning` capability to the reasoning rule ([FallbackProviders.reasoningSupport]);
 * the live catalog always overrides it.
 */

/** One bundled provider row. */
@Serializable
data class FallbackProvider(
    val id: String,
    val name: String = id,
    val popular: Boolean = false,
    /** `oauth` | `local` | `api-key` (the desktop's `getProviderAuthKind`). */
    val authKind: String = AUTH_KIND_API_KEY,
    /** True only where `cline auth --baseurl` is accepted (openai-native, openai-compatible). */
    val supportsBaseUrl: Boolean = false,
    val defaultModelId: String? = null,
    /** The registry's provider capabilities, sorted (schema 2); empty in a schema-1 file. */
    val capabilities: List<String> = emptyList(),
) {
    /** The provider has the registry's `reasoning` capability (A2: its chat models reason). */
    val supportsReasoning: Boolean get() = CAPABILITY_REASONING in capabilities

    companion object {
        const val AUTH_KIND_OAUTH = "oauth"
        const val AUTH_KIND_LOCAL = "local"
        const val AUTH_KIND_API_KEY = "api-key"

        /** The provider capability `reasoning` (@cline/llms `ProviderCapability`). */
        const val CAPABILITY_REASONING = "reasoning"
    }
}

@Serializable
private data class FallbackProvidersFile(
    val schema: Int = 1,
    val clineDefaultModelId: String? = null,
    val providers: List<FallbackProvider> = emptyList(),
)

/** The parsed bundle; lookups are in memory. */
class FallbackProviders(val providers: List<FallbackProvider>, val clineDefaultModelId: String = CLINE_DEFAULT_MODEL_ID) {
    private val byId: Map<String, FallbackProvider> = providers.associateBy { it.id }

    operator fun get(providerId: String): FallbackProvider? = byId[providerId] ?: byId[normalizeProviderId(providerId)]

    fun authKind(providerId: String): String? = get(providerId)?.authKind

    /**
     * Reasoning support while the live catalog is unavailable (`reasoningCapabilitySource =
     * "fallback"`, S8.3, decision A2), in this order: a model in
     * [FALLBACK_PROVIDER_REASONING_MODELS] is true; any model of a provider with the
     * `reasoning` capability is true; a model only in [FALLBACK_PROVIDER_MODELS] is false;
     * anything else is null (unknown: the chip stays disabled). A custom model's
     * `supportsReasoning` in `models.json` reaches the app only through the live catalog.
     */
    fun reasoningSupport(providerId: String, modelId: String): Boolean? {
        val provider = normalizeProviderId(providerId)
        if (FALLBACK_PROVIDER_REASONING_MODELS[provider].orEmpty().contains(modelId)) return true
        if (get(providerId)?.supportsReasoning == true) return true
        if (FALLBACK_PROVIDER_MODELS[provider].orEmpty().contains(modelId)) return false
        return null
    }

    companion object {
        val EMPTY = FallbackProviders(emptyList())

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        fun parse(text: String): FallbackProviders {
            val file = json.decodeFromString(FallbackProvidersFile.serializer(), text)
            return FallbackProviders(
                providers = file.providers.filter { it.id.isNotBlank() },
                clineDefaultModelId = file.clineDefaultModelId?.takeIf { it.isNotBlank() } ?: CLINE_DEFAULT_MODEL_ID,
            )
        }

        /** Reads `res/raw/fallback_providers.json` off the main thread. */
        suspend fun load(context: Context): FallbackProviders = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.fallback_providers).bufferedReader().use { parse(it.readText()) }
        }
    }
}

/** `FALLBACK_PROVIDER_MODELS`. */
val FALLBACK_PROVIDER_MODELS: Map<String, List<String>> = mapOf(
    "cline" to listOf(CLINE_DEFAULT_MODEL_ID),
    "anthropic" to listOf("claude-sonnet-4-6"),
    "openai-native" to listOf("gpt-5.5"),
    "openrouter" to listOf("anthropic/claude-sonnet-4.6"),
    "gemini" to listOf("gemini-3-pro-latest"),
)

/** `FALLBACK_PROVIDER_REASONING_MODELS`: identical today, kept separate so they can diverge. */
val FALLBACK_PROVIDER_REASONING_MODELS: Map<String, List<String>> = mapOf(
    "cline" to listOf(CLINE_DEFAULT_MODEL_ID),
    "anthropic" to listOf("claude-sonnet-4-6"),
    "openai-native" to listOf("gpt-5.5"),
    "openrouter" to listOf("anthropic/claude-sonnet-4.6"),
    "gemini" to listOf("gemini-3-pro-latest"),
)

/**
 * §0.4 fallback model list for a provider: the table's models, then the model the user
 * saved for it in `providers.json` (when not already listed).
 */
fun fallbackModelIds(providerId: String, savedModel: String? = null): List<String> {
    val table = FALLBACK_PROVIDER_MODELS[normalizeProviderId(providerId)].orEmpty()
    val saved = savedModel?.trim()?.takeIf { it.isNotEmpty() && it !in table }
    return if (saved == null) table else table + saved
}
