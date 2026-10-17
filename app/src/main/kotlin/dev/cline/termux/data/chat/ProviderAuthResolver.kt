package dev.cline.termux.data.chat

/**
 * The catalog lookups [ProviderAuthResolver] needs (home-chat.md §1.3.5), declared here so
 * the resolver does not depend on the settings layer. The settings lane's
 * `ProviderCatalogRepository` implements it over the helper catalog, the persisted
 * `catalog-cache.json` projection, `fallback_providers.json` and `ProviderPresence`.
 * All lookups are in-memory and must not block.
 */
interface ProviderAuthCatalog {
    /** Step 2: the settings-helper catalog in memory (SP §0.7), when it is loaded. */
    fun liveProvider(providerId: String): CatalogAuthFacts?

    /** Step 3a: the persisted non-secret catalog projection (`filesDir/catalog-cache.json`). */
    fun cachedProvider(providerId: String): CatalogAuthFacts?

    /** Step 3b: `authKind` of the bundled `fallback_providers.json` row: `local` | `oauth` | `api-key`, or null. */
    fun fallbackAuthKind(providerId: String): String?

    /** Step 4: `ProviderPresence.configuredIds` contains the id (a custom provider the user configured). */
    fun isConfigured(providerId: String): Boolean

    /** No catalog at all: every provider except `cline` resolves to unknown auth. */
    object None : ProviderAuthCatalog {
        override fun liveProvider(providerId: String): CatalogAuthFacts? = null
        override fun cachedProvider(providerId: String): CatalogAuthFacts? = null
        override fun fallbackAuthKind(providerId: String): String? = null
        override fun isConfigured(providerId: String): Boolean = false
    }
}

/** A provider's auth facts from a catalog (`ProviderListItem.capabilities` + `metadata.localCliCommand`). */
data class CatalogAuthFacts(
    val capabilities: List<String>,
    val localCliCommand: String? = null,
    val docsUrl: String? = null,
)

/**
 * `ProviderAuthResolver.resolve(providerId)` (home-chat.md §1.3.5): the auth facts stamped on
 * a `TurnRecord` at send time and on every error row, from which the credential hint and
 * action are resolved (`resolveCredentialFailureHint` / `resolveCredentialFailureAction`).
 */
class ProviderAuthResolver(private val catalog: ProviderAuthCatalog) {
    fun resolve(providerId: String?): ProviderAuthInfo? {
        val raw = providerId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 1. Cline: the hint and action do not depend on the rest.
        if (normalizeProviderId(raw) == "cline") return ProviderAuthInfo("cline")
        // 2. The helper catalog in memory.
        catalog.liveProvider(raw)?.let { return it.toInfo(raw) }
        // 3. The persisted projection, else the bundled fallback list by authKind.
        catalog.cachedProvider(raw)?.let { return it.toInfo(raw) }
        when (catalog.fallbackAuthKind(raw)) {
            // The CLI command is not known offline: the desktop's unknown-auth path applies.
            AUTH_KIND_LOCAL -> return null
            AUTH_KIND_OAUTH -> return ProviderAuthInfo(raw, capabilities = listOf(AUTH_KIND_OAUTH))
            AUTH_KIND_API_KEY -> return ProviderAuthInfo(raw, capabilities = listOf(AUTH_KIND_API_KEY))
        }
        // 4. Android rule: a provider the user configured is known.
        if (catalog.isConfigured(raw)) return ProviderAuthInfo(raw)
        // 5. Unknown.
        return null
    }

    private fun CatalogAuthFacts.toInfo(providerId: String) = ProviderAuthInfo(
        providerId = providerId,
        capabilities = capabilities,
        localCli = localCliCommand?.takeIf { it.isNotBlank() }?.let { LocalCli(it, docsUrl) },
    )

    companion object {
        const val AUTH_KIND_LOCAL = "local"
        const val AUTH_KIND_OAUTH = "oauth"
        const val AUTH_KIND_API_KEY = "api-key"
    }
}
