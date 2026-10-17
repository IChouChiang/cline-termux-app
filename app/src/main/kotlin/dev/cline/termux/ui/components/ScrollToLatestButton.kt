package dev.cline.termux.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.Spacing

/**
 * Desktop `ConversationScrollButton` (DS §4.2, HC §4.4): a `SmallFloatingActionButton`
 * (`surfaceContainerHigh` / `onSurface`) with `arrow_downward`, end-aligned 16dp above the
 * composer. The host decides [visible] (more than 120dp from the bottom) and what the tap
 * does (jump-then-glide to item 0, pin). Enter `scaleIn(.8) + fadeIn` 150ms, exit 100ms.
 */
@Composable
fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.components_scroll_to_latest)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(tween(ClineMotion.Short3), initialScale = 0.8f) + fadeIn(tween(ClineMotion.Short3)),
        exit = scaleOut(tween(ClineMotion.Short2), targetScale = 0.8f) + fadeOut(tween(ClineMotion.Short2)),
    ) {
        PlainTooltipBox(text = label) {
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(ClineIcons.Chrome.ScrollToLatest, contentDescription = label)
            }
        }
    }
}

@LightDarkPreviews
@Composable
private fun ScrollToLatestButtonPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.size(160.dp)) {
            ScrollToLatestButton(visible = true, onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l))
        }
    }
}
