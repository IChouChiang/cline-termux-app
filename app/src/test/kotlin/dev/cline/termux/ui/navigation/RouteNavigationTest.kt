package dev.cline.termux.ui.navigation

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.debug.DebugScreens
import dev.cline.termux.ui.theme.AppearancePrefs
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Navigation's own (de)serialisation of every route through the real graphs: each route is
 * navigated to and decoded back from its back-stack entry ([shellRoute] / [rootRoute]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteNavigationTest {
    /**
     * Navigating writes Compose snapshot state (the navigators' own). Once an earlier Compose test
     * in this sandbox has started Compose's global snapshot manager, each such write posts its
     * dispatch to the main looper; left there, the next test's looper reset drops it and Compose's
     * main dispatcher waits for it for good, so every later Compose test in the sandbox stops
     * recomposing. Run it before the test ends.
     */
    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun controller(): NavHostController = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        // Entries get a lifecycle and a SavedStateHandle only with an owner and a store, as in NavHost.
        setLifecycleOwner(ResumedOwner())
        setViewModelStore(ViewModelStore())
    }

    private class ResumedOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** The graph members are lazy; building the destinations never touches them. */
    private val graph by lazy { dev.cline.termux.AppGraph(ApplicationProvider.getApplicationContext()) }

    private fun shellGraph(editorsAsDialogs: Boolean): NavHostController {
        val nav = controller()
        val navigator = ShellNavigator(nav, NoopShellHost)
        nav.graph = nav.createGraph(startDestination = Chat()) { shellDestinations(navigator, graph, editorsAsDialogs) }
        return nav
    }

    @Test
    fun `every shell route decodes back from its back stack entry`() {
        for (asDialogs in listOf(false, true)) {
            val nav = shellGraph(asDialogs)
            assertEquals(Chat(), nav.currentBackStackEntry?.shellRoute())
            // Release builds register no developer screens (the src/release DebugScreens twin).
            assertEquals(DebugScreens.available, nav.graph.any { it.hasRoute<DebugKernelProbe>() })
            for (route in RouteSamples.shell.filter { DebugScreens.available || it != DebugKernelProbe }) {
                nav.navigate(route)
                assertEquals("$route (dialogs=$asDialogs)", route, nav.currentBackStackEntry?.shellRoute())
            }
        }
    }

    @Test
    fun `every root route decodes back from its back stack entry`() {
        val nav = controller()
        val root = RootViewModel(
            savedState = SavedStateHandle(),
            onboarding = object : OnboardingStore {
                override suspend fun completedAt(): Long? = 1L
                override suspend fun complete() = Unit
                override suspend fun reset() = Unit
            },
            appearance = emptyFlow<AppearancePrefs>(),
            termuxPresence = { TermuxPresence.Installed(1_000, "0.118.3") },
            addKnownSession = {},
        )
        val navigator = RootNavigator(nav, root)
        // RootNavHost registers Shell itself (it hosts the shell's ViewModel); the rest is shared.
        nav.graph = nav.createGraph(startDestination = Shell) {
            composable<Shell> {}
            rootDestinations(navigator, graph)
        }
        assertEquals(Shell, nav.currentBackStackEntry?.rootRoute())
        for (route in RouteSamples.root.filter { it != OnboardingGraph }) {
            nav.navigate(route)
            assertEquals(route.toString(), route, nav.currentBackStackEntry?.rootRoute())
        }
        // The onboarding graph lands on its start destination, Welcome.
        nav.navigate(OnboardingGraph)
        assertEquals(OnboardingWelcome, nav.currentBackStackEntry?.rootRoute())
    }

    @Test
    fun `the chat root keeps the selection in its saved state`() {
        val nav = shellGraph(editorsAsDialogs = false)
        val rootEntry = ShellNavigator(nav, NoopShellHost).chatRootEntry!!.savedStateHandle
        assertEquals(null, ChatSelection.selected(rootEntry))
        ChatSelection.select(rootEntry, "s-1", ChatSheet.Approval)
        assertEquals("s-1", ChatSelection.selected(rootEntry))
        assertEquals(ChatSheet.Approval, ChatSelection.takeSheet(rootEntry))
        assertEquals(null, ChatSelection.takeSheet(rootEntry))
    }

    @Test
    fun `search opens the docked bar on the expanded list pane, else the Search route`() {
        val expanded = ShellLayout(1280, 800)
        // A docked bar is plugged in and showing: it takes focus, nothing is pushed.
        val docked = RecordingShellHost(expanded, docked = true)
        val nav1 = shellGraph(docked)
        ShellNavigator(nav1, docked).openSearch()
        assertEquals(1, docked.dockedRequests)
        assertEquals(Chat(), nav1.currentBackStackEntry?.shellRoute())

        // No docked bar (none plugged in, or the list pane collapsed): the Search route.
        val none = RecordingShellHost(expanded, docked = false)
        val nav2 = shellGraph(none)
        ShellNavigator(nav2, none).openSearch()
        assertEquals(1, none.dockedRequests)
        assertEquals(Search, nav2.currentBackStackEntry?.shellRoute())

        // No list pane at all (phone): the Search route without asking for a docked bar.
        val phone = RecordingShellHost(ShellLayout.PhonePortrait, docked = true)
        val nav3 = shellGraph(phone)
        ShellNavigator(nav3, phone).openSearch()
        assertEquals(0, phone.dockedRequests)
        assertEquals(Search, nav3.currentBackStackEntry?.shellRoute())
    }

    @Test
    fun `changes and tool detail use the supporting pane only on large windows`() {
        val large = RecordingShellHost(ShellLayout(1280, 800), docked = false)
        val nav1 = shellGraph(large)
        ShellNavigator(nav1, large).openChanges("s-1")
        ShellNavigator(nav1, large).openTool("s-1", "t-1")
        assertEquals(listOf(SupportingContent.Changes("s-1"), SupportingContent.Tool("s-1", "t-1")), large.supporting)
        assertEquals(Chat(), nav1.currentBackStackEntry?.shellRoute())

        val phone = RecordingShellHost(ShellLayout.PhonePortrait, docked = false)
        val nav2 = shellGraph(phone)
        ShellNavigator(nav2, phone).openChanges("s-1")
        assertEquals(emptyList<SupportingContent>(), phone.supporting)
        assertEquals(ChatChanges("s-1"), nav2.currentBackStackEntry?.shellRoute())
    }

    @Test
    fun `new session at Home asks the composer for focus, elsewhere goes Home`() {
        val host = RecordingShellHost(ShellLayout.PhonePortrait, docked = false)
        val nav = shellGraph(host)
        val navigator = ShellNavigator(nav, host)
        navigator.newSession()
        assertEquals(1, host.focusRequests)

        navigator.openSession("s-1")
        navigator.newSession()
        assertEquals(1, host.focusRequests)
        assertEquals(null, ChatSelection.selected(navigator.chatRootEntry!!.savedStateHandle))
    }

    @Test
    fun `back never pops the chat root, even when called twice in a row`() {
        val nav = shellGraph(NoopShellHost)
        val navigator = ShellNavigator(nav, NoopShellHost)
        assertFalse(navigator.back())
        assertEquals(Chat(), nav.currentBackStackEntry?.shellRoute())

        navigator.openSessions()
        assertEquals(Sessions, nav.currentBackStackEntry?.shellRoute())
        assertTrue(navigator.back())
        assertFalse(navigator.back())
        assertEquals(Chat(), nav.currentBackStackEntry?.shellRoute())
    }

    @Test
    fun `rail and drawer switches fade through even with pushed pages on either side`() {
        val nav = shellGraph(NoopShellHost)
        val navigator = ShellNavigator(nav, NoopShellHost)

        // Appearance (pushed in Settings) → Schedules: Pushed → TopLevel, still a fade-through.
        navigator.open(TopLevelDestination.Settings)
        navigator.push(SettingsAppearance)
        val appearance = nav.currentBackStackEntry!!
        navigator.open(TopLevelDestination.Schedules)
        val schedules = nav.currentBackStackEntry!!
        assertEquals(Schedules(), schedules.shellRoute())
        assertEquals(TransitionKind.FadeThrough, navigator.transitionKind(appearance, schedules, pop = false))
        // Whichever side of the NavHost runs it (a singleTop switch can be reported as a pop).
        assertEquals(TransitionKind.FadeThrough, navigator.transitionKind(appearance, schedules, pop = true))

        // A push inside the area slides on X.
        navigator.push(ScheduleDetails("nightly build"))
        val details = nav.currentBackStackEntry!!
        assertEquals(TransitionKind.SharedAxisX, navigator.transitionKind(schedules, details, pop = false))

        // Back to Settings restores Appearance on top: TopLevel side is pushed, still a fade-through.
        navigator.open(TopLevelDestination.Settings)
        val restored = nav.currentBackStackEntry!!
        assertEquals(SettingsAppearance, restored.shellRoute())
        assertEquals(TransitionKind.FadeThrough, navigator.transitionKind(details, restored, pop = false))

        // Its back arrow is an ordinary pop: shared axis X, mirroring the push.
        assertTrue(navigator.back())
        val settings = nav.currentBackStackEntry!!
        assertEquals(Settings(), settings.shellRoute())
        assertEquals(TransitionKind.SharedAxisX, navigator.transitionKind(restored, settings, pop = true))

        // Two top-level entries outside a switch keep the route-kind rule.
        assertEquals(TransitionKind.FadeThrough, navigator.transitionKind(settings, navigator.chatRootEntry!!, pop = true))
    }

    private fun shellGraph(host: ShellHost): NavHostController {
        val nav = controller()
        nav.graph = nav.createGraph(startDestination = Chat()) { shellDestinations(ShellNavigator(nav, host), graph, editorsAsDialogs = false) }
        return nav
    }

    private class RecordingShellHost(override val layout: ShellLayout, private val docked: Boolean) : ShellHost {
        var dockedRequests = 0
        var focusRequests = 0
        val supporting = mutableListOf<SupportingContent>()
        override fun setDrawerOpen(open: Boolean) = Unit
        override fun toggleListPane() = Unit
        override fun openKernelStatus(diagnostics: Boolean) = Unit
        override fun openRoot(route: RootRoute) = Unit
        override fun showSupporting(content: SupportingContent) {
            supporting += content
        }
        override fun requestComposerFocus() {
            focusRequests++
        }
        override fun requestDockedSearch(): Boolean {
            dockedRequests++
            return docked
        }
    }

    private object NoopShellHost : ShellHost {
        override val layout: ShellLayout = ShellLayout.PhonePortrait
        override fun setDrawerOpen(open: Boolean) = Unit
        override fun toggleListPane() = Unit
        override fun openKernelStatus(diagnostics: Boolean) = Unit
        override fun openRoot(route: RootRoute) = Unit
        override fun showSupporting(content: SupportingContent) = Unit
        override fun requestComposerFocus() = Unit
        override fun requestDockedSearch(): Boolean = false
    }
}
