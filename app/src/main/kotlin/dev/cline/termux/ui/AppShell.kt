package dev.cline.termux.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.ui.chat.ImageLightboxOverlay
import dev.cline.termux.ui.chat.LocalShellLightbox
import dev.cline.termux.ui.chat.hiddenBehindLightbox
import dev.cline.termux.ui.chat.rememberLightboxState
import dev.cline.termux.ui.components.FocusReturnSlot
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.LocalKernelSheetFocusReturn
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.KernelChip
import dev.cline.termux.ui.components.KernelChipPlacement
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.HubUpdateRequiredDialog
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.BackOrder
import dev.cline.termux.ui.navigation.backStackFlow
import dev.cline.termux.ui.navigation.Chat
import dev.cline.termux.ui.navigation.ChatSelection
import dev.cline.termux.ui.navigation.ChildChat
import dev.cline.termux.ui.navigation.SyncScaffoldState
import dev.cline.termux.ui.navigation.DeepLink
import dev.cline.termux.ui.navigation.DrawerVariant
import dev.cline.termux.ui.navigation.LocalBackOrder
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.LocalShellNavigator
import dev.cline.termux.ui.navigation.LocalShortcutBus
import dev.cline.termux.ui.navigation.PaneFocusAnchor
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.navigation.RootRoute
import dev.cline.termux.ui.navigation.Routes
import dev.cline.termux.ui.navigation.ShellHost
import dev.cline.termux.ui.navigation.ShellInsets
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.navigation.ShellNavHost
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.ShellShortcut
import dev.cline.termux.ui.navigation.ShellShortcuts
import dev.cline.termux.ui.navigation.SupportingContent
import dev.cline.termux.ui.navigation.TopLevelDestination
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.navigation.consumeRailInsets
import dev.cline.termux.ui.navigation.rememberPaneDirective
import dev.cline.termux.ui.navigation.rememberShellLayout
import dev.cline.termux.ui.navigation.shellRoute
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------ seams

/**
 * The kernel surfaces' shared state (KS F.3): every screen that hosts a KernelBanner, the
 * drawer's kernel header item, the composer's read-only row and the KernelChip read the gate
 * here and send their button through [onAction] (the shell runs it: start, connect, status
 * sheet, repair, reliability).
 */
@Stable
class ShellKernelUi(
    val gate: KernelGate,
    val hubPort: Int?,
    /** The action whose call is running (its button shows "Starting…"). */
    val actionInFlight: KernelAction?,
    val onAction: (KernelAction) -> Unit,
)

/** Null outside the shell (onboarding and kernel screens render their own state). */
val LocalShellKernelUi = compositionLocalOf<ShellKernelUi?> { null }

/**
 * What the chat screen reads from the shell (docs/07 §6.1, §6.2): the supporting-pane
 * request at ≥ 1200 × ≥ 480 that [dev.cline.termux.ui.navigation.ChatSupportingPaneHost]
 * draws (`supporting`, `onCloseSupporting = closeSupporting`; DS §5.2, CT A.3, B.3), and the
 * composer-focus requests "New session" sends while Home is shown (HC §2.9).
 */
@Stable
class ShellChatUi(
    /** The Changes / Tool detail content of the chat's supporting pane; null when closed. */
    val supporting: SupportingContent?,
    /** The pane was closed (its close button, back at level 7a, or the scaffold dropped it). */
    val closeSupporting: () -> Unit,
    /** Each emission asks the Home composer to take focus. */
    val composerFocusRequests: SharedFlow<Unit>,
)

/** Null outside the shell (previews, root screens). */
val LocalShellChat = compositionLocalOf<ShellChatUi?> { null }

/**
 * The page KernelBanner host (KS F.3, F.4) for any screen inside the shell: binds
 * [KernelBannerHost] to the shell's gate and action handler. [hasComposer] is true on Home
 * and Chat (the composer's row carries the primary), false on pages.
 */
@Composable
fun ShellKernelBanner(hasComposer: Boolean, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val kernel = LocalShellKernelUi.current ?: return
    KernelBannerHost(
        gate = kernel.gate,
        hasComposer = hasComposer,
        onAction = kernel.onAction,
        modifier = modifier,
        actionInFlight = kernel.actionInFlight != null,
        contentPadding = contentPadding,
    )
}

/** What the sessions drawer content (SS S1) needs from the shell. */
@Stable
interface ShellDrawerScope {
    val variant: DrawerVariant
    val navigator: ShellNavigator
    val gate: KernelGate
    val hubPort: Int?
    val kernelActionInFlight: KernelAction?

    /** The session shown in the chat area (root selection or the pushed child), for the row highlight. */
    val activeSessionId: String?

    /** The drawer/rail area: the list's "Sessions" label is `onSurface` while S2 is on screen. */
    val area: TopLevelDestination

    /** The modal drawer is open (a scrim tap while renaming cancels the edit: SS §2.9). */
    val drawerOpen: Boolean

    /** Ctrl+K / search on expanded windows: focus the docked search bar (list pane only). */
    val dockedSearchRequests: SharedFlow<Unit>

    fun onKernelAction(action: KernelAction)

