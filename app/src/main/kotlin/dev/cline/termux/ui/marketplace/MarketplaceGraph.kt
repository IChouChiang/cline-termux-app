package dev.cline.termux.ui.marketplace

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.components.rememberOpenExternal
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.Customize
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.Marketplace
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.navigation.rememberPaneDirective
import dev.cline.termux.ui.navigation.MarketplaceEntryRoute
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The marketplace destinations (customize-and-rest.md C3 / C4, docs/07 §6.2):
 *
 * - `marketplace?type=&tag=`: the list. On expanded windows (≥ 840dp and not height-compact,
 *   CR §0.6) it is a `NavigableListDetailPaneScaffold` of its own (list 400dp + detail, both
 *   always shown, never nested in another scaffold); otherwise a single page whose rows push
 *   the detail.
 * - `marketplace/entry/{type}/{id}`: the pushed detail on compact and medium, sharing the list
 *   entry's [MarketplaceViewModel]. Reached on an expanded window (a rotation with the detail
 *   open), it hands its key to the list's pane selection and pops.
 *
 * Registered from `shellDestinations`.
 */
fun NavGraphBuilder.marketplaceGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<Marketplace> { entry ->
        val vm: MarketplaceViewModel = viewModel(viewModelStoreOwner = entry, factory = MarketplaceViewModel.factory(graph))
        MarketplaceListRoute(vm, nav)
    }
    composable<MarketplaceEntryRoute> { entry ->
        val route = entry.toRoute<MarketplaceEntryRoute>()
        val listEntry = remember(entry) { listEntryBelow(nav) }
        val vm: MarketplaceViewModel = viewModel(viewModelStoreOwner = listEntry ?: entry, factory = MarketplaceViewModel.factory(graph))
        MarketplaceEntryRouteContent(vm, nav, key = "${route.type}:${route.id}", hasList = listEntry != null)
    }
}

/** True on windows that show the list-detail scaffold (CR §0.6: expanded, not height-compact). */
val ShellLayout.marketplacePanes: Boolean get() = listDetailPanes

/** The marketplace list pane width (CR §3.3). */
val MarketplaceListPaneWidth: Dp = 400.dp

/** The detail route for an entry key ("<type>:<id>"). */
fun marketplaceEntryRoute(key: String): MarketplaceEntryRoute =
    MarketplaceEntryRoute(type = key.substringBefore(':'), id = key.substringAfter(':'))

private fun listEntryBelow(nav: ShellNavigator): NavBackStackEntry? =
    runCatching { nav.nav.getBackStackEntry<Marketplace>() }.getOrNull()

/**
 * "Installed" (CR §3.7): Customize on the tab matching the type filter, popping to an
 * existing `customize` entry (its `tab` argument updated in its `SavedStateHandle`) instead of
 * pushing a second one.
 */
fun ShellNavigator.openInstalled(tab: String) {
    val existing = runCatching { nav.getBackStackEntry<Customize>() }.getOrNull()
    if (existing != null) {
        existing.savedStateHandle[CUSTOMIZE_TAB_KEY] = tab
        nav.popBackStack<Customize>(inclusive = false)
    } else {
        nav.navigate(Customize(tab = tab, pushed = true))
    }
}

/** The `customize?tab=` argument, also the key CustomizeViewModel reads from its `SavedStateHandle`. */
const val CUSTOMIZE_TAB_KEY = "tab"

@Composable
private fun MarketplaceListRoute(vm: MarketplaceViewModel, nav: ShellNavigator) {
    val derived by vm.state.collectAsStateWithLifecycle()
    // The field shows the synchronous text; the derived lists follow a frame later.
    val state = derived.copy(query = vm.queryText)
    val layout = LocalShellLayout.current
    val listState = rememberLazyListState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val openUrl = rememberOpenExternal()
    val scope = rememberCoroutineScope()

    val detailActions = MarketplaceDetailActions(
        onInstall = vm::install,
        onUninstall = vm::uninstall,
        onOpenUrl = openUrl,
        onSelectTag = { tag ->
            // Expanded keeps the pane and scrolls the list to the top (CR §3.6).
            vm.setTag(tag)
            scope.launch { listState.animateScrollToItem(0) }
        },
        onClose = { vm.select(null) },
        onRetryHelper = vm::retryHelper,
        onStartKernel = vm::startKernel,
    )

    if (layout.marketplacePanes) {
        val listActions = listActions(vm, nav, state.type) { key -> vm.select(key) }
        MarketplaceListDetail(state, listActions, detailActions, listState, onSelectionCleared = { vm.select(null) })
    } else {
        // A selection made on an expanded window (rotation) continues as the pushed detail.
        LaunchedEffect(state.selectedKey) {
            val key = state.selectedKey ?: return@LaunchedEffect
            vm.select(null)
            nav.push(marketplaceEntryRoute(key))
        }
        val listActions = listActions(vm, nav, state.type) { key -> nav.push(marketplaceEntryRoute(key)) }
        MarketplaceScreen(
            state = state,
            actions = listActions,
            gutter = layout.gutter,
            // The medium window's centred column (CR §0.6).
            maxContentWidth = if (layout.width == WidthClass.Medium) Spacing.pageMaxWidth else Dp.Unspecified,
            listState = listState,
        )
    }
}

