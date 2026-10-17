package dev.cline.termux.ui.marketplace

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.marketplace.CliStep
import dev.cline.termux.data.marketplace.MarketplaceDone
import dev.cline.termux.data.marketplace.MarketplaceFailure
import dev.cline.termux.data.marketplace.MarketplaceGlyph
import dev.cline.termux.data.marketplace.MarketplaceTint
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.data.marketplace.meta
import dev.cline.termux.ui.components.FailureOverrides
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.customize.CustomizeTab
import dev.cline.termux.ui.components.toUiText
import dev.cline.termux.ui.components.toUnavailableModel
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.theme.ClineTheme

/*
 * Resource and colour mapping for the marketplace (customize-and-rest.md §3.5): the data
 * package keeps TYPE_META's glyph and tint and reports typed install results; every word
 * the screens show comes from strings_marketplace.xml through this file.
 */

/** TYPE_META as string resources. */
@Immutable
data class TypeCopy(
    @param:StringRes val label: Int,
    @param:StringRes val plural: Int,
    @param:StringRes val short: Int,
    @param:StringRes val blurb: Int,
)

fun MarketplaceType.copy(): TypeCopy = when (this) {
    MarketplaceType.Skill -> TypeCopy(
        R.string.marketplace_type_skill_label, R.string.marketplace_type_skill_plural,
        R.string.marketplace_type_skill_short, R.string.marketplace_type_skill_blurb,
    )
    MarketplaceType.Mcp -> TypeCopy(
        R.string.marketplace_type_mcp_label, R.string.marketplace_type_mcp_plural,
        R.string.marketplace_type_mcp_short, R.string.marketplace_type_mcp_blurb,
    )
    MarketplaceType.Plugin -> TypeCopy(
        R.string.marketplace_type_plugin_label, R.string.marketplace_type_plugin_plural,
        R.string.marketplace_type_plugin_short, R.string.marketplace_type_plugin_blurb,
    )
}

/** CR §3.5 type tints: the tile/pill container and the glyph/pill text, M3 roles only. */
@Immutable
data class TypeTint(val container: Color, val content: Color)