    /** A row entered or left rename mode (the drawer drag gesture follows it). */
    fun setRenaming(renaming: Boolean)
}

/**
 * The feature slots of the shell (docs/07 §6.1). Defaults keep the shell usable on its own:
 * no session list, the "Settings" footer item, the KernelChip, no badge, and no sheet or
 * dialog until their lanes plug them in.
 */
@Stable
class ShellSlots(
    /** SS S1 list area: list header (Sessions/Projects, sort, filter), kernel header item, sections, paging. */
    val sessionList: @Composable ShellDrawerScope.(Modifier) -> Unit = {},
    /** SS S3 docked search bar at the top of the expanded list pane; null keeps the header's search icon. */
    val dockedSearch: (@Composable ShellDrawerScope.() -> Unit)? = null,
    /** SS §2.3 footer (account row + settings button); null shows a "Settings" drawer item. */
    val drawerFooter: (@Composable ShellDrawerScope.() -> Unit)? = null,
    /** DS §4.2 KernelChip in the drawer header and as the rail's bottom item. */
    val kernelChip: @Composable (placement: KernelChipPlacement, onOpen: () -> Unit) -> Unit = { placement, onOpen ->
        LocalShellKernelUi.current?.let { KernelChip(it.gate, it.hubPort, onOpen, placement = placement) }
    },
    /** SS §2.3: the 6dp unread badge on a rail item (Chats when any session is unread). */
    val railBadge: @Composable (TopLevelDestination) -> Boolean = { false },
    /** KS E: the Kernel status sheet, shown while the shell's sheet request is set. */
    val kernelStatusSheet: @Composable (request: KernelSheetRequest, onDismiss: () -> Unit) -> Unit = { _, _ -> },
    /** CR C11: the What's-new dialog (shell variant, with "See all changes"). */
    val whatsNewDialog: @Composable (id: String, onClose: () -> Unit, onSeeAll: () -> Unit) -> Unit = { _, _, _ -> },
)

// ------------------------------------------------------------------------------ shell

/**
 * The app shell (DS §5.1–5.2, docs/07 §6.1): compact = `ModalNavigationDrawer` + single pane;
 * medium (and height-compact) = `NavigationRail` + the same modal drawer; expanded = rail +
 * `NavigableListDetailPaneScaffold` (sessions list pane, NavHost detail pane). It owns the
 * shell NavController (hoisted, so a window-class change keeps the same back stack), the
 * kernel surfaces' state, the Kernel status sheet, the Hub update dialog, the What's-new
 * trigger, deep-link routing and the shortcut keys.
 */
