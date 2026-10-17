package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/*
 * Badge policy (DS §4.3): a count is the M3 `Badge`; a tag ("Images", "Reasoning", "Global",
 * "Beta", "Files: N") is a `Surface(shape = extraSmall, color = surfaceContainerHighest)`
 * with `labelSmall` text. Never colour-only: every badge carries text or a label.
 */

/** M3 count badge (approval banner "N", rail unread). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Badge(
        modifier = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier,
    ) { Text(count.toString()) }
}

/** The 6dp dot badge with no number (drawer "Chats, unread sessions"); [contentDescription] is required so it is never colour-only. */
@Composable
fun DotBadge(contentDescription: String, modifier: Modifier = Modifier) {
    Badge(modifier = modifier.semantics { this.contentDescription = contentDescription })
}

/** Text tag: `Surface(extraSmall, surfaceContainerHighest)` + `labelSmall`, optional 16dp leading icon. */
@Composable
fun TagBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@LightDarkPreviews
@Composable
private fun BadgesPreview() {
    ComponentPreview {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountBadge(3)
            Spacer(Modifier.width(Spacing.l))
            DotBadge(contentDescription = "Chats, unread sessions")
            Spacer(Modifier.width(Spacing.l))
            TagBadge("Images", icon = ClineIcons.Image)
            Spacer(Modifier.width(Spacing.s))
            TagBadge("Reasoning")
            Spacer(Modifier.width(Spacing.s))
            TagBadge("Files: 4")
        }
    }
}
