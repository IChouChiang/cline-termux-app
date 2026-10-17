package dev.cline.termux.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The shell's insets (DS §5.6). The bottom safe area every screen uses is
 * `WindowInsets.bottomSafe` in `ui/components/Insets.kt` (one definition, Phase 0); these are
 * the extra rules for the shell's own chrome.
 */
object ShellInsets {
    /**
     * The rail: system bars **and the display cutout** on its vertical and start edges, so in
     * landscape the camera cutout never covers a rail item (DS §5.6 "safeDrawing on the rail
     * and the list pane in landscape": safe drawing is there for the cutout). Never the IME:
     * an open keyboard must not shrink the rail (in a 412dp-tall landscape window it would
     * leave the destinations a sliver), as M3's own `NavigationRailDefaults.windowInsets`.
     */
    val rail: WindowInsets
        @Composable get() = railSafeArea.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)

    /**
     * The list pane: vertical safe drawing (the rail beside it already took the start edge).
     * The IME is included on purpose: the pane shrinks above the keyboard for the rename field
     * and the docked search.
     */
    val listPane: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)

    /**
     * The modal drawer sheet: system bars, cutout and IME on the vertical and start edges (the
     * sheet shrinks above the keyboard for its rename field).
     */
    val drawer: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)

    /** System bars plus the display cutout, without the IME: the set the rail pads for. */
    val railSafeArea: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

/**
 * For the content beside the rail: the rail already applied the start inset, so screens
 * inside (their `Scaffold`s use `ScaffoldDefaults.contentWindowInsets`) must not pad for it
 * again.
 */
@Composable
fun Modifier.consumeRailInsets(): Modifier =
    consumeWindowInsets(ShellInsets.railSafeArea.only(WindowInsetsSides.Start))
