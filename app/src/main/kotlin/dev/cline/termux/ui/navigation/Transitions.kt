package dev.cline.termux.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import dev.cline.termux.ui.theme.ClineMotion

/**
 * Route transitions (DS §6, docs/07 §6.2): fade-through between top-level destinations,
 * shared axis X for pushes, shared axis Y + fade for editors. navigation-compose 2.10 drives
 * the pop transitions from the predictive-back progress, so every pop is seekable (DS §5.8
 * item 9); nothing here registers a back handler.
 */
enum class TransitionKind { FadeThrough, SharedAxisX, SharedAxisY }

object NavTransitions {
    /** Shared-axis slide distance (DS §6: "slide 30dp"). */
    const val SLIDE_DP = 30

    /** Total shared-axis / fade-through duration. */
    const val TOTAL_MS = ClineMotion.Medium2

    /** Outgoing fade (first 90ms). */
    const val OUT_MS = 90

    /** Incoming fade (last 210ms, after [OUT_MS]). */
    const val IN_MS = TOTAL_MS - OUT_MS

    /** Fade-through incoming scale (0.92 → 1). */
    const val FADE_THROUGH_SCALE = 0.92f

    /**
     * The shell rule. A push to a route of kind [to] from [from], or the pop that undoes it
     * (the same pair, so a push and its pop always mirror): between two top-level
     * destinations it is a fade-through; when either side is an editor it is shared axis Y;
     * otherwise shared axis X. Unknown routes (null) count as pushed.
     */
    fun shellKind(from: RouteKind?, to: RouteKind?): TransitionKind = when {
        from == RouteKind.TopLevel && to == RouteKind.TopLevel -> TransitionKind.FadeThrough
        from == RouteKind.Editor || to == RouteKind.Editor -> TransitionKind.SharedAxisY
        else -> TransitionKind.SharedAxisX
    }

    /**
     * The root rule: replacing the shell (blocking repair, Install Termux, onboarding replay)
     * or entering it (end of onboarding) is a fade-through; screens pushed on top of the shell
     * and every onboarding step are shared axis X.
     */
    fun rootKind(from: RootRoute?, to: RootRoute?): TransitionKind = when {
        from == Shell && to != null && !pushedOverShell(to) -> TransitionKind.FadeThrough
        to == Shell && from != null && !pushedOverShell(from) -> TransitionKind.FadeThrough
        else -> TransitionKind.SharedAxisX
    }

    /** Root routes shown on top of the shell (they pop back to it). */
    fun pushedOverShell(route: RootRoute): Boolean = when (route) {
        is KernelRepair -> route.pushed
        KernelReliability, SetupConnect, SetupDone -> true
        else -> false
    }

    /** The incoming side of [kind]; [forward] is false on a pop. */
    fun AnimatedContentTransitionScope<*>.enter(kind: TransitionKind, forward: Boolean, slidePx: Int): EnterTransition =
        when (kind) {
            TransitionKind.FadeThrough ->
                fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = ClineMotion.Standard)) +
                    scaleIn(tween(IN_MS, delayMillis = OUT_MS, easing = ClineMotion.Standard), initialScale = FADE_THROUGH_SCALE)
            TransitionKind.SharedAxisX ->
                slideIntoContainer(
                    towards = if (forward) SlideDirection.Start else SlideDirection.End,
                    animationSpec = tween(TOTAL_MS, easing = ClineMotion.Standard),
                    initialOffset = { full -> signed(full, slidePx) },
                ) + fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = ClineMotion.Standard))
            TransitionKind.SharedAxisY ->
                slideIntoContainer(
                    towards = if (forward) SlideDirection.Up else SlideDirection.Down,
                    animationSpec = tween(TOTAL_MS, easing = ClineMotion.Standard),
                    initialOffset = { full -> signed(full, slidePx) },
                ) + fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = ClineMotion.Standard))
        }

    /** The outgoing side of [kind]; [forward] is false on a pop. */
    fun AnimatedContentTransitionScope<*>.exit(kind: TransitionKind, forward: Boolean, slidePx: Int): ExitTransition =
        when (kind) {
            TransitionKind.FadeThrough -> fadeOut(tween(OUT_MS, easing = ClineMotion.Standard))
            TransitionKind.SharedAxisX ->
                slideOutOfContainer(
                    towards = if (forward) SlideDirection.Start else SlideDirection.End,
                    animationSpec = tween(TOTAL_MS, easing = ClineMotion.Standard),
                    targetOffset = { full -> signed(full, slidePx) },
                ) + fadeOut(tween(OUT_MS, easing = ClineMotion.Standard))
            TransitionKind.SharedAxisY ->
                slideOutOfContainer(
                    towards = if (forward) SlideDirection.Up else SlideDirection.Down,
                    animationSpec = tween(TOTAL_MS, easing = ClineMotion.Standard),
                    targetOffset = { full -> signed(full, slidePx) },
                ) + fadeOut(tween(OUT_MS, easing = ClineMotion.Standard))
        }

    /** Keeps the direction of a full-size slide offset, with the magnitude of [slidePx]. */
    fun signed(fullOffset: Int, slidePx: Int): Int = if (fullOffset < 0) -slidePx else slidePx
}
