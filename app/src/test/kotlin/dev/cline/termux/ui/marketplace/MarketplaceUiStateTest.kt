package dev.cline.termux.ui.marketplace

import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.customize.block
import dev.cline.termux.data.marketplace.MarketplaceAuthor
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceCounts
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceEnvVar
import dev.cline.termux.data.marketplace.MarketplaceInstall
import dev.cline.termux.data.marketplace.MarketplaceTagInfo
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.ui.components.KernelBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A catalog in the shape of the live `catalog.json`, shared by the marketplace UI tests. */
internal object TestCatalog {
    fun entry(
        id: String,
        type: MarketplaceType,
        name: String = id,
        featured: Boolean? = null,
        tags: List<String> = emptyList(),
        verified: Boolean? = null,
        author: String? = null,
        homepage: String? = null,
        repo: String? = null,
        env: List<MarketplaceEnvVar>? = null,
        tagline: String = "$name tagline",
    ) = MarketplaceEntry(
        id = id, type = type, name = name, featured = featured, tagline = tagline, description = "$name description",
        tags = tags, author = author?.let { MarketplaceAuthor(it) }, homepage = homepage, repo = repo, verified = verified,
        install = MarketplaceInstall(listOf(id), "x", env),
    )

    val prReview = entry("pr-review", MarketplaceType.Skill, "PR Review", featured = true, tags = listOf("git"), verified = true, author = "Cline")
    val figmaSkill = entry("figma", MarketplaceType.Skill, "Figma", tags = listOf("design"))
    val github = entry(
        "github", MarketplaceType.Mcp, "GitHub", tags = listOf("git", "dev"), verified = true,
        repo = "https://github.com/github/github-mcp-server",
        env = listOf(
            MarketplaceEnvVar("GITHUB_HOST", required = false),
            MarketplaceEnvVar("GITHUB_TOKEN", required = true, url = "https://github.com/settings/tokens"),
            MarketplaceEnvVar("GITHUB_ORG"),
        ),
    )
    val linear = entry("linear", MarketplaceType.Mcp, "Linear", featured = true, tags = listOf("dev"), homepage = "https://linear.app", repo = "https://github.com/linear")
    val lint = entry("lint", MarketplaceType.Plugin, "Lint", tags = listOf("dev"))

    val catalog = MarketplaceCatalog(
        version = 2,
        generatedAt = "2026-10-01",
        baseUrl = null,
        counts = MarketplaceCounts(5, 1, 2, 2),
        tags = listOf(
            MarketplaceTagInfo("git", "Git", 2),
            MarketplaceTagInfo("dev", "Developer tools", 9),
            MarketplaceTagInfo("design", "Design", 1),
        ),
        // Deliberately not in maturity order: plugins first.
        entries = listOf(lint, github, figmaSkill, linear, prReview),
    )
}

class MarketplaceUiStateTest {
    private fun loaded(
        query: String = "",
        type: MarketplaceType? = null,
        tag: String? = null,
        installed: Set<String> = emptySet(),
        kernel: KernelGate = KernelGate.Ready,
    ) = MarketplaceUiState.derive(
        MarketplaceUiState(
            catalogState = MarketplaceCatalogState.Loaded(stale = false),
            catalog = TestCatalog.catalog,
            query = query,
            type = type,
            tag = tag,
            installedKeys = installed,
            installedReady = true,
            kernel = kernel,
            retained = TestCatalog.catalog.entries.associateBy { it.key },
        ),
    )

    @Test
    fun `sections follow maturity order with featured entries first`() {
        val state = loaded()
        assertEquals(listOf(MarketplaceType.Skill, MarketplaceType.Mcp, MarketplaceType.Plugin), state.sections.map { it.type })
        assertEquals(listOf("skill:pr-review", "skill:figma"), state.sections[0].rows.map { it.key })
        assertEquals(listOf("mcp:linear", "mcp:github"), state.sections[1].rows.map { it.key })
    }

    @Test
    fun `rows carry verified and installed`() {
        val rows = loaded(installed = setOf("mcp:github")).sections.flatMap { it.rows }.associateBy { it.key }
        assertTrue(rows.getValue("mcp:github").verified)
        assertTrue(rows.getValue("mcp:github").installed)
        assertFalse(rows.getValue("mcp:linear").verified)
        assertFalse(rows.getValue("mcp:linear").installed)
    }

