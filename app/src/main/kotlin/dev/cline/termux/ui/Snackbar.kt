package dev.cline.termux.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The one app-wide snackbar host (design-system.md §4.1 #17, desktop `TOAST_LIMIT = 1`).
 * Every screen's `Scaffold` hosts the same state, so a message sits above that screen's
 * composer or FAB. Provided by the app shell; the default instance only exists so that
 * previews and the developer probe compose without a provider.
 */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Shows [message], first dismissing the same message if it is already up, so copying three
 * blocks in a row does not queue three snackbars. Any other message is left alone.
 */
suspend fun SnackbarHostState.showReplacing(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult {
    currentSnackbarData?.takeIf { it.visuals.message == message }?.dismiss()
    return showSnackbar(message, actionLabel = actionLabel, withDismissAction = actionLabel != null, duration = duration)
}

/**
 * [showReplacing] for a message that is only true for a while, such as "The agent did not stop"
 * (trial row 11): when [ended] returns first, the snackbar is taken down, or never shown if it was
 * still waiting for the host, and the result is null. Only this snackbar is affected; whatever
 * else the host shows stays.
 */
suspend fun SnackbarHostState.showReplacingUntil(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    ended: suspend () -> Unit,
): SnackbarResult? = coroutineScope {
    val shown = async { showReplacing(message, actionLabel, duration) }
    val end = launch {
        ended()
        shown.cancel()
    }
    try {
        shown.await()
    } catch (_: CancellationException) {
        // The caller's own cancellation goes on; [ended] winning is an ordinary outcome.
        ensureActive()
        null
    } finally {
        end.cancel()
    }
}
