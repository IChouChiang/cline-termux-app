package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * Inline error (CR Appendix A): `Surface(errorContainer, shape = medium)` row with a 20dp
 * `error` icon, the message in `bodyMedium` `onErrorContainer` (kernel data, verbatim) and
 * an optional trailing `TextButton` ("Retry" by default). Min height 56dp, 12dp padding,
 * announced assertively.
 */
@Composable
fun InlineErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ClineIcons.ErrorFilled, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(Spacing.m))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onAction != null) {
                Spacer(Modifier.width(Spacing.s))
                TextButton(onClick = onAction, modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text(actionLabel ?: stringResource(R.string.components_retry)) }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun InlineErrorCardPreview() {
    ComponentPreview {
        InlineErrorCard(message = "Failed to load configuration lists: settings_list_failed", onAction = {})
    }
}

@LightDarkPreviews
@Composable
private fun InlineErrorCardNoActionPreview() {
    ComponentPreview { InlineErrorCard(message = "Cline Hub disconnected. Try again when it reconnects.") }
}
