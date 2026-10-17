package dev.cline.termux.ui.model

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.model.CapabilityBadge
import dev.cline.termux.data.model.ModelPickerData
import dev.cline.termux.data.model.PickerModel
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.model.buildModelPickerData
import dev.cline.termux.data.model.capabilityBadges
import dev.cline.termux.data.model.freeTextModelId
import dev.cline.termux.data.model.matches
import dev.cline.termux.data.model.withCurrentModel
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderModel

/** Where the picker was opened from (SP S8.3): Home (no session yet) or a session, with its liveness. */
sealed interface PickerHost {
    data object Home : PickerHost

    data class Session(val sessionId: String, val live: Boolean) : PickerHost
}

/** One provider chip / provider-column row. */
@Immutable
data class PickerProvider(val id: String, val name: String, val selected: Boolean)

/**
 * One model row (SP S8.5). [label] and [tierBadge] are catalog data (shown untranslated);
 * [section] is the `buildModelPickerData` section id the row sits in.
 */
@Immutable
data class PickerRow(
    val id: String,
    val label: String,
    /** The model description, when the catalog has one; the row shows the id otherwise. */
    val supporting: String?,
    val tierBadge: String?,
    val images: Boolean,
    val reasoning: Boolean,
    val section: String?,
) {
    /**
     * The row's mono id line: shown only when there is no description and the id adds something
     * the label does not already say (the desktop SearchCombobox draws no second line otherwise).
     */
    val showsIdLine: Boolean get() = supporting == null && id != label
}

/**
 * The TalkBack label of a model row: label, section name, capability badges and tier badge, folded
 * pairwise through [join] (the `model_picker_a11y_join` resource) so a translation picks its own
 * separator (DS §8.2.3) instead of a hard-coded ", ".
 */
fun modelRowA11yLabel(
    row: PickerRow,
    sectionLabel: String?,
    imagesLabel: String,
    reasoningLabel: String,
    join: (String, String) -> String,
): String = listOfNotNull(
    row.label,
    sectionLabel,
    imagesLabel.takeIf { row.images },
    reasoningLabel.takeIf { row.reasoning },
    row.tierBadge,
).reduce(join)

/**
 * A group of rows. [id] null = the flat list (no header). Header and description copy is
 * mapped from [id] by the UI (`recommended`, `free`, `all`, `subscribed`, `current`); [clinePass]
 * picks the ClinePass wording of the Free description.
 */
@Immutable
data class PickerSectionUi(val id: String?, val clinePass: Boolean, val rows: List<PickerRow>)

/** SP S8.5 `ModelPickerUiState`. */
@Immutable
data class ModelPickerUiState(
    val host: PickerHost = PickerHost.Home,
    /** A run is in flight on the hosting session: rows and chips are disabled, the busy line shows. */
    val busy: Boolean = false,
    val providers: List<PickerProvider> = emptyList(),
    val activeProviderId: String? = null,
    val query: String = "",
    val providerQuery: String = "",
    val sections: List<PickerSectionUi> = emptyList(),
    /** The free-text id when the search matches nothing (S8.2): "Use model id “…”". */
    val freeTextRow: String? = null,
    val selectedModelId: String? = null,
    /** A refresh of the active provider's models (or the catalog) is in flight. */
    val loading: Boolean = false,
    val source: CatalogSource = CatalogSource.Fallback,
    /** The model whose `session.update_connection` is in flight (16dp row spinner). */
    val pendingModelId: String? = null,
    /** The provider chip whose `session.update_connection` is in flight. */
    val pendingProviderId: String? = null,
    /** The helper is not running: "Refresh models" acquires it on demand. */
    val canRefresh: Boolean = false,
    /** Nothing is configured yet (First run): only "Set up another provider". */
    val noProviders: Boolean = false,
    /** "No models found." (an empty list, or a search with no match and no free-text row). */
    val noModels: Boolean = false,
) {
    /** Rows and chips accept taps: not busy and no switch in flight. */
    val interactive: Boolean get() = !busy && pendingModelId == null && pendingProviderId == null

    /** The provider column shows its search only with more than six providers (two-column layout). */
    val showProviderSearch: Boolean get() = providers.size > PROVIDER_SEARCH_THRESHOLD

    /** [providers] filtered by [providerQuery] (two-column layout). */
    val visibleProviders: List<PickerProvider>
        get() {
            val needle = providerQuery.trim()
            if (needle.isEmpty() || !showProviderSearch) return providers
            return providers.filter { it.name.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true) }
        }

    val showFallbackNotice: Boolean get() = source == CatalogSource.Fallback && !noProviders

    /** Index of the selected provider in [providers] (catalog order), or -1; the chip row keeps it in view. */
    val selectedProviderIndex: Int get() = providers.indexOfFirst { it.selected }

    companion object {
        const val PROVIDER_SEARCH_THRESHOLD = 6
    }
}

/** Results the hosting screen (the composer) folds into its own state. */
sealed interface ModelPickerResult {
    /** Home: the next `session.create` uses this pair. Live session: `update_connection` succeeded. */
    data class Selected(val host: PickerHost, val providerId: String, val modelId: String?) : ModelPickerResult

