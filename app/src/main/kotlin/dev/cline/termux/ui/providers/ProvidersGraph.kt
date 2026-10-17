package dev.cline.termux.ui.providers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.rememberKernelBlockHandler
import dev.cline.termux.ui.components.PanePageAppBar
import dev.cline.termux.ui.components.panePageScrollBehavior
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.SettingsProvider
import dev.cline.termux.ui.navigation.SettingsProviders
import dev.cline.termux.ui.navigation.SettingsProvidersAdd
import dev.cline.termux.ui.navigation.ShellNavigationIcon
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.rememberPaneDirective
import kotlinx.serialization.Serializable

/*
 * The `settings/providers` graph (settings-providers.md S4–S6, docs/07 §6.2): S4, S5 and the
 * S6 editor are destinations of one nested graph whose back-stack entry owns the single
 * [ProvidersViewModel], so a save started on S5 finishes after the pop back to S4. On
 * expanded windows S4 and S5 are one `NavigableListDetailPaneScaffold` (list 360dp).
 */

/** The nested graph's route; its back-stack entry scopes [ProvidersViewModel]. */
@Serializable
data object ProvidersGraph

/**
 * Registers S4, S5 and S6 under [ProvidersGraph] (called from `shellDestinations`).
 * [editorsAsDialogs] = `ShellLayout.editorsAsDialogs` (S6 as a dialog on expanded).
 */
fun NavGraphBuilder.providersGraph(navigator: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean) {
    navigation<ProvidersGraph>(startDestination = SettingsProviders) {
        composable<SettingsProviders> { entry ->
            val vm = providersViewModel(navigator, entry, graph)
            ProvidersEntry(vm, navigator, graph, route = SettingsProviders, initialSelection = null)
        }
        composable<SettingsProvider> { entry ->
            val vm = providersViewModel(navigator, entry, graph)
            val route = entry.toRoute<SettingsProvider>()
            ProvidersEntry(vm, navigator, graph, route = route, initialSelection = route.providerId)
        }
        if (editorsAsDialogs) {
            dialog<SettingsProvidersAdd>(
                dialogProperties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    // Back and outside taps go through the discard guard (S6.2).
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) { entry ->
                val vm = providersViewModel(navigator, entry, graph)
                ProvidersEventsEffect(vm, navigator, twoPane = true)
                AddProviderRoute(vm, asDialog = true, onClose = { navigator.back() }, copySecret = graph.secureClipboard::copySecret)
            }
        } else {
            composable<SettingsProvidersAdd> { entry ->
                val vm = providersViewModel(navigator, entry, graph)
                ProvidersEventsEffect(vm, navigator, twoPane = false)
                AddProviderRoute(vm, asDialog = false, onClose = { navigator.back() }, copySecret = graph.secureClipboard::copySecret)
            }
        }
    }
}

/** The graph-scoped VM for a destination [entry] of [ProvidersGraph]. */
@Composable
private fun providersViewModel(navigator: ShellNavigator, entry: NavBackStackEntry, graph: AppGraph): ProvidersViewModel {
    val owner = remember(entry) { navigator.nav.getBackStackEntry<ProvidersGraph>() }
    return viewModel(viewModelStoreOwner = owner, factory = ProvidersViewModel.factory(graph))
}

/** The VM factory over [AppGraph] (the lease, sign-in, favourites and saves on the app scope). */
fun ProvidersViewModel.Companion.factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ProvidersViewModel(
            savedState = createSavedStateHandle(),
            catalog = graph.providerCatalog,
            helper = ProvidersHelper.of(graph.settingsHelper, graph.appScope),
            signIn = ProvidersSignIn.of(graph.signIn),
            favoritesStore = FavoriteModelsStore.of(graph.prefs),
            termux = graph.termux,
            kernelStarter = KernelStarter { graph.kernel.ensureConnected(startIfDown = true, userInitiated = true) },
            workScope = graph.appScope,
        )
    }
}

