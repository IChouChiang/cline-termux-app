package dev.cline.termux.data.providers

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.model.FeaturedInfo
import dev.cline.termux.data.model.FeaturedTier
import dev.cline.termux.data.model.PickerModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * The provider catalog shapes of the settings helper (`desktopCommand list_provider_catalog`
 * → `ProviderCatalogResponse`, `list_provider_models` → `ProviderModelsResponse`;
 * sdk/packages/shared/src/rpc/runtime.ts, desktop lib/provider-schema.ts) and the desktop's
 * pure classification rules over them: `isProviderConnected` / `getProviderAuthKind`
 * (lib/provider-connection.ts), `isChatModel` (lib/provider-model-catalog.ts over
 * `isChatCompatibleModel`, shared/src/llms/model-info.ts), `featuredBadges`
 * (components/views/settings/provider-list-view.tsx) and `toSettingsPatch`
 * (components/views/settings/settings-patch.ts). settings-providers.md §0.3, §0.7, S4.3, S5.
 */

/** `ProviderModelFeatured` as it arrives on the wire. */
@Immutable
@Serializable
data class ProviderModelFeatured(
    val tier: String,
    val rank: Int = 0,
    val tags: List<String> = emptyList(),
)

/** `ProviderModel`: one entry of a provider's model list. */
@Immutable
@Serializable
data class ProviderModel(
    val id: String,
    val name: String = id,
    val description: String? = null,
    val featured: ProviderModelFeatured? = null,
    /** `language` | `image-generation` | `speech-generation` | `video-generation` | `transcription` | `realtime`. */
    val operation: String? = null,
    /** `batch` | `streaming`. */
    val operationModes: List<String>? = null,
    val contextWindow: Long? = null,
    val supportsAttachments: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
) {
    /** S8.3 side output: `inputModalities` contains image, else `supportsVision`, else null. */
    val supportsImages: Boolean?
        get() = inputModalities?.contains("image") ?: supportsVision

    /** The fields `buildModelPickerData` reads (data/model/ModelPickerData.kt). */
    fun toPickerModel(): PickerModel = PickerModel(
        id = id,
        name = name,
        description = description,
        featured = featured?.let { f -> FeaturedTier.parse(f.tier)?.let { FeaturedInfo(it, f.rank, f.tags) } },
        inputModalities = inputModalities,
        supportsVision = supportsVision == true,
        supportsReasoning = supportsReasoning == true,
    )
}

/** `ProviderConfigFieldOption`. */
@Immutable
@Serializable
data class ConfigFieldOption(val label: String, val value: JsonElement)

/** `ProviderConfigField`: one row of the provider detail form (SP S5.2). */
@Immutable
@Serializable
data class ConfigField(
    val path: String,
    val label: String,
    /** `text` | `password` | `url` | `number` | `select` | `boolean`. */
    val type: String,
    val description: String? = null,
    val placeholder: String? = null,
    val required: Boolean? = null,
    val secret: Boolean? = null,
    val options: List<ConfigFieldOption>? = null,
    val defaultValue: JsonElement? = null,
) {
    /** S5.2: `password` or `secret` fields are masked and never leave memory. */
    val isSecret: Boolean get() = secret == true || type == FIELD_PASSWORD

    companion object {
        const val FIELD_TEXT = "text"
        const val FIELD_PASSWORD = "password"
        const val FIELD_URL = "url"
        const val FIELD_NUMBER = "number"
        const val FIELD_SELECT = "select"
        const val FIELD_BOOLEAN = "boolean"
    }
}

/** `ProviderLocalCli`. */
@Immutable
@Serializable
data class ProviderLocalCliInfo(val command: String, val docsUrl: String? = null)

/** `ProviderAuthInfo` as the catalog stamps it on each provider. */
@Immutable
@Serializable
data class ProviderAuthFacts(
    val providerId: String = "",
    val capabilities: List<String> = emptyList(),
    val localCli: ProviderLocalCliInfo? = null,
)

/**
 * `ProviderListItem`. [apiKey] is the plaintext key the dashboard returns (FM): it exists
 * only in the catalog a screen asked for and is never written anywhere (SP §0.6). The
 * app-wide catalog state carries [redacted] copies, where [apiKeyPresent] remembers that
 * a key exists.
 */
