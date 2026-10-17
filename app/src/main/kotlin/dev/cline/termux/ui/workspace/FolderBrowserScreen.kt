package dev.cline.termux.ui.workspace

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.TERMUX_SHARED_STORAGE
import dev.cline.termux.data.workspace.formatWorkspacePath
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.rememberKernelBlockHandler
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.CloseNavIcon
import dev.cline.termux.ui.navigation.EditorDialogWindow
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The `workspace/browse?start=` destination (SP S9.2): full screen with a close icon and "Use
 * this folder" in the app bar on compact/medium; the expanded dialog surface with footer buttons
 * "Cancel" / "Use this folder" (no top-bar action). Back walks the visited folders first
 * (instant pops while [FolderBrowserUiState.canGoBack]), then closes with the NavHost's seekable
 * pop (full screen) or this route's own close handler (dialog). A choice is handed to the hosting entry under [KEY_WORKSPACE_CHOSEN].
 */
@Composable
fun FolderBrowserRoute(start: String?, asDialog: Boolean, navigator: ShellNavigator, graph: AppGraph) {
    val viewModel: FolderBrowserViewModel = viewModel(factory = FolderBrowserViewModel.factory(graph, start))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val openTermuxFailed = stringResource(R.string.workspace_storage_open_termux_failed)
    val currentNavigator by rememberUpdatedState(navigator)
    val close = { navigator.back(); Unit }
    // The dialog window never dismisses itself (EditorDialogProperties): with no folder left to
    // walk back to, Back closes the browser here.
    BackHandler(enabled = asDialog && !state.canGoBack, onBack = close)
    BackHandler(enabled = state.canGoBack) { viewModel.back() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FolderBrowserEffect.Done -> {
                    currentNavigator.nav.previousBackStackEntry?.savedStateHandle?.set(KEY_WORKSPACE_CHOSEN, effect.path)
                    currentNavigator.back()
                }
                FolderBrowserEffect.BringTermuxForward -> {
                    val started = runCatching { context.startActivity(TermuxRunner.termuxActivityIntent()) }.isSuccess
                    if (!started) scope.launch { snackbar.showReplacing(openTermuxFailed) }
                }
            }
        }
    }
    val blockActions = rememberKernelBlockHandler(navigator, onRetry = viewModel::retry)
    if (asDialog) {
        FolderBrowserDialog(state, viewModel, blockActions, close)
    } else {
        FolderBrowserScreen(state, viewModel, blockActions, close)
    }
}

/** Compact/medium: `Scaffold` + `TopAppBar(navigationIcon = close, actions = TextButton("Use this folder"))`, no bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBrowserScreen(
    state: FolderBrowserUiState,
    viewModel: FolderBrowserViewModel,
    blockActions: KernelBlockHandler,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspace_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { CloseNavIcon(onClose) },
                actions = {
                    TextButton(onClick = viewModel::useThisFolder, enabled = state.canUse, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.workspace_browser_use))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        FolderBrowserBody(
            state = state,
            actions = viewModel.asActions(),
            blockActions = blockActions,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Expanded: the editor dialog surface (DS #16 amendment) with footer buttons only. */
@Composable
private fun FolderBrowserDialog(
    state: FolderBrowserUiState,
    viewModel: FolderBrowserViewModel,
    blockActions: KernelBlockHandler,
    onClose: () -> Unit,
) {
    // An outside tap is a Cancel; the browser has no unsaved state to guard.
    EditorDialogWindow(onTapOutside = onClose, surfaceModifier = Modifier.heightIn(max = 720.dp)) {
        Column(Modifier.padding(top = Spacing.xxl)) {
            Text(
                stringResource(R.string.workspace_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
            Spacer(Modifier.height(Spacing.l))
            FolderBrowserBody(
                state = state,
                actions = viewModel.asActions(),
                blockActions = blockActions,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.components_cancel)) }
                TextButton(onClick = viewModel::useThisFolder, enabled = state.canUse) { Text(stringResource(R.string.workspace_browser_use)) }
            }
        }
    }
}

