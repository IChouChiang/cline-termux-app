package dev.cline.termux.data.marketplace

import androidx.compose.runtime.Immutable

/*
 * Port of `TYPE_META`, `MATURITY_ORDER` and the pure list rules of
 * apps/examples/desktop-app/webview/components/views/marketplace-explorer-view.tsx
 * (design/screens/customize-and-rest.md §3). The desktop's Connectors type is desktop-only
 * (Composio) and not ported. Tints are named by [MarketplaceTint]; the UI resolves them to
 * M3 container roles (CR §3.5 "Type tints").
 */

/** `MarketplacePrimitiveType`. */
enum class MarketplaceType(val wire: String) {
    Skill("skill"), Mcp("mcp"), Plugin("plugin");

    companion object {
        fun parse(value: String?): MarketplaceType? = entries.firstOrNull { it.wire == value }
    }
}

/** The desktop's per-type hue (amber, sky, violet). */
enum class MarketplaceTint { Amber, Sky, Violet }

/** The desktop's lucide glyph per type (Zap, Server, Puzzle). */
enum class MarketplaceGlyph { Zap, Server, Puzzle }

/**
 * `MarketplaceTypeMeta`, visual half: glyph and tint. The desktop's label / plural / short /
 * blurb copy lives only in strings_marketplace.xml (`marketplace_type_*`, read through
 * ui/marketplace/MarketplaceCopy `MarketplaceType.copy()`), so there is one English source.
 */
@Immutable
data class MarketplaceTypeMeta(
    val glyph: MarketplaceGlyph,
    val tint: MarketplaceTint,
)

/** Ordered most-mature first: skills > MCP servers > plugins. */
val MATURITY_ORDER: List<MarketplaceType> = listOf(MarketplaceType.Skill, MarketplaceType.Mcp, MarketplaceType.Plugin)

/** `TYPE_META` (glyph and tint; the copy is in resources). */
val TYPE_META: Map<MarketplaceType, MarketplaceTypeMeta> = mapOf(
    MarketplaceType.Skill to MarketplaceTypeMeta(glyph = MarketplaceGlyph.Zap, tint = MarketplaceTint.Amber),
    MarketplaceType.Mcp to MarketplaceTypeMeta(glyph = MarketplaceGlyph.Server, tint = MarketplaceTint.Sky),
    MarketplaceType.Plugin to MarketplaceTypeMeta(glyph = MarketplaceGlyph.Puzzle, tint = MarketplaceTint.Violet),
)

val MarketplaceType.meta: MarketplaceTypeMeta get() = TYPE_META.getValue(this)

/** The fields of a catalog entry the list rules read. */
@Immutable
data class MarketplaceListEntry(
    val id: String,
    val type: MarketplaceType,
    val name: String,
    val tagline: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val authorName: String? = null,
    val featured: Boolean = false,
)

/** `entryKey`: "<type>:<id>", the installed-keys format. */
fun entryKey(type: MarketplaceType, id: String): String = "${type.wire}:$id"

/** `entrySearchText`: name, tagline, description, type, author and tag labels, lower-cased. */
fun entrySearchText(entry: MarketplaceListEntry, tagLabels: Map<String, String>): String =
    (listOf(entry.name, entry.tagline, entry.description, entry.type.wire, entry.authorName.orEmpty()) +
        entry.tags.map { tagLabels[it] ?: it })
        .joinToString(" ")
        .lowercase()

/** Type and query filtering (before tags, so tag counts reflect what each tag would narrow to). */
fun filterByTypeAndQuery(
    entries: List<MarketplaceListEntry>,
    type: MarketplaceType?,
    query: String,
    tagLabels: Map<String, String>,
): List<MarketplaceListEntry> {
    val normalized = query.trim().lowercase()
    return entries.filter { entry ->
        (type == null || entry.type == type) &&
            (normalized.isEmpty() || entrySearchText(entry, tagLabels).contains(normalized))
    }
}

/** Per-tag counts over the type/query-filtered entries. */
fun tagCounts(entries: List<MarketplaceListEntry>): Map<String, Int> =
    entries.flatMap { it.tags }.groupingBy { it }.eachCount()

/** A catalog tag (`MarketplaceTag`). */
@Immutable
data class MarketplaceTagInfo(val id: String, val label: String, val count: Int)

/**
 * Tags offered in the filter: those with matches, plus the selected one (an active filter
 * never hides its own option), sorted by the catalog's global count.
 */
fun visibleTags(tags: List<MarketplaceTagInfo>, counts: Map<String, Int>, selectedTag: String?): List<MarketplaceTagInfo> =
    tags.filter { (counts[it.id] ?: 0) > 0 || it.id == selectedTag }.sortedByDescending { it.count }

/** One list section. */
@Immutable
data class MarketplaceGroup(val type: MarketplaceType, val entries: List<MarketplaceListEntry>)

/** Sections in [MATURITY_ORDER], featured entries first, empty sections dropped. */
fun groupByMaturity(entries: List<MarketplaceListEntry>, selectedTag: String?): List<MarketplaceGroup> {
    val filtered = entries.filter { selectedTag == null || selectedTag in it.tags }
    return MATURITY_ORDER.map { type ->
        MarketplaceGroup(type, filtered.filter { it.type == type }.sortedByDescending { it.featured })
    }.filter { it.entries.isNotEmpty() }
}

/** Search results across every type name their type on each row. */
fun showTypePills(typeFilter: MarketplaceType?, query: String): Boolean = typeFilter == null && query.trim().isNotEmpty()