private fun listActions(
    vm: MarketplaceViewModel,
    nav: ShellNavigator,
    type: dev.cline.termux.data.marketplace.MarketplaceType?,
    onOpenEntry: (String) -> Unit,
) = MarketplaceListActions(
    onBack = { nav.back() },
    onOpenInstalled = { nav.openInstalled(customizeTabFor(type)) },
    onOpenEntry = onOpenEntry,
    onQueryChange = vm::setQuery,
    onAllTypes = vm::showAllTypes,
    onToggleType = vm::toggleType,
    onSelectTag = vm::setTag,
    onRefresh = vm::refresh,
    onRetry = { vm.load(force = true) },
    onRetryHelper = vm::retryHelper,
    onStartKernel = vm::startKernel,
)

/**
 * Expanded: the canonical list-detail layout with both panes always shown (CR §3.3). The
 * ViewModel's selection is the source of truth; the scaffold's destination follows it (List
 * with no selection, Detail with one), and the scaffold's own predictive back (Detail → List)
 * clears it, so back with a selection returns to the placeholder and back without one pops
 * `marketplace` (CR §3.7).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MarketplaceListDetail(
    state: MarketplaceUiState,
    listActions: MarketplaceListActions,
    detailActions: MarketplaceDetailActions,
    listState: LazyListState,
    onSelectionCleared: () -> Unit,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(
        scaffoldDirective = rememberPaneDirective(MarketplaceListPaneWidth),
    )
    val selected = state.selectedKey != null
    val cleared by rememberUpdatedState(onSelectionCleared)
    val currentSelected by rememberUpdatedState(selected)

    LaunchedEffect(navigator, selected) {
        val showingDetail = navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
        if (selected && !showingDetail) navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        if (!selected && showingDetail) navigator.navigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)
    }
    LaunchedEffect(navigator) {
        snapshotFlow { navigator.currentDestination?.pane }
            .distinctUntilChanged()
            .drop(1)
            .collect { pane -> if (pane == ListDetailPaneScaffoldRole.List && currentSelected) cleared() }
    }
    ReportBackLevel(BackLevel.ListDetailPane, selected)

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                MarketplaceScreen(state = state, actions = listActions, paneMode = true, listState = listState)
            }
        },
        detailPane = {
            AnimatedPane { MarketplaceDetailPane(state, detailActions) }
        },
        defaultBackBehavior = BackNavigationBehavior.PopUntilCurrentDestinationChange,
    )
}

/**
 * The pushed detail. On an expanded window with the list below, the key becomes the list's
 * pane selection and this entry pops; otherwise the page is shown, and a tag chip filters the
 * list (popping back to it, or opening a filtered list when the detail was entered directly).
 */
@Composable
private fun MarketplaceEntryRouteContent(vm: MarketplaceViewModel, nav: ShellNavigator, key: String, hasList: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val layout = LocalShellLayout.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val openUrl = rememberOpenExternal()

    if (layout.marketplacePanes && hasList) {
        LaunchedEffect(key) {
            vm.select(key)
            nav.back()
        }
    }
    MarketplaceDetailPage(
        state = state,
        key = key,
        gutter = layout.gutter,
        actions = MarketplaceDetailActions(
            onInstall = vm::install,
            onUninstall = vm::uninstall,
            onOpenUrl = openUrl,
            onSelectTag = { tag ->
                vm.setTag(tag)
                if (hasList) {
                    nav.back()
                } else {
                    nav.nav.navigate(Marketplace(tag = tag)) {
                        popUpTo<MarketplaceEntryRoute> { inclusive = true }
                    }
                }
            },
            onClose = { nav.back() },
            onRetryHelper = vm::retryHelper,
            onStartKernel = vm::startKernel,
        ),
    )
}
