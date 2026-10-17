package dev.cline.termux.ui.providers

import dev.cline.termux.core.kernel.termux.ConfiguredProvider
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.data.providers.ConfigField
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.providers.audioBadges
import dev.cline.termux.data.providers.authKind
import dev.cline.termux.data.providers.BADGE_FREE
import dev.cline.termux.data.providers.BADGE_RECOMMENDED
import dev.cline.termux.data.providers.featuredBadges
import dev.cline.termux.data.providers.hasAudioSupport
import dev.cline.termux.data.providers.isProviderConnected
import dev.cline.termux.data.providers.matchesQuery
import dev.cline.termux.data.providers.numberFieldValue
import dev.cline.termux.data.providers.sectionProviders
import dev.cline.termux.data.providers.sortModelsByFavorites
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Pure rules of the providers screens (settings-providers.md S4–S6), ported from the desktop's
 * provider-list-view.tsx (`getInitialConfigValues`, `fieldValueToString`, `coerceFieldValue`,
 * the connection-section branches, the title block), settings-view.tsx
 * (`effectiveSelectedProviderId`) and add-provider.tsx / model-id-input.tsx. lib/provider-key-urls.ts
 * lives in data/providers/ProviderKeyUrls.kt. No Android types: unit-tested on the JVM.
 */

// ---- field values ----------------------------------------------------------------------

/** `fieldValueToString`: null/absent → "", otherwise the primitive's text. */
fun fieldValueText(value: JsonElement?): String = when (value) {
    null, is JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    else -> value.toString()
}

/**
 * `getInitialConfigValues` for one field: the provider's `configValues`, then `apiKey` /
 * `baseUrl`, then the field's `defaultValue`. [secretValue] stands in for the plaintext
 * `apiKey` and secret config values, which the redacted catalog no longer carries.
 */
fun initialFieldValue(provider: ProviderListItem, field: ConfigField, secretValue: String? = null): JsonElement? {
    val values = provider.configValues.orEmpty()
    values[field.path]?.let { return it }
    if (field.isSecret || field.path == ProviderListItem.API_KEY_PATH) {
        val secret = secretValue ?: provider.apiKey.takeIf { field.path == ProviderListItem.API_KEY_PATH }
        if (secret != null) return JsonPrimitive(secret)
    }
    if (field.path == ProviderListItem.BASE_URL_PATH) provider.baseUrl?.let { return JsonPrimitive(it) }
    return field.defaultValue
}

/** `coerceFieldValue` for a text-like draft (S5.2): empty → null, numbers parsed, select options matched. */
fun coerceFieldValue(field: ConfigField, raw: String): JsonElement {
    if (field.type == ConfigField.FIELD_BOOLEAN) return JsonPrimitive(raw.trim().equals("true", ignoreCase = true))
    if (field.type == ConfigField.FIELD_SELECT) {
        field.options?.firstOrNull { fieldValueText(it.value) == raw }?.let { return it.value }
    }
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonNull
    if (field.type == ConfigField.FIELD_NUMBER) return numberFieldValue(trimmed)
    return JsonPrimitive(trimmed)
}

/** `coerceFieldValue` for a boolean commit. */
fun coerceBooleanValue(checked: Boolean): JsonElement = JsonPrimitive(checked)

/** A boolean field's stored state (`Boolean(value)`). */
fun fieldChecked(value: JsonElement?): Boolean = when (value) {
    is JsonPrimitive -> value.booleanOrNull ?: (value.contentOrNull?.let { it.isNotEmpty() && it != "false" && it != "0" } ?: false)
    else -> false
}

/** Whether a draft would change the stored value (unchanged drafts are not re-saved). */
fun draftDiffers(field: ConfigField, draft: String, stored: JsonElement?): Boolean =
    fieldValueText(coerceFieldValue(field, draft)) != fieldValueText(stored)

// ---- the detail's connection section ----------------------------------------------------

/** Which connection section S5 shows (desktop `connectionSection` branches, S5.1). */
enum class ConnectionVariant {
    /** OAuth, signed in through the browser: "Signed in via browser". */
    OAuthSignedIn,

