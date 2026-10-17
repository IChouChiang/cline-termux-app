package dev.cline.termux.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing

/** 56dp FAB + 16dp margin + 16dp air (CR §0.6, SP S4.1). */
val FAB_CLEARANCE: Dp = 88.dp

/**
 * How a page's bottom-end ExtendedFAB and the list under it share the window (DS §5.4,
 * CR §0.6).
 *
 * On a normal window the FAB expands while the list is at rest or scrolls up, and the list
 * pads its bottom by [FAB_CLEARANCE] so the last item's trailing buttons scroll clear of it.
 *
 * A height-compact window (phone landscape, under 480dp) is too short for that: at the
 * initial scroll position the bottom-end FAB sits over the first card's trailing control
 * (the Schedules switch), and no bottom padding can move it. There the FAB stays icon-only
 * (its label in the tooltip and the content description, as on the compact rail) and the list
 * reserves an end lane of [FAB_CLEARANCE], so no item ever runs under the FAB.
 */
@Immutable
data class FabLane(val expanded: Boolean, val endLane: Boolean) {
    /** The list's end padding: the [gutter], or the FAB's lane when it reserves one. */
    fun endPadding(gutter: Dp): Dp = if (endLane) maxOf(gutter, FAB_CLEARANCE) else gutter

    /** The space under the last item: the FAB clearance, or ordinary end space beside a lane. */
    val bottomClearance: Dp get() = if (endLane) Spacing.l else FAB_CLEARANCE
}

/**
 * The [FabLane] for a FAB over a list that is [scrollingUp] (`isScrollingUp()`), on a window
 * that is [heightCompact] or not.
 */
fun fabLane(heightCompact: Boolean, scrollingUp: Boolean): FabLane =
    FabLane(expanded = scrollingUp && !heightCompact, endLane = heightCompact)

/** [fabLane] for the current window. */
@Composable
fun rememberFabLane(scrollingUp: Boolean): FabLane = fabLane(dev.cline.termux.ui.navigation.isHeightCompact, scrollingUp)

/**
 * Wraps a page FAB in its label's tooltip while [lane] keeps it icon-only (DS §4.1 #14), so
 * a long-press still names it.
 */
@Composable
fun FabLaneTooltip(lane: FabLane, label: String, fab: @Composable () -> Unit) {
    if (lane.endLane) PlainTooltipBox(text = label) { fab() } else fab()
}
