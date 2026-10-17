package dev.cline.termux.data.model

import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Model and reasoning selection (settings-providers.md S8.3, S8.4; docs/07 §4.5): DataStore
 * `model.selection` = `{lastProvider, lastModelByProvider}` (desktop
 * `cline.code.model-selection.v1`) and `model.reasoning` = `{providerId: level}` (desktop
 * `cline.code.reasoning-selection.v1`), written only on explicit picks, never when a
 * session's config is loaded ("Mirroring every provider/model prop change … would silently
 * replace the user's chosen default"). The pure rules the picker and composer need sit next
 * to it: Home defaults, the provider-chip model resolution (`handleProviderSelect`), the
 * reasoning chip label, and the S8.3 side outputs.
 */

/** The persistence the store needs; [AppPreferences] in the app, a map in tests. */
interface ModelSelectionPrefs {
    val selection: Flow<ModelSelection>
    suspend fun selection(): ModelSelection
    suspend fun writeSelection(selection: ModelSelection)
    val reasoning: Flow<Map<String, String>>
    suspend fun reasoning(): Map<String, String>
    suspend fun writeReasoning(providerId: String, level: String?)

    companion object {
        fun of(prefs: AppPreferences): ModelSelectionPrefs = object : ModelSelectionPrefs {
            override val selection: Flow<ModelSelection> get() = prefs.modelSelection
            override suspend fun selection(): ModelSelection = prefs.modelSelection()
            override suspend fun writeSelection(selection: ModelSelection) {
                prefs.setModelSelection(selection)
            }
            override val reasoning: Flow<Map<String, String>> get() = prefs.reasoningByProvider
            override suspend fun reasoning(): Map<String, String> = prefs.reasoningByProvider()
            override suspend fun writeReasoning(providerId: String, level: String?) {
                prefs.setReasoning(providerId, level)
            }
        }
    }
}

class ModelSelectionStore(private val prefs: ModelSelectionPrefs) {

    /** `{lastProvider, lastModelByProvider}`. */
    val selection: Flow<ModelSelection> get() = prefs.selection

    /** Remembered effort per provider; unknown levels are dropped (desktop parse). */
    val reasoning: Flow<Map<String, ReasoningEffort>>
        get() = prefs.reasoning.map(::parseLevels).distinctUntilChanged()

    suspend fun current(): ModelSelection = prefs.selection()

    suspend fun reasoningNow(): Map<String, ReasoningEffort> = parseLevels(prefs.reasoning())

    /** An explicit model pick (S8.3): remembers the provider and its model. */
    suspend fun recordPick(providerId: String, modelId: String) {
        val provider = providerId.trim()
        val model = modelId.trim()
        if (provider.isEmpty() || model.isEmpty()) return
        prefs.writeSelection(prefs.selection().withPick(provider, model))
    }

    /** A provider-chip pick (S8.3): remembers the provider, and the resolved model when there is one. */
    suspend fun recordProviderPick(providerId: String, modelId: String?) {
        val provider = providerId.trim()
        if (provider.isEmpty()) return
        val current = prefs.selection()
        val model = modelId?.trim().orEmpty()
        prefs.writeSelection(if (model.isEmpty()) current.copy(lastProvider = provider) else current.withPick(provider, model))
    }

    /**
     * `rememberProviderSelection` (onboarding-view.tsx): the provider just connected becomes
     * `lastProvider`; its default model is remembered only when known.
     */
    suspend fun rememberProviderSelection(providerId: String, defaultModelId: String? = null) {
        val current = prefs.selection()
        val model = defaultModelId?.trim().orEmpty()
        prefs.writeSelection(
            ModelSelection(
                lastProvider = providerId,
                lastModelByProvider = if (model.isEmpty()) current.lastModelByProvider else current.lastModelByProvider + (providerId to model),
            ),
        )
    }

    /** An explicit reasoning pick (S8.4), stored per provider. */
    suspend fun recordReasoning(providerId: String, effort: ReasoningEffort) {
        if (providerId.isBlank()) return
        prefs.writeReasoning(providerId, effort.wire)
    }

    suspend fun rememberedReasoning(providerId: String): ReasoningEffort? = reasoningNow()[providerId]

    private fun parseLevels(raw: Map<String, String>): Map<String, ReasoningEffort> =
        raw.mapNotNull { (provider, level) -> ReasoningEffort.parse(level)?.let { provider to it } }.toMap()

    companion object {
        fun of(prefs: AppPreferences) = ModelSelectionStore(ModelSelectionPrefs.of(prefs))
    }
}

