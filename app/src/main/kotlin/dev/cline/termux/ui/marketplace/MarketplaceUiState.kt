package dev.cline.termux.ui.marketplace

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.marketplace.MATURITY_ORDER
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceEnvVar
import dev.cline.termux.data.marketplace.MarketplaceFailure
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.data.marketplace.entryKey
import dev.cline.termux.data.marketplace.filterByTypeAndQuery
import dev.cline.termux.data.marketplace.groupByMaturity
import dev.cline.termux.data.marketplace.showTypePills
import dev.cline.termux.data.marketplace.tagCounts
import dev.cline.termux.data.marketplace.visibleTags

/*
 * UI state of the marketplace list (C3) and entry detail (C4), customize-and-rest.md §3.4 and
 * §3.8, as pure data plus the pure functions that derive it. The list rules themselves
 * (search text, type/query filter, tag counts, visible tags, maturity grouping) are the data
 * package's port of marketplace-explorer-view.tsx; this file only assembles them.
 */

/** CR §3.8 `CatalogLoad` as the screen sees it (the catalog itself is [MarketplaceUiState.catalog]). */
sealed interface MarketplaceCatalogState {
    data object Loading : MarketplaceCatalogState

    /** [httpStatus] set for a non-2xx answer ("Failed to fetch marketplace: <status>"); null = no network and no cache. */
    data class Error(val httpStatus: Int?) : MarketplaceCatalogState

    /** [stale] = served from the OkHttp cache after the network failed ("Showing the last downloaded catalog."). */
    data class Loaded(val stale: Boolean) : MarketplaceCatalogState
}

/** Desktop `EntryActionState` (marketplace-explorer-view.tsx), one per entry key. */
sealed interface EntryAction {
    data object Idle : EntryAction

    data class Installing(val startedAtMs: Long) : EntryAction

    data class Uninstalling(val startedAtMs: Long) : EntryAction

    /** "installed" / "uninstalled": the typed result; MarketplaceCopy words it (the helper's own message stays verbatim). */
    data class Done(val done: MarketplaceDone, val installed: Boolean) : EntryAction

    /** "failed": the typed failure ([install] = which action, for a retry); the UI picks the copy (CR §0.5) and shows any CLI output as data. */
    data class Failed(val failure: MarketplaceFailure, val install: Boolean) : EntryAction {
        constructor(failure: RepoFailure, install: Boolean) : this(MarketplaceFailure.Repo(failure), install)
    }

    /** The outcome stayed unknown after re-reading installed keys (CR §3.9): "Check the result…". */
    data object Interrupted : EntryAction

    val busy: Boolean get() = this is Installing || this is Uninstalling
}

/** The detail's primary button (desktop `actionLabelFor`). */
enum class PrimaryButton { Checking, Installing, Uninstalling, Install, Uninstall }

/** Desktop `actionLabelFor(state, installed, ready)`. */
fun primaryButton(action: EntryAction, installed: Boolean, installedReady: Boolean): PrimaryButton = when {
    !installedReady -> PrimaryButton.Checking
    action is EntryAction.Installing -> PrimaryButton.Installing
    action is EntryAction.Uninstalling -> PrimaryButton.Uninstalling
    installed -> PrimaryButton.Uninstall
    else -> PrimaryButton.Install
}

/** One list row (desktop `MarketplaceListRow` props). */
@Immutable
data class MarketplaceRow(
    val key: String,
    val name: String,
    val tagline: String,
    val type: MarketplaceType,
    val verified: Boolean,
    val installed: Boolean,
)

/** One section: the type's header (glyph, plural, blurb) and its rows, featured first. */
@Immutable
data class MarketplaceSection(val type: MarketplaceType, val rows: List<MarketplaceRow>)

/** A category option: the tag, its label and how many of the type/query-filtered entries carry it. */
@Immutable
data class TagCount(val id: String, val label: String, val count: Int)

/** The open entry, resolved against the full catalog (or the last known copy when it vanished, CR §3.9). */
@Immutable
data class MarketplaceDetail(
    val entry: MarketplaceEntry,
    val installed: Boolean,
    val installedReady: Boolean,
    val action: EntryAction,
    /** The entry is no longer in the refreshed catalog: Install becomes unavailable. */
    val vanished: Boolean,
    /** Kernel down or Termux blocked (CR §3.4): Install/Uninstall disabled with the banner. */
    val kernelBlocked: Boolean,
    val tagLabels: Map<String, String>,
) {
    val key: String get() = entry.key
    val button: PrimaryButton get() = primaryButton(action, installed, installedReady)
    val buttonEnabled: Boolean get() = installedReady && !action.busy && !kernelBlocked && !vanished

    /** Homepage is usually the docs/product page; the repo is the fallback (desktop). */
    val learnMoreUrl: String? get() = entry.homepage ?: entry.repo

    /** Required variables first, then optional ones (`required !== false` counts as required). */
    val env: List<MarketplaceEnvVar>
        get() = entry.install.env.orEmpty().let { all -> all.filter { it.required != false } + all.filter { it.required == false } }

    fun tagLabel(tag: String): String = tagLabels[tag] ?: tag
}