@Immutable
@Serializable
data class ProviderListItem(
    val id: String,
    val name: String = id,
    val models: Int? = null,
    val enabled: Boolean = false,
    val configured: Boolean? = null,
    val apiKey: String? = null,
    val oauthAccessTokenPresent: Boolean? = null,
    val baseUrl: String? = null,
    val defaultModelId: String? = null,
    val protocol: String? = null,
    val client: String? = null,
    val capabilities: List<String>? = null,
    val authDescription: String? = null,
    val baseUrlDescription: String? = null,
    val configFields: List<ConfigField>? = null,
    val configValues: Map<String, JsonElement>? = null,
    val modelList: List<ProviderModel>? = null,
    val auth: ProviderAuthFacts? = null,
    val family: String? = null,
    /**
     * The SDK's `metadata.usageCostDisplay` ("show", "hide", "subscription") when the catalog
     * carries it; it then wins over the bundled `provider_usage_cost.json` (A11.9). Today's
     * settings helper does not send it.
     */
    val usageCostDisplay: String? = null,
    /** App-side: a non-blank key exists (set by [redacted]; never on the wire). */
    val apiKeyPresent: Boolean = false,
) {
    val hasApiKey: Boolean get() = apiKeyPresent || !apiKey.isNullOrBlank()

    /**
     * The copy that may live app-wide: no plaintext key, no values of secret config fields.
     * The base URL and plain config values stay (they are shown in the detail screen).
     */
    fun redacted(): ProviderListItem {
        val secretPaths = configFields.orEmpty().filter { it.isSecret }.map { it.path }.toSet()
        return copy(
            apiKey = null,
            apiKeyPresent = hasApiKey,
            configValues = configValues?.filterKeys { it !in secretPaths && it != API_KEY_PATH },
        )
    }

    override fun toString(): String =
        "ProviderListItem(id=$id, enabled=$enabled, configured=$configured, hasApiKey=$hasApiKey, models=${modelList?.size ?: models})"

    companion object {
        const val API_KEY_PATH = "apiKey"
        const val BASE_URL_PATH = "baseUrl"
    }
}

/** `ProviderCatalogResponse` (the `voiceInput` selection is not used by the app). */
@Serializable
data class ProviderCatalogResponse(
    val providers: List<ProviderListItem> = emptyList(),
    val settingsPath: String? = null,
)

/** `ProviderModelsResponse`. */
@Serializable
data class ProviderModelsResponse(
    val providerId: String = "",
    val models: List<ProviderModel> = emptyList(),
)

/** `ProviderAuthKind`: which connect UI a provider gets (S4.3, S5.3). */
enum class AuthKind(val wire: String) {
    OAuth("oauth"),
    Local("local"),
    ApiKey("api-key"),
    ;

    companion object {
        fun parse(value: String?): AuthKind? = entries.firstOrNull { it.wire == value }
    }
}

/** `OAUTH_PROVIDER_IDS` (lib/provider-connection.ts): ClinePass shares the Cline sign-in. */
val OAUTH_PROVIDER_IDS: Set<String> = setOf("cline", "cline-pass", "oca", "openai-codex")

/** `OAUTH_LOGIN_TIMEOUT_MS`. */
const val OAUTH_LOGIN_TIMEOUT_MS: Long = 15 * 60_000L

/** `getProviderAuthKind`. */
fun providerAuthKind(id: String, capabilities: List<String>?): AuthKind = when {
    capabilities?.contains("oauth") == true || id in OAUTH_PROVIDER_IDS -> AuthKind.OAuth
    capabilities?.contains("local-auth") == true -> AuthKind.Local
    else -> AuthKind.ApiKey
}

fun ProviderListItem.authKind(): AuthKind = providerAuthKind(id, capabilities)

/**
 * `isProviderConnected`: a plain API key or OAuth is definitive; otherwise the sidecar's
 * `configured` readiness, and only for an enabled entry.
 */
fun isProviderConnected(provider: ProviderListItem): Boolean {
    if (provider.hasApiKey) return true
    if (provider.oauthAccessTokenPresent == true) return true
    return provider.enabled && provider.configured == true
}

/** `supportsChatModalities`: missing metadata counts as chat-compatible. */
fun supportsChatModalities(input: List<String>?, output: List<String>?): Boolean =
    (input == null || "text" in input) && (output == null || "text" in output)

/** `isChatCompatibleModel`: the language operation (or none) with text in and out. */
fun isChatCompatibleModel(operation: String?, input: List<String>?, output: List<String>?): Boolean =
    (operation == null || operation == "language") && supportsChatModalities(input, output)

/** `isChatModel`: the desktop also offers image generation from its composer. */
fun isChatModel(model: ProviderModel): Boolean =
    model.operation == "image-generation" ||
        isChatCompatibleModel(model.operation, model.inputModalities, model.outputModalities)

/** `filterChatModels`. */
fun filterChatModels(models: List<ProviderModel>?): List<ProviderModel> = models.orEmpty().filter(::isChatModel)

