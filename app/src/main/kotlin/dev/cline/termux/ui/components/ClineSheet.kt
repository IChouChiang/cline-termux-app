package dev.cline.termux.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The app's `ModalBottomSheet` (DS §5.5): drag handle, a title row (`titleMedium`, 24dp
 * gutters, marked as a heading and given focus when the sheet opens, with the keyboard focus
 * ring, DS §8.1.7), the content, and a bottom inset of `navigationBars` + 16dp.
 *
 * Accessibility (DS §8.1.7): the sheet is a pane titled [paneTitle] (the [title] by default),
 * so TalkBack announces it by name instead of a generic "Bottom Sheet". [returnFocus] is the
 * opening control's requester: focus goes back to it once the sheet has closed, however it
 * closed (scrim, back, drag, an action). A [footer] is pinned below the
 * content; with [footerRidesIme] it also rides the keyboard (ask-question, prompt-queue
 * edit). Container `surfaceContainerLow`, max width 640dp (M3 default).
 *
 * Back is M3's own predictive collapse (DS §5.8 item 2). A sheet that never needs a
 * half-height anchor passes `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, so
 * that collapse hides it in one gesture. A sheet that keeps the anchor (the approval and
 * question sheets, driven to PartiallyExpanded) passes [backDismisses]: M3 would first step it
 * from Expanded down to the anchor, so a predictive back handler replaces M3's for the open
 * sheet. It scales the sheet surface from 1 to 0.9 about its bottom centre with the gesture,
 * springs back on cancel, and on commit hides the sheet and calls [onDismissRequest]. Back
 * callbacks registered later in the content (an open edit) still take it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClineSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    titleActions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    footerRidesIme: Boolean = false,
    secure: Boolean = false,
    backDismisses: Boolean = false,
    paneTitle: String? = title,
    returnFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReturnFocusOnClose(returnFocus)
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val reducedMotion = ClineTheme.reducedMotion
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // contentWindowInsets is zero (the bottom inset and the IME are handled below), so keep the
        // expanded sheet out of the status bar here: in height-compact landscape it would otherwise
        // slide under the status bar icons with its drag handle.
        modifier = modifier
            .then(if (paneTitle != null) Modifier.semantics { this.paneTitle = paneTitle } else Modifier)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .then(
                if (backDismisses) {
                    Modifier.graphicsLayer {
                        val scale = sheetBackScale(backProgress.value)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                } else {
                    Modifier
                },
            ),
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { WindowInsets(0.dp) },
        properties = ModalBottomSheetProperties(
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        if (backDismisses) {
            PredictiveBackHandler(enabled = sheetState.targetValue != SheetValue.Hidden) { events ->
                try {
                    events.collect { backProgress.snapTo(it.progress) }
                } catch (cancelled: CancellationException) {
                    scope.launch { if (reducedMotion) backProgress.snapTo(0f) else backProgress.animateTo(0f) }
                    throw cancelled
                }
                // The surface keeps its back scale while it slides away, as M3's own collapse does.
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) dismiss()
                    scope.launch { backProgress.snapTo(0f) }
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (title != null || titleContent != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Spacing.sheetGutter)
                        .focusRing(MaterialTheme.shapes.small)
                        .focusRequester(focusRequester)
                        .focusable()
                        .semantics { heading() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(
                            title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    titleActions?.invoke(this)
                }
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            }
            Column(Modifier.weight(1f, fill = false), content = content)
            if (footer != null) {
                val insets = if (footerRidesIme) WindowInsets.ime.union(WindowInsets.sheetBottom) else WindowInsets.sheetBottom
                Column(Modifier.fillMaxWidth().windowInsetsPadding(insets)) { footer() }
            } else {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.sheetBottom))
            }
        }
    }
}

/**
 * Hands focus back to [returnFocus] once the calling sheet or dialog has left composition
 * (DS §8.1.7). Call it in the host's composition, outside the sheet's own window: the request
 * is posted to the host view, so it runs after the sheet window is gone. A trigger that has
 * left the screen meanwhile is skipped; touch mode leaves focus where it is, because a
 * clickable is not focusable there.
 */
@Composable
fun ReturnFocusOnClose(returnFocus: FocusRequester?) {
    if (returnFocus == null) return
    val view = LocalView.current
    DisposableEffect(returnFocus, view) {
        onDispose { view.post { runCatching { returnFocus.requestFocus() } } }
    }
}

/**
 * A one-slot hand-off for a sheet hosted far from its trigger (the shell's Kernel status sheet,
 * opened through the navigator): the trigger [offer]s its requester just before it asks for the
 * sheet, and the sheet [take]s it as its `returnFocus` when it opens (DS §8.1.7).
 */
@Stable
class FocusReturnSlot {
    private var pending: FocusRequester? = null

    fun offer(requester: FocusRequester) {
        pending = requester
    }

    /** The offered requester, once; null when the sheet was opened by something else. */
    fun take(): FocusRequester? = pending.also { pending = null }
}

/** The shell's [FocusReturnSlot] for the Kernel status sheet; null outside the shell. */
val LocalKernelSheetFocusReturn = staticCompositionLocalOf<FocusReturnSlot?> { null }

/** Smallest scale of the sheet surface at full back progress (DS §5.8: 1 to 0.9). */
internal const val SHEET_BACK_MIN_SCALE = 0.9f

/** The sheet surface's scale for a predictive back [progress] in 0..1. */
internal fun sheetBackScale(progress: Float): Float = 1f - (1f - SHEET_BACK_MIN_SCALE) * progress.coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@LightDarkPreviews
@Composable
private fun ClineSheetPreview() {
    ComponentPreview(padding = false) {
        ClineSheet(
            onDismissRequest = {},
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            title = "Session details",
        ) {
            ListItem(headlineContent = { Text("Workspace") }, supportingContent = { Text("cline-app") })
            ListItem(headlineContent = { Text("Model") }, supportingContent = { Text("claude-sonnet-5-5") })
        }
    }
}
