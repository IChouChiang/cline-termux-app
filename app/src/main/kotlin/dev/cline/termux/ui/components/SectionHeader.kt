package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing

/**
 * List group header (DS §4.1 #12 settings sections in `titleSmall` `primary`; drawer
 * sections "Pinned" / "Scheduled" / "Tasks" with counts in `labelMedium`
 * `onSurfaceVariant`). Marked as a heading; optional trailing slot for a sort or filter
 * control. Groups are separated by 8dp above the header (DS §3.3).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = Spacing.l, end = if (trailing == null) Spacing.l else Spacing.xs, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() }.weight(1f, fill = false),
        )
        if (count != null) {
            Spacer(Modifier.width(Spacing.s))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@LightDarkPreviews
@Composable
private fun SectionHeaderPreview() {
    ComponentPreview(padding = false) {
        Column {
            SectionHeader("General")
            SectionHeader("Pinned", count = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Tasks", count = 12, color = MaterialTheme.colorScheme.onSurfaceVariant, trailing = {
                TextButton(onClick = {}) { Text("Show more") }
            })
        }
    }
}