@Composable
fun AppShell(
    viewModel: AppShellViewModel,
    onOpenRoot: (RootRoute) -> Unit,
    modifier: Modifier = Modifier,
    slots: ShellSlots = ShellSlots(),
    navController: NavHostController = rememberNavController(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val layout = rememberShellLayout()
    val layoutState = rememberUpdatedState(layout)
    val openRoot by rememberUpdatedState(onOpenRoot)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val backOrder = remember { BackOrder() }
    // HC §5.2: the one image lightbox, drawn above the rail and every pane.
    val lightbox = rememberLightboxState()

    val slotsState = rememberUpdatedState(slots)
    val host = remember(viewModel) { ShellHostImpl(viewModel, layoutState, slotsState) { openRoot(it) } }
    val navigator = remember(navController, host) { ShellNavigator(navController, host) }

    LaunchedEffect(layout) {
        viewModel.onLayout(layout)
        if (!layout.usesModalDrawer) viewModel.setDrawerOpen(false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }

    DrawerSync(drawerState, open = ui.drawerOpen && layout.usesModalDrawer, onChanged = viewModel::setDrawerOpen)

    val backStack by navController.backStackFlow().collectAsStateWithLifecycle()
    val routes = remember(backStack) { backStack.mapNotNull { it.shellRoute() } }
    val current = routes.lastOrNull()
    val area = Routes.areaOf(routes)
    ShellEffects(viewModel, navigator, navController, ui, layout, area, backOrder)
    val rootEntry = remember(backStack) { backStack.firstOrNull { it.shellRoute() is Chat } }
    val selectionFlow = remember(rootEntry) { rootEntry?.savedStateHandle?.let(ChatSelection::selectedFlow) ?: MutableStateFlow(null) }
    val selected by selectionFlow.collectAsStateWithLifecycle()
    val homeActive = current is Chat && selected == null
    val activeSessionId = when {
        current is ChildChat -> current.sessionId
        area == TopLevelDestination.Chats -> selected
        else -> null
    }

    // The chat's supporting pane is on screen (it lives in the chat route at ≥ 1200 × ≥ 480).
    val supportingOpen = ui.supporting != null && layout.supportingPaneAvailable && (current is Chat || current is ChildChat)
    val listHidden = ui.listPaneCollapsed || supportingOpen

    val kernelUi = ShellKernelUi(ui.gate, ui.hubPort, ui.kernelActionInFlight, viewModel::onKernelAction)
    val chatUi = ShellChatUi(ui.supporting, viewModel::clearSupporting, viewModel.composerFocusRequests)
    val drawerScope = { variant: DrawerVariant ->
        ShellDrawerScopeImpl(
            variant = variant,
            navigator = navigator,
            gate = ui.gate,
            hubPort = ui.hubPort,
            kernelActionInFlight = ui.kernelActionInFlight,
            activeSessionId = activeSessionId,
            area = area,
            drawerOpen = ui.drawerOpen,
            dockedSearchRequests = viewModel.dockedSearchRequests,
            onKernel = viewModel::onKernelAction,
            onRenaming = viewModel::setDrawerRenaming,
        )
    }
    val nav = DrawerNav(
        homeActive = homeActive,
        area = area,
        onNewSession = navigator::newSession,
        onDestination = navigator::open,
        onSearch = navigator::openSearch,
        onKernelStatus = { navigator.openKernelStatus() },
    )

    val kernelSheetFocusReturn = remember { FocusReturnSlot() }
    CompositionLocalProvider(
        LocalShellLayout provides layout,
        LocalShellNavigator provides navigator,
        LocalBackOrder provides backOrder,
        LocalShellKernelUi provides kernelUi,
        LocalShellChat provides chatUi,
        LocalShellListPaneOnScreen provides (layout.showsListPane && !listHidden && area == TopLevelDestination.Chats),
        LocalShellLightbox provides lightbox,
        LocalKernelSheetFocusReturn provides kernelSheetFocusReturn,
    ) {
        ReportBackLevel(BackLevel.Drawer, drawerState.isOpen)
        Box(modifier.fillMaxSize()) {
            // HC §5.6: while the lightbox is up, everything behind it leaves the accessibility tree.
            Box(Modifier.fillMaxSize().hiddenBehindLightbox(lightbox)) {
                when {
                    layout.width == WidthClass.Compact -> ModalNavigationDrawer(
                        drawerState = drawerState,
                        // SS §2.8: content drag opens the drawer only at the chat root and not while
                        // renaming or while the lightbox covers the chat; once open, gestures stay on
                        // so a drag or a scrim tap closes it.
                        gesturesEnabled = drawerState.isOpen || (current is Chat && !ui.drawerRenaming && !lightbox.isOpen),
                        drawerContent = { ShellDrawerSheet(drawerState, drawerScope(DrawerVariant.CompactDrawer), nav, slots) },
                    ) {
                        ShellNavHost(navController, navigator)
                    }
                    layout.usesModalDrawer -> ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = { ShellDrawerSheet(drawerState, drawerScope(DrawerVariant.MediumDrawer), nav, slots) },
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            ShellRail(
                                layout = layout,
                                area = area,
                                listPaneCollapsed = ui.listPaneCollapsed,
                                onMenu = navigator::openDrawer,
                                onNewSession = navigator::newSession,
                                onDestination = navigator::open,
                                onKernelStatus = { navigator.openKernelStatus() },
                                slots = slots,
                            )
                            Box(Modifier.weight(1f).fillMaxHeight().consumeRailInsets()) { ShellNavHost(navController, navigator) }
                        }
                    }
                    else -> ExpandedShell(
                        layout = layout,
                        area = area,
                        listPaneCollapsed = ui.listPaneCollapsed,
                        supportingOpen = supportingOpen,
                        navigator = navigator,
                        navController = navController,
                        viewModel = viewModel,
                        listContent = { ShellDrawerContent(drawerScope(DrawerVariant.ListPane), nav, slots) },
                        slots = slots,
                    )
                }
            }
            // HC §5.2: last in the root Box, above the rail, the list pane, the NavHost and the
            // supporting pane, so the scrim covers the whole window.
            ImageLightboxOverlay(lightbox)
        }
        ShellOverlays(viewModel, ui, navigator, homeActive, slots)
    }
}

/** The navigation callbacks of the drawer frame (header and destination items). */
@Stable
private class DrawerNav(
    val homeActive: Boolean,
    val area: TopLevelDestination,
    val onNewSession: () -> Unit,
    val onDestination: (TopLevelDestination) -> Unit,
    val onSearch: () -> Unit,
    val onKernelStatus: () -> Unit,
)

private class ShellDrawerScopeImpl(
    override val variant: DrawerVariant,
    override val navigator: ShellNavigator,
    override val gate: KernelGate,
    override val hubPort: Int?,
    override val kernelActionInFlight: KernelAction?,
    override val activeSessionId: String?,
    override val area: TopLevelDestination,
    override val drawerOpen: Boolean,
    override val dockedSearchRequests: SharedFlow<Unit>,
    private val onKernel: (KernelAction) -> Unit,
    private val onRenaming: (Boolean) -> Unit,
) : ShellDrawerScope {
    override fun onKernelAction(action: KernelAction) = onKernel(action)
    override fun setRenaming(renaming: Boolean) = onRenaming(renaming)
}

