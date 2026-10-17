package dev.cline.termux.ui.marketplace

import dev.cline.termux.data.marketplace.MarketplaceAuthor
import dev.cline.termux.data.marketplace.MarketplaceCatalog
import dev.cline.termux.data.marketplace.MarketplaceCounts
import dev.cline.termux.data.marketplace.MarketplaceEntry
import dev.cline.termux.data.marketplace.MarketplaceEnvVar
import dev.cline.termux.data.marketplace.MarketplaceInstall
import dev.cline.termux.data.marketplace.MarketplaceTagInfo
import dev.cline.termux.data.marketplace.MarketplaceType

/** A small catalog in the shape of `catalog.json`, for `@Preview`s only (CR §3.3 sketches). */
internal object MarketplacePreviewData {
    val github = MarketplaceEntry(
        id = "github",
        type = MarketplaceType.Mcp,
        name = "GitHub",
        featured = true,
        tagline = "Issues, PRs and repos as tools",
        description = "The official GitHub MCP server: search code, triage issues and review pull requests from Cline.",
        tags = listOf("developer-tools", "git"),
        author = MarketplaceAuthor("GitHub, Inc.", "https://github.com"),
        homepage = "https://github.com/github/github-mcp-server",
        verified = true,
        install = MarketplaceInstall(
            args = listOf("github", "--", "npx", "-y", "@github/mcp-server"),
            command = "cline mcp install",
            env = listOf(
                MarketplaceEnvVar("GITHUB_PERSONAL_ACCESS_TOKEN", required = true, description = "A token with repo scope.", url = "https://github.com/settings/tokens"),
                MarketplaceEnvVar("GITHUB_HOST", required = false, description = "For GitHub Enterprise Server."),
            ),
            notes = "Requires Docker or npx on the PATH inside Termux.",
        ),
    )

    private val review = MarketplaceEntry(
        id = "code-review",
        type = MarketplaceType.Skill,
        name = "Code review",
        featured = true,
        tagline = "Review diffs for bugs and style",
        description = "Walks through the working tree diff and reports bugs, risky changes and style issues.",
        tags = listOf("git"),
        author = MarketplaceAuthor("Cline"),
        install = MarketplaceInstall(listOf("cline/skills", "--skill", "code-review"), "npx skills add"),
    )

    private val figma = MarketplaceEntry(
        id = "figma",
        type = MarketplaceType.Skill,
        name = "Figma",
        tagline = "Turn Figma frames into components",
        description = "Reads a Figma frame and writes matching components.",
        tags = listOf("design"),
        verified = true,
        install = MarketplaceInstall(listOf("figma/skills"), "npx skills add"),
    )

    private val lint = MarketplaceEntry(
        id = "lint",
        type = MarketplaceType.Plugin,
        name = "Lint on edit",
        tagline = "Runs the project linter after every edit",
        description = "A hook bundle that lints edited files and reports problems to Cline.",
        tags = listOf("developer-tools"),
        install = MarketplaceInstall(listOf("https://example.com/lint.git"), "cline plugin install"),
    )

    val catalog = MarketplaceCatalog(
        version = 2,
        generatedAt = "2026-10-01",
        baseUrl = null,
        counts = MarketplaceCounts(total = 4, plugins = 1, skills = 2, mcps = 1),
        tags = listOf(
            MarketplaceTagInfo("developer-tools", "Developer tools", 2),
            MarketplaceTagInfo("git", "Git", 2),
            MarketplaceTagInfo("design", "Design", 1),
        ),
        entries = listOf(review, figma, github, lint),
    )

    fun loaded(stale: Boolean = false): MarketplaceUiState = MarketplaceUiState(
        catalogState = MarketplaceCatalogState.Loaded(stale),
        catalog = catalog,
        installedKeys = setOf(github.key),
        installedReady = true,
        retained = catalog.entries.associateBy { it.key },
    )
}