    /** OAuth provider connected with an API key: "Configured with an API key." + the key row. */
    OAuthWithApiKey,

    /** OAuth, not connected: the "Sign in to <name>" card. */
    OAuthSignIn,

    /** Local CLI auth: "Uses your local CLI sign-in". */
    Local,

    /** Plain API-key provider: its config fields. */
    ApiKey,
}

fun connectionVariant(authKind: AuthKind, oauthSignedIn: Boolean, connected: Boolean, apiKeyPresent: Boolean): ConnectionVariant =
    when (authKind) {
        AuthKind.OAuth -> when {
            oauthSignedIn -> ConnectionVariant.OAuthSignedIn
            connected && apiKeyPresent -> ConnectionVariant.OAuthWithApiKey
            else -> ConnectionVariant.OAuthSignIn
        }
        AuthKind.Local -> ConnectionVariant.Local
        AuthKind.ApiKey -> ConnectionVariant.ApiKey
    }

/** `providers.json` `tokenSource` written by the browser sign-in. */
const val TOKEN_SOURCE_OAUTH = "oauth"

/**
 * S5.1 fallback: "signed in via browser" from the redacted `providers.json` entry. The token
 * (`settings.auth.accessToken`) must be there, and either the browser sign-in wrote the entry
 * last (`tokenSource == "oauth"`) or there is no key it could stand beside.
 */
fun fallbackOAuthSignedIn(entry: ConfiguredProvider?): Boolean =
    entry != null && entry.hasOAuth && (entry.tokenSource == TOKEN_SOURCE_OAUTH || !entry.hasApiKey)

/** OAuth providers whose key `cline auth --provider <id> --apikey` saves in fallback mode ("Use a Cline API key", S7). */
val FALLBACK_OAUTH_KEY_PROVIDER_IDS = setOf("cline")

/**
 * Whether fallback mode (§0.4) shows the `cline auth` form: alone for an api-key provider; as the
 * OAuth key row when a key is already configured or the provider takes one; never for
 * local-auth or a browser-signed-in OAuth provider.
 */
fun fallbackFormShown(fallback: Boolean, connection: ConnectionVariant, providerId: String): Boolean = fallback && when (connection) {
    ConnectionVariant.ApiKey, ConnectionVariant.OAuthWithApiKey -> true
    ConnectionVariant.OAuthSignIn -> providerId in FALLBACK_OAUTH_KEY_PROVIDER_IDS
    ConnectionVariant.OAuthSignedIn, ConnectionVariant.Local -> false
}

/** "Changes to the fields below are saved automatically." (title block): connected API-key providers, never in fallback. */
fun showsAutoSaveNote(connected: Boolean, authKind: AuthKind, fallback: Boolean): Boolean =
    !fallback && connected && authKind != AuthKind.OAuth && authKind != AuthKind.Local

/** The top-bar action label: "Sign out" for an OAuth provider signed in through the browser, else "Disconnect". */
fun isSignOutAction(authKind: AuthKind, oauthSignedIn: Boolean): Boolean = authKind == AuthKind.OAuth && oauthSignedIn

/** `effectiveSelectedProviderId`: the selection, else the first connected provider, else the first one. */
fun effectiveSelectedProviderId(selectedId: String?, providers: List<ProviderListItem>): String? {
    if (selectedId != null && providers.any { it.id == selectedId }) return selectedId
    return providers.firstOrNull(::isProviderConnected)?.id ?: providers.firstOrNull()?.id
}

// ---- list rows -------------------------------------------------------------------------

/** The three S4 sections. */
enum class ProviderSection { Configured, Popular, All }

/** One S4 row. */
data class ProviderRowUi(
    val id: String,
    val name: String,
    val connected: Boolean,
    val authKind: AuthKind,
    /** A save (incl. a draft committed on leave) is in flight for this provider. */
    val saving: Boolean = false,
)

data class ProviderRows(
    val configured: List<ProviderRowUi>,
    val popular: List<ProviderRowUi>,
    val others: List<ProviderRowUi>,
) {
    val isEmpty: Boolean get() = configured.isEmpty() && popular.isEmpty() && others.isEmpty()

    fun of(section: ProviderSection): List<ProviderRowUi> = when (section) {
        ProviderSection.Configured -> configured
        ProviderSection.Popular -> popular
        ProviderSection.All -> others
    }
}