/** S4 or S5: single pane below expanded, the list-detail scaffold at expanded. */
@Composable
private fun ProvidersEntry(
    vm: ProvidersViewModel,
    navigator: ShellNavigator,
    graph: AppGraph,
    route: dev.cline.termux.ui.navigation.ShellRoute,
    initialSelection: String?,
) {
    val layout = LocalShellLayout.current
    val twoPane = layout.listDetailPanes
    ProvidersEventsEffect(vm, navigator, twoPane)
    val blockHandler = rememberKernelBlockHandler(navigator, onRetry = vm::retryLoad)
    if (twoPane) {
        ProvidersListDetail(vm, navigator, graph, initialSelection, blockHandler)
        return
    }
    if (initialSelection == null) {
        val state by vm.listState.collectAsStateWithLifecycle()
        ProvidersListScreen(
            state = state,
            actions = rememberListActions(vm, navigator, state.fallback, blockHandler, twoPane = false),
            navigationIcon = { ShellNavigationIcon(route, navigator) },
            gutter = layout.gutter,
        )
    } else {
        ProviderDetailRoute(
            vm = vm,
            providerId = initialSelection,
            pane = false,
            navigationIcon = { BackNavIcon { navigator.back() } },
            copySecret = graph.secureClipboard::copySecret,
            blockHandler = blockHandler,
            gutter = layout.gutter,
        )
    }
}

@Composable
private fun rememberListActions(
    vm: ProvidersViewModel,
    navigator: ShellNavigator,
    fallback: Boolean,
    blockHandler: KernelBlockHandler,
    twoPane: Boolean,
): ProvidersListActions = remember(vm, navigator, fallback, blockHandler, twoPane) {
    ProvidersListActions(
        onQuery = vm::setQuery,
        onToggleSection = vm::toggleSection,
        onOpen = { id -> if (twoPane) vm.select(id) else navigator.push(SettingsProvider(id)) },
        onAdd = {
            // §0.4: without the helper, "Add provider" configures openai-compatible through `cline auth`.
            if (fallback) {
                if (twoPane) vm.select(ProvidersViewModel.OPENAI_COMPATIBLE_ID) else navigator.push(SettingsProvider(ProvidersViewModel.OPENAI_COMPATIBLE_ID))
            } else {
                navigator.push(SettingsProvidersAdd)
            }
        },
        onRefresh = vm::refresh,
        onRetryLoad = vm::retryLoad,
        onRetryHelper = vm::retryHelper,
        onStartKernel = vm::startKernel,
        blockHandler = blockHandler,
    )
}

/** Expanded: the list pane (360dp) and the selected provider's detail pane side by side. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersListDetail(
    vm: ProvidersViewModel,
    navigator: ShellNavigator,
    graph: AppGraph,
    initialSelection: String?,
    blockHandler: KernelBlockHandler,
) {
    val state by vm.listState.collectAsStateWithLifecycle()
    LaunchedEffect(initialSelection) { if (initialSelection != null) vm.select(initialSelection) }
    val selected = state.selectedId
    val paneNavigator = rememberListDetailPaneScaffoldNavigator<Nothing>(scaffoldDirective = rememberPaneDirective(360.dp))
    LaunchedEffect(paneNavigator, selected) {
        if (selected != null && paneNavigator.currentDestination?.pane != ListDetailPaneScaffoldRole.Detail) {
            paneNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }
    val actions = rememberListActions(vm, navigator, state.fallback, blockHandler, twoPane = true)
    val gutter = LocalShellLayout.current.gutter
    val listLabel = stringResource(R.string.providers_list_label)
    NavigableListDetailPaneScaffold(
        navigator = paneNavigator,
        defaultBackBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
        listPane = {
            AnimatedPane(Modifier.semantics { paneTitle = listLabel }) {
                val scrollBehavior = panePageScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        PanePageAppBar(
                            title = stringResource(R.string.providers_title),
                            scrollBehavior = scrollBehavior,
                            navigationIcon = { ShellNavigationIcon(SettingsProviders, navigator) },
                            actions = { if (state.showFab) AddProviderTonalButton(onClick = actions.onAdd, enabled = true) },
                            loading = state.loading || state.helperStarting,
                        )
                    },
                    snackbarHost = { androidx.compose.material3.SnackbarHost(dev.cline.termux.ui.LocalSnackbarHost.current) },
                ) { padding ->
                    ProvidersListBody(
                        state = state,
                        actions = actions,
                        contentPadding = padding,
                        gutter = gutter,
                        bottomExtra = 0.dp,
                        selectedId = selected,
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                if (selected != null) {
                    key(selected) {
                        ProviderDetailRoute(
                            vm = vm,
                            providerId = selected,
                            pane = true,
                            navigationIcon = null,
                            copySecret = graph.secureClipboard::copySecret,
                            blockHandler = blockHandler,
                            gutter = gutter,
                        )
                    }
                } else {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                }
            }
        },
    )
}
