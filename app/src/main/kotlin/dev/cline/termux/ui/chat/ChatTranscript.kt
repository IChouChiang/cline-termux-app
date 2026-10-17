package dev.cline.termux.ui.chat

import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import dev.cline.termux.ui.navigation.LocalShellLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.ui.chat.rows.ChatTransportState
import dev.cline.termux.ui.chat.rows.ErrorBanner
import dev.cline.termux.ui.chat.rows.ErrorBannerAction
import dev.cline.termux.ui.chat.rows.TranscriptCallbacks
import dev.cline.termux.ui.chat.rows.TranscriptItem
import dev.cline.termux.ui.chat.rows.TranscriptModel
import dev.cline.termux.ui.chat.rows.TranscriptSelection
import dev.cline.termux.ui.chat.rows.TransportLine
import dev.cline.termux.ui.chat.rows.WORK_PIN_MS
import dev.cline.termux.ui.chat.rows.height
import dev.cline.termux.ui.chat.rows.rememberTranscriptSelection
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.components.ScrollToLatestButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/*
 * Screen B (home-chat.md §3) as one reusable composable: the reversed LazyColumn over the
 * render items with stable keys, the stick-to-bottom intent and scroll button (screen C), the
 * "Thinking..." row, the 180 ms switching/loading loader, the transport line and error banner,
 * the missing-session state, the polite status announcements, "Select text" exit on a tap
 * outside, and the disclosure header hold. Rows come from `ui.chat.rows`; state from
 * [TranscriptController]; the host supplies the actions through [TranscriptCallbacks].
 */

/** Read by the conversation header: older content runs under the app bar (hairline, §3.3). */
@Stable
class TranscriptChrome {
    var contentUnderTopBar: Boolean by mutableStateOf(false)
        internal set
}

/** HC §3.7, voice `GROWTH_SCROLL_SIGN`: scrolling forward (toward older rows) by a row's growth holds its header. */
private const val GROWTH_SCROLL_SIGN = 1f

/** Keys of the list's own items under the rows (reversed order: these sit at the bottom). */
private const val BANNER_KEY = "banner"
private const val TRANSPORT_KEY = "transport"
private const val THINKING_KEY = "thinking"

/** Desktop switch-transition delay before the loader appears (§3.5). */
const val LOADER_DELAY_MS = 180L

/** §3.3 content padding: top 8dp; bottom 16dp + 40dp clearance for the scroll button (desktop `pb-16`). */
private val ContentTop = 8.dp
private val ContentBottom = 16.dp + 40.dp

/**
 * The transcript's bottom padding: [ContentBottom], except with the IME open on a
 * height-compact window (D18), where the transcript keeps about 72dp on the S25U and the 40dp
 * scroll-button clearance would leave it no row. There the newest row sits 8dp above the
 * composer and the scroll button, shown only when scrolled up, floats over older rows.
 */
internal fun transcriptBottomPadding(heightCompact: Boolean, imeOpen: Boolean): Dp =
    if (heightCompact && imeOpen) 8.dp else ContentBottom

/** Saveable-holder key for Home (no session). */
private const val HOME_KEY = "home"

/**
 * The thread transcript.
 *
 * - [state]: from [TranscriptController] (one per screen; `transcriptUiStates` for the flow).
 * - [callbacks]: row actions; their null members hide the matching control. When
 *   `onExpandImage` is null and [lightbox] is given, images open in [lightbox].
 * - [onBannerAction]: the banner's "Retry" (and the credential action when the callbacks carry none).
 * - [onNewSession]: the missing-session state's "New session".
 * - [bottomInset]: space taken by docked banners over the transcript's bottom edge (the
 *   approval banner), so the scroll button sits above them (§4.4).
 * - [snackbarInset]: the height of the snackbar showing over the transcript's bottom edge, 0dp
 *   when none; the scroll button rides above it (DS §4.1 #17), animated over Short4.
 * - [chrome]: receives the app-bar hairline signal.
 * - [liveRegion]: the conversation's one polite status region (DS §8.1.4, CA C1.14), hoisted
 *   by the host that also announces the composer's and the approval sheet's facts into it;
 *   null hosts a region here (previews, a transcript without a composer).
 *
 * Per-session UI state (scroll position, row disclosures, selection) lives in a saveable holder
 * keyed by the session id (§1.1), so switching back restores it within the process.
 */
