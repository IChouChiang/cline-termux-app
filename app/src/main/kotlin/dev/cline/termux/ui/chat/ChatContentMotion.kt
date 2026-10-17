package dev.cline.termux.ui.chat

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import dev.cline.termux.ui.navigation.NavTransitions
import dev.cline.termux.ui.navigation.TransitionKind
import dev.cline.termux.ui.theme.ClineMotion

/** How the chat content area changes between two content keys (HC §2.7, §2.9; DS §6). */
enum class ChatSwap {
    /** Same content, or the first send's pending row becoming the created session: no motion. */
    None,

    /** Home ⇄ the first send's conversation: DS "Welcome ⇄ conversation swap" (180 ms fade + 4 dp rise). */
    Welcome,

    /** Selecting a session (from Home or another session): shared axis X forward. */
    Forward,

    /** Back to Home from a selected session: the shared-axis-X pop (seekable with predictive back). */
    Back,
}

/**
 * The chat content keys and the swap rule. Home is [HOME]; the first send's conversation is
 * [PENDING] until `session.create` answers, then the session id (that hand-off is not a switch:
 * the composer and the transcript stay put, §2.9).
 */
object ChatContentMotion {
    const val HOME = "home"
    const val PENDING = "pending"

    /** Welcome swap duration (DS §6, desktop `cline-view-enter 180ms`). */
    const val WELCOME_MS = 180

    /** Welcome swap rise (DS §6, translateY 4px). */
    const val WELCOME_RISE_DP = 4

    fun keyOf(state: ChatUiState): String = when (state) {
        is ChatUiState.Home -> HOME
        is ChatUiState.Session -> state.sessionId ?: PENDING
    }

    fun swap(from: String, to: String): ChatSwap = when {
        from == to -> ChatSwap.None
        from == PENDING && to != HOME -> ChatSwap.None
        from == HOME && to == PENDING -> ChatSwap.Welcome
        from == PENDING && to == HOME -> ChatSwap.Welcome
        to == HOME -> ChatSwap.Back
        else -> ChatSwap.Forward
    }

    /** The [AnimatedContentTransitionScope] spec for [swap]; [risePx] and [slidePx] are the DS distances in pixels. */
    fun AnimatedContentTransitionScope<String>.transform(reducedMotion: Boolean, risePx: Int, slidePx: Int): ContentTransform {
        val swap = if (reducedMotion) ChatSwap.None else swap(initialState, targetState)
        return when (swap) {
            ChatSwap.None -> EnterTransition.None togetherWith ExitTransition.None
            ChatSwap.Welcome ->
                (fadeIn(tween(WELCOME_MS, easing = ClineMotion.StandardDecelerate)) + slideInVertically(tween(WELCOME_MS)) { risePx }) togetherWith
                    fadeOut(tween(WELCOME_MS, easing = ClineMotion.StandardDecelerate))
            ChatSwap.Forward -> with(NavTransitions) {
                enter(TransitionKind.SharedAxisX, forward = true, slidePx) togetherWith exit(TransitionKind.SharedAxisX, forward = true, slidePx)
            }
            ChatSwap.Back -> with(NavTransitions) {
                enter(TransitionKind.SharedAxisX, forward = false, slidePx) togetherWith exit(TransitionKind.SharedAxisX, forward = false, slidePx)
            }
        }
    }
}