private class ShellHostImpl(
    private val viewModel: AppShellViewModel,
    private val layoutState: State<ShellLayout>,
    private val slots: State<ShellSlots>,
    private val openRoot: (RootRoute) -> Unit,
) : ShellHost {
    override val layout: ShellLayout get() = layoutState.value
    override fun setDrawerOpen(open: Boolean) = viewModel.setDrawerOpen(open && layout.usesModalDrawer)
    override fun toggleListPane() = viewModel.toggleListPane()
    override fun openKernelStatus(diagnostics: Boolean) = viewModel.openKernelSheet(diagnostics)
    override fun openRoot(route: RootRoute) = openRoot.invoke(route)
    override fun showSupporting(content: SupportingContent) = viewModel.showSupporting(content)
    override fun requestComposerFocus() = viewModel.requestComposerFocus()
    override fun requestDockedSearch(): Boolean {
        // The docked bar exists only when a lane plugged it in and the list pane is not collapsed.
        val ui = viewModel.ui.value
        if (slots.value.dockedSearch == null || ui.listPaneCollapsed || ui.supporting != null) return false
        viewModel.requestDockedSearch()
        return true
    }
}

/**
 * Keeps the M3 [DrawerState] and the ViewModel's `drawerOpen` in step, both ways.
 *
 * Both directions watch [DrawerState.targetValue], not the settled value (trial row 25): a touch on the
 * content while the drawer is still sliding open starts a drag at once, which cancels the programmatic
 * [DrawerState.open], and the release can settle the drawer back to Closed before it ever reached halfway.
 * The settled value never changes in that case, so a sync on it would leave `drawerOpen` true with the
 * drawer closed, and every later menu tap would set the flag to the value it already has. The target
 * value does change (Open when the animation starts, Closed when the drag settles), so the flag follows.
 */
@Composable
private fun DrawerSync(drawerState: DrawerState, open: Boolean, onChanged: (Boolean) -> Unit) {
    val changed by rememberUpdatedState(onChanged)
    LaunchedEffect(open) {
        if (open && drawerState.targetValue == DrawerValue.Closed) drawerState.open()
        if (!open && drawerState.targetValue == DrawerValue.Open) drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }.distinctUntilChanged().collect { changed(it == DrawerValue.Open) }
    }
}

/** Events, shortcuts, deep links and the supporting-pane fallback. */
@Composable
private fun ShellEffects(
    viewModel: AppShellViewModel,
    navigator: ShellNavigator,
    navController: NavHostController,
    ui: ShellUiState,
    layout: ShellLayout,
    area: TopLevelDestination,
    backOrder: BackOrder,
) {
    val snackbar = LocalSnackbarHost.current
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShellEvent.Snackbar -> snackbar.showReplacing(resources.getString(event.text))
                is ShellEvent.OpenRoot -> navigator.openRoot(event.route)
            }
        }
    }
    val bus = LocalShortcutBus.current
    val currentLayout by rememberUpdatedState(layout)
    val currentArea by rememberUpdatedState(area)
    LaunchedEffect(bus, navigator) {
        bus.shortcuts.collect { shortcut ->
            if (ShellShortcuts.suppressedByOverlay(shortcut, backOrder::isOpen)) return@collect
            when (shortcut) {
                ShellShortcut.Search -> navigator.openSearch()
                ShellShortcut.NewSession -> navigator.newSession()
                // The drawer's / rail's Settings destination (CR §0.2).
                ShellShortcut.Settings -> navigator.open(TopLevelDestination.Settings)
                ShellShortcut.ToggleDrawer -> when {
                    currentLayout.usesModalDrawer -> if (viewModel.ui.value.drawerOpen) navigator.closeDrawer() else navigator.openDrawer()
                    else -> expandedMenu(viewModel, navigator, currentArea)
                }
            }
        }
    }
    // docs/07 §6.4: a queued link is routed once the NavHost has its graph (the chat root exists).
    val graphReady = navigator.chatRootEntry != null
    LaunchedEffect(ui.pendingDeepLink, graphReady) {
        if (!graphReady) return@LaunchedEffect
        when (val link = viewModel.consumeDeepLink()) {
            is DeepLink.Session -> navigator.openSession(link.sessionId, link.sheet)
            DeepLink.KernelStatus -> navigator.openKernelStatus()
            null -> Unit
        }
    }
    // The window shrank below the supporting-pane size: the open pane becomes its pushed route.
    LaunchedEffect(layout.supportingPaneAvailable, ui.supporting) {
        val content = ui.supporting ?: return@LaunchedEffect
        if (!layout.supportingPaneAvailable) {
            viewModel.clearSupporting()
            navController.navigate(content.route)
        }
    }
}

/** The sheet, dialogs and What's new above the panes. */
@Composable
private fun ShellOverlays(
    viewModel: AppShellViewModel,
    ui: ShellUiState,
    navigator: ShellNavigator,
    homeActive: Boolean,
    slots: ShellSlots,
) {
    val sheet = ui.kernelSheet
    ReportBackLevel(BackLevel.Sheet, sheet != null)
    if (sheet != null) slots.kernelStatusSheet(sheet, viewModel::dismissKernelSheet)

    ui.hubUpdateDialog?.let { dialog ->
        HubUpdateRequiredDialog(dialog, onRestart = viewModel::confirmHubUpdate, onLater = viewModel::dismissHubUpdate)
    }

    // CR §8.4: once Home is drawn, never over onboarding or a repair screen (those are not the shell).
    val whatsNew = ui.whatsNewId
    if (whatsNew != null && homeActive && ui.hubUpdateDialog == null) {
        slots.whatsNewDialog(
            whatsNew,
            viewModel::closeWhatsNew,
            {
                viewModel.closeWhatsNew()
                navigator.push(dev.cline.termux.ui.navigation.About)
            },
        )
    }
}

