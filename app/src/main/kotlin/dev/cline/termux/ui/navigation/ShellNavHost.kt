package dev.cline.termux.ui.navigation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import dev.cline.termux.debug.DebugScreens
import androidx.compose.ui.platform.LocalContext
import dev.cline.termux.AppGraph
import dev.cline.termux.appGraph
import dev.cline.termux.ui.about.AboutRoute
import dev.cline.termux.ui.about.aboutGraph
import dev.cline.termux.ui.account.AccountRoute
import dev.cline.termux.ui.account.accountGraph
import dev.cline.termux.ui.chat.chatGraph
import dev.cline.termux.ui.customize.customizeGraph
import dev.cline.termux.ui.kernel.KernelSettingsScreen
import dev.cline.termux.ui.kernel.kernelSettingsDestination
import dev.cline.termux.ui.marketplace.marketplaceGraph
import dev.cline.termux.ui.providers.providersGraph
import dev.cline.termux.ui.schedules.schedulesGraph
import dev.cline.termux.ui.sessions.sessionsGraph
import dev.cline.termux.ui.settings.SettingsDetailPanes
import dev.cline.termux.ui.settings.SettingsSection
import dev.cline.termux.ui.settings.settingsGraph
import dev.cline.termux.ui.workspace.workspaceGraph
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.MenuNavIcon
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.theme.Spacing

/**
 * The shell's NavHost (docs/07 §6.2): the chat root as the fixed start destination, the
 * top-level destinations, the pushed routes and the editors ([shellDestinations]).
 *
 * Placements (DS §5.2): the chat root is the detail pane of the shell's list-detail scaffold
 * on expanded windows (AppShell); Changes and Tool detail are pushed here below 1200dp and
 * shown in the chat's supporting pane above ([ChatSupportingPaneHost], [ShellNavigator.openChanges]);
 * Settings, Providers, Marketplace and Schedules own their list-detail scaffolds inside their
 * own routes (SP §0.2: never nested); editors are `dialog()` destinations on expanded windows
 * and full-screen `composable()`s otherwise.
 */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    navigator: ShellNavigator,
    modifier: Modifier = Modifier,
) {
    val layout = LocalShellLayout.current
    val graph = LocalContext.current.appGraph
    val editorsAsDialogs = rememberEditorsAsDialogs(navController, layout.editorsAsDialogs)
    val slidePx = with(LocalDensity.current) { NavTransitions.SLIDE_DP.dp.roundToPx() }
    NavHost(
        navController = navController,
        startDestination = Chat(),
        modifier = modifier,
        // Rail/drawer switches fade through whatever sits on top of either side; a pop mirrors
        // the push it undoes ([ShellNavigator.transitionKind]).
        enterTransition = {
            with(NavTransitions) { enter(navigator.transitionKind(initialState, targetState, pop = false), forward = true, slidePx) }
        },
        exitTransition = {
            with(NavTransitions) { exit(navigator.transitionKind(initialState, targetState, pop = false), forward = true, slidePx) }
        },
        popEnterTransition = {
            with(NavTransitions) { enter(navigator.transitionKind(initialState, targetState, pop = true), forward = false, slidePx) }
        },
        popExitTransition = {
            with(NavTransitions) { exit(navigator.transitionKind(initialState, targetState, pop = true), forward = false, slidePx) }
        },
    ) {
        shellDestinations(navigator, graph, editorsAsDialogs)
    }
}

/** The shell route kind of a back-stack entry (unknown entries count as pushed). */
fun NavBackStackEntry.routeKind(): RouteKind? = shellRoute()?.let(Routes::kindOf)

/**
 * The editor presentation actually used by the graph. It follows the window ([wanted]) but
 * never flips while an editor is on the back stack: changing an open editor's destination
 * type (dialog ↔ composable) under its entry would hand the entry to the wrong navigator.
 * The open editor keeps its presentation until it closes (CR §0.2: it keeps its state
 * through its ViewModel either way).
 */
@Composable
fun rememberEditorsAsDialogs(navController: NavHostController, wanted: Boolean): Boolean {
    val stack by navController.backStackFlow().collectAsState()
    val editorOpen = stack.any { entry -> entry.shellRoute()?.let(Routes::isEditor) == true }
    var latched by remember { mutableStateOf(wanted) }
    val effective = EditorPresentation.effective(wanted = wanted, latched = latched, editorOpen = editorOpen)
    SideEffect { if (!editorOpen) latched = wanted }
    return effective
}

/** The latch rule of [rememberEditorsAsDialogs], pure. */
object EditorPresentation {
    fun effective(wanted: Boolean, latched: Boolean, editorOpen: Boolean): Boolean = if (editorOpen) latched else wanted
}

/**
 * Every shell destination (docs/07 §6.2), through the feature packages' graph extensions:
 * chat (+ Changes and Tool detail), sessions and search, schedules, customize (+ MCP editor),
 * settings (+ appearance, notifications), providers (+ add), kernel settings, account, about
 * (+ licences, diagnostics), marketplace, the folder browser and the debug-only kernel probe.
 */