    /**
     * Not-live session (SP S8.3): store as the session's pending connection; the resume
     * `session.create {sessionConfig:{sessionId, providerId, modelId, thinking, reasoningEffort}}`
     * on the next send applies it.
     */
    data class PendingConnection(val sessionId: String, val update: ConnectionUpdate) : ModelPickerResult

    /** S8.4: the explicit thinking level for the host (Home: sent at `session.create`). */
    data class ReasoningSelected(val host: PickerHost, val providerId: String, val effort: ReasoningEffort) : ModelPickerResult
}

/** One-shot effects for the sheet host. */
sealed interface ModelPickerEffect {
    data object Close : ModelPickerEffect

    /** "Could not switch model: <message>" (kernel text). */
    data class SwitchFailed(val message: String) : ModelPickerEffect

    /** A thinking-level change failed on a live session. */
    data class ReasoningFailed(val message: String) : ModelPickerEffect

    data class Result(val result: ModelPickerResult) : ModelPickerEffect
}

/** The rows of [providerId] for [query] (SP S8.2), pure so it is unit-tested. */
data class PickerSections(val sections: List<PickerSectionUi>, val freeTextRow: String?, val noModels: Boolean)

/**
 * The per-provider part of [ModelPickerSections.build]: the provider's chat models and their
 * `buildModelPickerData`. It depends only on the provider and its model list, so it is computed
 * once per list and reused for every search keystroke ([ModelPickerDataCache]).
 */
class PreparedPickerModels internal constructor(
    val providerId: String,
    internal val byId: Map<String, PickerModel>,
    internal val data: ModelPickerData,
)

/**
 * Memo of [ModelPickerSections.prepare] for the last provider (docs/07 §8: catalog filtering runs
 * on `Dispatchers.Default`, and a keystroke only re-filters). Keyed by the provider id and the
 * identity of `CatalogState.modelsByProvider[providerId]`: [CatalogState] is immutable, so every
 * catalog load, `list_provider_models` refresh or invalidation that changes the list replaces
 * that list instance, while unrelated state copies (loading, generation, presence) keep it.
 * Not thread-safe: one collector (the picker's state pipeline) owns an instance.
 */
class ModelPickerDataCache {
    private var providerId: String? = null
    private var source: List<ProviderModel>? = null
    private var prepared: PreparedPickerModels? = null

    fun get(catalog: CatalogState, providerId: String): PreparedPickerModels {
        val list = catalog.modelsByProvider[providerId]
        val hit = prepared
        if (hit != null && this.providerId == providerId && source === list) return hit
        return ModelPickerSections.prepare(catalog, providerId).also {
            this.providerId = providerId
            source = list
            prepared = it
        }
    }
}

object ModelPickerSections {
    /** The provider's chat models as picker models plus their `buildModelPickerData`. */
    fun prepare(catalog: CatalogState, providerId: String): PreparedPickerModels {
        val models = catalog.chatModels(providerId).map { it.toPickerModel() }
        return PreparedPickerModels(providerId, models.associateBy { it.id }, buildModelPickerData(providerId, models))
    }

    /**
     * `buildModelPickerData` over the provider's chat models, a selected id outside the offer
     * (under "Current model" when the offer is sectioned, a plain row in a flat list), the
     * SearchCombobox match on `"<label> <description> <id>"` (section headers stay for every
     * section that still has a row), and the free-text row.
     */
    fun build(catalog: CatalogState, providerId: String?, selectedModelId: String?, query: String): PickerSections {
        if (providerId.isNullOrBlank()) return PickerSections(emptyList(), null, noModels = false)
        return build(prepare(catalog, providerId), selectedModelId, query)
    }

    /** [build] over an already [prepare]d provider: only the current-model row, filter and regroup. */
    fun build(prepared: PreparedPickerModels, selectedModelId: String?, query: String): PickerSections {
        val byId = prepared.byId
        val data = prepared.data.withCurrentModel(selectedModelId, selectedModelId?.trim()?.let { byId[it]?.name })
        val freeText = data.freeTextModelId(query)
        val matching = data.options.filter { it.matches(query) }
        val clinePass = prepared.providerId == CLINE_PASS
        val order = data.sections?.takeIf { it.isNotEmpty() }?.map { it.id }
        val grouped = matching.groupBy { it.section }
        val sections = buildList {
            if (order == null) {
                if (matching.isNotEmpty()) add(PickerSectionUi(null, clinePass, matching.map { it.toRow(byId) }))
            } else {
                for (id in order) grouped[id]?.let { rows -> add(PickerSectionUi(id, clinePass, rows.map { it.toRow(byId) })) }
            }
        }
        return PickerSections(sections, freeText, noModels = sections.isEmpty() && freeText == null)
    }

    private fun dev.cline.termux.data.model.PickerOption.toRow(byId: Map<String, PickerModel>): PickerRow {
        val badges = byId[value]?.capabilityBadges().orEmpty()
        return PickerRow(
            id = value,
            label = label,
            supporting = description,
            tierBadge = badge,
            images = CapabilityBadge.Images in badges,
            reasoning = CapabilityBadge.Reasoning in badges,
            section = section,
        )
    }

    private const val CLINE_PASS = "cline-pass"
}
