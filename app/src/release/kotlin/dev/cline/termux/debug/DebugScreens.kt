package dev.cline.termux.debug

import androidx.compose.runtime.Composable

/**
 * Release twin of the debug `DebugScreens`: identical signatures, no developer screens. The
 * shell graph skips the `DebugKernelProbe` route because [available] is false, and About
 * hides its link.
 */
object DebugScreens {
    const val available: Boolean = false

    @Composable
    fun KernelProbe(onBack: () -> Unit) = Unit
}