@Composable
fun ChatTranscript(
    state: TranscriptUiState,
    callbacks: TranscriptCallbacks,
    modifier: Modifier = Modifier,
    lightbox: LightboxState? = null,
    chrome: TranscriptChrome? = null,
    onBannerAction: (TranscriptBannerAction) -> Unit = {},
    onNewSession: () -> Unit = {},
    bottomInset: Dp = 0.dp,
    liveRegion: LiveRegionState? = null,
    snackbarInset: Dp = 0.dp,
) {
    val holder = rememberSaveableStateHolder()
    val ownRegion = rememberLiveRegionState()
    val region = liveRegion ?: ownRegion
    LaunchedEffect(state.sessionId) { lightbox?.closeUnlessSession(state.sessionId) }
    holder.SaveableStateProvider(state.sessionId ?: HOME_KEY) {
        if (state.missing) {
            if (chrome != null) SideEffect { chrome.contentUnderTopBar = false }
            MissingSession(onNewSession, modifier)
        } else {
            SessionTranscript(state, callbacks, modifier, lightbox, chrome, onBannerAction, bottomInset, snackbarInset, region, hostsRegion = liveRegion == null)
        }
    }
}

/** §3.5 Missing: `session.get` → `session_not_found`. */
@Composable
private fun MissingSession(onNewSession: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.chat_missing_title),
            body = stringResource(R.string.chat_missing_body),
            icon = ClineIcons.ChatBubble,
            actionLabel = stringResource(R.string.chat_new_session),
            onAction = onNewSession,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionTranscript(
    state: TranscriptUiState,
    callbacks: TranscriptCallbacks,
    modifier: Modifier,
    lightbox: LightboxState?,
    chrome: TranscriptChrome?,
    onBannerAction: (TranscriptBannerAction) -> Unit,
    bottomInset: Dp,
    snackbarInset: Dp,
    liveRegion: LiveRegionState,
    hostsRegion: Boolean,
) {
    val listState = rememberLazyListState()
    val scroll = rememberTranscriptScrollState(listState)
    val selection = rememberTranscriptSelection()
    val scope = rememberCoroutineScope()
    val reducedMotion = ClineTheme.reducedMotion
    val resources = LocalContext.current.resources
    val strings = remember(resources) { TranscriptStrings.from(resources) }

    if (chrome != null) {
        LaunchedEffect(scroll, chrome) { snapshotFlow { scroll.contentUnderTopBar }.collect { chrome.contentUnderTopBar = it } }
    }

    // §3.5: the loader (and its announcement) only after 180 ms of waiting.
    // Previews have no frame clock for the delay: show the loader at once there.
    val inspection = LocalInspectionMode.current
    var loaderVisible by remember { mutableStateOf(inspection && state.switching != Switching.None) }
    val loadingText = stringResource(R.string.chat_loading)
    val switchingText = stringResource(R.string.chat_switching)
    LaunchedEffect(state.switching) {
        if (inspection) return@LaunchedEffect
        loaderVisible = false
        if (state.switching == Switching.None) return@LaunchedEffect
        delay(LOADER_DELAY_MS)
        loaderVisible = true
        liveRegion.announce(if (state.switching == Switching.Loading) loadingText else switchingText)
    }

    // §3.15: one polite status text per conversation.
    val announcer = remember(strings) { TranscriptAnnouncer(strings) }
    val workingText = stringResource(R.string.chat_status_working)
    val failedFormat = stringResource(R.string.chat_status_failed)
    LaunchedEffect(state) {
        when (val announcement = announcer.next(state)) {
            TranscriptAnnouncement.Working -> liveRegion.announce(workingText)
            is TranscriptAnnouncement.Completed -> liveRegion.announce(announcement.excerpt)
            is TranscriptAnnouncement.Failed -> liveRegion.announce(String.format(failedFormat, announcement.detail))
            null -> Unit
        }
    }

    // §4.4: follow the newest row while pinned (before measure), and always after a new user message;
    // not pinned, the content holds still while the newest row grows out of view.
    val userCount = remember { StateRef(state.userMessageCount) }
    val reversedRows = remember(state.model) { state.model.rows.asReversed() }
    val leadKeys = listOfNotNull(
        state.banner?.let { BANNER_KEY },
        TRANSPORT_KEY.takeIf { state.transport != ChatTransportState.Connected },
        state.indicator?.let { THINKING_KEY },
    )
    SideEffect {
        val held = SystemClock.uptimeMillis() < scroll.holdUntilMs
        if (state.userMessageCount > userCount.value) {
            scroll.jumpToNewest()
        } else if (scroll.pinned && !scroll.userScrolling && !held &&
            (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
        ) {
            scroll.follow()
        } else if (!held) {
            scroll.holdPosition { index -> leadKeys.getOrNull(index) ?: reversedRows.getOrNull(index - leadKeys.size)?.key }
        }
        userCount.value = state.userMessageCount
    }

    val wrapped = remember(callbacks, scroll, lightbox, state.sessionId) {
        wrapCallbacks(callbacks, scroll, lightbox, state.sessionId)
    }

    // §3.14: "Select text" ends on a tap outside the selected message.
    val selectedId = selection.selectedMessageId
    val selectedRowKey = remember(selectedId, state.model) { selectedId?.let { rowKeyContaining(state.model, it) } }
    val selectedBounds = remember { StateRef<Rect?>(null) }
    val listOrigin = remember { StateRef(Offset.Zero) }

    val paneTitle = stringResource(R.string.chat_pane_title)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // §3.3: the transcript column is centred and at most 840dp wide.
        val gutter = Spacing.gutterFor(maxWidth)
        val side = max(gutter, (maxWidth - Spacing.contentMaxWidth) / 2)
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                start = side,
                end = side,
                top = ContentTop,
                bottom = transcriptBottomPadding(LocalShellLayout.current.heightCompact, WindowInsets.isImeVisible) + bottomInset,
            ),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { listOrigin.value = it.positionInRoot() }
                .pointerInput(selectedId != null) {
                    if (selectedId == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val inRoot = listOrigin.value + down.position
                        if (selectedBounds.value?.contains(inRoot) != true) selection.clear()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.PageUp -> scope.launch { scroll.page(older = true) }.let { true }
                        Key.PageDown -> scope.launch { scroll.page(older = false) }.let { true }
                        else -> false
                    }
                }
                // DS §8.1.7: the conversation holding focus (PageUp/PageDown) shows the ring too.
                .focusRing(RectangleShape, inset = true)
                .focusable()
                .semantics {
                    this.paneTitle = paneTitle
                    isTraversalGroup = true
                },
        ) {
            // Reversed: the first item sits at the bottom. Desktop order under the rows: indicator, transport line, banner.
            state.banner?.let { banner ->
                item(key = BANNER_KEY, contentType = "banner") {
                    TranscriptBannerItem(banner, callbacks, onBannerAction, Modifier.semantics { traversalIndex = 3f })
                }
            }
            if (state.transport != ChatTransportState.Connected) {
                item(key = TRANSPORT_KEY, contentType = "transport") {
                    TransportLine(state.transport, Modifier.semantics { traversalIndex = 2f })
                }
            }
            state.indicator?.let { indicator ->
                item(key = THINKING_KEY, contentType = "thinking") {
                    ThinkingIndicatorRow(
                        Modifier
                            .animateItem(
                                fadeInSpec = if (reducedMotion) null else tween(ClineMotion.Short3),
                                placementSpec = null,
                                fadeOutSpec = if (reducedMotion) null else tween(ClineMotion.Short3),
                            )
                            .padding(top = indicator.gapAbove.height)
                            .semantics { traversalIndex = 1f },
                    )
                }
            }
            items(reversedRows, key = { it.key }, contentType = { it.type }) { row ->
                val tracked = if (row.key == selectedRowKey) {
                    Modifier.onGloballyPositioned { selectedBounds.value = it.boundsInRoot() }
                } else {
                    Modifier
                }
                TranscriptItem(
                    row = row,
                    actionsState = state.actions,
                    callbacks = wrapped,
                    selection = selection,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = if (reducedMotion) null else tween(ClineMotion.Short3),
                            placementSpec = null,
                            fadeOutSpec = if (reducedMotion) null else tween(ClineMotion.Short2),
                        )
                        .then(tracked),
                )
            }
        }

        if (loaderVisible && state.switching != Switching.None) {
            val description = if (state.switching == Switching.Loading) loadingText else switchingText
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .semantics { contentDescription = description },
            )
        }

        // DS §4.1 #17: a showing snackbar pushes the button up (M3 Scaffold's FAB rule).
        val snackbarLift by animateDpAsState(
            targetValue = snackbarInset,
            animationSpec = if (reducedMotion) snap() else tween(ClineMotion.Short4),
            label = "snackbarLift",
        )
        ScrollToLatestButton(
            visible = scroll.showButton,
            onClick = { scope.launch { scroll.scrollToLatest(reducedMotion) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = side, bottom = Spacing.l + bottomInset + snackbarLift),
        )
        if (hostsRegion) LiveRegionAnnouncer(liveRegion, Modifier.align(Alignment.TopStart))
    }
}

