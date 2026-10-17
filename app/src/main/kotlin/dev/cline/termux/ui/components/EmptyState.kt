package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * Centred empty state (DS §4.2; desktop `ConversationEmptyState` / `PageEmptyState`): a 48dp
 * icon in `onSurfaceVariant`, a `titleMedium` title, a `bodyMedium` `onSurfaceVariant` body
 * and an optional `FilledTonalButton`. Defaults are the desktop's "No messages yet" /
 * "Start a conversation to see messages here.".
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.components_empty_title_default),
    body: String? = stringResource(R.string.components_empty_body_default),
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 480.dp,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = maxWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.l))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (body != null) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            extra?.let {
                Spacer(Modifier.height(Spacing.l))
                it()
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Spacing.xxl))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun EmptyStateDefaultPreview() {
    ComponentPreview { EmptyState() }
}

@LightDarkPreviews
@Composable
private fun EmptyStateWithIconAndActionPreview() {
    ComponentPreview {
        EmptyState(
            icon = ClineIcons.Chrome.Schedules,
            title = "No schedules yet",
            body = "Create a routine to run Cline on a schedule.",
            actionLabel = "New schedule",
            onAction = {},
        )
    }
}
