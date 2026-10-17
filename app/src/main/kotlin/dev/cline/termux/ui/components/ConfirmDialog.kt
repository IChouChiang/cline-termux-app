package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cline.termux.R
import dev.cline.termux.ui.theme.Spacing

/**
 * The confirmation dialog (DS §4.1 #16; desktop `AlertDialog` usage): title, text, a
 * dismiss `TextButton` and a confirm `TextButton` (in `error` when [destructive]).
 *
 * States: idle · confirming ([confirming] = true: the confirm button shows a 16dp spinner
 * and [confirmingLabel], both buttons are disabled and dismissal is blocked) · error
 * ([error] as inline `bodySmall` `error` text, buttons enabled again).
 */
private val DialogSideMargin = 24.dp

@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String = stringResource(R.string.components_cancel),
    destructive: Boolean = false,
    confirming: Boolean = false,
    confirmingLabel: String = confirmLabel,
    error: String? = null,
    icon: ImageVector? = null,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!confirming) onDismiss() },
        // One UI's platform dialog window has no side inset, so a dialog whose text fills
        // the M3 560dp maximum ran edge to edge on a phone; keep the M3 margin ourselves.
        modifier = modifier.padding(horizontal = DialogSideMargin),
        properties = DialogProperties(
            dismissOnBackPress = !confirming,
            dismissOnClickOutside = !confirming,
            usePlatformDefaultWidth = false,
        ),
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = if (text == null && error == null && content == null) {
            null
        } else {
            {
                Column {
                    if (text != null) Text(text)
                    content?.invoke()
                    if (error != null) {
                        if (text != null || content != null) Spacer(Modifier.size(Spacing.s))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !confirming,
                modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge),
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                if (confirming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineSpinner()
                        Spacer(Modifier.width(Spacing.s))
                        Text(confirmingLabel)
                    }
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !confirming, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text(dismissLabel) }
        },
    )
}

@LightDarkPreviews
@Composable
private fun ConfirmDialogIdlePreview() {
    ComponentPreview(padding = false) {
        ConfirmDialog(
            title = "Delete session?",
            text = "This removes \"Fix the login bug\" from local history.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ConfirmDialogConfirmingPreview() {
    ComponentPreview(padding = false) {
        ConfirmDialog(
            title = "Delete session?",
            text = "This removes \"Fix the login bug\" from local history.",
            confirmLabel = "Delete",
            confirmingLabel = "Deleting...",
            confirming = true,
            destructive = true,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun ConfirmDialogErrorPreview() {
    ComponentPreview(padding = false) {
        ConfirmDialog(
            title = "Delete session?",
            text = "This removes \"Fix the login bug\" from local history.",
            confirmLabel = "Delete",
            destructive = true,
            error = "Couldn't delete this session: hub_connection_closed",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
