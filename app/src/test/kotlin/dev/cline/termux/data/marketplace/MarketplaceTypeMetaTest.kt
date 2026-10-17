package dev.cline.termux.data.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceTypeMetaTest {
    private fun entry(id: String, type: MarketplaceType, tags: List<String> = emptyList(), featured: Boolean = false, name: String = id) =
        MarketplaceListEntry(id, type, name, "tagline $id", "desc", tags, "Author", featured)

    private val entries = listOf(
        entry("p1", MarketplaceType.Plugin, listOf("git")),
        entry("s1", MarketplaceType.Skill, listOf("docs")),
        entry("s2", MarketplaceType.Skill, listOf("git", "docs"), featured = true),
        entry("m1", MarketplaceType.Mcp, name = "Postgres"),
    )

    @Test fun `type metadata and wire parsing`() {
        assertEquals(MarketplaceType.Mcp, MarketplaceType.parse("mcp"))
        assertNull(MarketplaceType.parse("connector"))
        assertEquals(MarketplaceGlyph.Server, MarketplaceType.Mcp.meta.glyph)
        assertEquals(MarketplaceTint.Violet, MarketplaceType.Plugin.meta.tint)
        assertEquals("skill:s1", entryKey(MarketplaceType.Skill, "s1"))
    }

    @Test fun `filters by type and query including tag labels`() {
        val labels = mapOf("git" to "Version control")
        assertEquals(listOf("s1", "s2"), filterByTypeAndQuery(entries, MarketplaceType.Skill, "", labels).map { it.id })
        assertEquals(listOf("p1", "s2"), filterByTypeAndQuery(entries, null, " VERSION ", labels).map { it.id })
        assertEquals(listOf("m1"), filterByTypeAndQuery(entries, null, "postgres", labels).map { it.id })
        assertEquals(listOf("m1"), filterByTypeAndQuery(entries, null, "mcp author", labels).map { it.id })
    }

    @Test fun `tag counts and visible tags keep the selected one`() {
        val counts = tagCounts(entries)
        assertEquals(mapOf("git" to 2, "docs" to 2), counts)
        val tags = listOf(MarketplaceTagInfo("rare", "Rare", 1), MarketplaceTagInfo("git", "Git", 9), MarketplaceTagInfo("docs", "Docs", 5))
        assertEquals(listOf("git", "docs"), visibleTags(tags, counts, null).map { it.id })
        assertEquals(listOf("git", "docs", "rare"), visibleTags(tags, counts, "rare").map { it.id })
    }

    @Test fun `groups by maturity with featured first`() {
        val groups = groupByMaturity(entries, null)
        assertEquals(listOf(MarketplaceType.Skill, MarketplaceType.Mcp, MarketplaceType.Plugin), groups.map { it.type })
        assertEquals(listOf("s2", "s1"), groups[0].entries.map { it.id })
        assertEquals(listOf(MarketplaceType.Skill, MarketplaceType.Plugin), groupByMaturity(entries, "git").map { it.type })
    }

    @Test fun `type pills only for cross-type searches`() {
        assertTrue(showTypePills(null, "x"))
        assertFalse(showTypePills(null, "  "))
        assertFalse(showTypePills(MarketplaceType.Skill, "x"))
    }
}
