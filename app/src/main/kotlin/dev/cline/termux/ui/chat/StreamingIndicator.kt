package dev.cline.termux.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.chat.rows.DisclosureHeader
import dev.cline.termux.ui.chat.rows.DisclosureIconSlot
import dev.cline.termux.ui.chat.rows.DisclosureRowTokens
import dev.cline.termux.ui.chat.rows.DisclosureSpinner
import dev.cline.termux.ui.chat.rows.ShimmerText
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.ScrollToLatestButton
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

/*
 * Screen C (home-chat.md §4): the "Thinking..." row and the stick-to-bottom / scroll-to-latest
 * logic of the desktop `Conversation` ("sticking is an intent, not a position"), with both
 * thresholds copied: 24dp to stay stuck, 120dp before the button shows.
 */

/** Desktop `STICK_TO_BOTTOM_THRESHOLD_PX`, in dp. */
const val STICK_TO_BOTTOM_DP = 24

/** Desktop `SCROLL_BUTTON_THRESHOLD_PX`, in dp. */
const val SCROLL_BUTTON_THRESHOLD_DP = 120

/** Voice jump-then-glide: from beyond this index, jump to [JUMP_TO_INDEX] first. */
const val JUMP_FROM_INDEX = 6
const val JUMP_TO_INDEX = 3

/** The pure stick-to-bottom rules (§4.4), in pixels so they can be tested without a density. */
object TranscriptScrollRules {
    /** `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 24dp`. */
    fun isAtBottom(firstIndex: Int, firstOffsetPx: Int, stickPx: Int): Boolean = firstIndex == 0 && firstOffsetPx <= stickPx

    /** `distanceFromBottom > 120dp`: any item past the newest, or the newest scrolled by more than 120dp. */
    fun showsScrollButton(firstIndex: Int, firstOffsetPx: Int, buttonPx: Int): Boolean = firstIndex > 0 || firstOffsetPx > buttonPx

    /**
     * The pin intent: reaching the bottom pins; a user drag or fling away from it releases;
     * programmatic scrolls never release it.
     */
    fun nextPinned(pinned: Boolean, atBottom: Boolean, userScrolling: Boolean): Boolean = when {
        atBottom -> true
        userScrolling -> false
        else -> pinned
    }

    /** The jump-then-glide plan of the scroll button: an optional instant jump, then the glide (or a jump under reduced motion). */
    fun jumpTarget(firstIndex: Int): Int? = if (firstIndex > JUMP_FROM_INDEX) JUMP_TO_INDEX else null

    /**
     * Not following (§4.4): a reversed list keeps its first visible item's *bottom* where it was,
     * so a streaming row that grows there pushes everything above it up the screen. The desktop
     * list keeps the content still and lets the newest line grow out of view, so the position is
     * re-requested anchored on the item above it, at the offset it was laid out at; that item
     * then stays put and the growth goes below the viewport. Null when there is no item above.
     *
     * @param firstOffsetPx the first visible item's laid-out offset (`LazyListItemInfo.offset`).
     * @return the index and `requestScrollToItem` offset of the anchor.
     */
    fun holdAnchor(firstIndex: Int, firstOffsetPx: Int, firstSizePx: Int, spacingPx: Int, totalCount: Int): Pair<Int, Int>? {
        val above = firstIndex + 1
        if (above >= totalCount) return null
        return above to -(firstOffsetPx + firstSizePx + spacingPx)
    }
}

/**
 * The list's scroll intent for one session's transcript. [pinned] starts true (opening a session
 * lands at the bottom); [atBottom] and [showButton] are derived from the list position.
 */
