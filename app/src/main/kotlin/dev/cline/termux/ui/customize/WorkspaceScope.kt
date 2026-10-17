package dev.cline.termux.ui.customize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.data.workspace.formatWorkspacePath
import dev.cline.termux.data.workspace.looksLikeFolderPath
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.SectionHeader
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.model.PickerSheet
import dev.cline.termux.ui.model.PickerSheetDefaults
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * CR Appendix A WorkspaceScopeChip: `AssistChip` with `folder_open`, the workspace basename
 * in `ClineMono.small` and `arrow_drop_down`; opens the scope sheet. "No workspace" while
 * none is known.
 */
@Composable
fun WorkspaceScopeChip(workspaceRoot: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = workspaceRoot?.let(CustomizeRules::basename) ?: stringResource(R.string.customize_no_workspace)
    val cd = workspaceRoot?.let { stringResource(R.string.customize_workspace_cd, it) } ?: label
    AssistChip(
        onClick = onClick,
        enabled = workspaceRoot != null,
        modifier = modifier.semantics { contentDescription = cd },
        label = { LtrText(label, style = ClineMono.small, maxLines = 1, overflow = TextOverflow.Ellipsis, readAsWritten = false) },
        leadingIcon = { Icon(ClineIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        trailingIcon = { Icon(ClineIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/**
 * The workspace picker of SP S9 in "scope" mode (CR §1.6): recents and a typed path, no
 * "Just chat". Choosing validates the folder in Termux; only Customize's scope changes, the
 * Home selection is untouched. [returnFocus] is the scope chip's requester: focus goes back to
 * it once the sheet closes (DS §8.1.7).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScopeSheet(
    currentRoot: String?,
    candidates: List<String>,
    picker: WorkspacePickerState,
    onPick: (String) -> Unit,
    onQueryChanged: () -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    // PickerSheet, not ClineSheet: its content insets include the IME, so the folder list
    // shrinks above the keyboard while the search field is focused (DS §5.5, §5.7).
    PickerSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.customize_workspace_title),
        returnFocus = returnFocus,
    ) {
        WorkspaceScopeContent(currentRoot, candidates, picker, onPick, onQueryChanged)
    }
}

@Composable
private fun WorkspaceScopeContent(
    currentRoot: String?,
    candidates: List<String>,
    picker: WorkspacePickerState,
    onPick: (String) -> Unit,
    onQueryChanged: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val pathLike = looksLikeFolderPath(query.trim())
    val active = currentRoot?.let { WorkspaceChoice.Folder(it) } ?: WorkspaceChoice.Chat
    val rows = WorkspaceStore.rows(listOf(TERMUX_HOME) + candidates.filterNot { it == TERMUX_HOME }, active, query)
    val busy = picker.validating != null
    Column(Modifier.fillMaxWidth()) {
        SearchField(
            value = query,
            onValueChange = {
                query = it
                onQueryChanged()
            },
            placeholder = stringResource(R.string.customize_workspace_search),
            clearContentDescription = stringResource(R.string.customize_clear_search),
            modifier = Modifier.padding(horizontal = Spacing.sheetGutter),
            imeAction = ImeAction.Go,
            onImeAction = {
                if (pathLike && !busy) onPick(query.trim()) else keyboard?.hide()
            },
        )
        picker.invalidPath?.let { path ->
            ErrorSurface(stringResource(R.string.customize_workspace_error, path))
        }
        picker.blocked?.let { problem -> ErrorSurface(problem.text()) }
        LazyColumn(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PickerSheetDefaults.listBottomPadding) {
            if (pathLike) {
                item(key = "open") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.customize_workspace_open_folder, query.trim())) },
                        leadingContent = { Icon(ClineIcons.FolderOpen, contentDescription = null) },
                        trailingContent = if (picker.validating == query.trim()) ({ InlineSpinner() }) else null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .focusRing(RectangleShape, inset = true)
                            .selectable(selected = false, enabled = !busy, role = Role.Button, onClick = { onPick(query.trim()) }),
                    )
                }
            }
            item(key = "header") { SectionHeader(stringResource(R.string.customize_workspace_section)) }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (pathLike) R.string.customize_workspace_empty_path else R.string.customize_workspace_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.m),
                    )
                }
            }
            items(rows, key = { it.path }) { row ->
                val home = row.path == TERMUX_HOME
                ListItem(
                    headlineContent = {
                        if (home) Text(stringResource(R.string.customize_workspace_home)) else LtrText(row.display, style = ClineMono.small, readAsWritten = false)
                    },
                    supportingContent = if (home) ({ LtrText(formatWorkspacePath(row.path), style = ClineMono.small, readAsWritten = false) }) else null,
                    leadingContent = { Icon(ClineIcons.Folder, contentDescription = null) },
                    trailingContent = when {
                        picker.validating == row.path -> ({ InlineSpinner() })
                        row.isActive -> ({ Icon(ClineIcons.Check, contentDescription = null) })
                        else -> null
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .focusRing(RectangleShape, inset = true)
                        .selectable(
                            selected = row.isActive,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { onPick(row.path) },
                        )
                        .semantics { contentDescription = row.path },
                )
            }
        }
    }
}

@Composable
private fun ErrorSurface(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Icon(ClineIcons.ErrorFilled, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(Spacing.s))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@LightDarkPreviews
@Composable
private fun WorkspaceScopeChipPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            WorkspaceScopeChip("/data/data/com.termux/files/home/projects/cline-termux-app", onClick = {})
            WorkspaceScopeChip(null, onClick = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun WorkspaceScopeContentPreview() {
    ComponentPreview(padding = false) {
        WorkspaceScopeContent(
            currentRoot = "/data/data/com.termux/files/home/projects/app",
            candidates = listOf("/data/data/com.termux/files/home/projects/app", "/data/data/com.termux/files/home/notes"),
            picker = WorkspacePickerState(invalidPath = "~/does-not-exist"),
            onPick = {},
            onQueryChanged = {},
        )
    }
}
