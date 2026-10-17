package dev.cline.termux.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.icons.ClineIcons

/**
 * One context-menu entry (DS §4.1 #15). [pendingLabel] is what the item reads while this
 * action is in flight ("Renaming...", "Forking...", "Deleting..."); [destructive] items use
 * `error` text and icon.
 */
@Immutable
data class ContextMenuItem(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val pendingLabel: String? = null,
)

/**
 * A `DropdownMenu` anchored at the long-press point ([offset], from [PressPoint] /
 * [Modifier.trackPress] or [Modifier.contextMenuAnchor]) or at an overflow button. Items are 48dp tall, the menu at
 * least 200dp wide. With [pendingItemId] set, that item shows a 16dp spinner and its
 * [ContextMenuItem.pendingLabel] and every item is disabled (the desktop keeps the row's
 * menu open and inert while its action runs).
 */
@Composable
fun AnchoredContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<ContextMenuItem>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    pendingItemId: String? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp),
        offset = offset,
    ) {
        val anyPending = pendingItemId != null
        items.forEach { item ->
            val pending = item.id == pendingItemId
            val colors = if (item.destructive) {
                MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                )
            } else {
                MenuDefaults.itemColors()
            }
            DropdownMenuItem(
                text = { Text(if (pending) item.pendingLabel ?: item.label else item.label) },
                onClick = {
                    onDismissRequest()
                    item.onClick()
                },
                enabled = item.enabled && !anyPending,
                colors = colors,
                leadingIcon = when {
                    pending -> {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    }
                    item.icon != null -> {
                        { Icon(item.icon, contentDescription = null) }
                    }
                    else -> null
                },
            )
        }
    }
}

/**
 * The always-visible overflow variant: a `more_vert` `IconButton` with the same items. The
 * [contentDescription] is the row's label ("Session actions for <title>").
 */
@Composable
fun OverflowMenuButton(
    contentDescription: String,
    items: List<ContextMenuItem>,
    modifier: Modifier = Modifier,
    pendingItemId: String? = null,
    enabled: Boolean = true,
    icon: ImageVector = ClineIcons.Chrome.SessionActions,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TooltipIconButton(icon, contentDescription = contentDescription, onClick = { open = true }, enabled = enabled)
        AnchoredContextMenu(
            expanded = open,
            onDismissRequest = { open = false },
            items = items,
            pendingItemId = pendingItemId,
        )
    }
}

/**
 * The `DropdownMenu` offset that opens the menu at a press. M3's `DropdownMenuPositionProvider`
 * measures `offset.y` from the anchor's BOTTOM edge and, in RTL, `offset.x` from the anchor's
 * END edge (the menu's end edge lands there), while a press is measured from the anchor's
 * top-left corner. [down] and [anchor] are in px on the node that hosts the menu (the same
 * node the press was measured on).
 */
fun pressMenuOffset(down: Offset, anchor: IntSize, layoutDirection: LayoutDirection, density: Density): DpOffset {
    val x = if (layoutDirection == LayoutDirection.Rtl) anchor.width - down.x else down.x
    val y = down.y - anchor.height
    return with(density) { DpOffset(x.toDp(), y.toDp()) }
}

/**
 * Where the last press on a menu anchor landed, as a ready `DropdownMenu` offset (see
 * [pressMenuOffset]), so a long-press menu opens at the finger. Fill it with
 * [Modifier.trackPress] on the Box that hosts the [AnchoredContextMenu].
 */
@Stable
class PressPoint {
    /** The menu offset for the last finger-down; read it when the long-press fires. */
    var offset by mutableStateOf(DpOffset.Zero)
        internal set
    internal var size: IntSize = IntSize.Zero
}

/**
 * Records every finger-down on this node into [point] without consuming it (Initial pass, so
 * children keep their gestures). Put it on the Box that also hosts the [AnchoredContextMenu]:
 * the menu's anchor is that Box, and the offset is measured against its size.
 */
@Composable
fun Modifier.trackPress(point: PressPoint): Modifier {
    val direction = LocalLayoutDirection.current
    return this
        .onSizeChanged { point.size = it }
        .pointerInput(point, direction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                point.offset = pressMenuOffset(down.position, point.size, direction, this)
            }
        }
}

/**
 * Long-press anchor: reports the press as a menu offset (see [pressMenuOffset]) so the menu
 * opens where the finger is. Put it on the node that hosts the [AnchoredContextMenu].
 * [onClick] is optional so the same node can keep a plain tap; prefer `combinedClickable` +
 * [trackPress] when the row already has a click.
 */
@Composable
fun Modifier.contextMenuAnchor(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongPress: (DpOffset) -> Unit,
): Modifier {
    val direction = LocalLayoutDirection.current
    return this.then(
        Modifier
            .pointerInput(enabled, onClick, onLongPress, direction) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = onClick?.let { tap -> { _ -> tap() } },
                    onLongPress = { position -> onLongPress(pressMenuOffset(position, size, direction, this)) },
                )
            },
    )
}

/**
 * The long-press menu as TalkBack custom actions on the row (DS §8.1.6). Pending or
 * disabled items are left out so TalkBack never offers an action that would be ignored.
 */
fun List<ContextMenuItem>.toCustomActions(pendingItemId: String? = null): List<CustomAccessibilityAction> =
    if (pendingItemId != null) emptyList()
    else filter { it.enabled }.map { item -> CustomAccessibilityAction(item.label) { item.onClick(); true } }

/** A press on the anchor: the haptic every long-press menu in the app gives (one place). */
@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

private val previewItems: List<ContextMenuItem>
    @Composable get() = listOf(
        ContextMenuItem("pin", "Pin", {}, icon = ClineIcons.PushPin),
        ContextMenuItem("rename", "Rename", {}, icon = ClineIcons.Edit, pendingLabel = "Renaming..."),
        ContextMenuItem("fork", "Fork", {}, icon = ClineIcons.ForkRight, pendingLabel = "Forking..."),
        ContextMenuItem("details", "Details", {}, icon = ClineIcons.Info),
        ContextMenuItem("delete", "Delete", {}, icon = ClineIcons.Delete, destructive = true, pendingLabel = "Deleting..."),
    )

@LightDarkPreviews
@Composable
private fun AnchoredContextMenuPreview() {
    ComponentPreview(padding = false) {
        val density = LocalDensity.current
        Box {
            ListItem(headlineContent = { Text("Fix the login bug") }, trailingContent = {
                OverflowMenuButton(contentDescription = "Session actions for Fix the login bug", items = previewItems)
            })
            AnchoredContextMenu(expanded = true, onDismissRequest = {}, items = previewItems, offset = with(density) { DpOffset(24.dp, 8.dp) })
        }
    }
}

@LightDarkPreviews
@Composable
private fun AnchoredContextMenuPendingPreview() {
    ComponentPreview(padding = false) {
        Box {
            ListItem(headlineContent = { Text("Fix the login bug") })
            AnchoredContextMenu(expanded = true, onDismissRequest = {}, items = previewItems, pendingItemId = "delete")
        }
    }
}