    @Test
    fun `type counts cover the whole catalog regardless of filters`() {
        val state = loaded(type = MarketplaceType.Plugin, query = "zzz")
        assertEquals(2, state.typeCount(MarketplaceType.Skill))
        assertEquals(2, state.typeCount(MarketplaceType.Mcp))
        assertEquals(1, state.typeCount(MarketplaceType.Plugin))
    }

    @Test
    fun `type pills only when searching across every type`() {
        assertFalse(loaded().showTypePills)
        assertTrue(loaded(query = "git").showTypePills)
        assertFalse(loaded(query = "git", type = MarketplaceType.Mcp).showTypePills)
        assertFalse(loaded(query = "   ").showTypePills)
    }

    @Test
    fun `search matches name, tagline, description, type, author and tag labels`() {
        assertEquals(listOf("skill:pr-review"), loaded(query = "cline").sections.flatMap { it.rows }.map { it.key })
        // "Developer tools" is the label of tag "dev".
        assertEquals(
            setOf("mcp:github", "mcp:linear", "plugin:lint"),
            loaded(query = "developer").sections.flatMap { it.rows }.map { it.key }.toSet(),
        )
        assertEquals(setOf("mcp:github", "mcp:linear"), loaded(query = "MCP").sections.flatMap { it.rows }.map { it.key }.toSet())
    }

    @Test
    fun `visible tags count the type and query filter, sort by global count and keep the selected tag`() {
        val all = loaded()
        assertEquals(listOf("dev", "git", "design"), all.visibleTags.map { it.id })
        assertEquals(listOf(3, 2, 1), all.visibleTags.map { it.count })

        // Plugins have no "git" entry, but the selected tag stays listed (with zero) and the list empties.
        val plugins = loaded(type = MarketplaceType.Plugin, tag = "git")
        assertEquals(listOf("dev", "git"), plugins.visibleTags.map { it.id })
        assertEquals(0, plugins.visibleTags.single { it.id == "git" }.count)
        assertTrue(plugins.filteredEmpty)
        assertEquals("Git", plugins.selectedTagLabel)
    }

    @Test
    fun `tag filter narrows the sections`() {
        val state = loaded(tag = "git")
        assertEquals(listOf("skill:pr-review", "mcp:github"), state.sections.flatMap { it.rows }.map { it.key })
        assertTrue(state.showCategoryChip)
    }

    @Test
    fun `filters and chips are hidden until the catalog loads`() {
        val loading = MarketplaceUiState.derive(MarketplaceUiState())
        assertFalse(loading.showFilters)
        assertFalse(loading.showCategoryChip)
        assertFalse(loading.filteredEmpty)
        val error = MarketplaceUiState.derive(MarketplaceUiState(catalogState = MarketplaceCatalogState.Error(500), catalog = TestCatalog.catalog))
        assertFalse(error.showFilters)
        assertFalse(error.filteredEmpty)
        assertTrue(MarketplaceUiState.derive(MarketplaceUiState(catalogState = MarketplaceCatalogState.Loaded(stale = true), catalog = TestCatalog.catalog)).stale)
    }

    @Test
    fun `primary button follows desktop actionLabelFor`() {
        assertEquals(PrimaryButton.Checking, primaryButton(EntryAction.Installing(0), installed = true, installedReady = false))
        assertEquals(PrimaryButton.Installing, primaryButton(EntryAction.Installing(0), installed = false, installedReady = true))
        assertEquals(PrimaryButton.Uninstalling, primaryButton(EntryAction.Uninstalling(0), installed = true, installedReady = true))
        assertEquals(PrimaryButton.Uninstall, primaryButton(EntryAction.Idle, installed = true, installedReady = true))
        assertEquals(PrimaryButton.Install, primaryButton(EntryAction.Failed(RepoFailure.HubTimeout, install = true), installed = false, installedReady = true))
        assertEquals(PrimaryButton.Install, primaryButton(EntryAction.Done(MarketplaceDone.Uninstalled("GitHub"), installed = false), installed = false, installedReady = true))
    }

    @Test
    fun `detail resolves from the full catalog whatever the filters`() {
        val detail = loaded(type = MarketplaceType.Plugin, installed = setOf("mcp:github")).detail("mcp:github")!!
        assertEquals("GitHub", detail.entry.name)
        assertTrue(detail.installed)
        assertTrue(detail.buttonEnabled)
        assertEquals(PrimaryButton.Uninstall, detail.button)
        assertFalse(detail.vanished)
    }

