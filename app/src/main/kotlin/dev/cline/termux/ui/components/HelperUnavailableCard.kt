package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.cline.termux.R
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** Why the settings helper (the dashboard server in Termux) is not available (SP §0.3). */
enum class HelperUnavailableReason { StartTimeout, SocketFailed, BuildUnsupported, PortInUse, KernelStopped }

/**
 * The plain model the card renders: a reason plus the RUN_COMMAND `errmsg` / stderr tail
 * shown by "Details" when there is one. Built by `SettingsHelper`'s state; never contains a
 * secret (the dashboard room secret is not part of any error text the bridge keeps).
 */
@Immutable
data class HelperUnavailableModel(
    val reason: HelperUnavailableReason,
    val detail: String? = null,
)

/**
 * The SP §0.3 reason for a helper failure; null for the RUN_COMMAND blocks (Termux missing,
 * permission missing, external apps blocked), which SP §0.5's KernelBlockedCard shows instead.
 */
fun HelperFailure.toUnavailableReason(): HelperUnavailableReason? = when (this) {
    HelperFailure.StartTimeout -> HelperUnavailableReason.StartTimeout
    HelperFailure.SocketFailed -> HelperUnavailableReason.SocketFailed
    HelperFailure.BuildUnsupported -> HelperUnavailableReason.BuildUnsupported
    HelperFailure.PortInUse -> HelperUnavailableReason.PortInUse
    HelperFailure.KernelStopped -> HelperUnavailableReason.KernelStopped
    HelperFailure.TermuxMissing, HelperFailure.PermissionMissing, HelperFailure.ExternalAppsBlocked -> null
}

/** The [HelperUnavailableCard] model for an unavailable helper; null for a SP §0.5 block. */
fun HelperState.Unavailable.toUnavailableModel(): HelperUnavailableModel? =
    reason.toUnavailableReason()?.let { HelperUnavailableModel(it, detail) }

/**
 * DS #12 "helper unavailable" card (SP §0.3 table): `Surface(warning.container, shape =
 * large)` at the top of the content with `onContainer` text, a title, the body for the
 * reason, and the actions the table lists: "Retry" ([onRetry]), "Details" (an `AlertDialog`
 * with the detail in `ClineMono.body`), or "Start kernel" ([onStartKernel]) for the
 * kernel-stopped variant. Announced politely when it appears.
 *
 * [body] replaces the reason's body for screens whose spec words the card for their own
 * fallback (the marketplace, CR §3.4; the MCP server editor, CR §2.5); title, actions and
 * the Details dialog stay those of the reason.
 */
@Composable
fun HelperUnavailableCard(
    model: HelperUnavailableModel,
    modifier: Modifier = Modifier,
    body: String? = null,
    onRetry: (() -> Unit)? = null,
    onStartKernel: (() -> Unit)? = null,
) {
    val colors = ClineTheme.colors.warning
    val title = when (model.reason) {
        HelperUnavailableReason.KernelStopped -> stringResource(R.string.components_helper_kernel_stopped_title)
        else -> stringResource(R.string.components_helper_unavailable_title)
    }
    val bodyText = body ?: when (model.reason) {
        HelperUnavailableReason.StartTimeout, HelperUnavailableReason.SocketFailed -> stringResource(R.string.components_helper_start_failed_body)
        HelperUnavailableReason.BuildUnsupported -> stringResource(R.string.components_helper_build_unsupported_body)
        HelperUnavailableReason.PortInUse -> stringResource(R.string.components_helper_port_in_use_body)
        HelperUnavailableReason.KernelStopped -> stringResource(R.string.components_helper_kernel_stopped_body)
    }
    val detailsText = when (model.reason) {
        HelperUnavailableReason.PortInUse -> stringResource(R.string.components_helper_port_in_use_detail)
        else -> model.detail?.takeIf { it.isNotBlank() }
    }
    val showRetry = onRetry != null && model.reason in setOf(
        HelperUnavailableReason.StartTimeout, HelperUnavailableReason.SocketFailed, HelperUnavailableReason.PortInUse,
    )
    val showStart = onStartKernel != null && model.reason == HelperUnavailableReason.KernelStopped
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(bodyText, style = MaterialTheme.typography.bodyMedium)
            if (showRetry || showStart || detailsText != null) {
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (showStart) TextButton(onClick = { onStartKernel?.invoke() }) { Text(stringResource(R.string.components_helper_start_kernel)) }
                    if (showRetry) TextButton(onClick = { onRetry?.invoke() }) { Text(stringResource(R.string.components_retry)) }
                    if (detailsText != null) TextButton(onClick = { detailsOpen = true }) { Text(stringResource(R.string.components_details)) }
                }
            }
        }
    }

    if (detailsOpen && detailsText != null) {
        // PortInUse explains itself in prose; every other reason shows the captured stderr / errmsg.
        val prose = model.reason == HelperUnavailableReason.PortInUse
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            // One UI's platform dialog window has no side inset; keep the M3 margin (see ConfirmDialog).
            modifier = Modifier.padding(horizontal = Spacing.xxl),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(title) },
            text = {
                if (prose) {
                    Text(detailsText, modifier = Modifier.verticalScroll(rememberScrollState()))
                } else {
                    SelectionContainer {
                        LtrText(
                            text = detailsText,
                            style = ClineMono.body,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text(stringResource(R.string.components_close)) } },
        )
    }
}

@LightDarkPreviews
@Composable
private fun HelperUnavailableStartFailedPreview() {
    ComponentPreview {
        HelperUnavailableCard(
            model = HelperUnavailableModel(HelperUnavailableReason.StartTimeout, detail = "error: listen EADDRINUSE: address already in use 127.0.0.1:8787"),
            onRetry = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun HelperUnavailableBuildUnsupportedPreview() {
    ComponentPreview { HelperUnavailableCard(model = HelperUnavailableModel(HelperUnavailableReason.BuildUnsupported, detail = "Bun.serve is not available")) }
}

@LightDarkPreviews
@Composable
private fun HelperUnavailablePortInUsePreview() {
    ComponentPreview { HelperUnavailableCard(model = HelperUnavailableModel(HelperUnavailableReason.PortInUse), onRetry = {}) }
}

@LightDarkPreviews
@Composable
private fun HelperUnavailableKernelStoppedPreview() {
    ComponentPreview { HelperUnavailableCard(model = HelperUnavailableModel(HelperUnavailableReason.KernelStopped), onStartKernel = {}) }
}