/** The banner with its action routed: credential actions go where the error rows send them. */
@Composable
private fun TranscriptBannerItem(
    banner: TranscriptBanner,
    callbacks: TranscriptCallbacks,
    onBannerAction: (TranscriptBannerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = when (val a = banner.action) {
        null -> null
        is TranscriptBannerAction.Retry -> ErrorBannerAction.Retry { onBannerAction(a) }
        is TranscriptBannerAction.Credential -> ErrorBannerAction.Credential(a.action) {
            callbacks.onCredentialAction?.invoke(a.action) ?: onBannerAction(a)
        }
    }
    ErrorBanner(message = banner.text, showText = banner.showText, action = action, modifier = modifier)
}

/**
 * The host's callbacks plus the list's own needs: a toggled disclosure holds follow-the-bottom
 * for [WORK_PIN_MS] and its growth is scrolled away so the header stays under the finger
 * (§3.7); images open in the lightbox when the host did not take them.
 */
private fun wrapCallbacks(
    callbacks: TranscriptCallbacks,
    scroll: TranscriptScrollState,
    lightbox: LightboxState?,
    sessionId: String?,
): TranscriptCallbacks = callbacks.copy(
    onDisclosureToggled = { key ->
        scroll.holdUntilMs = SystemClock.uptimeMillis() + WORK_PIN_MS
        callbacks.onDisclosureToggled?.invoke(key)
    },
    onRowHeightDelta = { key, delta ->
        // Pinned or not, the toggled row's header stays under the finger (§3.16 "the header of
        // the tapped row does not jump"; voice `ChatScreen` compensates in both cases). At the
        // bottom of a reversed list an unheld expansion would push the header off the top. Once
        // a row opens there, the reader is reading it: drop the pin so following the newest
        // line does not snap the list back after the hold.
        if (scroll.listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
            scroll.listState.dispatchRawDelta(GROWTH_SCROLL_SIGN * delta)
            if (delta > 0 && scroll.pinned) scroll.unpinForDisclosure()
        }
        callbacks.onRowHeightDelta?.invoke(key, delta)
    },
    onExpandImage = callbacks.onExpandImage ?: lightbox?.let { box -> { gallery, index -> box.open(sessionId, gallery, index) } },
)

/** The key of the top-level row that renders [messageId] (directly or inside a run / work group). */
fun rowKeyContaining(model: TranscriptModel, messageId: String): String? =
    model.rows.firstOrNull { renders(it.item, messageId) }?.key

private fun renders(item: ChatRenderItem, messageId: String): Boolean = when (item) {
    is ChatRenderItem.Message -> item.message.id == messageId || item.reasoningMessages.any { it.id == messageId }
    is ChatRenderItem.Tools -> item.messages.any { it.id == messageId }
    is ChatRenderItem.Run -> item.items.any { renders(it, messageId) }
    is ChatRenderItem.Work -> item.items.any { renders(it, messageId) }
}

/** A plain holder for values read by effects and gesture handlers without recomposing. */
private class StateRef<T>(var value: T)
