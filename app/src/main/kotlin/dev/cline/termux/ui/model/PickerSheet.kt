package dev.cline.termux.ui.model

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.components.ReturnFocusOnClose
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.theme.Spacing

/**
 * The sheet shell of the model and workspace pickers (SP §11 DS §5.5 amendment): a
 * `ModalBottomSheet` whose content insets are `BottomSheetDefaults.windowInsets ∪ ime`, so the
 * content shrinks above the keyboard instead of being covered; the drag handle; a `titleMedium`
 * title row (24dp gutters, heading, focused on open, DS §8.1.7); and a snackbar host *inside* the
 * sheet window, because the app's host sits under the sheet's scrim. The sheet's insets already
 * carry the navigation bar, so lists inside add only [PickerSheetDefaults.listBottomPadding] to
 * reach the DS "navigation bars + 16dp" bottom inset.
 *
 * As [ClineSheet], the sheet is a pane titled [paneTitle] (the [title] by default), the
 * focused title shows the keyboard focus ring, and [returnFocus] gets focus back on close
 * (DS §8.1.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    title: String,
    modifier: Modifier = Modifier,
    /** Cap on the whole sheet's height (handle, title and body), e.g. S8's "up to 90 %". */
    maxHeight: Dp = Dp.Unspecified,
    snackbarHostState: SnackbarHostState? = null,
    titleActions: (@Composable RowScope.() -> Unit)? = null,
    paneTitle: String = title,
    returnFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReturnFocusOnClose(returnFocus)
    val titleFocus = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.semantics { this.paneTitle = paneTitle },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { BottomSheetDefaults.windowInsets.union(WindowInsets.ime) },
    ) {
        // The cap must sit inside the sheet: on the sheet's own modifier it also shrinks the
        // layout the sheet anchors against, which pins the sheet to the top of the screen.
        val bodyCap = if (maxHeight == Dp.Unspecified) {
            Modifier
        } else {
            val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
            Modifier.heightIn(max = (maxHeight - DragHandleHeight - navBottom).coerceAtLeast(0.dp))
        }
        Box(bodyCap) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = Spacing.sheetGutter, end = if (titleActions == null) Spacing.sheetGutter else Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .focusRing(MaterialTheme.shapes.small)
                            .focusRequester(titleFocus)
                            .focusable()
                            .semantics { heading() },
                    )
                    titleActions?.invoke(this)
                }
                content()
            }
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(Spacing.s))
            }
        }
        LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    }
}

/** `BottomSheetDefaults.DragHandle`: a 4dp bar with 22dp vertical padding. */
private val DragHandleHeight = 48.dp

object PickerSheetDefaults {
    /** Added to the sheet's own navigation-bar inset (DS §5.5: navigation bars + 16dp). */
    val listBottomPadding = PaddingValues(bottom = Spacing.l)
}
