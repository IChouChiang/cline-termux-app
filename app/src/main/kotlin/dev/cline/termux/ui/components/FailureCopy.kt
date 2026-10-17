package dev.cline.termux.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.R
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.kernel.UiText

/*
 * The one mapping of a typed repository failure to screen copy (customize-and-rest.md §0.5;
 * docs/07 §9), shared by Customize, the MCP editor, Marketplace, Schedules, Account, the
 * agents roster and About. Kernel, helper and CLI text is data and is shown verbatim
 * (DS §8.2 item 8); the typed cases get the CR §0.5 sentences of strings_components.xml.
 * A screen with its own wording for a case passes it as an override; everything else keeps
 * the shared sentence, so the same failure reads the same on every screen.
 */

/** Feature-specific copy for some failures; returns null to keep the shared sentence. */
fun interface FailureOverrides {
    fun textFor(failure: RepoFailure): UiText?

    companion object {
        val None = FailureOverrides { null }
    }
}

/** [this] failure as copy: [overrides] first, then the shared sentence. */
fun RepoFailure.toUiText(overrides: FailureOverrides = FailureOverrides.None): UiText =
    overrides.textFor(this) ?: sharedText()

/** [this] failure resolved against [resources] (ViewModel-free call sites: snackbars, events). */
fun RepoFailure.text(resources: Resources, overrides: FailureOverrides = FailureOverrides.None): String =
    toUiText(overrides).resolve(resources)

/** [this] failure resolved against the current configuration. */
@Composable
fun RepoFailure.text(overrides: FailureOverrides = FailureOverrides.None): String = text(LocalResources.current, overrides)

private fun RepoFailure.sharedText(): UiText = when (this) {
    is RepoFailure.Hub -> verbatim(message.ifBlank { code })
    RepoFailure.HubDisconnected -> UiText.Res(R.string.components_failure_hub_disconnected)
    RepoFailure.HubTimeout -> UiText.Res(R.string.components_failure_hub_timeout)
    RepoFailure.HubDraining -> UiText.Res(R.string.components_failure_hub_draining)
    is RepoFailure.Termux -> UiText.Res(problem.sentence())
    is RepoFailure.Command ->
        if (message.isBlank()) UiText.Res(R.string.components_failure_command_exit, exitCode) else UiText.Raw(message)
    is RepoFailure.HelperDown -> UiText.Res(R.string.components_failure_helper_down)
    is RepoFailure.Helper -> verbatim(message)
    RepoFailure.HelperLost -> UiText.Res(R.string.components_failure_helper_lost)
    RepoFailure.HelperTimeout -> UiText.Res(R.string.components_failure_helper_timeout)
    is RepoFailure.Malformed -> verbatim(message)
}

/** Kernel text verbatim; an empty reply gets the desktop's "Unknown error" instead of a blank line. */
private fun verbatim(text: String): UiText =
    if (text.isBlank()) UiText.Res(R.string.components_failure_unknown) else UiText.Raw(text)

private fun TermuxProblem.sentence(): Int = when (this) {
    TermuxProblem.NotInstalled -> R.string.components_failure_termux_missing
    TermuxProblem.PermissionMissing -> R.string.components_failure_termux_permission
    TermuxProblem.ExternalAppsBlocked -> R.string.components_failure_termux_external_apps
    TermuxProblem.TimedOut -> R.string.components_failure_termux_timeout
    TermuxProblem.StartFailed -> R.string.components_failure_termux_start
}
