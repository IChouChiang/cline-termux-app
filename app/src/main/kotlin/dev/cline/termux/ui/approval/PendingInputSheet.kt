package dev.cline.termux.ui.approval

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import dev.cline.termux.ui.components.ClineSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The modal frame shared by the approval (C3) and ask-question (C4) sheets: composed while
 * [visibility] is open (and while it animates away), driven to Expanded / PartiallyExpanded
 * from the controller, and collapsed to the banner by swipe, scrim, back or Esc through
 * [onCollapse]; dismissing never answers. The title row gets focus on open (DS §8.1.7).
 *
 * [returnFocus] gets focus back once the sheet has gone (DS §8.1.7: closing returns it to the
 * trigger). The host picks it from the state the sheet closes into, see [pendingInputReturnTarget]:
 * the banner after a collapse, the composer field once nothing is left. The value read is the
 * one current when the sheet leaves, so the hand-off is done here rather than by ClineSheet's own
 * `returnFocus`, which would also fire for the banner the moment the target switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingInputSheet(
    visibility: SheetVisibility,
    hasContent: Boolean,
    onCollapse: () -> Unit,
    titleContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    paneTitle: String? = null,
    footer: (@Composable () -> Unit)? = null,
    footerRidesIme: Boolean = false,
    returnFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = visibility.isOpen && hasContent
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var composed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val collapse by rememberUpdatedState(onCollapse)
    val returnTarget by rememberUpdatedState(returnFocus)
    val view = LocalView.current

    LaunchedEffect(open) {
        if (open) {
            composed = true
        } else if (composed) {
            settleHidden(sheetState)
            composed = false
        }
    }
    if (!composed) return

    // Posted to the host view so it runs after the sheet window is gone; a target that is not on
    // screen (or not focusable in touch mode) leaves focus where it is.
    DisposableEffect(view) {
        onDispose { view.post { runCatching { returnTarget?.requestFocus() } } }
    }

    LaunchedEffect(visibility) {
        when (visibility) {
            SheetVisibility.Expanded -> {
                // The sheet's own show() settles first; then expand so every control is visible without dragging.
                snapshotFlow { sheetState.currentValue }.first { it != SheetValue.Hidden }
                if (sheetState.currentValue != SheetValue.Expanded) sheetState.expand()
            }
            SheetVisibility.PartiallyExpanded ->
                if (sheetState.currentValue == SheetValue.Expanded && sheetState.hasPartiallyExpandedState) sheetState.partialExpand()
            else -> Unit
        }
    }

    ClineSheet(
        onDismissRequest = { collapse() },
        sheetState = sheetState,
        paneTitle = paneTitle,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) collapse() }
                    true
                } else {
                    false
                }
            },
        titleContent = titleContent,
        footer = footer,
        footerRidesIme = footerRidesIme,
        // Keeps the half-height anchor, so ClineSheet's own predictive back collapses it to the
        // banner in one gesture instead of M3 stepping Expanded down to PartiallyExpanded first.
        backDismisses = true,
        content = content,
    )
}

/**
 * Hides [state] even when our animation is interrupted. Back, the scrim or a swipe start their
 * own `hide()` on the same state, which cancels ours; without this the window stayed composed
 * with a hidden sheet and its full-screen scrim swallowed every tap on the chat. Rethrows only
 * when the caller itself is cancelled (the sheet reopened).
 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun settleHidden(state: SheetState) {
    while (state.currentValue != SheetValue.Hidden || state.targetValue != SheetValue.Hidden) {
        try {
            state.hide()
        } catch (interrupted: CancellationException) {
            currentCoroutineContext().ensureActive()
            snapshotFlow { state.currentValue == state.targetValue }.first { it }
        }
    }
}
