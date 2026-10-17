package dev.cline.termux.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import dev.cline.termux.R
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.WorkspaceRow
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.rememberKernelBlockHandler
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.model.PickerSheet
import dev.cline.termux.ui.model.PickerSheetDefaults
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WorkspaceBrowse
import dev.cline.termux.ui.navigation.backStackFlow
import dev.cline.termux.ui.navigation.shellRoute
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.LocalReducedMotion
import dev.cline.termux.ui.theme.Spacing

/**
 * The hoisted "open" flag of the workspace sheet (SP §0.2: sheets are not routes; it survives
 * rotation and process death). While the folder browser is a full-screen destination the sheet
 * is hidden but stays open, so it reappears when the browser pops without a choice.
 */
@Stable
class WorkspacePickerSheetState(open: Boolean = false) {
    var open by mutableStateOf(open)

    companion object {
        val Saver: Saver<WorkspacePickerSheetState, Boolean> = Saver(save = { it.open }, restore = { WorkspacePickerSheetState(it) })
    }
}

@Composable
fun rememberWorkspacePickerSheetState(): WorkspacePickerSheetState =
    rememberSaveable(saver = WorkspacePickerSheetState.Saver) { WorkspacePickerSheetState() }

/**
 * SP S9 host for the Home workspace chip: the sheet while [state] is open, "Open folder..." →
 * `workspace/browse?start=` (hiding the sheet on compact/medium, over it on expanded), and the
 * browser's choice handed back through this entry's `SavedStateHandle` ([KEY_WORKSPACE_CHOSEN]),
 * which clears the open flag so the sheet stays closed after "Use this folder".
 *
 * The ViewModel is `viewModel(factory = WorkspacePickerViewModel.factory(graph))` in the host
 * screen; [navigator] is the shell's; [returnFocus] is the workspace chip's requester.
 */
@Composable
fun WorkspacePickerSheetHost(
    state: WorkspacePickerSheetState,
    viewModel: WorkspacePickerViewModel,
    navigator: ShellNavigator,
    returnFocus: FocusRequester? = null,
) {
    val hostEntry = LocalViewModelStoreOwner.current as? NavBackStackEntry
    val backStack by navigator.nav.backStackFlow().collectAsState()
    val browserOnStack = backStack.any { it.shellRoute() is WorkspaceBrowse }
    val chosenFlow = remember(hostEntry) { hostEntry?.savedStateHandle?.getStateFlow<String?>(KEY_WORKSPACE_CHOSEN, null) }
    val chosen = chosenFlow?.collectAsState()?.value
    LaunchedEffect(chosen) {
        if (chosen != null) {
            state.open = false
            // Set to null, never remove(): remove() drops the key but leaves this collected
            // StateFlow on the old path, which kept `chosen != null` and the sheet hidden forever.
            hostEntry?.savedStateHandle?.set<String?>(KEY_WORKSPACE_CHOSEN, null)
        }
    }
    val currentNavigator by rememberUpdatedState(navigator)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                WorkspacePickerEffect.Close -> state.open = false
                is WorkspacePickerEffect.OpenBrowser -> currentNavigator.push(WorkspaceBrowse(effect.start))
            }
        }
    }
    val asDialog = navigator.layout.editorsAsDialogs
    val visible = state.open && chosen == null && !(browserOnStack && !asDialog)
    if (visible) {
        val ui by viewModel.uiState.collectAsStateWithLifecycle()
        val blockActions = rememberKernelBlockHandler(navigator, onRetry = viewModel::retry)
        LaunchedEffect(Unit) { viewModel.onOpened() }
        WorkspacePickerSheet(
            state = ui,
            onDismissRequest = { state.open = false },
            onQueryChange = viewModel::setQuery,
            onOpenTyped = viewModel::openTyped,
            onSelect = viewModel::select,
            onOpenBrowser = viewModel::openBrowser,
            onJustChat = viewModel::justChat,
            blockActions = blockActions,
            returnFocus = returnFocus,
        )
    }
}

