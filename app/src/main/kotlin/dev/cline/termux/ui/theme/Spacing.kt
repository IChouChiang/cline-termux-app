package dev.cline.termux.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4dp grid (design-system.md §3.3) and the few layout constants every screen shares. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp

    /** Screen horizontal gutter on compact windows. */
    val gutterCompact = 16.dp

    /** Screen horizontal gutter on medium and expanded windows. */
    val gutterWide = 24.dp

    /** The compact/medium window boundary (§5.1) where the gutter widens from 16dp to 24dp. */
    val gutterBreakpoint = 600.dp

    /** The horizontal gutter for a container [maxWidth] wide (§3.3): 16dp below 600dp, else 24dp. */
    fun gutterFor(maxWidth: Dp): Dp = if (maxWidth >= gutterBreakpoint) gutterWide else gutterCompact

    /** Transcript and composer column width (desktop `max-w-(--breakpoint-lg)` = 64rem). */
    val contentMaxWidth = 840.dp

    /** Page content column on medium and expanded windows: settings, account, editors, detail pages. */
    val pageMaxWidth = 720.dp

    /** Bottom-sheet content gutter. */
    val sheetGutter = 24.dp

    /** Minimum touch target (§3.4). */
    val touchTarget = 48.dp
}
