package dev.cline.termux.ui.customize

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.cline.termux.ui.components.FabLaneTooltip
import dev.cline.termux.ui.components.findActivity
import dev.cline.termux.ui.components.rememberFabLane
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.components.isScrollingUp
import dev.cline.termux.ui.components.openAppDetails
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.data.customize.CustomizeCounts
import dev.cline.termux.data.customize.CustomizeRepository
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.Scope
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsKind
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.ShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelStatePanel
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.customize.tabs.hooksTabItems
import dev.cline.termux.ui.customize.tabs.mcpTabItems
import dev.cline.termux.ui.customize.tabs.pluginsTabItems
import dev.cline.termux.ui.customize.tabs.rulesTabItems
import dev.cline.termux.ui.customize.tabs.skillsTabItems
import dev.cline.termux.ui.customize.tabs.toolsTabItems
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.Customize
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.Marketplace
import dev.cline.termux.ui.navigation.McpEdit
import dev.cline.termux.ui.navigation.ShellNavigationIcon
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the screen does outside itself (navigation, the shell's kernel actions). */
class CustomizeNavigation(
    val onMarketplace: (type: String?) -> Unit,
    val onEditMcp: (name: String?) -> Unit,
    val onInstallTermux: () -> Unit,
    val onKernelSetup: (CheckId) -> Unit,
    val navigationIcon: (@Composable () -> Unit)?,
) {
    companion object {
        fun of(route: Customize, navigator: ShellNavigator) = CustomizeNavigation(
            onMarketplace = { type -> navigator.push(Marketplace(type = type)) },
            onEditMcp = { name -> navigator.push(McpEdit(name)) },
            onInstallTermux = { navigator.openRoot(KernelInstallTermux) },
            onKernelSetup = { focus -> navigator.openRoot(KernelRepair.of(focus, pushed = true)) },
            navigationIcon = { ShellNavigationIcon(route, navigator) },
        )

        val None = CustomizeNavigation({}, {}, {}, {}, null)
    }
}

/**
 * Customize (CR C1): one screen for both entry kinds (top-level drawer/rail destination and
 * pushed from Settings, CR §0.2) — only the navigation icon differs, via [nav].
 */
@OptIn(FlowPreview::class)
@Composable
fun CustomizeScreen(viewModel: CustomizeViewModel, nav: CustomizeNavigation) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val context = LocalContext.current
    val kernelUi = LocalShellKernelUi.current
    var uninstallTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceSheetOpen by rememberSaveable { mutableStateOf(false) }
    // DS §8.1.7: the scope sheet returns focus to the workspace chip that opened it.
    val workspaceChipFocus = remember { FocusRequester() }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val res = context.resources
            when (event) {
                is CustomizeEvent.ToggleFailed -> snackbar.showReplacing(event.failure.text(res), duration = androidx.compose.material3.SnackbarDuration.Long)
                CustomizeEvent.WorkspaceMissing -> snackbar.showReplacing(res.getString(R.string.customize_workspace_missing))
                CustomizeEvent.UninstallMayHaveFinished -> snackbar.showReplacing(res.getString(R.string.customize_uninstall_check), duration = androidx.compose.material3.SnackbarDuration.Long)
                CustomizeEvent.WorkspacePicked -> workspaceSheetOpen = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            val activity = context.findActivity()
            permissionDenied = activity != null && !activity.shouldShowRequestPermissionRationale(TermuxRunner.PERMISSION)
        }
        viewModel.refresh()
    }

    val pathCopier = rememberCopier(legacyConfirmation = stringResource(R.string.customize_copied_path))
    val pathClipLabel = stringResource(R.string.components_clip_path)
    val actions = CustomizeActions(
        onToggleTool = viewModel::toggleTool,
        onSetAllTools = viewModel::setAllTools,
        onToggleItem = viewModel::toggleItem,
        onToggleMcp = viewModel::toggleMcp,
        onRequestUninstall = { key, _ -> uninstallTarget = key },
        onCopyPath = { path -> pathCopier.copy(pathClipLabel, path) },
        onToggleExpanded = viewModel::toggleExpanded,
        onQuery = viewModel::setQuery,
        onEditMcp = nav.onEditMcp,
        onDeleteMcp = viewModel::requestMcpDelete,
        onRetry = viewModel::refresh,
        onRetryHelper = viewModel::retryHelper,
        onStartKernel = { kernelUi?.onAction?.invoke(KernelAction.StartKernel) },
        onBlockedAction = { action ->
            when (action) {
                KernelBlockAction.InstallTermux -> nav.onInstallTermux()
                KernelBlockAction.RequestPermission -> permissionLauncher.launch(TermuxRunner.PERMISSION)
                KernelBlockAction.OpenAppSettings -> context.openAppDetails()
                KernelBlockAction.OpenKernelSetup -> nav.onKernelSetup(blockedFocus(state))
                KernelBlockAction.Retry -> viewModel.refresh()
                KernelBlockAction.RunDiagnostics -> kernelUi?.onAction?.invoke(KernelAction.KernelStatusDiagnostics)
            }
        },
        permissionPermanentlyDenied = permissionDenied,
    )

    CustomizeContent(
        state = state,
        actions = actions,
        nav = nav,
        onTabSelected = viewModel::selectTab,
        onTabSettled = viewModel::onSettledTab,
        onRefresh = viewModel::refresh,
        onWorkspaceChip = {
            viewModel.clearWorkspaceError()
            workspaceSheetOpen = true
        },
        onKernelAction = { kernelUi?.onAction?.invoke(it) },
        kernelActionInFlight = kernelUi?.actionInFlight != null,
        workspaceChipFocus = workspaceChipFocus,
    )

    // Uninstall confirm (CR §1.5, DS §3.4).
    uninstallTarget?.let { key ->
        val target = uninstallItem(state, key)
        if (target == null) {
            uninstallTarget = null
        } else {
            ConfirmDialog(
                title = stringResource(R.string.customize_uninstall_title, target.name),
                text = stringResource(R.string.customize_uninstall_text, target.name),
                confirmLabel = stringResource(R.string.customize_uninstall),
                destructive = true,
                onConfirm = {
                    uninstallTarget = null
                    viewModel.uninstall(key, target)
                },
                onDismiss = { uninstallTarget = null },
            )
        }
    }

    // MCP delete (CR §2.5 "Delete dialog").
    state.mcpDelete?.let { delete ->
        ConfirmDialog(
            title = stringResource(R.string.mcp_delete_title),
            text = if (delete.name.isNotBlank()) stringResource(R.string.mcp_delete_text, delete.name) else stringResource(R.string.mcp_delete_text_fallback),
            confirmLabel = stringResource(R.string.mcp_delete),
            destructive = true,
            confirming = delete.deleting,
            confirmingLabel = stringResource(R.string.mcp_deleting),
            error = delete.error?.text(),
            onConfirm = viewModel::confirmMcpDelete,
            onDismiss = viewModel::dismissMcpDelete,
        )
    }

    if (workspaceSheetOpen) {
        WorkspaceScopeSheet(
            currentRoot = state.workspaceRoot,
            candidates = state.workspaceCandidates,
            picker = state.workspacePicker,
            onPick = viewModel::pickWorkspace,
            onQueryChanged = viewModel::clearWorkspaceError,
            onDismiss = { workspaceSheetOpen = false },
            returnFocus = workspaceChipFocus,
        )
    }
}

