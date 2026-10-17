package dev.cline.termux.ui.customize

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.data.marketplace.MarketplaceRepository
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.ui.customize.mcp.McpServerEditorScreen
import dev.cline.termux.ui.customize.mcp.McpServerEditorViewModel
import dev.cline.termux.ui.marketplace.CUSTOMIZE_TAB_KEY
import dev.cline.termux.ui.navigation.Customize
import dev.cline.termux.ui.navigation.EditorDialogProperties
import dev.cline.termux.ui.navigation.McpEdit
import dev.cline.termux.ui.navigation.ShellNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Customize destinations (CR C1, C2), registered from `shellDestinations`: Customize is a page (top level, or pushed with
 * `?tab=`), the MCP editor a full-screen route on compact/medium and a dialog on expanded.
 */
fun NavGraphBuilder.customizeGraph(navigator: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean) {
    composable<Customize> { entry ->
        val route = entry.toRoute<Customize>()
        val vm: CustomizeViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    CustomizeViewModel(
                        repo = graph.customize,
                        mcp = graph.mcp,
                        helper = HelperLease.of(graph.settingsHelper),
                        gate = graph.kernel.gate,
                        workspace = AppCustomizeWorkspace(graph.prefs, graph.workspaceStore),
                        catalog = {
                            graph.marketplace.cachedCatalog ?: (graph.marketplace.catalog() as? MarketplaceRepository.CatalogLoad.Loaded)?.catalog
                        },
                        sessionRunning = graph.sessionsRepository.state
                            .map { snapshot -> snapshot.rows.any { it.summary.kernelStatus == KernelSessionStatus.Running } }
                            .distinctUntilChanged(),
                        savedState = createSavedStateHandle(),
                        routeTab = route.tab,
                        clockMs = { graph.clock.millis() },
                        leaseScope = graph.appScope,
                    )
                }
            },
        )
        // Marketplace "Installed" (openInstalled) pops back here with a new tab in the entry's
        // SavedStateHandle under CUSTOMIZE_TAB_KEY. The first value is the route argument, which
        // the ViewModel already applied through routeTab; later values switch the tab. Each value
        // is consumed (set to null) so a restored entry does not re-apply it.
        val entryState = entry.savedStateHandle
        LaunchedEffect(entryState, vm) {
            entryState.getStateFlow<String?>(CUSTOMIZE_TAB_KEY, null).collect { value ->
                // The StateFlow's first emission is the entry's initial value (the route argument or null).
                val routeArgument = entryState.get<Boolean>(KEY_TAB_ARG_CONSUMED) != true
                if (routeArgument) entryState[KEY_TAB_ARG_CONSUMED] = true
                if (value == null) return@collect
                entryState[CUSTOMIZE_TAB_KEY] = null
                if (!routeArgument) CustomizeTab.parse(value)?.let(vm::selectTab)
            }
        }
        CustomizeScreen(vm, CustomizeNavigation.of(route, navigator))
    }

    if (editorsAsDialogs) {
        // Back and outside taps go through the editor's own guard (discard confirm, consumed while saving):
        // the window never dismisses itself, the editor draws EditorDialogWindow for outside taps.
        dialog<McpEdit>(dialogProperties = EditorDialogProperties) { entry ->
            McpEditorDestination(entry.toRoute<McpEdit>(), navigator, graph, asDialog = true)
        }
    } else {
        composable<McpEdit> { entry -> McpEditorDestination(entry.toRoute<McpEdit>(), navigator, graph, asDialog = false) }
    }
}

/** Set in the `customize` entry once its `tab` route argument has been seen (see customizeGraph). */
private const val KEY_TAB_ARG_CONSUMED = "customize.tabArgConsumed"

@Composable
private fun McpEditorDestination(route: McpEdit, navigator: ShellNavigator, graph: AppGraph, asDialog: Boolean) {
    val vm: McpServerEditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                McpServerEditorViewModel(
                    mcp = graph.mcp,
                    helper = HelperLease.of(graph.settingsHelper),
                    gate = graph.kernel.gate,
                    savedState = createSavedStateHandle(),
                    originalName = route.name,
                    leaseScope = graph.appScope,
                )
            }
        },
    )
    McpServerEditorScreen(vm, asDialog = asDialog, onClose = { navigator.back() })
}
