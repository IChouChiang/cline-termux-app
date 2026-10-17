package dev.cline.termux.ui.marketplace

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.marketplace.CliStep
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The install / uninstall result lines (CR §3.5) come from strings_marketplace.xml, not the data layer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarketplaceResultCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `done results use the dashboard wording, the helper's message verbatim`() {
        assertEquals("Installed GitHub.", MarketplaceDone.Installed("GitHub", global = false).toUiText().resolve(res))
        assertEquals("Installed PR Review globally for Cline.", MarketplaceDone.Installed("PR Review", global = true).toUiText().resolve(res))
        assertEquals("Uninstalled GitHub.", MarketplaceDone.Uninstalled("GitHub").toUiText().resolve(res))
        assertEquals("PR Review is not installed.", MarketplaceDone.NotInstalled("PR Review").toUiText().resolve(res))
        assertEquals("Installed to ~/.cline", MarketplaceDone.Message("Installed to ~/.cline").toUiText().resolve(res))
    }

    @Test
    fun `CLI failures word the step and exit code, with a colon only when output follows`() {
        assertEquals("Plugin install failed with exit code 1:", MarketplaceFailure.Cli(CliStep.PluginInstall, 1, "clone failed").toUiText().resolve(res))
        assertEquals("MCP uninstall failed with exit code 2", MarketplaceFailure.Cli(CliStep.McpUninstall, 2, "").toUiText().resolve(res))
        assertEquals("Skill install failed with exit code 127:", MarketplaceFailure.Cli(CliStep.SkillInstall, 127, "npx: not found").toUiText().resolve(res))
        assertEquals("Plugin install expects exactly one argument.", MarketplaceFailure.PluginArguments.toUiText().resolve(res))
        assertEquals("boom", MarketplaceFailure.Repo(RepoFailure.Helper("boom")).toUiText().resolve(res))
    }

    @Test
    fun `every CLI step has its own sentence pair`() {
        val ids = CliStep.entries.flatMap { listOf(it.failedText(withOutput = false), it.failedText(withOutput = true)) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
