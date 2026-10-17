package dev.cline.termux.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.ui.components.HelperUnavailableModel
import dev.cline.termux.ui.components.HelperUnavailableReason
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.ShellNavigator

/*
 * Small seams shared by the Account, Agents and About packages: the KernelBlockedCard host
 * mapping (SP §0.5), the helper-state → card models, and the labelled plain copy.
 */

/** A RUN_COMMAND-blocking helper failure → its KernelBlockedCard (SP §0.5); null for the others. */
fun HelperFailure.toKernelBlock(): KernelBlock? = when (this) {
    HelperFailure.TermuxMissing -> KernelBlock.TermuxMissing
    HelperFailure.PermissionMissing -> KernelBlock.PermissionMissing
    HelperFailure.ExternalAppsBlocked -> KernelBlock.ExternalAppsBlocked
    else -> null
}

/** The helper unavailable card's model for a non-blocking failure (SP §0.3). */
fun HelperState.Unavailable.toCardModel(): HelperUnavailableModel? {
    val reason = when (this.reason) {
        HelperFailure.StartTimeout -> HelperUnavailableReason.StartTimeout
        HelperFailure.SocketFailed -> HelperUnavailableReason.SocketFailed
        HelperFailure.BuildUnsupported -> HelperUnavailableReason.BuildUnsupported
        HelperFailure.PortInUse -> HelperUnavailableReason.PortInUse
        HelperFailure.KernelStopped -> HelperUnavailableReason.KernelStopped
        HelperFailure.TermuxMissing, HelperFailure.PermissionMissing, HelperFailure.ExternalAppsBlocked -> return null
    }
    return HelperUnavailableModel(reason, detail)
}

/**
 * Performs a KernelBlockedCard action: Install Termux, the pushed repair screen (it owns the
 * runtime permission request and the app-settings route), a retry, or the status sheet's
 * diagnostics.
 */
fun ShellNavigator.onKernelBlockAction(block: KernelBlock, action: KernelBlockAction, retry: () -> Unit) {
    when (action) {
        KernelBlockAction.InstallTermux -> openRoot(KernelInstallTermux)
        KernelBlockAction.RequestPermission, KernelBlockAction.OpenAppSettings, KernelBlockAction.OpenKernelSetup ->
            openRoot(KernelRepair.of(block.checkId(), pushed = true))
        KernelBlockAction.Retry -> retry()
        KernelBlockAction.RunDiagnostics -> openKernelStatus(diagnostics = true)
    }
}

/** A blocked gate's card, or null. */
fun KernelGate.blockOrNull(): KernelBlock? = (this as? KernelGate.Blocked)?.let { KernelBlock.of(it) }

/**
 * A plain (non-secret) copy with the KS "Copied <label>" snackbar on API ≤ 32; API 33+ shows
 * the system clipboard confirmation instead (docs/07 §14).
 */
@Composable
fun rememberLabelledCopy(): (label: String, value: String) -> Unit {
    val copier = rememberCopier()
    val resources = LocalResources.current
    return remember(copier, resources) {
        { label, value -> copier.copy(label, value, confirmation = resources.getString(R.string.kernel_copied_value, label)) }
    }
}