/** The S9.2 sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacePickerSheet(
    state: WorkspacePickerUiState,
    onDismissRequest: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenTyped: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onJustChat: () -> Unit,
    blockActions: KernelBlockHandler?,
    returnFocus: FocusRequester? = null,
) {
    PickerSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.workspace_title),
        returnFocus = returnFocus,
    ) {
        WorkspacePickerContent(state, onQueryChange, onOpenTyped, onSelect, onOpenBrowser, onJustChat, blockActions)
    }
}

/** Everything under the sheet title; stateless for previews. */
@Composable
fun WorkspacePickerContent(
    state: WorkspacePickerUiState,
    onQueryChange: (String) -> Unit,
    onOpenTyped: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onJustChat: () -> Unit,
    blockActions: KernelBlockHandler?,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val reducedMotion = LocalReducedMotion.current
    // The error is the list's last item; with the keyboard up it lands under the IME, so a new
    // failure is brought into view (DS §8: the message must be seen as well as announced).
    LaunchedEffect(state.error) {
        if (state.error == null) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        if (reducedMotion) listState.scrollToItem(last) else listState.animateScrollToItem(last)
    }
    Column(modifier.fillMaxWidth()) {
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.workspace_search),
            clearContentDescription = stringResource(R.string.components_clear_search),
            imeAction = ImeAction.Go,
            onImeAction = {
                if (state.pathLike) onOpenTyped() else keyboard?.hide()
            },
            modifier = Modifier.padding(horizontal = Spacing.sheetGutter),
        )
        LazyColumn(
            state = listState,
            contentPadding = PickerSheetDefaults.listBottomPadding,
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            val typed = state.query.trim()
            if (state.pathLike && state.blocked == null) {
                item(key = "open-typed") {
                    ActionRow(
                        text = stringResource(R.string.workspace_open_typed, typed),
                        icon = { Icon(ClineIcons.FolderOpen, contentDescription = null) },
                        enabled = !state.busy,
                        pending = state.validating == typed,
                        onClick = onOpenTyped,
                    )
                }
            }
            if (state.blocked != null) {
                item(key = "blocked") {
                    KernelBlockedCard(
                        block = state.blocked,
                        onAction = { blockActions?.onAction?.invoke(state.blocked, it) },
                        permissionPermanentlyDenied = blockActions?.permissionPermanentlyDenied == true,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
            } else {
                item(key = "header") {
                    Text(
                        stringResource(R.string.workspace_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = Spacing.sheetGutter, end = Spacing.sheetGutter, top = Spacing.m, bottom = Spacing.xs)
                            .semantics { heading() },
                    )
                }
                if (state.recents.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (state.pathLike) R.string.workspace_empty_path else R.string.workspace_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s),
                        )
                    }
                } else {
                    items(state.recents, key = { "recent:${it.path}" }) { row ->
                        RecentRow(row, enabled = !state.busy, pending = state.validating == row.path, onClick = { onSelect(row.path) })
                    }
                }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = Spacing.xs)) }
            if (state.blocked == null) {
                item(key = "open-folder") {
                    ActionRow(
                        text = stringResource(R.string.workspace_open_folder),
                        icon = { Icon(ClineIcons.AddCircle, contentDescription = null) },
                        enabled = !state.busy,
                        onClick = onOpenBrowser,
                    )
                }
            }
            item(key = "just-chat") {
                ActionRow(
                    text = stringResource(R.string.workspace_just_chat),
                    icon = { Icon(ClineIcons.AddComment, contentDescription = null) },
                    enabled = !state.busy,
                    onClick = onJustChat,
                )
            }
            state.error?.let { failed ->
                item(key = "error") { ValidationError(failed, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) }
            }
        }
    }
}

@Composable
private fun RecentRow(row: WorkspaceRow, enabled: Boolean, pending: Boolean, onClick: () -> Unit) {
    val validating = stringResource(R.string.workspace_validating)
    ListItem(
        headlineContent = {
            LtrText(row.display, style = ClineMono.small, maxLines = 1, overflow = TextOverflow.Ellipsis, readAsWritten = false)
        },
        leadingContent = { Icon(ClineIcons.Folder, contentDescription = null) },
        trailingContent = {
            Box(Modifier.size(24.dp)) {
                when {
                    pending -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    row.isActive -> Icon(ClineIcons.Check, contentDescription = null)
                }
            }
        },
        colors = if (row.isActive) {
            // DS §1.8 / §8.1.9: the active row's secondaryContainer pairs with onSecondaryContainer.
            val on = MaterialTheme.colorScheme.onSecondaryContainer
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = on,
                leadingIconColor = on,
                trailingIconColor = on,
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = row.isActive, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                // DS §8.1.12: the full path as written, not the `~` short form.
                contentDescription = row.path
                if (pending) stateDescription = validating
            },
    )
}

@Composable
private fun ActionRow(
    text: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    pending: Boolean = false,
) {
    val validating = stringResource(R.string.workspace_validating)
    ListItem(
        headlineContent = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = icon,
        trailingContent = if (pending) {
            { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (pending) stateDescription = validating },
    )
}

/** S9.3 error surface: `Surface(errorContainer, shape = small)` + `bodySmall`, announced politely. */
@Composable
internal fun ValidationError(path: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.workspace_open_failed, path),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(horizontal = Spacing.s, vertical = Spacing.s)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The browser hands its choice to the hosting entry under this key, then pops. */
const val KEY_WORKSPACE_CHOSEN = "workspace.browse.chosen"

// ------------------------------------------------------------------------------- previews

private const val PREVIEW_HOME = "/data/data/com.termux/files/home"

private val previewRows = listOf(
    WorkspaceRow("$PREVIEW_HOME/projects/cline-termux-app", "~/projects/cline-termux-app", isActive = true),
    WorkspaceRow("$PREVIEW_HOME/storage/shared/Code/site", "~/storage/shared/Code/site", isActive = false),
    WorkspaceRow("$PREVIEW_HOME/notes", "~/notes", isActive = false),
)

@Composable
private fun Preview(state: WorkspacePickerUiState) {
    ComponentPreview(padding = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            WorkspacePickerContent(state, {}, {}, {}, {}, {}, null)
        }
    }
}

@LightDarkPreviews
@Composable
private fun WorkspacePickerPopulatedPreview() = Preview(
    WorkspacePickerUiState(active = WorkspaceChoice.Folder("$PREVIEW_HOME/projects/cline-termux-app"), recents = previewRows),
)

@LightDarkPreviews
@Composable
private fun WorkspacePickerFirstRunPreview() = Preview(WorkspacePickerUiState())

@LightDarkPreviews
@Composable
private fun WorkspacePickerTypedPathPreview() = Preview(
    WorkspacePickerUiState(query = "~/projects/new-app", pathLike = true),
)

@LightDarkPreviews
@Composable
private fun WorkspacePickerValidatingPreview() = Preview(
    WorkspacePickerUiState(recents = previewRows, validating = previewRows[1].path),
)

@LightDarkPreviews
@Composable
private fun WorkspacePickerErrorPreview() = Preview(
    WorkspacePickerUiState(query = "~/does-not-exist", pathLike = true, error = "~/does-not-exist"),
)

@LightDarkPreviews
@Composable
private fun WorkspacePickerBlockedPreview() = Preview(WorkspacePickerUiState(blocked = KernelBlock.PermissionMissing))
