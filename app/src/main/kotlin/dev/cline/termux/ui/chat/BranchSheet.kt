package dev.cline.termux.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.model.PickerSheet
import dev.cline.termux.ui.theme.Spacing

/**
 * The Branch sheet (home-chat.md §2.5): a search field over the local branches (most recent
 * first, capped at [BranchRules.CAP]), the current branch checked, a tap on another one runs
 * `git checkout`. Rows are disabled while an agent runs in this workspace ([BranchSheetState.busy])
 * or a switch is in flight. Failures surface as a snackbar from the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSheet(
    state: BranchSheetState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = stringResource(R.string.branch_sheet_title)
    // DS §5.5 sheet anatomy (drag handle, `titleMedium` heading row on 24dp gutters that takes
    // focus on open, DS §8.1.7) with the IME unioned into the content insets for the search field.
    PickerSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = title,
        returnFocus = returnFocus,
    ) {
        BranchSheetContent(state, onQueryChange, onSelect)
    }
}

@Composable
internal fun BranchSheetContent(
    state: BranchSheetState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val rows = state.visible
    val listState = rememberLazyListState()
    val search = remember { FocusRequester() }
    // Focus the search field on open only with a hardware keyboard (§2.5): on touch the IME would cover the list.
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    LaunchedEffect(state.loading, state.current) {
        val index = rows.indexOf(state.current)
        if (!state.loading && index > 0) listState.scrollToItem(index)
    }
    // A new filter shows its matches from the first one, not from the old scroll offset.
    LaunchedEffect(state.query) {
        if (state.query.isNotEmpty()) listState.scrollToItem(0)
    }
    LaunchedEffect(hardwareKeyboard) {
        if (!hardwareKeyboard) return@LaunchedEffect
        // The sheet focuses its title on open (DS §8.1.7); with a hardware keyboard the search
        // field takes focus one frame later, so typing filters at once.
        withFrameNanos { }
        runCatching { search.requestFocus() }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.l)) {
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.branch_search_placeholder),
            clearContentDescription = stringResource(R.string.components_clear_search),
            modifier = Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s).focusRequester(search),
            imeAction = ImeAction.Done,
            onImeAction = { rows.singleOrNull()?.let(onSelect) },
        )
        if (state.busy) Note(stringResource(R.string.branch_busy), small = true)
        when {
            // §2.5: loading is one row "Loading..." in the row geometry (spinner in the icon slot).
            state.loading -> Row(
                Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(horizontal = Spacing.sheetGutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                CircularProgressIndicator(Modifier.size(RowIconSize), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.branch_loading),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.empty -> Note(stringResource(R.string.branch_empty))
            // §2.5 `weight(1f)`: the list takes what the sheet has left above the IME and scrolls.
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), state = listState) {
                items(rows, key = { it }) { branch ->
                    BranchRow(
                        name = branch,
                        current = branch == state.current,
                        switching = branch == state.switchingTo,
                        enabled = state.rowsEnabled || branch == state.current,
                        onClick = { onSelect(branch) },
                    )
                }
            }
        }
        if (state.capped && !state.loading) {
            HorizontalDivider()
            Note(stringResource(R.string.branch_capped), small = true)
        }
    }
}

/**
 * §2.5 row: 48 dp, `call_split` 16 dp + name `labelLarge`, a trailing `check` on the current
 * branch, the current row tinted `secondaryContainer`; a 16 dp spinner while switching to it.
 */
@Composable
private fun BranchRow(name: String, current: Boolean, switching: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stateText = when {
        switching -> stringResource(R.string.branch_switching_state)
        current -> stringResource(R.string.branch_current_state)
        else -> null
    }
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.sheetGutter)
            .semantics { if (stateText != null) stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(ClineIcons.Chrome.Branch, contentDescription = null, Modifier.size(RowIconSize), tint = content)
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (current && enabled) MaterialTheme.colorScheme.onSecondaryContainer else content,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        when {
            switching -> CircularProgressIndicator(Modifier.size(RowIconSize), strokeWidth = 2.dp)
            current -> Icon(ClineIcons.Check, contentDescription = null, Modifier.size(RowCheckSize), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Note(text: String, small: Boolean = false) {
    // §2.5: the busy line and the 500-branch footer are `bodySmall`; "No branches found" reads as body text.
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.m),
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val RowHeight = 48.dp
private val RowIconSize = 16.dp
private val RowCheckSize = 20.dp
private const val DISABLED_ALPHA = 0.38f

@LightDarkPreviews
@Composable
private fun BranchSheetContentPreview() {
    ComponentPreview(padding = false) {
        BranchSheetContent(
            BranchSheetState(branches = listOf("main", "feature/chat-screen", "fix/hub-entry"), current = "main", switchingTo = "fix/hub-entry"),
            onQueryChange = {},
            onSelect = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun BranchSheetBusyPreview() {
    ComponentPreview(padding = false) {
        BranchSheetContent(BranchSheetState(branches = listOf("main", "dev"), current = "dev", busy = true), onQueryChange = {}, onSelect = {})
    }
}

@LightDarkPreviews
@Composable
private fun BranchSheetLoadingPreview() {
    ComponentPreview(padding = false) {
        BranchSheetContent(BranchSheetState(loading = true), onQueryChange = {}, onSelect = {})
    }
}

@LightDarkPreviews
@Composable
private fun BranchSheetEmptyPreview() {
    ComponentPreview(padding = false) {
        BranchSheetContent(BranchSheetState(branches = listOf("main", "dev"), current = "main", query = "zzz"), onQueryChange = {}, onSelect = {})
    }
}

@LightDarkPreviews
@Composable
private fun BranchSheetCappedPreview() {
    ComponentPreview(padding = false) {
        BranchSheetContent(
            BranchSheetState(branches = List(BranchRules.CAP) { i -> "feature/branch-$i" }, current = "feature/branch-0", capped = true),
            onQueryChange = {},
            onSelect = {},
        )
    }
}
