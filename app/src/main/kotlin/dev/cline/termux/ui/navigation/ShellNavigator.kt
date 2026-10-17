package dev.cline.termux.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * The chat root's selection (HC §2.9, docs/07 §6.2): top-level sessions are not back-stack
 * entries but values in the root [Chat] entry's `SavedStateHandle`, which `ChatViewModel`
 * shares (`savedState.getStateFlow<String?>(KEY_SESSION_ID, null)`). A pushed [ChildChat]
 * carries its id as its own route argument under the same key, so the chat reads one key in
 * both cases. [KEY_SHEET] is a one-shot request: the chat opens the sheet and writes null.
 */
object ChatSelection {
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_SHEET = "sheet"

    fun select(handle: SavedStateHandle, sessionId: String?, sheet: ChatSheet? = null) {
        handle[KEY_SESSION_ID] = sessionId
        handle[KEY_SHEET] = sheet?.wire
    }

    fun selected(handle: SavedStateHandle): String? = handle[KEY_SESSION_ID]

    fun selectedFlow(handle: SavedStateHandle): StateFlow<String?> = handle.getStateFlow(KEY_SESSION_ID, null)

    /** Reads and clears the sheet request. */
    fun takeSheet(handle: SavedStateHandle): ChatSheet? = ChatSheet.fromWire(handle.remove<String>(KEY_SHEET))
}

/** What the shell does for the navigator beyond the NavHost (implemented by `AppShell`). */
interface ShellHost {
    val layout: ShellLayout

    fun setDrawerOpen(open: Boolean)

    fun toggleListPane()

    fun openKernelStatus(diagnostics: Boolean)

    /** Pushes a root-level screen over the shell (pushed repair, reliability, setup/connect). */
    fun openRoot(route: RootRoute)

    fun showSupporting(content: SupportingContent)

    /** "New session" while Home is already shown: focus the composer (HC §2.9). */
    fun requestComposerFocus()

    /**
     * Ctrl+K / the search action while the expanded list pane is on screen: focuses its docked
     * search bar and returns true, or returns false when no docked bar is shown (none plugged
     * in, or the list pane is collapsed) so the navigator opens the Search route instead.
     */
    fun requestDockedSearch(): Boolean
}

/**
 * Every navigation the shell's screens perform, in one place (HC §2.9, SS §2.8–2.9,
 * CR §0.2, SP §0.2). Screens get it from [LocalShellNavigator]; they never call the
 * `NavHostController` themselves for these flows.
 */
@Stable
class ShellNavigator(val nav: NavHostController, private val host: ShellHost) {
    val layout: ShellLayout get() = host.layout

    /** The root [Chat] entry (always at the bottom of the shell stack once the graph is set). */
    val chatRootEntry: NavBackStackEntry?
        get() = runCatching { nav.getBackStackEntry<Chat>() }.getOrNull()

    /** Home: closes the drawer, pops to the chat root and clears the selection. */
    fun openHome() {
        host.setDrawerOpen(false)
        popToChatRoot()
        chatRootEntry?.let { ChatSelection.select(it.savedStateHandle, null) }
    }

    /** "New session" (drawer, rail FAB, header `add`, Ctrl+N): Home, or focus the composer when Home is already shown. */
    fun newSession() {
        val atHome = currentRoute() is Chat && chatRootEntry?.let { ChatSelection.selected(it.savedStateHandle) } == null
        if (atHome) {
            host.setDrawerOpen(false)
            host.requestComposerFocus()
        } else {
            openHome()
        }
    }

    /**
     * Opens a top-level session (drawer, list pane, Sessions screen, search, notification,
     * fork result): pop to the chat root, then select it, with an optional [sheet] (CA §0.6).
     */
    fun openSession(sessionId: String, sheet: ChatSheet? = null) {
        host.setDrawerOpen(false)
        popToChatRoot()
        chatRootEntry?.let { ChatSelection.select(it.savedStateHandle, sessionId, sheet) }
    }

    /** A subagent session (agents roster, child chips): pushed above the current chat. */
    fun openChildSession(sessionId: String) {
        nav.navigate(ChildChat(sessionId))
    }

