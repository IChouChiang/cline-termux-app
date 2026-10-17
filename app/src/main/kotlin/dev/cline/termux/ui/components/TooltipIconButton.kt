package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.icons.ClineIcons

/**
 * A `PlainTooltip` on long-press around any control (DS §4.1 #14: the desktop `title=`
 * strings). The anchor keeps its own semantics; the tooltip text is spoken by TalkBack only
 * when it is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTooltipBox(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

/**
 * An icon-only `IconButton` (48dp target, 24dp icon by default) with its desktop
 * `aria-label` as [contentDescription] and a long-press tooltip ([tooltip], defaulting to
 * the same text). Keyboard focus draws the DS §8.1.7 ring around the circular target.
 */
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String = contentDescription,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    PlainTooltipBox(text = tooltip, modifier = modifier) {
        IconButton(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.focusRing(CircleShape)) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

@LightDarkPreviews
@Composable
private fun TooltipIconButtonPreview() {
    ComponentPreview {
        Row {
            TooltipIconButton(ClineIcons.Chrome.Copy, contentDescription = "Copy message", onClick = {})
            TooltipIconButton(ClineIcons.Chrome.Fork, contentDescription = "Fork session", onClick = {}, enabled = false)
            TooltipIconButton(ClineIcons.Chrome.SessionActions, contentDescription = "Session actions", onClick = {})
        }
    }
}
