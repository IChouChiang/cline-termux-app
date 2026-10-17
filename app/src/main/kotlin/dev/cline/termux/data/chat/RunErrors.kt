package dev.cline.termux.data.chat

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.cline.termux.R

/*
 * Ports of the desktop's run-error presentation (home-chat.md §3.4.7):
 * `webview/lib/run-error.ts` (`formatRunError`) and `webview/hooks/chat-session/helpers.ts`
 * (`isCredentialFailure`, `resolveCredentialFailureHint`, `resolveCredentialFailureAction`,
 * `credentialFailureMeta`), plus `webview/lib/provider-id.ts`. The sentences are string
 * resources (`strings_session.xml`, desktop verbatim) passed in as [RunErrorStrings].
 */

/** `normalizeProviderId`: trims and maps the desktop's aliases. */
fun normalizeProviderId(providerId: String?): String {
    val trimmed = providerId?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    return PROVIDER_ID_ALIASES[trimmed] ?: trimmed
}

private val PROVIDER_ID_ALIASES = mapOf("openai" to "openai-native", "google" to "gemini")

/** Whether a failure message describes a credential problem; never a bare "token" (context limits are not credentials). */
fun isCredentialFailure(description: String): Boolean = CREDENTIAL_FAILURE.containsMatchIn(description)

private val CREDENTIAL_FAILURE = Regex(
    "unauthorized|401|403|forbidden|api key|credential|authenticat|sign in|auth token|access token|invalid token|expired token|token expired|session expired|not logged in|/login",
    RegexOption.IGNORE_CASE,
)

/** `matchingProviderAuth`: auth facts count only when they belong to [providerId]. */
fun matchingProviderAuth(providerId: String?, auth: ProviderAuthInfo?): ProviderAuthInfo? =
    auth?.takeIf { normalizeProviderId(it.providerId) == normalizeProviderId(providerId) }

/** The four hint sentences and the two failure sentences, from `strings_session.xml`. */
data class RunErrorStrings(
    /** "The run failed: %1$s". */
    val runFailedFormat: String,
    val runFailedEmpty: String,
    /** "Sign in again with the `%1$s` CLI in a terminal, then try again." */
    val hintLocalCliFormat: String,
    val hintCline: String,
    val hintUnknownAuth: String,
    val hintProviders: String,
) {
    companion object {
        fun from(resources: Resources): RunErrorStrings = RunErrorStrings(
            runFailedFormat = resources.getString(R.string.run_failed_prefix),
            runFailedEmpty = resources.getString(R.string.run_failed_empty),
            hintLocalCliFormat = resources.getString(R.string.cred_hint_cli),
            hintCline = resources.getString(R.string.cred_hint_cline),
            hintUnknownAuth = resources.getString(R.string.cred_hint_unknown),
            hintProviders = resources.getString(R.string.cred_hint_provider),
        )
    }
}

/** Kernel and daemon text starts this way; a detail that already does is not prefixed again. */
private const val RUN_FAILED_PREFIX = "The run failed"

/** Where to send the user after a credential-looking failure. */
fun resolveCredentialFailureHint(providerId: String?, auth: ProviderAuthInfo?, strings: RunErrorStrings): String {
    val providerAuth = matchingProviderAuth(providerId, auth)
    val cli = providerAuth?.localCli
    if (cli != null) return String.format(strings.hintLocalCliFormat, cli.command)
    if (normalizeProviderId(providerId) == "cline") return strings.hintCline
    if (providerAuth == null) return strings.hintUnknownAuth
    return strings.hintProviders
}

/** The same presentation for live failures and restored transcript errors (`formatRunError`). */
fun formatRunError(detail: String, providerId: String? = "", providerAuth: ProviderAuthInfo? = null, strings: RunErrorStrings): String {
    val description = detail.trim()
    val guidance = resolveCredentialFailureHint(providerId, providerAuth, strings)
    val looksCredentialRelated = description.isEmpty() || isCredentialFailure(description)
    val head = when {
        description.isEmpty() -> strings.runFailedEmpty
        description.startsWith(RUN_FAILED_PREFIX) -> description
        else -> String.format(strings.runFailedFormat, description)
    }
    val tail = if (looksCredentialRelated && !description.contains(guidance)) guidance else ""
    return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ")
}

/** `resolveCredentialFailureAction` targets: Account for Cline, the providers list otherwise. */
enum class CredentialAction(@param:StringRes val labelRes: Int) {
    /** "Sign in to Cline" → Settings > Account. */
    Account(R.string.cred_action_cline),

    /** "Open API providers" → Settings > Providers. */
    Providers(R.string.cred_action_providers),
}

/** The in-app fix for a credential failure, or null (local-auth providers are fixed in their CLI). */
fun resolveCredentialFailureAction(providerId: String?, auth: ProviderAuthInfo?): CredentialAction? {
    if (normalizeProviderId(providerId) == "cline") return CredentialAction.Account
    val providerAuth = matchingProviderAuth(providerId, auth) ?: return null
    if (providerAuth.localCli != null) return null
    return CredentialAction.Providers
}

/** `credentialFailureMeta`: the meta every credential-looking error row carries. */
fun credentialFailureMeta(providerId: String?, auth: ProviderAuthInfo?, base: Meta = Meta()): Meta =
    base.copy(reason = REASON_CREDENTIALS, providerId = providerId, providerAuth = matchingProviderAuth(providerId, auth))

/** `meta.reason` values the reducer sets on error rows. */
const val REASON_CREDENTIALS = "credentials"
const val REASON_INTERRUPTED = "interrupted"

/** `looksCredentialRelated` of `formatRunError`: empty details always get the hint. */
fun looksCredentialRelated(detail: String): Boolean = detail.isBlank() || isCredentialFailure(detail.trim())