/** The item an uninstall key refers to (skills, workflows, plugins). */
private fun uninstallItem(state: CustomizeUiState, key: String): SettingsItem? =
    state.commands.firstOrNull { it.key == key }?.item ?: state.plugins.firstOrNull { it.key == key }?.item

/** The repair checklist row a "Open kernel setup" from a blocked source focuses. */
private fun blockedFocus(state: CustomizeUiState): CheckId = when {
    state.gate is KernelGate.Blocked -> (state.gate as KernelGate.Blocked).focus
    state.toolsBlocked == TermuxProblem.ExternalAppsBlocked || state.hooksBlocked == TermuxProblem.ExternalAppsBlocked -> CheckId.ExternalApps
    (state.helper as? HelperState.Unavailable)?.reason == dev.cline.termux.core.kernel.dashboard.HelperFailure.ExternalAppsBlocked -> CheckId.ExternalApps
    else -> CheckId.Kernel
}

/**
 * The stateless page (previews use it directly): app bar with Refresh and Marketplace, the
 * description and workspace chip, the KernelBanner, the sticky tab row and the pager.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun CustomizeContent(
    state: CustomizeUiState,
    actions: CustomizeActions,
    nav: CustomizeNavigation,
    onTabSelected: (CustomizeTab) -> Unit,
    onTabSettled: (CustomizeTab) -> Unit,
    onRefresh: () -> Unit,
    onWorkspaceChip: () -> Unit,
    onKernelAction: (KernelAction) -> Unit,
    kernelActionInFlight: Boolean = false,
    workspaceChipFocus: FocusRequester? = null,
) {
    val layout = LocalShellLayout.current
    val tabs = CustomizeTab.entries
    val pagerState = rememberPagerState(initialPage = state.tab.ordinal) { tabs.size }
    val gridStates = tabs.map { rememberLazyGridState() }
    val scope = rememberCoroutineScope()
    val maxWidth: Dp? = when (layout.width) {
        WidthClass.Compact -> null
        WidthClass.Medium -> Spacing.pageMaxWidth
        WidthClass.Expanded -> 1040.dp
    }
    val gutter = layout.gutter

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { onTabSelected(tabs[it]) }
    }
    LaunchedEffect(state.tab) {
        // A tab chosen outside the pager (Marketplace "Installed" popping back here) moves it.
        if (pagerState.settledPage != state.tab.ordinal) pagerState.scrollToPage(state.tab.ordinal)
    }
    LaunchedEffect(pagerState) {
        // CR §1.6: only a page that stays settled for 500 ms acquires the helper.
        snapshotFlow { pagerState.settledPage }.debounce(CustomizeViewModel.HELPER_SETTLE_MS).collect { onTabSettled(tabs[it]) }
    }

    val onMcp = pagerState.currentPage == CustomizeTab.Mcp.ordinal
    val mcpGrid = gridStates[CustomizeTab.Mcp.ordinal]
    // DS §5.4 / FabLane: icon-only beside an end lane on a height-compact window.
    val fabLane = rememberFabLane(mcpGrid.isScrollingUp())
    val mcpFabShown = state.hasData && state.gate !is KernelGate.Blocked
    val onMcpWithFab = onMcp && mcpFabShown

    PageScaffold(
        title = stringResource(R.string.customize_title),
        navigationIcon = nav.navigationIcon,
        actions = {
            if (state.refreshing || (state.loading && state.hasData)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            } else {
                TooltipIconButton(icon = ClineIcons.Refresh, contentDescription = stringResource(R.string.customize_refresh), onClick = onRefresh)
            }
            TooltipIconButton(
                icon = ClineIcons.Storefront,
                contentDescription = stringResource(R.string.customize_marketplace),
                onClick = { nav.onMarketplace(tabs[pagerState.currentPage].marketplaceType) },
            )
        },
        floatingActionButton = {
            if (onMcpWithFab) {
                val fabLabel = stringResource(R.string.customize_mcp_add)
                FabLaneTooltip(fabLane, fabLabel) {
                    ExtendedFloatingActionButton(
                        text = { Text(fabLabel) },
                        // M3 clears the text slot's semantics, so the icon names the FAB whether
                        // expanded or collapsed on scroll (DS §8).
                        icon = { Icon(ClineIcons.Add, contentDescription = fabLabel) },
                        expanded = fabLane.expanded,
                        onClick = { actions.onEditMcp(null) },
                    )
                }
            }
        },
    ) { padding ->
        // Height-compact (phone landscape): the description block scrolls away above the sticky
        // tab row, or the cards would get a third of a 384dp window (CR §0.6, §1.3 "sticky").
        val header = rememberCollapsingHeader(enabled = layout.heightCompact)
        // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
        val direction = LocalLayoutDirection.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(), end = padding.calculateEndPadding(direction))
                .nestedScroll(header.connection),
        ) {
            CenteredColumn(maxWidth, gutter, modifier = header.modifier) {
                Text(
                    stringResource(R.string.customize_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    WorkspaceScopeChip(
                        state.workspaceRoot,
                        onClick = onWorkspaceChip,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(if (workspaceChipFocus != null) Modifier.focusRequester(workspaceChipFocus) else Modifier),
                    )
                    if (layout.width != WidthClass.Compact) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { nav.onMarketplace(tabs[pagerState.currentPage].marketplaceType) }) {
                            Icon(ClineIcons.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.customize_marketplace))
                        }
                    }
                }
                ShellKernelBanner(hasComposer = false, modifier = Modifier.padding(top = Spacing.s))
            }
            CustomizeTabRow(state.counts, pagerState, maxWidth, gutter) { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            }
            val busyStart = state.gate is KernelGate.Busy &&
                ((state.gate as KernelGate.Busy).kind == KernelGate.BusyKind.Starting || (state.gate as KernelGate.Busy).kind == KernelGate.BusyKind.Connecting)
            if (state.refreshing || (busyStart && !state.hasData)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(Spacing.xs))
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                modifier = Modifier.fillMaxSize(),
                key = { tabs[it].wire },
            ) { page ->
                val tab = tabs[page]
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    CustomizePage(
                        tab = tab,
                        state = state,
                        actions = actions,
                        gridState = gridStates[page],
                        maxWidth = maxWidth,
                        gutter = gutter,
                        expanded = layout.width == WidthClass.Expanded,
                        endPadding = if (tab == CustomizeTab.Mcp && mcpFabShown) fabLane.endPadding(gutter) else gutter,
                        bottomPadding = padding.calculateBottomPadding() + if (tab == CustomizeTab.Mcp) fabLane.bottomClearance else Spacing.l,
                        onKernelAction = onKernelAction,
                        kernelActionInFlight = kernelActionInFlight,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(maxWidth: Dp?, gutter: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = gutter),
        ) { content() }
    }
}

/** The sticky `PrimaryScrollableTabRow` with counts after the labels (CR §1.5, §1.10). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeTabRow(counts: CustomizeCounts?, pagerState: PagerState, maxWidth: Dp?, gutter: Dp, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier,
            edgePadding = gutter,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            CustomizeTab.entries.forEachIndexed { index, tab ->
                val label = tab.label()
                val count = counts?.of(tab)
                val cd = if (count != null) copyPluralStringResource(R.plurals.customize_tab_cd, count, label, count) else label
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.semantics { contentDescription = cd },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, maxLines = 1)
                            if (count != null) {
                                Spacer(Modifier.width(Spacing.xs))
                                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                )
            }
        }
    }
}

fun CustomizeCounts.of(tab: CustomizeTab): Int = when (tab) {
    CustomizeTab.Tools -> tools
    CustomizeTab.Plugins -> plugins
    CustomizeTab.Skills -> skills
    CustomizeTab.Rules -> rules
    CustomizeTab.Mcp -> mcp
    CustomizeTab.Hooks -> hooks
}

/** One tab's grid (CR §0.6: one column, two on expanded when the clamped content is ≥ 720dp). */
@Composable
private fun CustomizePage(
    tab: CustomizeTab,
    state: CustomizeUiState,
    actions: CustomizeActions,
    gridState: LazyGridState,
    maxWidth: Dp?,
    gutter: Dp,
    expanded: Boolean,
    endPadding: Dp,
    bottomPadding: Dp,
    onKernelAction: (KernelAction) -> Unit,
    kernelActionInFlight: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val contentWidth = (maxWidth?.let { minOf(it, this.maxWidth) } ?: this.maxWidth) - gutter * 2
        val columns = if (expanded && contentWidth >= 720.dp) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = endPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            fullSpan("description") { TabDescription(tab.description(state)) }
            if (tab == CustomizeTab.Plugins && state.sessionRunning) {
                fullSpan("running-note") {
                    Text(
                        stringResource(R.string.customize_plugins_running_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val gate = state.gate
            when {
                gate is KernelGate.Blocked -> fullSpan("blocked") { CustomizeBlockedCard(KernelBlock.of(gate), actions) }
                gate is KernelGate.Down && !state.hubLoaded -> fullSpan("kernel-panel") {
                    KernelStatePanel(gate = gate, hasComposer = false, onAction = onKernelAction, primaryInFlight = kernelActionInFlight)
                }
                !state.hasData -> repeat(3) { index -> fullSpan("skeleton-$index") { SkeletonCard() } }
                else -> {
                    state.loadError?.let { failure -> fullSpan("load-error") { LoadErrorCard(failure, actions.onRetry) } }
                    if (state.warnings.isNotEmpty()) {
                        fullSpan("warnings") { PartialResultsCard(state.warnings.map { it.text() }) }
                    }
                    when (tab) {
                        CustomizeTab.Tools -> toolsTabItems(state, actions)
                        CustomizeTab.Plugins -> pluginsTabItems(state, actions)
                        CustomizeTab.Skills -> skillsTabItems(state, actions)
                        CustomizeTab.Rules -> rulesTabItems(state, actions)
                        CustomizeTab.Mcp -> mcpTabItems(state, actions)
                        CustomizeTab.Hooks -> hooksTabItems(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeTab.description(state: CustomizeUiState): String = stringResource(
    when (this) {
        CustomizeTab.Tools -> R.string.customize_description_tools
        CustomizeTab.Plugins -> R.string.customize_description_plugins
        CustomizeTab.Skills -> R.string.customize_description_skills
        CustomizeTab.Rules -> R.string.customize_description_rules
        CustomizeTab.Mcp -> if (state.mcp.loaded && !state.mcp.hasSettingsFile) R.string.customize_description_mcp_no_file else R.string.customize_description_mcp
        CustomizeTab.Hooks -> R.string.customize_description_hooks
    },
)

// ---- previews --------------------------------------------------------------------------------------

private object PreviewData {
    const val ROOT = "/data/data/com.termux/files/home/projects/cline-termux-app"

    private fun item(id: String, name: String, kind: SettingsKind, path: String, enabled: Boolean = true, description: String? = null, source: String = "global") =
        SettingsItem(id = id, name = name, path = path, kind = kind, source = source, enabled = enabled, description = description, toggleable = true)

    val populated = CustomizeUiState(
        workspaceRoot = ROOT,
        loading = false,
        hasData = true,
        hubLoaded = true,
        counts = CustomizeCounts(tools = 3, plugins = 1, skills = 2, rules = 1, mcp = 1, hooks = 1),
        tools = ToolsState(
            builtin = listOf(
                ToolCard("tool:read_files", "read_files", "Read the contents of one or more files", true, null, null, listOf("read_file"), listOf("read_files", "read_file"), "read_files"),
                ToolCard("tool:editor", "editor", "Edit files with exact replacements or create new files.", true, null, null, listOf("editor", "apply_patch"), listOf("editor", "apply_patch"), "editor"),
            ),
            plugin = listOf(ToolCard("ptool:lint", "lint_fix", null, false, "my-plugin", "$ROOT/.cline/plugins/lint", emptyList(), listOf("lint_fix"), null)),
        ),
        commands = listOf(
            CommandCard(
                "skill:/s", item("s", "review", SettingsKind.Skill, "/h/.cline/skills/review/SKILL.md", description = "Review a pull request."), CommandType.Skill, "review",
                "Review a pull request.", "/h/.cline/skills/review/SKILL.md", Scope.Global, true, true, false, true, emptyList(),
            ),
            CommandCard(
                "workflow:/w", item("w", "release", SettingsKind.Workflow, "$ROOT/.clinerules/workflows/release.md"), CommandType.Workflow, "release",
                "", "$ROOT/.clinerules/workflows/release.md", Scope.Project, true, false, false, false, emptyList(),
            ),
        ),
        rules = listOf(RuleCard("rule:/r", "style.md", "Use 4 spaces.", "$ROOT/.clinerules/style.md", Scope.Project)),
        hooks = listOf(HookCard("hook:/p", "PreToolUse.sh", "PreToolUse", "$ROOT/.clinerules/hooks/PreToolUse.sh", Scope.Project)),
        mcp = McpTabState(
            settingsPath = "/data/data/com.termux/files/home/.cline/data/settings/cline_mcp_settings.json",
            hasSettingsFile = true,
            servers = listOf(
                McpServerCard(
                    "mcp:github", "github", dev.cline.termux.data.customize.McpTransportType.Stdio, "stdio", true, "npx",
                    listOf("-y", "@modelcontextprotocol/server-github"), null, null, listOf("GITHUB_TOKEN"), emptyList(), emptyList(),
                ),
            ),
            loaded = true,
        ),
        plugins = listOf(
            PluginCard(
                key = "plugin:/lint",
                item = item("lint", "my-plugin", SettingsKind.Plugin, "$ROOT/.cline/plugins/lint", source = "workspace"),
                name = "my-plugin",
                description = "Lint and fix files after every edit.",
                loadError = null,
                scope = Scope.Project,
                enabled = true,
                toggleable = true,
                agentPlugin = false,
                inspectionDisabled = false,
                groups = listOf(ContributionGroup(ContributionKind.Tools, listOf("lint_fix")), ContributionGroup(ContributionKind.Hooks, listOf("PostToolUse"))),
                canUninstall = true,
                marketplaceMatches = emptyList(),
            ),
        ),
    )
}

@Composable
private fun PreviewHost(state: CustomizeUiState) {
    ClinePreviewTheme {
        // The shell provides the gate for the KernelBanner; previews stand in for it.
        CompositionLocalProvider(LocalShellKernelUi provides ShellKernelUi(state.gate, null, null) {}) {
            CustomizeContent(
                state = state,
                actions = CustomizeActions.None,
                nav = CustomizeNavigation.None,
                onTabSelected = {},
                onTabSettled = {},
                onRefresh = {},
                onWorkspaceChip = {},
                onKernelAction = {},
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun CustomizeFirstLoadPreview() = PreviewHost(CustomizeUiState(workspaceRoot = PreviewData.ROOT))

@LightDarkPreviews
@Composable
private fun CustomizeToolsPreview() = PreviewHost(PreviewData.populated)

@LightDarkPreviews
@Composable
private fun CustomizeSkillsPreview() = PreviewHost(PreviewData.populated.copy(tab = CustomizeTab.Skills))

@LightDarkPreviews
@Composable
private fun CustomizeMcpHelperStartingPreview() = PreviewHost(PreviewData.populated.copy(tab = CustomizeTab.Mcp, helper = HelperState.Starting(0L)))

@LightDarkPreviews
@Composable
private fun CustomizeRulesPreview() = PreviewHost(PreviewData.populated.copy(tab = CustomizeTab.Rules))

@LightDarkPreviews
@Composable
private fun CustomizeHooksBlockedPreview() = PreviewHost(PreviewData.populated.copy(tab = CustomizeTab.Hooks, hooksBlocked = TermuxProblem.PermissionMissing))

@LightDarkPreviews
@Composable
private fun CustomizeErrorAndPartialPreview() = PreviewHost(
    PreviewData.populated.copy(
        loadError = RepoFailure.Hub("settings_list_failed", "plugin setup exploded"),
        warnings = listOf(CustomizeRepository.SourceWarning(CustomizeRepository.Source.Hooks, RepoFailure.Termux(TermuxProblem.TimedOut))),
        refreshing = true,
    ),
)

@LightDarkPreviews
@Composable
private fun CustomizeEmptyPreview() = PreviewHost(
    CustomizeUiState(workspaceRoot = PreviewData.ROOT, loading = false, hasData = true, tab = CustomizeTab.Plugins, counts = CustomizeCounts(0, 0, 0, 0, 0, 0)),
)

@LightDarkPreviews
@Composable
private fun CustomizeKernelDownPreview() = PreviewHost(
    CustomizeUiState(workspaceRoot = PreviewData.ROOT, gate = KernelGate.Down(KernelGate.DownVariant.Stopped, null)),
)

@LightDarkPreviews
@Composable
private fun CustomizeBlockedPreview() = PreviewHost(
    CustomizeUiState(workspaceRoot = PreviewData.ROOT, gate = KernelGate.Blocked(CheckId.Permission)),
)

@LightDarkPreviews
@Composable
private fun CustomizePluginsRunningSessionPreview() = PreviewHost(
    PreviewData.populated.copy(tab = CustomizeTab.Plugins, sessionRunning = true, expanded = setOf("plugin:/lint")),
)

@LightDarkPreviews
@Composable
private fun CustomizeMcpHelperUnavailablePreview() = PreviewHost(
    PreviewData.populated.copy(tab = CustomizeTab.Mcp, helper = HelperState.Unavailable(HelperFailure.StartTimeout, null)),
)

@LightDarkPreviews
@Composable
private fun CustomizeSearchEmptyPreview() = PreviewHost(PreviewData.populated.copy(queries = mapOf(CustomizeTab.Tools to "zzz")))

@LightDarkPreviews
@Composable
private fun CustomizeReconnectingPreview() = PreviewHost(
    PreviewData.populated.copy(gate = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false)),
)

@LightDarkPreviews
@Composable
private fun CustomizeKernelDownWithContentPreview() = PreviewHost(
    PreviewData.populated.copy(gate = KernelGate.Down(KernelGate.DownVariant.Disconnected, null)),
)

/**
 * A header that collapses as the content below it scrolls up and comes back on any downward
 * scroll ("enter always"), so it never waits behind pull to refresh. Disabled, it is inert.
 */
private class CollapsingHeader(private val enabled: Boolean) {
    var heightPx by mutableFloatStateOf(0f)
    var offsetPx by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || heightPx == 0f || available.y == 0f) return Offset.Zero
            val next = (offsetPx + available.y).coerceIn(-heightPx, 0f)
            val consumed = next - offsetPx
            offsetPx = next
            return Offset(0f, consumed)
        }
    }

    val modifier: Modifier = Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            heightPx = placeable.height.toFloat()
            if (!enabled) offsetPx = 0f
            val visible = (placeable.height + offsetPx.roundToInt()).coerceAtLeast(0)
            layout(placeable.width, visible) { placeable.place(0, offsetPx.roundToInt()) }
        }
}

@Composable
private fun rememberCollapsingHeader(enabled: Boolean): CollapsingHeader = remember(enabled) { CollapsingHeader(enabled) }