/** `isTranscriptionModel`: exactly audio in and text out. */
fun isTranscriptionModel(model: ProviderModel): Boolean =
    model.inputModalities == listOf("audio") && model.outputModalities == listOf("text")

/**
 * `featuredBadges`: "Recommended" / "Free" for the tier, then each feed tag not already
 * present (case-insensitive). The words are the desktop's pill labels.
 */
fun featuredBadges(model: ProviderModel): List<String> {
    val featured = model.featured ?: return emptyList()
    val badges = mutableListOf<String>()
    when (featured.tier) {
        "recommended" -> badges += BADGE_RECOMMENDED
        "free" -> badges += BADGE_FREE
    }
    for (tag in featured.tags) {
        if (badges.none { it.equals(tag, ignoreCase = true) }) badges += tag
    }
    return badges
}

const val BADGE_RECOMMENDED = "Recommended"
const val BADGE_FREE = "Free"

/** `AudioModelBadges` (audio-model-badges.tsx): which audio pill a model row shows (S5.4). */
enum class AudioBadge { Realtime, Transcription }

/** The pill, then whether the "Streaming not supported" icon follows (batch without streaming). */
data class AudioBadges(val badge: AudioBadge?, val streamingUnsupported: Boolean)

fun audioBadges(model: ProviderModel): AudioBadges {
    val modes = model.operationModes.orEmpty()
    val badge = when {
        model.operation == "realtime" || (model.operation == "transcription" && "streaming" in modes) -> AudioBadge.Realtime
        model.operation == "transcription" -> AudioBadge.Transcription
        else -> null
    }
    return AudioBadges(badge, streamingUnsupported = "batch" in modes && "streaming" !in modes)
}

/** S5.4 "Audio support": audio in either direction and not a transcription/realtime operation. */
fun hasAudioSupport(model: ProviderModel): Boolean {
    val audio = model.inputModalities?.contains("audio") == true || model.outputModalities?.contains("audio") == true
    return audio && model.operation != "transcription" && model.operation != "realtime"
}

/** S5.4 order: favourites first (in their saved order), then catalog order. */
fun sortModelsByFavorites(models: List<ProviderModel>, favorites: List<String>): List<ProviderModel> {
    if (favorites.isEmpty()) return models
    val favoriteSet = favorites.toSet()
    val (favored, rest) = models.partition { it.id in favoriteSet }
    return favored.sortedBy { favorites.indexOf(it.id) } + rest
}

/** S5.4 search: name or id, case-insensitive. */
fun ProviderModel.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return name.contains(needle, ignoreCase = true) || id.contains(needle, ignoreCase = true)
}

/** S4.3 search: name or id, case-insensitive. */
fun ProviderListItem.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return name.contains(needle, ignoreCase = true) || id.contains(needle, ignoreCase = true)
}

/** S4.3 sections, catalog order preserved inside each. */
data class ProviderSections(
    val configured: List<ProviderListItem>,
    val popular: List<ProviderListItem>,
    val others: List<ProviderListItem>,
)

/** Configured = filtered ∩ connected; Popular = not connected with capability `popular`; All = the rest. */
fun sectionProviders(providers: List<ProviderListItem>, query: String): ProviderSections {
    val filtered = providers.filter { it.matchesQuery(query) }
    val configured = filtered.filter(::isProviderConnected)
    val notConnected = filtered.filterNot(::isProviderConnected)
    val popular = notConnected.filter { it.capabilities?.contains("popular") == true }
    val others = notConnected.filterNot { it.capabilities?.contains("popular") == true }
    return ProviderSections(configured, popular, others)
}

/** `toSettingsPatch`: dotted config paths become a nested settings object. */
fun toSettingsPatch(values: Map<String, JsonElement?>): JsonObject {
    val root = LinkedHashMap<String, Any?>()
    for ((path, value) in values) {
        val segments = path.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var cursor = root
        for (segment in segments.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            val existing = cursor[segment] as? LinkedHashMap<String, Any?>
            cursor = existing ?: LinkedHashMap<String, Any?>().also { cursor[segment] = it }
        }
        cursor[segments.last()] = value ?: JsonNull
    }
    return root.toJsonObject()
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
    for ((key, value) in this@toJsonObject) {
        put(key, if (value is Map<*, *>) (value as Map<String, Any?>).toJsonObject() else (value as? JsonElement ?: JsonNull))
    }
}

/** `coerceFieldValue` for numbers: non-finite → null (S5.2). */
fun numberFieldValue(text: String): JsonElement {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonNull
    val number = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return JsonNull
    return if (number == Math.floor(number) && kotlin.math.abs(number) < 1e15) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
}

/** Lenient decoder for helper replies (unknown keys ignored, nulls coerced). */
internal val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}
