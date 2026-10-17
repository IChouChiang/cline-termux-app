package dev.cline.termux.debug

import androidx.compose.runtime.Composable
import dev.cline.termux.ui.debug.KernelProbeScreen

/**
 * Developer screens, debug builds only. The release source set has the same object with
 * [available] = false and empty bodies, so About (and the shell graph, which registers
 * `DebugKernelProbe` only when [available]) compile against both.
 */
object DebugScreens {
    /** True in debug builds: the shell registers the probe route and About may link it. */
    const val available: Boolean = true

    /** The RUN_COMMAND bridge probe (formerly the temporary home screen). */
    @Composable
    fun KernelProbe(onBack: () -> Unit) {
        KernelProbeScreen(onBack = onBack)
    }
}