@Composable
fun MarketplaceType.tint(): TypeTint = when (meta.tint) {
    MarketplaceTint.Amber -> ClineTheme.colors.warning.let { TypeTint(it.container, it.onContainer) }
    MarketplaceTint.Sky -> ClineTheme.colors.info.let { TypeTint(it.container, it.onContainer) }
    MarketplaceTint.Violet -> TypeTint(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
}

/** CR §3.5 glyphs: `bolt` (desktop Zap), `dns` (desktop Server), `extension` (desktop Puzzle). */
val MarketplaceType.glyph: ImageVector
    @Composable get() = when (meta.glyph) {
        MarketplaceGlyph.Zap -> ClineIcons.Bolt
        MarketplaceGlyph.Server -> ClineIcons.Dns
        MarketplaceGlyph.Puzzle -> ClineIcons.Extension
    }

/** The Customize tab the "Installed" action opens for the active type filter (CR §3.7; Tools when All). */
fun customizeTabFor(type: MarketplaceType?): String = when (type) {
    MarketplaceType.Skill -> CustomizeTab.Skills
    MarketplaceType.Mcp -> CustomizeTab.Mcp
    MarketplaceType.Plugin -> CustomizeTab.Plugins
    null -> CustomizeTab.Tools
}.wire

/** The primary button's label. */
@StringRes
fun PrimaryButton.label(): Int = when (this) {
    PrimaryButton.Checking -> R.string.marketplace_checking
    PrimaryButton.Installing -> R.string.marketplace_installing
    PrimaryButton.Uninstalling -> R.string.marketplace_uninstalling
    PrimaryButton.Install -> R.string.marketplace_install
    PrimaryButton.Uninstall -> R.string.marketplace_uninstall
}

/** The catalog error line (CR §3.4, §3.5). */
@Composable
fun MarketplaceCatalogState.Error.text(): String =
    httpStatus?.let { stringResource(R.string.marketplace_http_error, it) } ?: stringResource(R.string.marketplace_network_error)

/**
 * The marketplace's own sentences for a failed install or uninstall (CR §3.4, §3.5); every
 * other case keeps the shared copy of `RepoFailure.toUiText()` (ui/components/FailureCopy.kt),
 * kernel and CLI text verbatim. Termux prerequisites render as a KernelBlockedCard instead
 * (`TermuxProblem.block()`, ui/customize/CustomizeText.kt).
 */
val MarketplaceFailureOverrides = FailureOverrides { failure ->
    when (failure) {
        is RepoFailure.HelperDown -> UiText.Res(R.string.marketplace_helper_body)
        RepoFailure.HelperLost, RepoFailure.HelperTimeout -> UiText.Res(R.string.marketplace_interrupted)
        else -> null
    }
}

/** A finished action's result line (CR §3.5): the helper's own message verbatim, otherwise the dashboard wording. */
fun MarketplaceDone.toUiText(): UiText = when (this) {
    is MarketplaceDone.Message -> UiText.Raw(text)
    is MarketplaceDone.Installed -> UiText.Res(if (global) R.string.marketplace_done_installed_global else R.string.marketplace_done_installed, name)
    is MarketplaceDone.Uninstalled -> UiText.Res(R.string.marketplace_done_uninstalled, name)
    is MarketplaceDone.NotInstalled -> UiText.Res(R.string.marketplace_done_not_installed, name)
}

/**
 * The worded part of a failed action. For [MarketplaceFailure.Cli] that is the headline only
 * ("MCP install failed with exit code 1:"); the CLI's own output renders below it as data.
 */
fun MarketplaceFailure.toUiText(): UiText = when (this) {
    is MarketplaceFailure.Repo -> failure.toUiText(MarketplaceFailureOverrides)
    is MarketplaceFailure.Cli -> UiText.Res(step.failedText(withOutput = output.isNotEmpty()), exitCode)
    MarketplaceFailure.PluginArguments -> UiText.Res(R.string.marketplace_plugin_arguments)
}

/** "<step> failed with exit code %1$d", ending with ":" when the CLI output follows. */
@StringRes
fun CliStep.failedText(withOutput: Boolean): Int = when (this) {
    CliStep.McpInstall -> if (withOutput) R.string.marketplace_cli_mcp_install_failed_output else R.string.marketplace_cli_mcp_install_failed
    CliStep.PluginInstall -> if (withOutput) R.string.marketplace_cli_plugin_install_failed_output else R.string.marketplace_cli_plugin_install_failed
    CliStep.SkillInstall -> if (withOutput) R.string.marketplace_cli_skill_install_failed_output else R.string.marketplace_cli_skill_install_failed
    CliStep.McpUninstall -> if (withOutput) R.string.marketplace_cli_mcp_uninstall_failed_output else R.string.marketplace_cli_mcp_uninstall_failed
    CliStep.PluginUninstall -> if (withOutput) R.string.marketplace_cli_plugin_uninstall_failed_output else R.string.marketplace_cli_plugin_uninstall_failed
    CliStep.SkillUninstall -> if (withOutput) R.string.marketplace_cli_skill_uninstall_failed_output else R.string.marketplace_cli_skill_uninstall_failed
}

/** CR §3.4: a failure shows at most 12 lines until "Show more" expands it. */
const val FAILURE_COLLAPSED_LINES = 12

/** The failure text cut to its first [FAILURE_COLLAPSED_LINES] lines, or null when it already fits. */
fun collapsedFailure(text: String, lines: Int = FAILURE_COLLAPSED_LINES): String? {
    val all = text.lines()
    return if (all.size <= lines) null else all.take(lines).joinToString("\n")
}

/** What a marketplace pane shows for an unavailable settings helper (CR §3.4, SP §0.3, §0.5). */
sealed interface MarketplaceHelperView {
    /** The shared SP §0.3 card, worded with the marketplace body. */
    data class Card(val model: HelperUnavailableModel) : MarketplaceHelperView

    /** A RUN_COMMAND block: its KernelBlockedCard. */
    data class Blocked(val block: KernelBlock) : MarketplaceHelperView

    /** Nothing: the kernel surface already shows the block. */
    data object None : MarketplaceHelperView
}

/**
 * The helper surface for [kernel]: the SP §0.3 card for a start failure, an unsupported build,
 * a busy port or a stopped kernel; the SP §0.5 KernelBlockedCard for a RUN_COMMAND block,
 * except while the gate is blocked (MarketplaceKernelSurface shows that card already).
 */
fun HelperState.Unavailable.marketplaceView(kernel: KernelGate): MarketplaceHelperView {
    toUnavailableModel()?.let { return MarketplaceHelperView.Card(it) }
    if (kernel is KernelGate.Blocked) return MarketplaceHelperView.None
    return when (reason) {
        HelperFailure.TermuxMissing -> MarketplaceHelperView.Blocked(KernelBlock.TermuxMissing)
        HelperFailure.PermissionMissing -> MarketplaceHelperView.Blocked(KernelBlock.PermissionMissing)
        HelperFailure.ExternalAppsBlocked -> MarketplaceHelperView.Blocked(KernelBlock.ExternalAppsBlocked)
        HelperFailure.StartTimeout, HelperFailure.SocketFailed, HelperFailure.BuildUnsupported,
        HelperFailure.PortInUse, HelperFailure.KernelStopped -> MarketplaceHelperView.None
    }
}
