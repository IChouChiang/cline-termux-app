package dev.cline.termux.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** M3 motion tokens plus the desktop pulse curve (design-system.md §6). */
object ClineMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Tailwind `animate-pulse`, used by the desktop for provisioning dots. */
    val Pulse = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

    const val Short2 = 100
    const val Short3 = 150
    const val Short4 = 200
    const val Medium2 = 300
    const val Long2 = 500

    /** Running-label shimmer period (`cline-chat-title-shimmer 2.25s linear infinite`). */
    const val ShimmerMillis = 2250

    /** Status-dot pulse period (Tailwind `animate-pulse`). */
    const val PulseMillis = 2000

    /** Skeleton alpha shimmer. */
    const val SkeletonMillis = 1000
}

/**
 * True when the system animator scale is 0 (Settings > Developer options, or the
 * "Remove animations" accessibility switch). Shimmers, pulses, the skeleton
 * shimmer and the cursor blink must stop; disclosure and navigation transitions complete
 * instantly by themselves.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the animator scale and re-reads it every time the activity resumes. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember(context) { mutableStateOf(readReducedMotion(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = readReducedMotion(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
