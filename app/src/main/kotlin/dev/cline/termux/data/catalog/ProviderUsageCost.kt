package dev.cline.termux.data.catalog

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.data.chat.normalizeProviderId
import dev.cline.termux.data.providers.ProviderListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Whether a provider's usage cost is shown (A11.9 Verbose stats). The terminal's verbose line
 * reports a turn's cost only when `shouldShowCliUsageCost(providerId)` is true
 * (apps/cli/src/tui/hooks/use-prompt-input-controller.ts, the "done" entry), which is
 * `@cline/llms` `shouldShowProviderUsageCost`: the provider's `metadata.usageCostDisplay`
 * resolves to "show" (the default for a missing or unknown value). Subscription providers
 * (openai-codex, openai-codex-cli, claude-code, Cline Pass in cli-v3.0.69) price their runs
 * from a per-token catalog, so the run result carries a cost the user does not pay.
 *
 * The values come from `res/raw/provider_usage_cost.json`, generated from the kernel's own
 * resolver by tools/catalog/export-usage-cost-display.ts; a live catalog row that carries
 * `usageCostDisplay` wins over the bundle ([withLive]). Today's settings helper sends none.
 */

/** `metadata.usageCostDisplay` per provider id; ids not listed are "show". Equal by value. */
@Immutable
class ProviderUsageCost(private val displays: Map<String, String>) {
    /** The provider's `usageCostDisplay` ("show", "hide" or "subscription"); "show" when unknown or blank. */
    fun display(providerId: String?): String {
        val id = providerId?.trim().orEmpty()
        if (id.isEmpty()) return SHOW
        return displays[id] ?: displays[normalizeProviderId(id)] ?: SHOW
    }

    /** `shouldShowProviderUsageCost`: a cost is shown only when the display is "show". */
    fun showsCost(providerId: String?): Boolean = display(providerId) == SHOW

    /** The live catalog's values where its rows carry one, over this bundle's. */
    fun withLive(providers: List<ProviderListItem>): ProviderUsageCost {
        val live = providers.mapNotNull { row -> row.usageCostDisplay?.trim()?.takeIf { it in DISPLAYS }?.let { row.id to it } }
        if (live.isEmpty()) return this
        return ProviderUsageCost(displays + live)
    }

    override fun equals(other: Any?): Boolean = other is ProviderUsageCost && other.displays == displays

    override fun hashCode(): Int = displays.hashCode()

    override fun toString(): String = "ProviderUsageCost($displays)"

    @Serializable
    private data class File(val schema: Int = 1, val providers: Map<String, String> = emptyMap())

    companion object {
        const val SHOW = "show"

        /** `USAGE_COST_DISPLAYS` (sdk/packages/shared/src/llms/gateway.ts). */
        val DISPLAYS: Set<String> = setOf(SHOW, "hide", "subscription")

        /** Nothing known: every cost is shown. */
        val EMPTY = ProviderUsageCost(emptyMap())

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): ProviderUsageCost {
            val file = json.decodeFromString(File.serializer(), text)
            return ProviderUsageCost(file.providers.filter { (id, display) -> id.isNotBlank() && display in DISPLAYS })
        }

        /** Reads `res/raw/provider_usage_cost.json` off the main thread; [EMPTY] when it cannot be read. */
        suspend fun load(context: Context): ProviderUsageCost = withContext(Dispatchers.IO) {
            runCatching {
                context.resources.openRawResource(R.raw.provider_usage_cost).bufferedReader().use { parse(it.readText()) }
            }.getOrDefault(EMPTY)
        }
    }
}
