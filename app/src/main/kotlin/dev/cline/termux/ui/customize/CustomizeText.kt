package dev.cline.termux.ui.customize

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.CustomizeRepository
import dev.cline.termux.data.customize.McpTransportType
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.Scope
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.components.FailureOverrides
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.HelperUnavailableReason
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.kernel.UiText

/*
 * Resource mapping for the typed results of the data layer (CR §0.5, §1.5; docs/07 §9).
 * A failure maps through the shared `RepoFailure.text()` (ui/components/FailureCopy.kt);
 * this file keeps the Customize-only forms.
 */

/** The short Termux forms (SP §0.5 card titles; CR §1.9 "tools: Termux is blocking Cline"). */
@StringRes
fun TermuxProblem.titleRes(): Int = when (this) {
    TermuxProblem.NotInstalled -> R.string.kernel_blocked_termux_title
    TermuxProblem.PermissionMissing -> R.string.kernel_blocked_permission_title
    TermuxProblem.ExternalAppsBlocked -> R.string.kernel_blocked_external_title
    TermuxProblem.TimedOut -> R.string.kernel_blocked_timeout_title
    TermuxProblem.StartFailed -> R.string.components_failure_termux_start
}

@Composable
fun TermuxProblem.text(): String = stringResource(titleRes())

/** A blocked RUN_COMMAND source → its `KernelBlockedCard`; null when the card has no variant. */
fun TermuxProblem.block(): KernelBlock? = when (this) {
    TermuxProblem.NotInstalled -> KernelBlock.TermuxMissing
    TermuxProblem.PermissionMissing -> KernelBlock.PermissionMissing
    TermuxProblem.ExternalAppsBlocked -> KernelBlock.ExternalAppsBlocked
    TermuxProblem.TimedOut -> KernelBlock.TimedOut
    TermuxProblem.StartFailed -> null
}

/**
 * "Partial results" bullet: `<source>: <message>` (CR §1.4). A blocked RUN_COMMAND source uses
 * the short Termux form, as the spec's "hooks: Termux did not answer".
 */
@Composable
fun CustomizeRepository.SourceWarning.text(): String =
    stringResource(R.string.customize_warning_line, source.label, failure.text(SourceWarningOverrides))

/** CR §1.4 / §1.9 bullets: the [TermuxProblem.titleRes] short forms for Termux failures. */
val SourceWarningOverrides = FailureOverrides { failure ->
    (failure as? RepoFailure.Termux)?.problem?.let { UiText.Res(it.titleRes()) }
}

/** The helper's unavailable state → the SP §0.3 card model, or the Termux card for prerequisite failures. */
sealed interface HelperSurface {
    data class Card(val model: HelperUnavailableModel) : HelperSurface
    data class Blocked(val block: KernelBlock) : HelperSurface
}

fun HelperState.Unavailable.surface(): HelperSurface = when (reason) {
    HelperFailure.TermuxMissing -> HelperSurface.Blocked(KernelBlock.TermuxMissing)
    HelperFailure.PermissionMissing -> HelperSurface.Blocked(KernelBlock.PermissionMissing)
    HelperFailure.ExternalAppsBlocked -> HelperSurface.Blocked(KernelBlock.ExternalAppsBlocked)
    HelperFailure.StartTimeout -> HelperSurface.Card(HelperUnavailableModel(HelperUnavailableReason.StartTimeout, detail))
    HelperFailure.SocketFailed -> HelperSurface.Card(HelperUnavailableModel(HelperUnavailableReason.SocketFailed, detail))
    HelperFailure.BuildUnsupported -> HelperSurface.Card(HelperUnavailableModel(HelperUnavailableReason.BuildUnsupported, detail))
    HelperFailure.PortInUse -> HelperSurface.Card(HelperUnavailableModel(HelperUnavailableReason.PortInUse, detail))
    HelperFailure.KernelStopped -> HelperSurface.Card(HelperUnavailableModel(HelperUnavailableReason.KernelStopped, detail))
}

@Composable
fun CustomizeTab.label(): String = stringResource(
    when (this) {
        CustomizeTab.Tools -> R.string.customize_tab_tools
        CustomizeTab.Plugins -> R.string.customize_tab_plugins
        CustomizeTab.Skills -> R.string.customize_tab_skills
        CustomizeTab.Rules -> R.string.customize_tab_rules
        CustomizeTab.Mcp -> R.string.customize_tab_mcp
        CustomizeTab.Hooks -> R.string.customize_tab_hooks
    },
)

@Composable
fun Scope.label(): String = stringResource(if (this == Scope.Project) R.string.customize_scope_project else R.string.customize_scope_global)

/** Desktop `TRANSPORT_TYPE_LABELS`, else the raw wire value. */
@Composable
fun McpServerCard.transportText(): String = when (transport) {
    McpTransportType.Stdio -> stringResource(R.string.customize_transport_stdio)
    McpTransportType.Sse -> stringResource(R.string.customize_transport_sse)
    McpTransportType.StreamableHttp -> stringResource(R.string.customize_transport_http)
    null -> transportRaw
}

@Composable
fun ContributionKind.label(): String = stringResource(
    when (this) {
        ContributionKind.Tools -> R.string.customize_tab_tools
        ContributionKind.Skills -> R.string.customize_tab_skills
        ContributionKind.Rules -> R.string.customize_tab_rules
        ContributionKind.Hooks -> R.string.customize_tab_hooks
        ContributionKind.Commands -> R.string.customize_group_commands
        ContributionKind.McpServers -> R.string.customize_group_mcp_servers
        ContributionKind.Providers -> R.string.customize_group_providers
        ContributionKind.Capabilities -> R.string.customize_group_capabilities
    },
)