fun NavGraphBuilder.shellDestinations(navigator: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean) {
    chatGraph(navigator, graph)
    sessionsGraph(navigator, graph)
    schedulesGraph(navigator, graph, editorsAsDialogs)
    customizeGraph(navigator, graph, editorsAsDialogs)
    settingsGraph(navigator, graph, settingsPanes(navigator, graph))
    providersGraph(navigator, graph, editorsAsDialogs)
    kernelSettingsDestination(navigator)
    accountGraph(navigator, graph)
    aboutGraph(navigator, graph, editorsAsDialogs)
    marketplaceGraph(navigator, graph)
    workspaceGraph(navigator, graph, editorsAsDialogs)

    // Developer screens (debug builds only), reached from About's "Developer" row.
    if (DebugScreens.available) {
        composable<DebugKernelProbe> { DebugScreens.KernelProbe(onBack = { navigator.back() }) }
    }
}

/**
 * The single-page sections the expanded Settings scaffold shows in its detail pane (SP §0.2):
 * Kernel, Account and About without their back arrows. Providers, Installed, Marketplace and
 * Schedules replace the scaffold with their own.
 */
private fun settingsPanes(navigator: ShellNavigator, graph: AppGraph) = SettingsDetailPanes(
    mapOf(
        SettingsSection.Kernel to { KernelSettingsScreen(navigator, inPane = true) },
        SettingsSection.Account to { AccountRoute(navigator, graph, inPane = true) },
        SettingsSection.About to { AboutRoute(navigator, graph, inPane = true) },
    ),
)

/**
 * Registers an editor route as a `dialog()` (expanded) or a `composable()` (compact and
 * medium); [content] gets the entry and whether it is drawn in a dialog window.
 */
inline fun <reified T : Any> NavGraphBuilder.editor(
    asDialog: Boolean,
    noinline content: @Composable NavBackStackEntry.(asDialog: Boolean) -> Unit,
) {
    if (asDialog) {
        dialog<T>(dialogProperties = EditorDialogProperties) { entry -> entry.content(true) }
    } else {
        composable<T> { entry -> entry.content(false) }
    }
}

/**
 * The editor dialog window: full width available, edge to edge, the surface pads itself (CR §0.2).
 * The window never dismisses itself: navigation's `DialogNavigator` would pop the entry directly
 * and skip the editor's discard confirm and its consume-while-saving rule (docs/07 §6.3, DS §4.1
 * #16). Each editor draws [EditorDialogWindow] for outside taps and handles Back itself
 * ([EditorBackHandler] plus a clean-form close).
 */
val EditorDialogProperties = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

/**
 * The whole editor dialog window: a full-size tap target around a centred [EditorDialogSurface].
 * A tap outside the surface calls [onTapOutside], the editor's own close request (discard
 * confirm when dirty, ignored while saving); taps on the surface never reach it. The detector
 * adds no semantics, so TalkBack keeps the surface's own nodes and closes through Back.
 */
@Composable
fun EditorDialogWindow(
    onTapOutside: () -> Unit,
    surfaceModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { currentOnTapOutside() } },
        contentAlignment = Alignment.Center,
    ) {
        EditorDialogSurface(surfaceModifier.pointerInput(Unit) { detectTapGestures { } }, content)
    }
}

/**
 * The expanded editor surface inside a `dialog()` destination (DS §4.1 #16: `BasicAlertDialog`
 * anatomy, max width 560dp, 28dp corners, `surfaceContainerHigh`). The destination already is
 * the dialog window, so an editor draws this surface instead of opening a second window.
 */
@Composable
fun EditorDialogSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier
            .safeDrawingPadding()
            .imePadding()
            .padding(Spacing.l)
            .widthIn(max = 560.dp)
            .heightIn(min = 280.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        content = content,
    )
}

/**
 * The navigation icon a shell page shows (DS §5.3, CR §0.2): a pushed page has the back
 * arrow; a top-level destination has `menu` on compact (it opens the drawer) and nothing on
 * medium and expanded, where the rail holds `menu`.
 */
@Composable
fun ShellNavigationIcon(route: ShellRoute, navigator: ShellNavigator) {
    when {
        Routes.kindOf(route) != RouteKind.TopLevel -> BackNavIcon { navigator.back() }
        !LocalShellLayout.current.showsRail -> MenuNavIcon { navigator.openDrawer() }
    }
}

/**
 * An editor's `close` navigation icon (CR §0.2 full-screen editor anatomy, CD "Close"), with
 * its long-press tooltip (DS §4.1 #14).
 */
@Composable
fun CloseNavIcon(onClose: () -> Unit) {
    TooltipIconButton(ClineIcons.Close, contentDescription = stringResource(R.string.components_close), onClick = onClose)
}