/** The browser's callbacks, so the body stays stateless for previews. */
class FolderBrowserActions(
    val onOpen: (FolderEntry) -> Unit = {},
    val onHome: () -> Unit = {},
    val onUp: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSharedStorage: () -> Unit = {},
    val onSetUpStorage: () -> Unit = {},
)

private fun FolderBrowserViewModel.asActions() = FolderBrowserActions(
    onOpen = ::open,
    onHome = ::home,
    onUp = ::up,
    onRefresh = ::refresh,
    onSharedStorage = ::sharedStorage,
    onSetUpStorage = ::setUpSharedStorage,
)

@Composable
fun FolderBrowserBody(
    state: FolderBrowserUiState,
    actions: FolderBrowserActions,
    blockActions: KernelBlockHandler?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    Column(
        modifier.padding(
            start = contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction),
        ),
    ) {
        Text(
            stringResource(R.string.workspace_browser_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        RootChips(state, actions)
        if (state.showStorageSetup) StorageSetupRow(state, actions.onSetUpStorage)
        PathBar(state, actions)
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                state.blocked != null -> KernelBlockedCard(
                    block = state.blocked,
                    onAction = { blockActions?.onAction?.invoke(state.blocked, it) },
                    permissionPermanentlyDenied = blockActions?.permissionPermanentlyDenied == true,
                    modifier = Modifier.padding(Spacing.l),
                )
                state.loading -> CenteredStatus {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(Spacing.m))
                    Text(stringResource(R.string.workspace_browser_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.listFailed -> CenteredStatus {
                    Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        state.error ?: stringResource(R.string.workspace_browser_list_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                else -> FolderList(state, actions, contentPadding)
            }
        }
    }
}

@Composable
private fun RootChips(state: FolderBrowserUiState, actions: FolderBrowserActions) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "home") {
            FilterChip(
                selected = state.path == TERMUX_HOME || (state.path.startsWith("$TERMUX_HOME/") && !state.inSharedStorage),
                onClick = actions.onHome,
                enabled = !state.loading,
                label = { Text(stringResource(R.string.workspace_browser_home_chip)) },
                leadingIcon = { Icon(ClineIcons.Home, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (state.sharedStorage == SharedStorageState.Available) {
            item(key = "shared") {
                FilterChip(
                    selected = state.inSharedStorage,
                    onClick = actions.onSharedStorage,
                    enabled = !state.loading,
                    label = { Text(stringResource(R.string.workspace_browser_shared_chip)) },
                    leadingIcon = { Icon(ClineIcons.SdCard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun StorageSetupRow(state: FolderBrowserUiState, onSetUp: () -> Unit) {
    val settingUp = state.sharedStorage == SharedStorageState.SettingUp
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.workspace_storage_setup_title)) },
            supportingContent = { Text(stringResource(R.string.workspace_storage_setup_body)) },
            leadingContent = { Icon(ClineIcons.SdCard, contentDescription = null) },
            trailingContent = if (settingUp) {
                { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
            } else {
                { Icon(ClineIcons.OpenInNew, contentDescription = null) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .heightIn(min = 72.dp)
                .focusRing(RectangleShape, inset = true)
                .clickable(enabled = !settingUp, role = Role.Button, onClick = onSetUp),
        )
        state.storageError?.let { message ->
            Text(
                stringResource(R.string.workspace_storage_setup_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = Spacing.l, vertical = Spacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun PathBar(state: FolderBrowserUiState, actions: FolderBrowserActions) {
    // SP S9.3 display rule: the Termux home prefix reads as `~` (the diagram's `~/projects`).
    val shown = formatWorkspacePath(state.path)
    val currentCd = stringResource(R.string.workspace_browser_current_cd, shown)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(
            icon = ClineIcons.Home,
            contentDescription = stringResource(R.string.workspace_browser_home_cd),
            onClick = actions.onHome,
            enabled = !state.loading && !state.atHome,
        )
        TooltipIconButton(
            icon = ClineIcons.ArrowUpward,
            contentDescription = stringResource(R.string.workspace_browser_parent_cd),
            onClick = actions.onUp,
            enabled = !state.loading && state.parent != null,
        )
        Box(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = currentCd }) {
            LtrText(
                text = shown,
                style = ClineMono.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                readAsWritten = false,
            )
        }
        TooltipIconButton(
            icon = ClineIcons.Refresh,
            contentDescription = stringResource(R.string.workspace_browser_refresh_cd),
            onClick = actions.onRefresh,
            enabled = !state.loading,
        )
    }
}

@Composable
private fun FolderList(state: FolderBrowserUiState, actions: FolderBrowserActions, contentPadding: PaddingValues) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Spacing.l),
        modifier = Modifier.fillMaxWidth(),
    ) {
        state.useError?.let { path ->
            item(key = "use-error") { ValidationError(path, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) }
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.workspace_browser_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                )
            }
        }
        items(state.entries, key = { it.path }) { entry -> FolderRow(entry) { actions.onOpen(entry) } }
        if (state.truncated) {
            item(key = "truncated") {
                Text(
                    stringResource(R.string.workspace_browser_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(entry: FolderEntry, onClick: () -> Unit) {
    val cd = stringResource(R.string.workspace_browser_folder_cd, entry.name)
    ListItem(
        headlineContent = { LtrText(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, readAsWritten = false) },
        leadingContent = { Icon(ClineIcons.Folder, contentDescription = null) },
        trailingContent = { Icon(ClineIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    )
}

@Composable
private fun CenteredStatus(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.huge, horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// ------------------------------------------------------------------------------- previews

private val previewEntries = listOf("cline-termux-app", "cline-termux-voice", "site").map { FolderEntry(it, "$TERMUX_HOME/projects/$it") }

private val previewState = FolderBrowserUiState(
    path = "$TERMUX_HOME/projects",
    parent = TERMUX_HOME,
    atHome = false,
    history = listOf(TERMUX_HOME, "$TERMUX_HOME/projects"),
    entries = previewEntries,
    loading = false,
    sharedStorage = SharedStorageState.Available,
)

@Composable
private fun Preview(state: FolderBrowserUiState) {
    ComponentPreview(padding = false) {
        Surface(Modifier.heightIn(max = 640.dp)) {
            FolderBrowserBody(state, FolderBrowserActions(), null, PaddingValues(0.dp))
        }
    }
}

@LightDarkPreviews
@Composable
private fun FolderBrowserPopulatedPreview() = Preview(previewState)

@LightDarkPreviews
@Composable
private fun FolderBrowserLoadingPreview() = Preview(previewState.copy(loading = true, entries = emptyList()))

@LightDarkPreviews
@Composable
private fun FolderBrowserEmptyPreview() = Preview(previewState.copy(entries = emptyList()))

@LightDarkPreviews
@Composable
private fun FolderBrowserTruncatedPreview() = Preview(previewState.copy(truncated = true))

@LightDarkPreviews
@Composable
private fun FolderBrowserErrorPreview() = Preview(
    previewState.copy(
        path = "$TERMUX_SHARED_STORAGE/Android",
        entries = emptyList(),
        listFailed = true,
        error = "find: ‘$TERMUX_SHARED_STORAGE/Android’: Permission denied",
    ),
)

@LightDarkPreviews
@Composable
private fun FolderBrowserStorageMissingPreview() = Preview(previewState.copy(sharedStorage = SharedStorageState.NotSetUp))

@LightDarkPreviews
@Composable
private fun FolderBrowserBlockedPreview() = Preview(previewState.copy(blocked = KernelBlock.TimedOut))