    @Test
    fun `learn more prefers the homepage and falls back to the repo`() {
        val state = loaded()
        assertEquals("https://linear.app", state.detail("mcp:linear")!!.learnMoreUrl)
        assertEquals("https://github.com/github/github-mcp-server", state.detail("mcp:github")!!.learnMoreUrl)
        assertNull(state.detail("plugin:lint")!!.learnMoreUrl)
    }

    @Test
    fun `environment lists required variables before optional ones`() {
        val env = loaded().detail("mcp:github")!!.env
        assertEquals(listOf("GITHUB_TOKEN", "GITHUB_ORG", "GITHUB_HOST"), env.map { it.name })
    }

    @Test
    fun `tag labels fall back to the tag id`() {
        val detail = loaded().detail("mcp:github")!!
        assertEquals("Git", detail.tagLabel("git"))
        assertEquals("unknown", detail.tagLabel("unknown"))
    }

    @Test
    fun `an entry gone from a refreshed catalog stays with its last known data but cannot be installed`() {
        val refreshed = TestCatalog.catalog.copy(entries = TestCatalog.catalog.entries - TestCatalog.github)
        val state = loaded().copy(catalog = refreshed)
        val detail = state.detail("mcp:github")!!
        assertTrue(detail.vanished)
        assertFalse(detail.buttonEnabled)
        assertNull(state.detail("mcp:never-seen"))
    }

    @Test
    fun `kernel down or blocked disables install and uninstall`() {
        val down = loaded(kernel = KernelGate.Down(KernelGate.DownVariant.Stopped, null))
        assertTrue(down.kernelBlocksActions)
        assertFalse(down.detail("mcp:github")!!.buttonEnabled)
        assertTrue(loaded(kernel = KernelGate.Blocked(CheckId.Termux)).kernelBlocksActions)
        assertFalse(loaded(kernel = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)).kernelBlocksActions)
        assertFalse(loaded(kernel = KernelGate.DegradedNoCommands).kernelBlocksActions)
    }

    @Test
    fun `busy or unchecked entries disable the button`() {
        val busy = loaded().copy(actions = mapOf("mcp:github" to EntryAction.Installing(0))).detail("mcp:github")!!
        assertFalse(busy.buttonEnabled)
        val unchecked = loaded().copy(installedReady = false).detail("mcp:github")!!
        assertEquals(PrimaryButton.Checking, unchecked.button)
        assertFalse(unchecked.buttonEnabled)
    }

    @Test
    fun `installed action opens the customize tab of the type filter`() {
        assertEquals("skills", customizeTabFor(MarketplaceType.Skill))
        assertEquals("mcp", customizeTabFor(MarketplaceType.Mcp))
        assertEquals("plugins", customizeTabFor(MarketplaceType.Plugin))
        assertEquals("tools", customizeTabFor(null))
    }

    @Test
    fun `entry route round-trips the key`() {
        val route = marketplaceEntryRoute("mcp:github:enterprise")
        assertEquals("mcp", route.type)
        assertEquals("github:enterprise", route.id)
    }

    @Test
    fun `termux prerequisites render as their blocked card`() {
        assertEquals(KernelBlock.TermuxMissing, TermuxProblem.NotInstalled.block())
        assertEquals(KernelBlock.PermissionMissing, TermuxProblem.PermissionMissing.block())
        assertEquals(KernelBlock.ExternalAppsBlocked, TermuxProblem.ExternalAppsBlocked.block())
        assertEquals(KernelBlock.TimedOut, TermuxProblem.TimedOut.block())
        assertNull(TermuxProblem.StartFailed.block())
    }

    @Test
    fun `a failure longer than 12 lines collapses until show more`() {
        assertNull(collapsedFailure("one\ntwo"))
        assertNull(collapsedFailure((1..12).joinToString("\n")))
        assertEquals((1..12).joinToString("\n"), collapsedFailure((1..20).joinToString("\n")))
    }

    @Test
    fun `primary button labels`() {
        assertEquals(R.string.marketplace_checking, PrimaryButton.Checking.label())
        assertEquals(R.string.marketplace_installing, PrimaryButton.Installing.label())
        assertEquals(R.string.marketplace_uninstalling, PrimaryButton.Uninstalling.label())
        assertEquals(R.string.marketplace_install, PrimaryButton.Install.label())
        assertEquals(R.string.marketplace_uninstall, PrimaryButton.Uninstall.label())
    }
}
