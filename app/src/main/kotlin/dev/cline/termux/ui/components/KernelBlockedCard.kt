package dev.cline.termux.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.theme.Spacing

/**
 * The short Termux failure forms of settings-providers.md §0.5 (KC recovery matrix). The long
 * explanations live in Kernel setup; [KernelSetup] is the Android row for prerequisites the
 * table does not name (an old or missing port, an unfinished Termux bootstrap).
 */
enum class KernelBlock {
    TermuxMissing, PermissionMissing, ExternalAppsBlocked, TimedOut, KernelSetup;

    /** The Kernel setup checklist row this card's fix opens on. */
    fun checkId(): CheckId = when (this) {
        TermuxMissing -> CheckId.Termux
        PermissionMissing -> CheckId.Permission
        ExternalAppsBlocked -> CheckId.ExternalApps
        TimedOut, KernelSetup -> CheckId.Kernel
    }

    companion object {
        /** A RUN_COMMAND failure → its card; [TermuxUnavailable.StartFailed] has no card (callers show their own error). */
        fun of(error: TermuxUnavailable): KernelBlock? = when (error) {
            is TermuxUnavailable.NotInstalled -> TermuxMissing
            is TermuxUnavailable.PermissionMissing -> PermissionMissing
            is TermuxUnavailable.ExternalAppsBlocked -> ExternalAppsBlocked
            is TermuxUnavailable.TimedOut -> TimedOut
            is TermuxUnavailable.StartFailed -> null
        }

        /** A blocked kernel gate → its card. */
        fun of(gate: KernelGate.Blocked): KernelBlock = when (gate.focus) {
            CheckId.Termux -> TermuxMissing
            CheckId.Permission -> PermissionMissing
            CheckId.ExternalApps -> ExternalAppsBlocked
            CheckId.Kernel, CheckId.Hub -> KernelSetup
        }
    }
}

/** What the card's button does; the host maps each to its navigation or request. */
enum class KernelBlockAction {
    /** → Install Termux screen. */
    InstallTermux,

    /** Runtime request for `com.termux.permission.RUN_COMMAND`. */
    RequestPermission,

    /** Permanent denial: the app's details page in system settings. */
    OpenAppSettings,

    /** → Kernel setup (pushed repair variant). */
    OpenKernelSetup,

    /** Re-run the command that timed out. */
    Retry,

    /** `cline doctor --json` (the overflow item of the timeout card). */
    RunDiagnostics,
}

/**
 * SP §0.5 KernelBlockedCard: `Surface(errorContainer, shape = large)`, 16dp padding, `error`
 * icon, `titleSmall` title, `bodyMedium` body and one `TextButton`; the timeout card adds an
 * overflow with "Run diagnostics". [permissionPermanentlyDenied] switches "Allow" to "Open app
 * settings".
 */
@Composable
fun KernelBlockedCard(
    block: KernelBlock,
    onAction: (KernelBlockAction) -> Unit,
    modifier: Modifier = Modifier,
    permissionPermanentlyDenied: Boolean = false,
) {
    val title: Int
    val body: Int
    val action: KernelBlockAction
    val actionLabel: Int
    when (block) {
        KernelBlock.TermuxMissing -> {
            title = R.string.kernel_blocked_termux_title; body = R.string.kernel_blocked_termux_body
            action = KernelBlockAction.InstallTermux; actionLabel = R.string.kernel_blocked_termux_action
        }
        KernelBlock.PermissionMissing -> {
            title = R.string.kernel_blocked_permission_title; body = R.string.kernel_blocked_permission_body
            if (permissionPermanentlyDenied) {
                action = KernelBlockAction.OpenAppSettings; actionLabel = R.string.kernel_blocked_permission_settings
            } else {
                action = KernelBlockAction.RequestPermission; actionLabel = R.string.kernel_blocked_permission_action
            }
        }
        KernelBlock.ExternalAppsBlocked -> {
            title = R.string.kernel_blocked_external_title; body = R.string.kernel_blocked_external_body
            action = KernelBlockAction.OpenKernelSetup; actionLabel = R.string.kernel_blocked_open_setup
        }
        KernelBlock.TimedOut -> {
            title = R.string.kernel_blocked_timeout_title; body = R.string.kernel_blocked_timeout_body
            action = KernelBlockAction.Retry; actionLabel = R.string.kernel_blocked_retry
        }
        KernelBlock.KernelSetup -> {
            title = R.string.kernel_fix_kernel; body = R.string.kernel_blocked_setup_body
            action = KernelBlockAction.OpenKernelSetup; actionLabel = R.string.kernel_blocked_open_setup
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAction(action) }) { Text(stringResource(actionLabel)) }
                    if (block == KernelBlock.TimedOut) {
                        Spacer(Modifier.weight(1f))
                        TimeoutOverflow(onRunDiagnostics = { onAction(KernelBlockAction.RunDiagnostics) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeoutOverflow(onRunDiagnostics: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(ClineIcons.MoreVert, contentDescription = stringResource(R.string.kernel_blocked_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kernel_run_diagnostics)) },
                onClick = {
                    open = false
                    onRunDiagnostics()
                },
            )
        }
    }
}

/** A host's card actions: [onAction] performs one, [permissionPermanentlyDenied] switches "Allow" to "Open app settings". */
@Stable
class KernelBlockHandler(
    val onAction: (KernelBlock, KernelBlockAction) -> Unit,
    /** The permission was denied for good: "Allow" becomes "Open app settings". */
    val permissionPermanentlyDenied: Boolean,
)

/**
 * The SP §0.5 card actions, shared by every host that navigates through the shell: Install
 * Termux and Kernel setup (focused on [KernelBlock.checkId]) are root screens over the shell;
 * "Allow" makes the runtime request for Termux's RUN_COMMAND permission, a grant re-runs
 * [onRetry], and only a denial without rationale (permanent) switches the card to "Open app
 * settings"; Retry re-runs [onRetry]; "Run diagnostics" opens the Kernel status sheet's
 * diagnostics (KS E.6).
 */
@Composable
fun rememberKernelBlockHandler(navigator: ShellNavigator?, onRetry: () -> Unit): KernelBlockHandler {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetry)
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permanentlyDenied = false
            retry()
        } else {
            val activity = context.findActivity()
            permanentlyDenied = activity != null && !activity.shouldShowRequestPermissionRationale(TermuxRunner.PERMISSION)
        }
    }
    return remember(navigator, permanentlyDenied, launcher, context) {
        KernelBlockHandler(
            onAction = { block, action ->
                when (action) {
                    KernelBlockAction.InstallTermux -> navigator?.openRoot(KernelInstallTermux)
                    KernelBlockAction.RequestPermission -> launcher.launch(TermuxRunner.PERMISSION)
                    KernelBlockAction.OpenAppSettings -> context.openAppDetails()
                    KernelBlockAction.OpenKernelSetup -> navigator?.openRoot(KernelRepair.of(block.checkId(), pushed = true))
                    KernelBlockAction.Retry -> retry()
                    KernelBlockAction.RunDiagnostics -> navigator?.openKernelStatus(diagnostics = true)
                }
            },
            permissionPermanentlyDenied = permanentlyDenied,
        )
    }
}

@LightDarkPreviews
@Composable
private fun KernelBlockedCardPreview() {
    ComponentPreview {
        Column {
            KernelBlock.entries.forEach { block ->
                KernelBlockedCard(block, onAction = {})
                Spacer(Modifier.size(Spacing.s))
            }
            KernelBlockedCard(KernelBlock.PermissionMissing, onAction = {}, permissionPermanentlyDenied = true)
        }
    }
}
