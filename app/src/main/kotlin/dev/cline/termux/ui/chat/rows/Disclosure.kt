package dev.cline.termux.ui.chat.rows

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.toCustomActions
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The disclosure row shared by tool, reasoning and work rows (desktop `agent-chat/disclosure.tsx`
 * + `cline-chat-*-trigger`): a full-width compact header that toggles a body under the rail. Its
 * metrics are [DisclosureRowTokens] (A24). Every body starts folded and opens only when the user
 * taps its header (A23). DS §4.1 #2–#4, §3.3 (rail), §6 (motion), §8.1 #5–#6 (semantics).
 */

/**
 * The header. [expanded] null means "not expandable": no click, no ripple, plain text whose
 * [contentDescription] is label + status (HC §3.15). Otherwise the row is a button with
 * `stateDescription` "Expanded"/"Collapsed". [menuItems] are TalkBack custom actions;
 * [onLongPress] opens them as a menu, which the caller hosts in a Box that tracks the press
 * (`Modifier.trackPress`) so it opens at the finger.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DisclosureHeader(
    expanded: Boolean?,
    onToggle: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    menuItems: List<ContextMenuItem> = emptyList(),
    onLongPress: (() -> Unit)? = null,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(DisclosureRowTokens.IconLabelGap),
    content: @Composable RowScope.() -> Unit,
) {
    val expandedLabel = stringResource(R.string.chat_row_expanded)
    val collapsedLabel = stringResource(R.string.chat_row_collapsed)
    val actions: List<CustomAccessibilityAction> = menuItems.toCustomActions()
    val interactive = expanded != null
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = DisclosureRowTokens.Height)
    val gestures = when {
        interactive -> Modifier
            .focusRing(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongPress,
            )
        onLongPress != null -> Modifier
            .focusRing(MaterialTheme.shapes.small)
            .combinedClickable(
                indication = null,
                interactionSource = null,
                onClick = {},
                onLongClick = onLongPress,
            )
        else -> Modifier
    }
    val semantics = Modifier.clearAndSetSemantics {
        this.contentDescription = contentDescription
        if (expanded != null) {
            role = Role.Button
            stateDescription = if (expanded) expandedLabel else collapsedLabel
            onClick { onToggle(); true }
        }
        if (actions.isNotEmpty()) customActions = actions
    }
    Row(
        modifier = base.then(semantics).then(gestures),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/** A row's leading slot ([DisclosureRowTokens.IconSize] square): the kind icon, or the spinner while running. */
@Composable
internal fun DisclosureIconSlot(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(DisclosureRowTokens.IconSize), contentAlignment = Alignment.Center, content = content)
}

/** The spinner that takes the icon's place in [DisclosureIconSlot] while a row runs. */
@Composable
internal fun DisclosureSpinner(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(DisclosureRowTokens.SpinnerSize),
        strokeWidth = DisclosureRowTokens.SpinnerStroke,
        color = color,
    )
}

/**
 * Reports a disclosure body's growth in pixels, once per animation frame and before that frame
 * is laid out, so the transcript can scroll by as much in the same frame and hold the toggled
 * row's header in place (HC §3.7). Null where nothing holds headers (previews, other screens).
 */
internal val LocalDisclosureResize = staticCompositionLocalOf<((deltaPx: Int) -> Unit)?> { null }

/**
 * The body: DS §6 disclosure motion; with [rail], a 1dp `outlineVariant` line under the icon's
 * centre and the content inset past it ([DisclosureRowTokens.RailLineInset],
 * [DisclosureRowTokens.RailContentInset], DS §3.3). Content is composed only while open
 * (desktop `lazyContent`).
 *
 * Adapted from cline-termux-voice (same owner): ui/chat/WorkRows.kt `Unfold`. A reversed list
 * keeps a row's bottom edge fixed, so a growing row pushes its own header up. AnimatedVisibility
 * knows its size only while the list measures, where scrolling the list throws, so the body
 * animates its own height fraction and reports each frame's growth through
 * [LocalDisclosureResize] from the animation's frame callback instead.
 */
@Composable
internal fun DisclosureBody(
    visible: Boolean,
    modifier: Modifier = Modifier,
    rail: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val layoutDirection = LocalLayoutDirection.current
    val reduced = ClineTheme.reducedMotion
    val fraction = remember { Animatable(if (visible) 1f else 0f) }
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    val heights = remember { UnfoldHeights() }
    val resize by rememberUpdatedState(LocalDisclosureResize.current)
    LaunchedEffect(visible, reduced) {
        val target = if (visible) 1f else 0f
        if (fraction.value == target && alpha.value == target) return@LaunchedEffect
        heights.reported = heights.at(fraction.value)
        if (reduced) {
            alpha.snapTo(target)
            fraction.snapTo(target)
            val delta = heights.at(target) - heights.reported
            heights.reported = heights.at(target)
            if (delta != 0) resize?.invoke(delta)
            return@LaunchedEffect
        }
        launch {
            alpha.animateTo(
                target,
                if (visible) tween(ClineMotion.Short3, delayMillis = 50) else tween(ClineMotion.Short2),
            )
        }
        fraction.animateTo(target, tween(ClineMotion.Short4, easing = ClineMotion.Standard)) {
            // Counted against what was reported, not what was laid out, so a frame the list
            // did not measure is never counted twice.
            val next = heights.at(value)
            val delta = next - heights.reported
            heights.reported = next
            if (delta != 0) resize?.invoke(delta)
        }
    }
    val open by remember { derivedStateOf { fraction.value > 0f } }
    if (!visible && !open) return
    val railModifier = if (rail) {
        Modifier
            .drawBehind {
                val inset = DisclosureRowTokens.RailLineInset.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
                drawLine(railColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(start = DisclosureRowTokens.RailContentInset, bottom = Spacing.xs)
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                heights.natural = placeable.height
                layout(placeable.width, heights.at(fraction.value)) { placeable.place(0, 0) }
            }
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        Column(Modifier.fillMaxWidth().then(railModifier), content = content)
    }
}

/** The body's full height from its last measure, and the height last reported for it. */
private class UnfoldHeights {
    var natural = 0
    var reported = 0

    fun at(fraction: Float): Int = (natural * fraction).roundToInt()
}
