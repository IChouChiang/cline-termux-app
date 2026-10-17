package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The redacted projection of one `providers.json` entry (docs/07 §3.4, KS §0.6 rule 2).
 * Only presence flags leave the parser: no key, token, base URL or cloud credential value
 * is ever materialised into a field.
 */
data class ConfiguredProvider(
    val id: String,
    val hasApiKey: Boolean,
    val hasOAuth: Boolean,
    val hasCloudCredentials: Boolean,
    val model: String?,
    val hasBaseUrl: Boolean,
    /** `manual` | `oauth` | `migration` */
    val tokenSource: String?,
    val updatedAt: String?,
) {
    /** The docs/07 `isConfigured` clause: an API key or an OAuth token is present. */
    val configured: Boolean get() = hasApiKey || hasOAuth
}

/**
 * What the app knows about `~/.cline/data/settings/providers.json` without ever holding
 * its contents: `cat` output goes straight into [parse] and the raw string is dropped
 * by the caller. Replaces the old `ProvidersFile` (plaintext keys in memory, D5).
 */
data class ConfiguredProviders(
    val lastUsedProvider: String?,
    val providers: Map<String, ConfiguredProvider>,
) {
    val isEmpty: Boolean get() = providers.isEmpty()
    val configuredIds: List<String> get() = providers.values.filter { it.configured }.map { it.id }.sorted()

    fun isConfigured(id: String): Boolean = providers[id]?.configured == true

    /**
     * KS §0.6 approximation **A** of the desktop's `isProviderConnected` when the helper
     * is not up: key or OAuth, or a saved model on a keyless/cloud-credential provider.
     */
    fun isConnected(id: String, keylessProviderIds: Set<String> = KEYLESS_PROVIDER_IDS): Boolean {
        val entry = providers[id] ?: return false
        if (entry.configured) return true
        val hasModel = !entry.model.isNullOrBlank()
        return hasModel && (id in keylessProviderIds || entry.hasCloudCredentials)
    }

    /** True when at least one provider is connected: the Home setup notice stays hidden. */
    val anyConnected: Boolean get() = providers.keys.any { isConnected(it) }

    /** The provider a new session should default to. */
    val defaultProviderId: String?
        get() = lastUsedProvider?.takeIf { isConnected(it) } ?: configuredIds.firstOrNull() ?: providers.keys.firstOrNull { isConnected(it) }

    fun defaultModelId(providerId: String?): String? = providerId?.let { providers[it]?.model }

    /** `providers.<id>.updatedAt` at or after [startedAtMs] (SP S7.3 step 4; ISO-8601 or epoch ms). */
    fun updatedSince(id: String, startedAtMs: Long): Boolean {
        val stamp = providers[id]?.updatedAt ?: return false
        val millis = stamp.toLongOrNull() ?: runCatching { java.time.Instant.parse(stamp).toEpochMilli() }.getOrNull() ?: return false
        return millis >= startedAtMs
    }

    companion object {
        val EMPTY = ConfiguredProviders(lastUsedProvider = null, providers = emptyMap())

        /** Providers that need no key (`tools/gen-provider-fallback.ts`, KS §0.6). */
        val KEYLESS_PROVIDER_IDS: Set<String> = setOf("ollama", "lmstudio")

        /**
         * Parses the file text. Throws on malformed JSON; the structure is read from the
         * element tree so no data class with an `apiKey` field is ever created.
         */
        fun parse(text: String): ConfiguredProviders {
            val root = CommandJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("providers.json is not an object")
            val providers = LinkedHashMap<String, ConfiguredProvider>()
            (root["providers"] as? JsonObject)?.forEach { (id, entryElement) ->
                val entry = entryElement as? JsonObject ?: return@forEach
                val settings = entry["settings"] as? JsonObject
                val auth = settings?.get("auth") as? JsonObject
                providers[id] = ConfiguredProvider(
                    id = id,
                    hasApiKey = settings?.get("apiKey").isNonBlankString(),
                    hasOAuth = auth?.get("accessToken").isNonBlankString(),
                    hasCloudCredentials = settings?.get("aws").isNonEmptyObject() ||
                        settings?.get("gcp").isNonEmptyObject() ||
                        settings?.get("sap").isNonEmptyObject(),
                    model = settings?.get("model").stringOrNull(),
                    hasBaseUrl = settings?.get("baseUrl").isNonBlankString(),
                    tokenSource = entry["tokenSource"].stringOrNull(),
                    updatedAt = entry["updatedAt"].stringOrNull(),
                )
            }
            return ConfiguredProviders(lastUsedProvider = root["lastUsedProvider"].stringOrNull(), providers = providers)
        }

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.isNonBlankString(): Boolean = !stringOrNull().isNullOrBlank()

        private fun JsonElement?.isNonEmptyObject(): Boolean = when (this) {
            is JsonObject -> values.any { value ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull?.isNotBlank() == true
                    is JsonObject -> value.isNotEmpty()
                    is JsonArray -> value.isNotEmpty()
                }
            }
            else -> false
        }
    }
}

/** Outcome of `readProviderPresence` (`cat providers.json`): a missing file means "no providers". */
sealed interface ProviderPresence {
    data class Present(val providers: ConfiguredProviders) : ProviderPresence
    data object Missing : ProviderPresence
    data class Failed(val detail: String?) : ProviderPresence

    /** The projection to use for UI decisions; Missing and Failed both read as empty. */
    val providersOrEmpty: ConfiguredProviders
        get() = (this as? Present)?.providers ?: ConfiguredProviders.EMPTY

    companion object {
        /** Reduces the RUN_COMMAND result inside the bridge; the raw stdout is not retained. */
        fun from(result: TermuxResult): ProviderPresence = when {
            result.ok -> runCatching { Present(ConfiguredProviders.parse(result.stdout)) }
                .getOrElse { Failed("providers.json could not be parsed") }
            result.noSuchFile -> Missing
            result.internalError -> Failed(result.errmsg)
            else -> Failed(result.lastStderrLine)
        }
    }
}

/** `readProviderPresence` end to end: the raw stdout never leaves this call. */
suspend fun TermuxExecutor.readProviderPresence(): ProviderPresence =
    ProviderPresence.from(run(ClineCommands.readProviderPresence()))
