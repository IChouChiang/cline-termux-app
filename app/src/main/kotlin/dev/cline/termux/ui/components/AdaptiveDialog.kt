package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * An editor surface (DS §4.1 #16 as amended by SP §11 / CR Appendix A, docs/07 §6.2):
 *
 * - **compact and medium** ([expanded] = false): a full-screen page, hosted by the NavHost
 *   as a `composable()` destination: `Scaffold` + `TopAppBar(navigationIcon = close "Close",
 *   actions = TextButton(confirmLabel))`, content padding includes the IME. It is content,
 *   not a `Dialog` window, so it is edge to edge, gets the activity's IME handling and
 *   `FLAG_SECURE`, and drives the seekable predictive-back transition.
 * - **expanded** ([expanded] = true): a `BasicAlertDialog` of max width 560dp with
 *   `decorFitsSystemWindows = false` + `imePadding`, footer buttons only (Cancel + confirm).
 *
 * [busy] disables both actions and blocks dismissal (the "consume while saving" rule);
 * [secure] sets `SecureFlagPolicy.SecureOn` on the expanded dialog while a secret shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveDialog(
    expanded: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = stringResource(R.string.components_save),
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    secure: Boolean = false,
    dismissLabel: String = stringResource(R.string.components_cancel),
    content: @Composable (PaddingValues) -> Unit,
) {
    if (expanded) {
        BasicAlertDialog(
            onDismissRequest = { if (!busy) onDismissRequest() },
            modifier = modifier,
            properties = DialogProperties(
                dismissOnBackPress = !busy,
                dismissOnClickOutside = !busy,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(Spacing.l)
                    .widthIn(max = 560.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(Spacing.xxl)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.size(Spacing.l))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        content(PaddingValues(0.dp))
                    }
                    Spacer(Modifier.size(Spacing.xxl))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismissRequest, enabled = !busy) { Text(dismissLabel) }
                        if (confirmLabel != null) {
                            Spacer(Modifier.width(Spacing.s))
                            ConfirmTextButton(confirmLabel, onConfirm, enabled = confirmEnabled && !busy, busy = busy)
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime).union(WindowInsets.bottomSafe),
            snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        TooltipIconButton(
                            icon = ClineIcons.Chrome.Close,
                            contentDescription = stringResource(R.string.components_close),
                            onClick = onDismissRequest,
                            enabled = !busy,
                        )
                    },
                    actions = {
                        if (confirmLabel != null) {
                            ConfirmTextButton(confirmLabel, onConfirm, enabled = confirmEnabled && !busy, busy = busy)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            content = content,
        )
    }
}

@Composable
private fun ConfirmTextButton(label: String, onClick: () -> Unit, enabled: Boolean, busy: Boolean) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (busy) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
        }
        Text(label)
    }
}

@LightDarkPreviews
@Composable
private fun AdaptiveDialogCompactPreview() {
    ComponentPreview(padding = false) {
        AdaptiveDialog(expanded = false, title = "Add MCP Server", onDismissRequest = {}) { padding ->
            Column(Modifier.padding(padding).padding(horizontal = Spacing.l)) {
                OutlinedTextField(value = "", onValueChange = {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun AdaptiveDialogCompactBusyPreview() {
    ComponentPreview(padding = false) {
        AdaptiveDialog(expanded = false, title = "Add MCP Server", onDismissRequest = {}, busy = true) { padding ->
            Column(Modifier.padding(padding).padding(horizontal = Spacing.l)) {
                OutlinedTextField(value = "github", onValueChange = {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun AdaptiveDialogExpandedPreview() {
    ComponentPreview(padding = false) {
        AdaptiveDialog(expanded = true, title = "Add MCP Server", onDismissRequest = {}) {
            OutlinedTextField(value = "", onValueChange = {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
