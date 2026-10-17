package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * One invisible live-region `Text` per surface (DS §8.1.4): "Agent is working…" on
 * `run.started`, the first 200 characters of the answer on `run.completed`, "Run failed:
 * <error>", "Tool approval required" / "Follow-up question" when those sheets open. The
 * transcript itself is never a live region per delta. Polite by default; ask-question
 * errors pass [LiveRegionMode.Assertive].
 *
 * TalkBack announces a live region when its text changes, so repeating the same sentence
 * needs a new text: use [LiveRegionState.announce], which alternates a trailing zero-width
 * space between identical messages.
 *
 * The region is a status channel, not a stop in swipe navigation: it sorts last in traversal
 * and both overloads empty it [LIVE_REGION_CLEAR_MILLIS] after each announcement, so a
 * TalkBack user swiping through the screen never lands on a stale, invisible "Agent is
 * working…" or on a duplicate of text that stays visible next to it.
 *
 * This overload announces [message] each time it changes to a new non-null text (a call site
 * may keep passing the same text while it stays on screen; it is announced once) and empties
 * the region when it becomes null.
 */
@Composable
fun LiveRegionAnnouncer(
    message: String?,
    modifier: Modifier = Modifier,
    mode: LiveRegionMode = LiveRegionMode.Polite,
) {
    val state = rememberLiveRegionState()
    LaunchedEffect(state, message) { state.follow(message) }
    LiveRegionAnnouncer(state, modifier, mode)
}

/** The invisible 1dp live-region text itself; only the two public overloads place it. */
@Composable
private fun LiveRegionText(message: String?, modifier: Modifier, mode: LiveRegionMode) {
    Text(
        text = message.orEmpty(),
        color = Color.Transparent,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .size(1.dp)
            .clipToBounds()
            .semantics {
                liveRegion = mode
                traversalIndex = Float.MAX_VALUE
                isTraversalGroup = false
            },
    )
}

/** Holds the current announcement; [announce] re-announces even an identical text. */
@Stable
class LiveRegionState {
    var message: String? by mutableStateOf(null)
        private set
    private var toggle = false

    fun announce(text: String) {
        val base = text.trimEnd(ZERO_WIDTH_SPACE)
        message = if (message?.trimEnd(ZERO_WIDTH_SPACE) == base) {
            toggle = !toggle
            if (toggle) base + ZERO_WIDTH_SPACE else base
        } else {
            toggle = false
            base
        }
    }

    fun clear() {
        message = null
    }

    /**
     * Mirrors a caller's current text (the String overload of [LiveRegionAnnouncer]): a text
     * is announced, null empties the region. Called once per change of that text.
     */
    fun follow(text: String?) {
        if (text == null) clear() else announce(text)
    }

    /** Empties the region if it still shows [shown] (a newer announcement keeps its text). */
    fun clearIfShowing(shown: String) {
        if (message == shown) message = null
    }

    private companion object {
        const val ZERO_WIDTH_SPACE = '​'
    }
}

@Composable
fun rememberLiveRegionState(): LiveRegionState = remember { LiveRegionState() }

/** [LiveRegionAnnouncer] driven by a [LiveRegionState]. */
@Composable
fun LiveRegionAnnouncer(state: LiveRegionState, modifier: Modifier = Modifier, mode: LiveRegionMode = LiveRegionMode.Polite) {
    val message = state.message
    LaunchedEffect(state, message) {
        if (message != null) {
            delay(LIVE_REGION_CLEAR_MILLIS)
            state.clearIfShowing(message)
        }
    }
    LiveRegionText(message = message, modifier = modifier, mode = mode)
}

/** How long an announcement stays in the region before it is emptied (DS §8.1.4). */
const val LIVE_REGION_CLEAR_MILLIS = 1_500L

@LightDarkPreviews
@Composable
private fun LiveRegionAnnouncerPreview() {
    ComponentPreview {
        val state = rememberLiveRegionState()
        Column {
            LiveRegionAnnouncer(state)
            Button(onClick = { state.announce("Tool approval required") }) { Text("Announce") }
            Text(state.message.orEmpty())
        }
    }
}
