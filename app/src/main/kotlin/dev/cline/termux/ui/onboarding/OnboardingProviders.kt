package dev.cline.termux.ui.onboarding

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.providers.OAUTH_PROVIDER_IDS
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.getProviderApiKeyUrl
import java.text.Collator

/*
 * Pure ports for Connect a model (onboarding-kernel.md C.2, C.7): `PREFERRED_PROVIDER_ORDER`,
 * `isApiKeyOnlyProvider` and `sortProvidersForApiKeySetup` from
 * `webview/components/views/onboarding/onboarding-view.tsx`, the desktop URLs, and the
 * client-side form validation of C.5. Key URLs come from data/providers/ProviderKeyUrls.kt,
 * the account URLs from ui/account/AccountUrls.
 */

/** `CLINE_PASS_SUBSCRIBE_URL` (onboarding-view.tsx line 56): "Get ClinePass". */
const val CLINE_PASS_SUBSCRIBE_URL = "https://app.cline.bot/onboarding/individual-plan"

/** The provider that needs a base URL in the onboarding form (C.4). */
const val OPENAI_COMPATIBLE_ID = "openai-compatible"

/** Providers surfaced first in the bring-your-own-key picker; everything else follows alphabetically. */
val PREFERRED_PROVIDER_ORDER: List<String> = listOf(
    "anthropic",
    "openai-native",
    "openrouter",
    "gemini",
    "xai",
    "groq",
    "mistral",
    "deepseek",
    "ollama",
)

/** A provider as the API-key setup step sees it. [configFieldPaths] null = no metadata (plain key field). */
@Immutable
data class ApiKeySetupCandidate(val id: String, val name: String, val configFieldPaths: List<String>?)

/**
 * `isApiKeyOnlyProvider`: an API-key config field and nothing beyond key/base-URL. Missing
 * metadata means the catalog fell back to a plain API-key field.
 */
fun isApiKeyOnlyProvider(configFieldPaths: List<String>?): Boolean {
    val fields = configFieldPaths ?: return true
    return fields.any { it == "apiKey" } && fields.all { it == "apiKey" || it == "baseUrl" }
}

/**
 * `sortProvidersForApiKeySetup`: OAuth-managed providers and those needing more than a key
 * are dropped, [PREFERRED_PROVIDER_ORDER] comes first, the rest follow by name
 * (`localeCompare`, here a default-locale [Collator]).
 */
fun <T> sortProvidersForApiKeySetup(providers: List<T>, candidate: (T) -> ApiKeySetupCandidate): List<T> {
    val collator = Collator.getInstance()
    fun rank(id: String): Int = PREFERRED_PROVIDER_ORDER.indexOf(id).let { if (it == -1) PREFERRED_PROVIDER_ORDER.size else it }
    return providers
        .filter { val c = candidate(it); c.id !in OAUTH_PROVIDER_IDS && isApiKeyOnlyProvider(c.configFieldPaths) }
        .sortedWith { a, b ->
            val ca = candidate(a)
            val cb = candidate(b)
            val byRank = rank(ca.id) - rank(cb.id)
            if (byRank != 0) byRank else collator.compare(ca.name, cb.name)
        }
}

/** One row of the provider dropdown (C.7). */
@Immutable
data class ProviderOption(
    val id: String,
    val name: String,
    val defaultModelId: String?,
    val needsBaseUrl: Boolean,
    val keyUrl: String?,
    val docLabel: String? = null,
)

/** Where the dropdown's rows came from (C.7 "Provider list sources"). */
enum class ProviderSource { Helper, Bundled }

object ProviderOptions {
    /** C.7 source 1: the settings helper's `list_provider_catalog`, filtered and sorted. */
    fun fromCatalog(items: List<ProviderListItem>): List<ProviderOption> =
        sortProvidersForApiKeySetup(items) { ApiKeySetupCandidate(it.id, it.name, it.configFields?.map { field -> field.path }) }
            .map { item ->
                ProviderOption(
                    id = item.id,
                    name = item.name,
                    defaultModelId = item.defaultModelId?.takeIf { it.isNotBlank() },
                    needsBaseUrl = item.id == OPENAI_COMPATIBLE_ID,
                    keyUrl = getProviderApiKeyUrl(item.id),
                )
            }

    /**
     * C.7 source 2: the bundled list. The [PREFERRED_PROVIDER_ORDER] ids that exist in the
     * bundle as API-key providers (so `ollama` only while it is API-key-only), in that order,
     * then `openai-compatible` with no default model and a required base URL.
     */
    fun bundled(fallback: FallbackProviders): List<ProviderOption> {
        val preferred = PREFERRED_PROVIDER_ORDER.mapNotNull { id ->
            fallback[id]?.takeIf { it.id == id && it.authKind == dev.cline.termux.data.catalog.FallbackProvider.AUTH_KIND_API_KEY }?.let { row ->
                ProviderOption(
                    id = row.id,
                    name = row.name,
                    defaultModelId = row.defaultModelId?.takeIf { it.isNotBlank() },
                    needsBaseUrl = false,
                    keyUrl = getProviderApiKeyUrl(row.id),
                )
            }
        }
        val compatible = ProviderOption(
            id = OPENAI_COMPATIBLE_ID,
            name = fallback[OPENAI_COMPATIBLE_ID]?.name ?: OPENAI_COMPATIBLE_NAME,
            defaultModelId = null,
            needsBaseUrl = true,
            keyUrl = getProviderApiKeyUrl(OPENAI_COMPATIBLE_ID),
        )
        return preferred + compatible
    }

    /** The bundle's name for openai-compatible when the bundle lacks the row (C.7 names it). */
    const val OPENAI_COMPATIBLE_NAME = "OpenAI Compatible"
}

/** The API-key form's fields (C.5 validation errors are keyed by these). */
enum class ConnectField { Provider, ApiKey, BaseUrl, Model }

/** Why a field is invalid; the screen maps each to its string. */
enum class ConnectFieldError { ModelRequired, BaseUrlInvalid }

object ConnectValidation {
    /** `http(s)://` with a host. */
    fun isValidBaseUrl(value: String): Boolean {
        val trimmed = value.trim()
        val scheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://"
            trimmed.startsWith("http://", ignoreCase = true) -> "http://"
            else -> return false
        }
        val rest = trimmed.substring(scheme.length)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotBlank() && host.none { it.isWhitespace() }
    }

    /**
     * Field errors shown under the fields (C.5). The key never gets an error line (the desktop
     * only disables Connect for it); [touched] keeps untouched fields quiet.
     */
    fun fieldErrors(option: ProviderOption?, modelId: String, baseUrl: String, touched: Set<ConnectField>): Map<ConnectField, ConnectFieldError> = buildMap {
        if (ConnectField.Model in touched && modelId.isBlank()) put(ConnectField.Model, ConnectFieldError.ModelRequired)
        if (option?.needsBaseUrl == true && ConnectField.BaseUrl in touched && !isValidBaseUrl(baseUrl)) {
            put(ConnectField.BaseUrl, ConnectFieldError.BaseUrlInvalid)
        }
    }

    /** "Connect" is enabled (desktop `!selectedProvider || !apiKey.trim() || saving`, plus model and base URL). */
    fun canSubmit(option: ProviderOption?, apiKey: String, modelId: String, baseUrl: String, saving: Boolean): Boolean =
        option != null && apiKey.isNotBlank() && modelId.isNotBlank() && !saving &&
            (!option.needsBaseUrl || isValidBaseUrl(baseUrl))

    /** The last non-blank stderr line, the C.6 "Failed to save provider" detail. */
    fun lastLine(text: String): String? = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
}