// ------------------------------------------------------------------------- expanded

/**
 * The expanded rail's `menu` (SS §2.9.1), also Ctrl+B there: outside Chats it opens Chats with
 * the list pane shown, in Chats it collapses or expands the list pane.
 */
private fun expandedMenu(viewModel: AppShellViewModel, navigator: ShellNavigator, area: TopLevelDestination) {
    if (area != TopLevelDestination.Chats) {
        viewModel.setListPaneCollapsed(false)
        navigator.open(TopLevelDestination.Chats)
    } else {
        navigator.toggleListPane()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ExpandedShell(
    layout: ShellLayout,
    area: TopLevelDestination,
    listPaneCollapsed: Boolean,
    supportingOpen: Boolean,
    navigator: ShellNavigator,
    navController: NavHostController,
    viewModel: AppShellViewModel,
    listContent: @Composable () -> Unit,
    slots: ShellSlots,
) {
    // SS §2.9.1: the list pane is for the Chats area; the user may collapse it with the rail menu.
    // CT DS-A1: the chat's supporting pane hides the list pane while it is open; closing the
    // pane brings back whatever the user had (listPaneCollapsed itself is never changed).
    val showList = area == TopLevelDestination.Chats && !listPaneCollapsed && !supportingOpen
    // SS §2.9.1 / HC §2.9 order: selected session → Home, then reveal the list pane, then leave.
    // Registered before the scaffold and its NavHost, so the detail NavHost and the chat's
    // selection back (both registered later) win while they are enabled. It stands down for
    // the levels above it but does not report itself: reporting would disable the chat's
    // level-9 selection back, which must run first here.
    val backOrder = LocalBackOrder.current
    BackHandler(enabled = backOrder.allows(BackLevel.ListDetailPane, listPaneCollapsed && area == TopLevelDestination.Chats)) {
        viewModel.setListPaneCollapsed(false)
    }
    Row(Modifier.fillMaxSize()) {
        ShellRail(
            layout = layout,
            area = area,
            listPaneCollapsed = !showList,
            onMenu = { expandedMenu(viewModel, navigator, area) },
            onNewSession = navigator::newSession,
            onDestination = navigator::open,
            onKernelStatus = { navigator.openKernelStatus() },
            slots = slots,
        )
        val scaffold = rememberListDetailPaneScaffoldNavigator(
            scaffoldDirective = rememberPaneDirective(layout.listPaneWidth, maxPartitions = if (showList) null else 1),
            initialDestinationHistory = listOf(ThreePaneScaffoldDestinationItem<Any>(ListDetailPaneScaffoldRole.Detail, null)),
        )
        SyncScaffoldState(scaffold)
        NavigableListDetailPaneScaffold(
            navigator = scaffold,
            modifier = Modifier.weight(1f).fillMaxHeight().consumeRailInsets(),
            listPane = {
                AnimatedPane {
                    PaneFocusAnchor {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.windowInsetsPadding(ShellInsets.listPane)) { listContent() }
                        }
                    }
                }
            },
            detailPane = { AnimatedPane { PaneFocusAnchor { ShellNavHost(navController, navigator) } } },
            defaultBackBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
        )
    }
}

// ----------------------------------------------------------------------------- rail

/**
 * DS §5.2 rail (80dp, `surface`): header `menu` (medium: opens the modal drawer, "Open
 * sessions"; expanded: toggles the list pane, "Collapse sessions" / "Expand sessions") and the
 * "New session" FAB (`edit_square`); the four destinations; the KernelChip as the bottom item.
 *
 * A height-compact window (DS §5.1, under 480dp: phones in landscape) cannot fit all of that at
 * full size, so the rail compacts: the FAB becomes a 40dp `SmallFloatingActionButton`, the
 * destinations are 56dp icon-only items (label in the tooltip and the content description),
 * and the KernelChip joins the scrolling column after them. On any window the column scrolls
 * the selected destination into view, so it is never clipped or off screen (DS §3.4).
 */