/** A provider + model pair. */
data class ModelChoice(val providerId: String, val modelId: String?)

/**
 * S8.3 Home defaults: provider = remembered `lastProvider` if configured, else
 * `providers.json` `lastUsedProvider` (if configured), else the first configured provider in
 * catalog order; model = remembered for that provider, else its `defaultModelId`, else the
 * first offered chat model. Null when nothing is configured.
 */
fun resolveHomeSelection(selection: ModelSelection, catalog: CatalogState): ModelChoice? {
    val remembered = selection.lastProvider?.trim()?.takeIf { it.isNotEmpty() && catalog.configured(it) }
    val lastUsed = catalog.presence?.lastUsedProvider?.takeIf { catalog.configured(it) }
    val firstConfigured = catalog.providers.firstOrNull { catalog.configured(it.id) }?.id
        ?: catalog.presence?.configuredIds?.firstOrNull()
    val providerId = remembered ?: lastUsed ?: firstConfigured ?: return null
    val offered = catalog.chatModels(providerId).map { it.id }
    val model = selection.modelFor(providerId)?.takeIf { it.isNotBlank() }
        ?: catalog.provider(providerId)?.defaultModelId?.takeIf { it.isNotBlank() }
        ?: offered.firstOrNull()
    return ModelChoice(providerId, model)
}

/**
 * `handleProviderSelect`: the remembered model when the picker offers it or it is a
 * live-only id missing from the bundled list; otherwise the first provider model the picker
 * offers, else the first provider model.
 */
fun resolveProviderPickModel(remembered: String?, providerModelIds: List<String>, optionIds: Set<String>): String? {
    if (!remembered.isNullOrEmpty() && (remembered in optionIds || remembered !in providerModelIds)) return remembered
    return providerModelIds.firstOrNull { it in optionIds } ?: providerModelIds.firstOrNull()
}

/** S8.4 chip label (the UI maps it to "Reasoning", "None" or the level's label). */
sealed interface ReasoningChipLabel {
    /** No explicit choice and support unknown: "Reasoning". */
    data object Generic : ReasoningChipLabel
    data class Level(val effort: ReasoningEffort) : ReasoningChipLabel
}

fun reasoningChipLabel(explicit: ReasoningEffort?, supportsReasoning: Boolean?): ReasoningChipLabel = when {
    explicit != null -> ReasoningChipLabel.Level(explicit)
    supportsReasoning == false -> ReasoningChipLabel.Level(ReasoningEffort.None)
    supportsReasoning == null -> ReasoningChipLabel.Generic
    else -> ReasoningChipLabel.Level(ReasoningEffort.DEFAULT)
}

/** `sessionConfig.thinking` / `reasoningEffort` for an effort (None → thinking off, no effort). */
data class ThinkingConfig(val thinking: Boolean, val reasoningEffort: String?)

fun thinkingConfig(effort: ReasoningEffort): ThinkingConfig =
    if (effort == ReasoningEffort.None) ThinkingConfig(false, null) else ThinkingConfig(true, effort.wire)

/** S8.3 side outputs to the composer. */
data class ModelCapabilities(
    /** `inputModalities` contains image, else `supportsVision`, else null. */
    val supportsImages: Boolean?,
    /** The catalog's `supportsReasoning`; the bundle's A2 rule in fallback. */
    val supportsReasoning: Boolean?,
    /** For the usage ring; null hides it. */
    val contextWindow: Long?,
)

/**
 * The selected model's side outputs. A live or cached catalog decides on its own row (a
 * custom model's `models.json` `supportsReasoning` arrives there as the `reasoning`
 * capability; a model the catalog does not list is false). Only in fallback does the bundled
 * list answer, through [CatalogState.bundle] ([FallbackProviders.reasoningSupport]).
 */
fun modelCapabilities(catalog: CatalogState, providerId: String?, modelId: String?): ModelCapabilities {
    if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return ModelCapabilities(null, null, null)
    if (catalog.source == CatalogSource.Fallback) {
        return ModelCapabilities(supportsImages = null, supportsReasoning = catalog.bundle.reasoningSupport(providerId, modelId), contextWindow = null)
    }
    val model = catalog.chatModels(providerId).firstOrNull { it.id == modelId }
        ?: return ModelCapabilities(supportsImages = null, supportsReasoning = false, contextWindow = null)
    return ModelCapabilities(
        supportsImages = model.supportsImages,
        supportsReasoning = model.supportsReasoning == true,
        contextWindow = model.contextWindow,
    )
}
