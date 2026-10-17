package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import dev.cline.termux.ui.theme.Spacing

// Adapted from cline-termux-voice (same owner): ui/Components.kt (`bottomSafe`).

/**
 * The lowest edge content may reach (DS §5.6): the navigation bar ∪ the bottom gesture area
 * ∪ 16dp, because in gesture mode the phone reports a zero navigation-bar inset while its
 * handle still sits over the last line.
 */
val WindowInsets.Companion.bottomSafe: WindowInsets
    @Composable get() = navigationBars
        .union(mandatorySystemGestures.only(WindowInsetsSides.Bottom))
        .union(WindowInsets(bottom = Spacing.l))

/** Bottom-sheet content inset (DS §5.5): `navigationBars` **plus** 16dp. */
val WindowInsets.Companion.sheetBottom: WindowInsets
    @Composable get() = navigationBars.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = Spacing.l))