@Composable
private fun ShellRail(
    layout: ShellLayout,
    area: TopLevelDestination,
    listPaneCollapsed: Boolean,
    onMenu: () -> Unit,
    onNewSession: () -> Unit,
    onDestination: (TopLevelDestination) -> Unit,
    onKernelStatus: () -> Unit,
    slots: ShellSlots,
) {
    val compactHeight = layout.heightCompact
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = ShellInsets.rail,
        header = {
            val togglesPane = layout.showsListPane
            val menuLabel = when {
                !togglesPane -> stringResource(R.string.components_open_sessions)
                listPaneCollapsed -> stringResource(R.string.shell_expand_sessions)
                else -> stringResource(R.string.shell_collapse_sessions)
            }
            val menuIcon = when {
                !togglesPane -> ClineIcons.Chrome.Menu
                listPaneCollapsed -> ClineIcons.Chrome.ListPaneOpen
                else -> ClineIcons.Chrome.ListPaneClose
            }
            TooltipIconButton(icon = menuIcon, contentDescription = menuLabel, onClick = onMenu)
            val newSession = stringResource(R.string.shell_new_session)
            PlainTooltipBox(text = stringResource(R.string.shell_new_session_tooltip)) {
                if (compactHeight) {
                    // 40dp visual inside a 48dp touch target (DS §3.4).
                    SmallFloatingActionButton(onClick = onNewSession) {
                        Icon(ClineIcons.Chrome.NewSessionRail, contentDescription = newSession)
                    }
                } else {
                    FloatingActionButton(onClick = onNewSession) {
                        Icon(ClineIcons.Chrome.NewSessionRail, contentDescription = newSession)
                    }
                }
            }
        },
    ) {
        val scrollState = rememberScrollState()
        val itemBounds = remember { mutableStateMapOf<TopLevelDestination, IntRange>() }
        RevealSelectedRailItem(scrollState, itemBounds, area)
        Column(
            Modifier.weight(1f).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // A spacer rather than padding, so each item's position in this column is its
            // offset in the scroll content (what [railScrollTarget] works in).
            Spacer(Modifier.size(if (compactHeight) Spacing.xs else Spacing.m))
            TopLevelDestination.entries.forEach { destination ->
                Box(
                    Modifier.onPlaced { coordinates ->
                        val top = coordinates.positionInParent().y.roundToInt()
                        itemBounds[destination] = top..(top + coordinates.size.height)
                    },
                ) {
                    RailItem(
                        destination,
                        selected = area == destination,
                        badge = slots.railBadge(destination),
                        iconOnly = compactHeight,
                        onClick = { onDestination(destination) },
                    )
                }
            }
            if (compactHeight) {
                slots.kernelChip(KernelChipPlacement.Rail, onKernelStatus)
                Spacer(Modifier.size(Spacing.xs))
            }
        }
        if (!compactHeight) {
            slots.kernelChip(KernelChipPlacement.Rail, onKernelStatus)
            Spacer(Modifier.size(Spacing.s))
        }
    }
}

/**
 * Keeps the selected destination inside the rail's scroll viewport: on a destination change
 * and whenever the items or the viewport are re-measured (rotation, multi-window resize). It
 * reads neither the scroll position's changes nor gestures, so the user can still scroll the
 * rail freely afterwards. Reduced motion jumps instead of animating.
 */
@Composable
private fun RevealSelectedRailItem(
    scrollState: ScrollState,
    itemBounds: Map<TopLevelDestination, IntRange>,
    area: TopLevelDestination,
) {
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(scrollState, area, reducedMotion) {
        snapshotFlow { itemBounds[area] to scrollState.viewportSize }
            .distinctUntilChanged()
            .collectLatest { (bounds, viewport) ->
                if (bounds == null) return@collectLatest
                val target = railScrollTarget(bounds.first, bounds.last, scrollState.value, viewport) ?: return@collectLatest
                if (reducedMotion) scrollState.scrollTo(target) else scrollState.animateScrollTo(target)
            }
    }
}

/**
 * The scroll value that brings the item spanning [itemTop]..[itemBottom] (scroll-content
 * pixels) fully into a [viewport]-tall window currently scrolled to [scroll], moving as little
 * as possible; null when it is already fully visible or nothing is measured yet. An item
 * taller than the viewport aligns its top.
 */
internal fun railScrollTarget(itemTop: Int, itemBottom: Int, scroll: Int, viewport: Int): Int? = when {
    viewport <= 0 || itemBottom < itemTop -> null
    itemTop < scroll -> itemTop
    itemBottom > scroll + viewport -> if (itemBottom - itemTop > viewport) itemTop else itemBottom - viewport
    else -> null
}

@Composable
private fun RailItem(destination: TopLevelDestination, selected: Boolean, badge: Boolean, iconOnly: Boolean, onClick: () -> Unit) {
    val label = destinationLabel(destination)
    val badgeLabel = stringResource(R.string.shell_unread_sessions)
    val spoken = if (badge) stringResource(R.string.shell_rail_item_with_badge, label, badgeLabel) else label
    val railLabel: (@Composable () -> Unit)? =
        if (iconOnly) null else { { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    val item: @Composable () -> Unit = {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = spoken },
            icon = {
                BadgedBox(badge = { if (badge) Badge() }) {
                    Icon(destinationIcon(destination, selected), contentDescription = null)
                }
            },
            // Height-compact: a 56dp icon-only item; the label lives in the tooltip and in the
            // content description above.
            label = railLabel,
            alwaysShowLabel = !iconOnly,
        )
    }
    if (iconOnly) PlainTooltipBox(text = label) { item() } else item()
}

@Composable
private fun destinationLabel(destination: TopLevelDestination): String = stringResource(
    when (destination) {
        TopLevelDestination.Chats -> R.string.shell_destination_chats
        TopLevelDestination.Schedules -> R.string.shell_destination_schedules
        TopLevelDestination.Customize -> R.string.shell_destination_customize
        TopLevelDestination.Settings -> R.string.shell_destination_settings
    },
)

