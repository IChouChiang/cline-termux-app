package dev.cline.termux.data.providers

import java.util.Locale

/*
 * The one port of the desktop's `webview/lib/provider-key-urls.ts`, shared by Connect a model
 * (onboarding), the providers screens and the Sign in with Cline sheet. The provider catalog's
 * `docUrl` wins when present, but the catalog does not populate it today, so the curated map
 * covers the providers a new user is most likely to bring a key from. Everything else renders
 * no link.
 */

/** `PROVIDER_API_KEY_URLS`: where users create API keys for well-known providers. */
val PROVIDER_API_KEY_URLS: Map<String, String> = mapOf(
    "anthropic" to "https://console.anthropic.com/settings/keys",
    "openai-native" to "https://platform.openai.com/api-keys",
    "openai" to "https://platform.openai.com/api-keys",
    "openrouter" to "https://openrouter.ai/settings/keys",
    "gemini" to "https://aistudio.google.com/apikey",
    "xai" to "https://console.x.ai",
    "groq" to "https://console.groq.com/keys",
    "mistral" to "https://console.mistral.ai/api-keys",
    "deepseek" to "https://platform.deepseek.com/api_keys",
    "cerebras" to "https://cloud.cerebras.ai",
    "fireworks" to "https://app.fireworks.ai/settings/users/api-keys",
    "together" to "https://api.together.ai/settings/api-keys",
    "cline" to "https://app.cline.bot",
    "wandb" to "https://wandb.ai/settings",
)

/** `CLINE_DASHBOARD_URL`: the Cline account dashboard, where Cline API keys are created ("Find your key"). */
const val CLINE_DASHBOARD_URL = "https://app.cline.bot"

/**
 * `getProviderApiKeyUrl`: the "get an API key" URL for a provider, preferring the catalog's own
 * [docUrl] (trimmed, blank ignored) and falling back to [PROVIDER_API_KEY_URLS] by the trimmed,
 * lower-cased [id]; null when neither knows one.
 */
fun getProviderApiKeyUrl(id: String, docUrl: String? = null): String? {
    docUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return PROVIDER_API_KEY_URLS[id.trim().lowercase(Locale.ROOT)]
}
