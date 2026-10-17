package dev.cline.termux.ui.navigation

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cline.termux.ui.theme.Spacing

/** DS §5.1 width classes. */
enum class WidthClass { Compact, Medium, Expanded }

/** Which drawer content variant a surface shows (sessions.md §2.3). */
enum class DrawerVariant {
    /** Compact modal drawer: header, the three destination items, the list, the footer. */
    CompactDrawer,

    /** Medium (and height-compact) modal drawer: header, "New session" only, the list; no footer. */
    MediumDrawer,

    /** Expanded list pane: header with the docked search, no destination items, the list, the footer. */
    ListPane,
}

/**
 * The shell's layout decisions for one window size (DS §5.1, §5.2, sessions.md §2.3, CT A.3),
 * as a pure value so every rule is unit-tested. Width and height are the window's, in dp.
 */
@Immutable
data class ShellLayout(val widthDp: Int, val heightDp: Int) {
    val width: WidthClass = when {
        widthDp >= EXPANDED_MIN_WIDTH -> WidthClass.Expanded
        widthDp >= MEDIUM_MIN_WIDTH -> WidthClass.Medium
        else -> WidthClass.Compact
    }

    /** Height < 480dp (phones in landscape): no list pane, no hero, scrolling composer row. */
    val heightCompact: Boolean = heightDp < HEIGHT_COMPACT_MAX

    /** A `NavigationRail` on medium and expanded windows. */
    val showsRail: Boolean get() = width != WidthClass.Compact

    /**
     * List and detail side by side (DS §5.1): expanded width, not height-compact. A
     * height-compact window is "expanded width but no list pane (rail + single pane)", so the
     * shell's sessions pane and every list-detail screen (Settings, Providers, Schedules,
     * Marketplace) fall back to a single pane there.
     */
    val listDetailPanes: Boolean get() = width == WidthClass.Expanded && !heightCompact

    /** The persistent sessions list pane: expanded width, not height-compact. */
    val showsListPane: Boolean get() = listDetailPanes

    /** The `ModalNavigationDrawer` exists whenever there is no list pane (compact, medium, height-compact). */
    val usesModalDrawer: Boolean get() = !showsListPane

    /** The modal drawer's content variant. */
    val drawerVariant: DrawerVariant get() = if (width == WidthClass.Compact) DrawerVariant.CompactDrawer else DrawerVariant.MediumDrawer

    /** Changes / Tool detail open as the chat's 400dp supporting pane at ≥ 1200 × ≥ 480 (CT A.3, B.3). */
    val supportingPaneAvailable: Boolean get() = widthDp >= LARGE_MIN_WIDTH && !heightCompact

    /**
     * Editors are `dialog()` destinations on expanded windows, full-screen `composable()`s
     * otherwise (CR §0.2). A height-compact window keeps them full screen: a 560dp dialog
     * with the IME open does not fit under 480dp.
     */
    val editorsAsDialogs: Boolean get() = width == WidthClass.Expanded && !heightCompact

    /** The list pane is 360dp, 400dp at ≥ 1200dp (DS §5.2). */
    val listPaneWidth: Dp get() = if (widthDp >= LARGE_MIN_WIDTH) 400.dp else 360.dp

    /** Screen horizontal gutter (DS §3.3). */
    val gutter: Dp get() = if (width == WidthClass.Compact) Spacing.gutterCompact else Spacing.gutterWide

    /** The Home hero is hidden on height-compact windows (DS §4.2, §5.1). */
    val heroVisible: Boolean get() = !heightCompact

    companion object {
        const val MEDIUM_MIN_WIDTH = 600
        const val EXPANDED_MIN_WIDTH = 840
        const val LARGE_MIN_WIDTH = 1200
        const val HEIGHT_COMPACT_MAX = 480

        /** The S25U in portrait, the default outside a window (previews). */
        val PhonePortrait = ShellLayout(412, 915)
    }
}

/** The window size class from `material3-adaptive` (`currentWindowAdaptiveInfo()`). */
val LocalWindowSizeClass = staticCompositionLocalOf { WindowSizeClass(412, 915) }

/** The shell's [ShellLayout] for the current window. */
val LocalShellLayout = staticCompositionLocalOf { ShellLayout.PhonePortrait }

/** True on windows under 480dp tall (DS §5.1 height-compact modifier). */
val isHeightCompact: Boolean
    @Composable @ReadOnlyComposable get() = LocalShellLayout.current.heightCompact

/** Measures the window (its container size, in dp) once per size change. */
@Composable
fun rememberShellLayout(): ShellLayout {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(size, density) {
        with(density) { ShellLayout(size.width.toDp().value.toInt(), size.height.toDp().value.toInt()) }
    }
}

/** Provides [LocalWindowSizeClass] and [LocalShellLayout] for [content]. */
@Composable
fun ProvideWindowClass(content: @Composable () -> Unit) {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val layout = rememberShellLayout()
    CompositionLocalProvider(
        LocalWindowSizeClass provides sizeClass,
        LocalShellLayout provides layout,
        content = content,
    )
}