/** DS §5.2: outlined, filled when selected. */
@Composable
private fun destinationIcon(destination: TopLevelDestination, selected: Boolean) = when (destination) {
    TopLevelDestination.Chats -> if (selected) ClineIcons.Chrome.ChatsSelected else ClineIcons.Chrome.Chats
    TopLevelDestination.Schedules -> if (selected) ClineIcons.Chrome.SchedulesSelected else ClineIcons.Chrome.Schedules
    TopLevelDestination.Customize -> if (selected) ClineIcons.Chrome.CustomizeSelected else ClineIcons.Chrome.Customize
    TopLevelDestination.Settings -> if (selected) ClineIcons.Chrome.SettingsSelected else ClineIcons.Chrome.Settings
}

// --------------------------------------------------------------------------- drawer

/** The modal drawer sheet (DS §5.2: max 360dp, `surfaceContainerLow`, predictive close). */
@Composable
private fun ShellDrawerSheet(drawerState: DrawerState, scope: ShellDrawerScope, nav: DrawerNav, slots: ShellSlots) {
    val paneTitle = stringResource(R.string.shell_sessions_pane_title)
    ModalDrawerSheet(
        drawerState = drawerState,
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        windowInsets = ShellInsets.drawer,
        modifier = Modifier.semantics { this.paneTitle = paneTitle },
    ) {
        ShellDrawerContent(scope, nav, slots)
    }
}

/**
 * The drawer / list-pane frame (sessions.md §2.3): header row, destination items for the
 * variant, the docked search (list pane), the sessions slot, and the footer (not on the
 * medium drawer, where Settings is a rail destination).
 */
@Composable
private fun ShellDrawerContent(scope: ShellDrawerScope, nav: DrawerNav, slots: ShellSlots) {
    Column(Modifier.fillMaxHeight()) {
        DrawerHeader(scope, nav, slots)
        if (scope.variant == DrawerVariant.ListPane) slots.dockedSearch?.invoke(scope)
        DrawerDestinations(scope.variant, nav)
        Box(Modifier.weight(1f).fillMaxWidth()) { slots.sessionList(scope, Modifier.fillMaxSize()) }
        if (scope.variant != DrawerVariant.MediumDrawer) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            val footer = slots.drawerFooter
            if (footer != null) {
                footer(scope)
            } else {
                DrawerItem(
                    label = stringResource(R.string.shell_destination_settings),
                    icon = if (nav.area == TopLevelDestination.Settings) ClineIcons.Chrome.SettingsSelected else ClineIcons.Chrome.Settings,
                    selected = nav.area == TopLevelDestination.Settings,
                    onClick = { nav.onDestination(TopLevelDestination.Settings) },
                )
            }
        }
    }
}

/**
 * Header row (56dp): "Cline Termux", the KernelChip, search. No logo button (trial row 27):
 * "New session", the drawer item or the rail FAB, already opens Home.
 */
@Composable
private fun DrawerHeader(scope: ShellDrawerScope, nav: DrawerNav, slots: ShellSlots) {
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    Row(
        // 16dp start: the title lines up with the "Sessions" label of the list header below.
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.shell_drawer_title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The chip takes what the title leaves and ellipsizes its own label, so a large font
        // scale shortens "Cline Hub @25463" instead of clipping the app name.
        Box(Modifier.weight(1f).padding(start = Spacing.xs), contentAlignment = Alignment.CenterEnd) {
            slots.kernelChip(KernelChipPlacement.DrawerHeader, nav.onKernelStatus)
        }
        if (scope.variant != DrawerVariant.ListPane || slots.dockedSearch == null) {
            TooltipIconButton(
                icon = ClineIcons.Chrome.Search,
                contentDescription = stringResource(R.string.shell_search_sessions),
                tooltip = stringResource(if (hardwareKeyboard) R.string.shell_search_sessions_shortcut else R.string.shell_search_sessions),
                onClick = nav.onSearch,
            )
        } else {
            Spacer(Modifier.width(Spacing.xs))
        }
    }
}

/** "New session", "Schedules", "Customize" (compact); "New session" only (medium); none (list pane). */
@Composable
private fun DrawerDestinations(variant: DrawerVariant, nav: DrawerNav) {
    if (variant == DrawerVariant.ListPane) return
    Column(Modifier.padding(vertical = Spacing.xs)) {
        PlainTooltipBox(text = stringResource(R.string.shell_new_session_tooltip)) {
            DrawerItem(
                label = stringResource(R.string.shell_new_session),
                icon = ClineIcons.Chrome.NewSession,
                selected = nav.homeActive,
                onClick = nav.onNewSession,
            )
        }
        if (variant == DrawerVariant.CompactDrawer) {
            DrawerItem(
                label = stringResource(R.string.shell_destination_schedules),
                icon = if (nav.area == TopLevelDestination.Schedules) ClineIcons.Chrome.SchedulesSelected else ClineIcons.Chrome.Schedules,
                selected = nav.area == TopLevelDestination.Schedules,
                onClick = { nav.onDestination(TopLevelDestination.Schedules) },
            )
            PlainTooltipBox(text = stringResource(R.string.shell_customize_tooltip)) {
                DrawerItem(
                    label = stringResource(R.string.shell_destination_customize),
                    icon = if (nav.area == TopLevelDestination.Customize) ClineIcons.Chrome.CustomizeSelected else ClineIcons.Chrome.Customize,
                    selected = nav.area == TopLevelDestination.Customize,
                    onClick = { nav.onDestination(TopLevelDestination.Customize) },
                )
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        // DS §8.1.7: the ring hugs the pill, so it goes after the item padding.
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).focusRing(CircleShape),
    )
}

// ------------------------------------------------------------------------- previews

private fun previewNav(homeActive: Boolean = true, area: TopLevelDestination = TopLevelDestination.Chats) =
    DrawerNav(homeActive, area, {}, {}, {}, {})

@Composable
private fun PreviewDrawer(variant: DrawerVariant, area: TopLevelDestination = TopLevelDestination.Chats, gate: KernelGate = KernelGate.Ready) {
    val navController = rememberNavController()
    val layout = ShellLayout.PhonePortrait
    val navigator = remember { ShellNavigator(navController, PreviewHost(layout)) }
    val scope = ShellDrawerScopeImpl(
        variant, navigator, gate, 25463, null, null, area, true, PreviewDockedSearchRequests, {}, {},
    )
    CompositionLocalProvider(LocalShellKernelUi provides ShellKernelUi(gate, 25463, null) {}) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(360.dp).heightIn(min = 480.dp)) {
            ShellDrawerContent(scope, previewNav(homeActive = area == TopLevelDestination.Chats, area = area), ShellSlots())
        }
    }
}