@Stable
class TranscriptScrollState internal constructor(
    val listState: LazyListState,
    private val stickPx: Int,
    private val buttonPx: Int,
) {
    /**
     * Starts true for a new list (opening a session lands at the bottom); a list restored by the
     * per-session holder (§1.1) starts pinned only if it was left at the bottom.
     */
    var pinned: Boolean by mutableStateOf(
        TranscriptScrollRules.isAtBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, stickPx),
    )
        internal set

    /** Set while a finger drags the list (and through the fling that follows it). */
    internal var userScrolling: Boolean by mutableStateOf(false)

    val atBottom: Boolean by derivedStateOf {
        TranscriptScrollRules.isAtBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, stickPx)
    }

    val showButton: Boolean by derivedStateOf {
        TranscriptScrollRules.showsScrollButton(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, buttonPx)
    }

    /**
     * Older content runs under the app bar (§3.3 "hairline on scroll"): in a reversed list that is
     * `canScrollForward`, not `firstVisibleItemIndex > 0`.
     */
    val contentUnderTopBar: Boolean by derivedStateOf { listState.canScrollForward }

    /** Until this uptime, follow-the-bottom is off: a disclosure tap holds its header in place (§3.7). */
    internal var holdUntilMs: Long = 0L

    /** Before measure: keep the newest row in view while pinned (§4.4 `requestScrollToItem(0)`). */
    internal fun follow() {
        listState.requestScrollToItem(0)
    }

    /**
     * Before measure, while not pinned: hold the content still instead of letting the first visible
     * item's growth push it up ([TranscriptScrollRules.holdAnchor]). Skipped during any scroll (a
     * request would stop a fling or the button's glide) and when the items at the anchor changed
     * since the last layout (an insertion is already held by the list's key tracking).
     *
     * @param keyAt the key the list now has at an index.
     */
    internal fun holdPosition(keyAt: (Int) -> Any?) {
        if (pinned || userScrolling || listState.isScrollInProgress) return
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return
        if (keyAt(first.index) != first.key) return
        val above = info.visibleItemsInfo.getOrNull(1)
        if (above != null && keyAt(above.index) != above.key) return
        val (index, offset) = TranscriptScrollRules.holdAnchor(
            first.index,
            first.offset,
            first.size,
            info.mainAxisItemSpacing,
            info.totalItemsCount,
        ) ?: return
        listState.requestScrollToItem(index, offset)
    }

    /** The button tap: jump then glide to the newest row, and pin (§4.4). */
    suspend fun scrollToLatest(reducedMotion: Boolean) {
        pinned = true
        if (reducedMotion) {
            listState.scrollToItem(0)
            return
        }
        TranscriptScrollRules.jumpTarget(listState.firstVisibleItemIndex)?.let { listState.scrollToItem(it) }
        listState.animateScrollToItem(0)
    }

    /**
     * A disclosure opened while pinned: its growth was scrolled away to hold the header (§3.7),
     * so the list is no longer at the bottom by the reader's choice; stop following.
     */
    internal fun unpinForDisclosure() {
        pinned = false
    }

    /** A new user message: to the bottom regardless of the pin (desktop `AutoScrollOnSend`). */
    internal fun jumpToNewest() {
        pinned = true
        listState.requestScrollToItem(0)
    }

    /** Hardware PageUp (toward older rows: forward in a reversed list) / PageDown. */
    internal suspend fun page(older: Boolean) {
        val viewport = listState.layoutInfo.viewportSize.height
        val amount = (viewport * 0.9f).coerceAtLeast(1f)
        listState.animateScrollBy(if (older) amount else -amount)
    }
}

/** A [TranscriptScrollState] over [listState] (which the per-session saveable holder restores). */
@Composable
fun rememberTranscriptScrollState(listState: LazyListState): TranscriptScrollState {
    val density = LocalDensity.current
    val stickPx = with(density) { STICK_TO_BOTTOM_DP.dp.roundToPx() }
    val buttonPx = with(density) { SCROLL_BUTTON_THRESHOLD_DP.dp.roundToPx() }
    val state = remember(listState, stickPx, buttonPx) { TranscriptScrollState(listState, stickPx, buttonPx) }
    // A drag (and its fling) is a user scroll; programmatic scrolls never are.
    LaunchedEffect(state) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) state.userScrolling = true
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling -> if (!scrolling) state.userScrolling = false }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.atBottom to state.userScrolling }.collect { (atBottom, userScrolling) ->
            state.pinned = TranscriptScrollRules.nextPinned(state.pinned, atBottom, userScrolling)
        }
    }
    return state
}

/**
 * The "Thinking..." row (§4.3): the non-interactive disclosure header with its spinner in the
 * icon slot and the `onSurfaceVariant` shimmer label — the tool row's exact geometry
 * ([DisclosureRowTokens]), so the first real row replaces it in place. Reduced motion: static
 * label, spinner kept.
 */
@Composable
fun ThinkingIndicatorRow(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.chat_thinking)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DisclosureHeader(expanded = null, onToggle = {}, contentDescription = label, modifier = modifier) {
        DisclosureIconSlot { DisclosureSpinner(muted) }
        ShimmerText(label, shimmer = !ClineTheme.reducedMotion, color = muted, style = DisclosureRowTokens.labelStyle)
    }
}

@LightDarkPreviews
@Composable
private fun ThinkingIndicatorPreview() {
    ComponentPreview {
        Column {
            ThinkingIndicatorRow()
        }
    }
}

@LightDarkPreviews
@Composable
private fun ScrollToLatestPlacementPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
            ScrollToLatestButton(
                visible = true,
                onClick = {},
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
            )
        }
    }
}