fun providerRows(providers: List<ProviderListItem>, query: String, saving: Set<String> = emptySet()): ProviderRows {
    val sections = sectionProviders(providers, query)
    fun row(p: ProviderListItem) = ProviderRowUi(p.id, p.name, isProviderConnected(p), p.authKind(), p.id in saving)
    return ProviderRows(sections.configured.map(::row), sections.popular.map(::row), sections.others.map(::row))
}

// ---- models card -----------------------------------------------------------------------

/** One pill of a model row's headline (S5.4). */
sealed interface ModelBadge {
    data object Recommended : ModelBadge
    data object Free : ModelBadge

    /** A feed tag (e.g. "NEW"), shown as data. */
    data class Tag(val text: String) : ModelBadge
}

enum class AudioPill { Realtime, Transcription }

/** One S5.4 model row. */
data class ModelRowUi(
    val id: String,
    val name: String,
    val description: String?,
    val badges: List<ModelBadge>,
    val fileSupport: Boolean,
    val imageSupport: Boolean,
    val audioSupport: Boolean,
    val reasoningSupport: Boolean,
    val audioPill: AudioPill?,
    val streamingUnsupported: Boolean,
    val favorite: Boolean,
)

fun modelRow(model: ProviderModel, favorite: Boolean): ModelRowUi {
    val audio = audioBadges(model)
    return ModelRowUi(
        id = model.id,
        name = model.name,
        description = model.description?.takeIf { it.isNotBlank() },
        badges = featuredBadges(model).map { label ->
            when (label) {
                BADGE_RECOMMENDED -> ModelBadge.Recommended
                BADGE_FREE -> ModelBadge.Free
                else -> ModelBadge.Tag(label)
            }
        },
        fileSupport = model.supportsAttachments == true,
        imageSupport = model.supportsVision == true,
        audioSupport = hasAudioSupport(model),
        reasoningSupport = model.supportsReasoning == true,
        audioPill = when (audio.badge) {
            dev.cline.termux.data.providers.AudioBadge.Realtime -> AudioPill.Realtime
            dev.cline.termux.data.providers.AudioBadge.Transcription -> AudioPill.Transcription
            null -> null
        },
        streamingUnsupported = audio.streamingUnsupported,
        favorite = favorite,
    )
}

/** S5.4 list: the query filters by name or id, favourites first, then catalog order. */
fun modelRows(models: List<ProviderModel>, query: String, favorites: List<String>): List<ModelRowUi> {
    val matching = models.filter { it.matchesQuery(query) }
    val favoriteSet = favorites.toSet()
    return sortModelsByFavorites(matching, favorites).map { modelRow(it, it.id in favoriteSet) }
}

/** The search field shows only for a non-empty list; in fallback only above five models (S5.4). */
fun showsModelSearch(totalModels: Int, fallback: Boolean): Boolean =
    if (fallback) totalModels > FALLBACK_SEARCH_THRESHOLD else totalModels > 0

const val FALLBACK_SEARCH_THRESHOLD = 5

/** `featuredModelList` / `copiedModelState` timeout (provider-list-view.tsx). */
const val COPIED_MODEL_MS = 1_600L

/** Below this many dp of row width per unit of text scale, an S4 row stacks its status under the name. */
const val PROVIDER_ROW_STACK_WIDTH_DP = 360f

/**
 * Whether an S4 row moves its status (and auth hint) from the trailing slot into the
 * supporting line, leaving only the chevron trailing: true when the row is narrower than
 * [PROVIDER_ROW_STACK_WIDTH_DP] of scaled text. [textScale] is the system font scale times the
 * in-app font size multiplier (DS §8.1.8: layouts work at 2.0 × size 20).
 */
fun providerRowStacked(widthDp: Float, textScale: Float): Boolean =
    widthDp / textScale.coerceAtLeast(MIN_TEXT_SCALE) < PROVIDER_ROW_STACK_WIDTH_DP

private const val MIN_TEXT_SCALE = 0.1f