private val PreviewDockedSearchRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()

private class PreviewHost(override val layout: ShellLayout) : ShellHost {
    override fun setDrawerOpen(open: Boolean) = Unit
    override fun toggleListPane() = Unit
    override fun openKernelStatus(diagnostics: Boolean) = Unit
    override fun openRoot(route: RootRoute) = Unit
    override fun showSupporting(content: SupportingContent) = Unit
    override fun requestComposerFocus() = Unit
    override fun requestDockedSearch(): Boolean = false
}

@LightDarkPreviews
@Composable
private fun DrawerCompactPreview() {
    ComponentPreview(padding = false) { PreviewDrawer(DrawerVariant.CompactDrawer) }
}

@LightDarkPreviews
@Composable
private fun DrawerCompactSchedulesKernelDownPreview() {
    ComponentPreview(padding = false) {
        PreviewDrawer(DrawerVariant.CompactDrawer, TopLevelDestination.Schedules, KernelGate.Down(KernelGate.DownVariant.Asleep, null))
    }
}

@LightDarkPreviews
@Composable
private fun DrawerMediumPreview() {
    ComponentPreview(padding = false) { PreviewDrawer(DrawerVariant.MediumDrawer) }
}

@LightDarkPreviews
@Composable
private fun ListPanePreview() {
    ComponentPreview(padding = false) { PreviewDrawer(DrawerVariant.ListPane) }
}

@Composable
private fun PreviewRail(layout: ShellLayout, area: TopLevelDestination, collapsed: Boolean, badge: Boolean, gate: KernelGate = KernelGate.Ready) {
    CompositionLocalProvider(LocalShellKernelUi provides ShellKernelUi(gate, 25463, null) {}) {
        // Height-compact previews pin the rail to a phone-in-landscape height (the window minus
        // the status bar), so the column scrolls as it does on the device.
        Box(if (layout.heightCompact) Modifier.height(layout.heightDp.dp - 24.dp) else Modifier.heightIn(min = 640.dp)) {
            ShellRail(
                layout = layout,
                area = area,
                listPaneCollapsed = collapsed,
                onMenu = {},
                onNewSession = {},
                onDestination = {},
                onKernelStatus = {},
                slots = ShellSlots(railBadge = { it == TopLevelDestination.Chats && badge }),
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun RailMediumPreview() {
    ComponentPreview(padding = false) { PreviewRail(ShellLayout(778, 1200), TopLevelDestination.Chats, collapsed = false, badge = true) }
}

@LightDarkPreviews
@Composable
private fun RailExpandedPreview() {
    ComponentPreview(padding = false) { PreviewRail(ShellLayout(1244, 778), TopLevelDestination.Settings, collapsed = false, badge = false) }
}

@LightDarkPreviews
@Composable
private fun RailExpandedCollapsedConnectingPreview() {
    ComponentPreview(padding = false) {
        PreviewRail(
            ShellLayout(1244, 778),
            TopLevelDestination.Chats,
            collapsed = true,
            badge = false,
            gate = KernelGate.Busy(KernelGate.BusyKind.Connecting, null, slow = false),
        )
    }
}

/** DS §5.1 height-compact (S25U landscape): icon-only items, small FAB, chip in the scroll. */
@LightDarkPreviews
@Composable
private fun RailHeightCompactSettingsPreview() {
    ComponentPreview(padding = false) { PreviewRail(ShellLayout(891, 412), TopLevelDestination.Settings, collapsed = true, badge = false) }
}

@LightDarkPreviews
@Composable
private fun RailHeightCompactChatsBadgeKernelDownPreview() {
    ComponentPreview(padding = false) {
        PreviewRail(
            ShellLayout(891, 412),
            TopLevelDestination.Chats,
            collapsed = true,
            badge = true,
            gate = KernelGate.Down(KernelGate.DownVariant.Asleep, null),
        )
    }
}