/**
 * Everything C3 and C4 render. [query], [type], [tag] and [selectedKey] mirror the route's
 * `SavedStateHandle` (CR §3.8). [catalog] is the last catalog that loaded, kept through a
 * later error so an open detail stays (CR §3.4 "Catalog error"); [retained] keeps every entry
 * this screen has seen, for an entry that disappears from a refreshed catalog (CR §3.9).
 */
@Immutable
data class MarketplaceUiState(
    val catalogState: MarketplaceCatalogState = MarketplaceCatalogState.Loading,
    val catalog: MarketplaceCatalog? = null,
    val refreshing: Boolean = false,
    val query: String = "",
    val type: MarketplaceType? = null,
    val tag: String? = null,
    val sections: List<MarketplaceSection> = emptyList(),
    val visibleTags: List<TagCount> = emptyList(),
    val typeCounts: Map<MarketplaceType, Int> = emptyMap(),
    val showTypePills: Boolean = false,
    val installedKeys: Set<String> = emptySet(),
    val installedReady: Boolean = false,
    val installedApproximate: Boolean = false,
    val actions: Map<String, EntryAction> = emptyMap(),
    val selectedKey: String? = null,
    val helper: HelperState = HelperState.Idle,
    val kernel: KernelGate = KernelGate.Ready,
    val retained: Map<String, MarketplaceEntry> = emptyMap(),
) {
    /** Search and chips are shown only over a loaded catalog (hidden while loading and on error). */
    val showFilters: Boolean get() = catalogState is MarketplaceCatalogState.Loaded

    /** The category chip is offered only when a tag can be picked (desktop `visibleTags.length > 0`). */
    val showCategoryChip: Boolean get() = showFilters && visibleTags.isNotEmpty()

    /** "No entries match the current filters." */
    val filteredEmpty: Boolean get() = catalogState is MarketplaceCatalogState.Loaded && sections.isEmpty()

    val stale: Boolean get() = (catalogState as? MarketplaceCatalogState.Loaded)?.stale == true

    /** The category chip's label: the tag's label, or null for "All categories". */
    val selectedTagLabel: String? get() = tag?.let { catalog?.tagLabels?.get(it) ?: it }

    /** CR §3.4 "Kernel down / Termux blocked": install and uninstall are disabled. */
    val kernelBlocksActions: Boolean get() = kernel is KernelGate.Down || kernel is KernelGate.Blocked

    /** The total per type over the whole catalog (desktop counts ignore the filters). */
    fun typeCount(type: MarketplaceType): Int = typeCounts[type] ?: 0

    fun action(key: String): EntryAction = actions[key] ?: EntryAction.Idle

    /** C4 for [key]: from the current catalog, else the retained copy (marked vanished once a catalog has loaded). */
    fun detail(key: String): MarketplaceDetail? {
        val current = catalog?.find(key)
        val entry = current ?: retained[key] ?: return null
        return MarketplaceDetail(
            entry = entry,
            installed = key in installedKeys,
            installedReady = installedReady,
            action = action(key),
            vanished = current == null,
            kernelBlocked = kernelBlocksActions,
            tagLabels = catalog?.tagLabels.orEmpty(),
        )
    }

    companion object {
        /** Recomputes the derived list fields from the catalog, filters and installed keys. */
        fun derive(state: MarketplaceUiState): MarketplaceUiState {
            val catalog = state.catalog ?: return state.copy(
                sections = emptyList(), visibleTags = emptyList(), typeCounts = emptyMap(),
                showTypePills = showTypePills(state.type, state.query),
            )
            val tagLabels = catalog.tagLabels
            val byKey = catalog.entries.associateBy { it.key }
            val listEntries = catalog.entries.map { it.toListEntry() }
            val typeAndQuery = filterByTypeAndQuery(listEntries, state.type, state.query, tagLabels)
            val counts = tagCounts(typeAndQuery)
            val tags = visibleTags(catalog.tags, counts, state.tag).map { TagCount(it.id, it.label, counts[it.id] ?: 0) }
            val sections = groupByMaturity(typeAndQuery, state.tag).map { group ->
                MarketplaceSection(
                    type = group.type,
                    rows = group.entries.map { entry ->
                        val key = entryKey(entry.type, entry.id)
                        MarketplaceRow(
                            key = key,
                            name = entry.name,
                            tagline = entry.tagline,
                            type = entry.type,
                            verified = byKey[key]?.verified == true,
                            installed = key in state.installedKeys,
                        )
                    },
                )
            }
            val typeCounts = MATURITY_ORDER.associateWith { type -> catalog.entries.count { it.type == type } }
            return state.copy(
                sections = sections,
                visibleTags = tags,
                typeCounts = typeCounts,
                showTypePills = showTypePills(state.type, state.query),
            )
        }
    }
}