    /**
     * A drawer/rail destination (DS §5.2, CR §0.2): `popUpTo(chat root) { saveState }`,
     * `launchSingleTop`, `restoreState`, so each destination keeps its own pushed pages.
     */
    fun open(destination: TopLevelDestination) {
        host.setDrawerOpen(false)
        val from = nav.currentBackStackEntry?.id
        nav.navigate(Routes.routeOf(destination)) {
            popUpTo<Chat> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        val to = nav.currentBackStackEntry?.id
        lastTopLevelSwitch = if (from != null && to != null && from != to) from to to else null
    }

    /**
     * The (old top, new top) entry ids of the last [open]. Either side may be a pushed page
     * (a destination left or restored with its own pages on top), so the route kinds alone
     * would pick a slide; ids are unique per entry, so the pair matches only that switch.
     */
    private var lastTopLevelSwitch: Pair<String, String>? = null

    /**
     * The transition from the entry on top before a navigation ([initial]) to the one on top
     * after it ([target]). Every rail/drawer switch is a fade-through (DS §6, docs/07 §6.2),
     * whatever the kinds of the two entries; anything else follows [NavTransitions.shellKind],
     * with a pop mirroring the push it undoes.
     */
    fun transitionKind(initial: NavBackStackEntry, target: NavBackStackEntry, pop: Boolean): TransitionKind = when {
        lastTopLevelSwitch == (initial.id to target.id) -> TransitionKind.FadeThrough
        pop -> NavTransitions.shellKind(target.routeKind(), initial.routeKind())
        else -> NavTransitions.shellKind(initial.routeKind(), target.routeKind())
    }

    /** A pushed route (shared axis X) or an editor (shared axis Y / dialog). */
    fun push(route: ShellRoute) {
        when (route) {
            is Chat -> openSession(route.sessionId ?: return openHome(), route.chatSheet)
            is ChatChanges -> openChanges(route.sessionId)
            is ChatTool -> openTool(route.sessionId, route.toolCallId)
            else -> nav.navigate(route)
        }
    }

    /** Changes: the chat's supporting pane at ≥ 1200 × ≥ 480, else the pushed route (CT A.3). */
    fun openChanges(sessionId: String) = openSupporting(SupportingContent.Changes(sessionId))

    /** Tool detail: the chat's supporting pane at ≥ 1200 × ≥ 480, else the pushed route (CT B.3). */
    fun openTool(sessionId: String, toolCallId: String) = openSupporting(SupportingContent.Tool(sessionId, toolCallId))

    private fun openSupporting(content: SupportingContent) {
        if (layout.supportingPaneAvailable && currentRoute().let { it is Chat || it is ChildChat }) {
            host.showSupporting(content)
        } else {
            nav.navigate(content.route)
        }
    }

    /** The list header "Sessions" (S2): closes the drawer and pushes the Sessions screen. */
    fun openSessions() {
        host.setDrawerOpen(false)
        nav.navigate(Sessions) { launchSingleTop = true }
    }

    /**
     * Session search (S3, Ctrl+K / Ctrl+P): the docked bar when the expanded list pane is on
     * screen and shows a docked bar, otherwise the full-screen search route.
     */
    fun openSearch() {
        host.setDrawerOpen(false)
        val docked = layout.showsListPane && area() == TopLevelDestination.Chats && host.requestDockedSearch()
        if (!docked) {
            nav.navigate(Search) { launchSingleTop = true }
        }
    }

    /**
     * A pushed page's back arrow (the same pop as system back). Never pops the chat root: a
     * back-arrow tap that lands during a system-back pop transition (or a double tap) would
     * otherwise pop the last entry too and leave the shell's NavHost empty (a blank screen).
     */
    fun back(): Boolean = nav.previousBackStackEntry != null && nav.popBackStack()

    fun openDrawer() = host.setDrawerOpen(true)

    fun closeDrawer() = host.setDrawerOpen(false)

    /** The rail `menu` on expanded windows: collapse / expand the sessions list pane (SS §2.9.1). */
    fun toggleListPane() = host.toggleListPane()

    /** The Kernel status sheet (KS E.3); [diagnostics] scrolls it to E.6. */
    fun openKernelStatus(diagnostics: Boolean = false) = host.openKernelStatus(diagnostics)

    /** A root screen over the shell: `kernel/repair?pushed=true`, `kernel/reliability`, `setup/connect`. */
    fun openRoot(route: RootRoute) = host.openRoot(route)

    /** The current shell route, decoded. */
    fun currentRoute(): ShellRoute? = nav.currentBackStackEntry?.shellRoute()

    /** The current area (drawer/rail selection). */
    fun area(): TopLevelDestination = Routes.areaOf(nav.backStackFlow().value.mapNotNull { it.shellRoute() })

    private fun popToChatRoot() {
        if (chatRootEntry != null) nav.popBackStack<Chat>(inclusive = false)
    }
}

val LocalShellNavigator = staticCompositionLocalOf<ShellNavigator?> { null }

/** Decodes a shell back-stack entry into its route (null for the graph entry or unknown routes). */
fun NavBackStackEntry.shellRoute(): ShellRoute? {
    val d = destination
    return when {
        d.hasRoute<Chat>() -> toRoute<Chat>()
        d.hasRoute<ChildChat>() -> toRoute<ChildChat>()
        d.hasRoute<ChatChanges>() -> toRoute<ChatChanges>()
        d.hasRoute<ChatTool>() -> toRoute<ChatTool>()
        d.hasRoute<Sessions>() -> Sessions
        d.hasRoute<Search>() -> Search
        d.hasRoute<Schedules>() -> toRoute<Schedules>()
        d.hasRoute<Customize>() -> toRoute<Customize>()
        d.hasRoute<Settings>() -> toRoute<Settings>()
        d.hasRoute<SettingsAppearance>() -> SettingsAppearance
        d.hasRoute<SettingsNotifications>() -> SettingsNotifications
        d.hasRoute<SettingsProviders>() -> SettingsProviders
        d.hasRoute<SettingsProvider>() -> toRoute<SettingsProvider>()
        d.hasRoute<SettingsProvidersAdd>() -> SettingsProvidersAdd
        d.hasRoute<KernelSettings>() -> KernelSettings
        d.hasRoute<Account>() -> toRoute<Account>()
        d.hasRoute<About>() -> About
        d.hasRoute<Marketplace>() -> toRoute<Marketplace>()
        d.hasRoute<MarketplaceEntryRoute>() -> toRoute<MarketplaceEntryRoute>()
        d.hasRoute<ScheduleDetails>() -> toRoute<ScheduleDetails>()
        d.hasRoute<McpEdit>() -> toRoute<McpEdit>()
        d.hasRoute<ScheduleEdit>() -> toRoute<ScheduleEdit>()
        d.hasRoute<AboutDiagnostics>() -> AboutDiagnostics
        d.hasRoute<WorkspaceBrowse>() -> toRoute<WorkspaceBrowse>()
        d.hasRoute<AboutLicenses>() -> AboutLicenses
        d.hasRoute<DebugKernelProbe>() -> DebugKernelProbe
        else -> null
    }
}

/** Decodes a root back-stack entry into its route. */
fun NavBackStackEntry.rootRoute(): RootRoute? {
    val d = destination
    return when {
        d.hasRoute<OnboardingWelcome>() -> OnboardingWelcome
        d.hasRoute<OnboardingKernel>() -> OnboardingKernel
        d.hasRoute<OnboardingReliability>() -> OnboardingReliability
        d.hasRoute<OnboardingConnect>() -> OnboardingConnect
        d.hasRoute<OnboardingDone>() -> OnboardingDone
        d.hasRoute<KernelInstallTermux>() -> KernelInstallTermux
        d.hasRoute<KernelRepair>() -> toRoute<KernelRepair>()
        d.hasRoute<KernelReliability>() -> KernelReliability
        d.hasRoute<SetupConnect>() -> SetupConnect
        d.hasRoute<SetupDone>() -> SetupDone
        d.hasRoute<Shell>() -> Shell
        d.hasRoute<OnboardingGraph>() -> OnboardingGraph
        else -> null
    }
}
