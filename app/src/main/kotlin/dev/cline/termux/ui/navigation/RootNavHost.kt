package dev.cline.termux.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.cline.termux.AppGraph
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.ui.AppShell
import dev.cline.termux.ui.AppShellViewModel
import dev.cline.termux.ui.ShellSlots
import dev.cline.termux.ui.WhatsNewTrigger
import dev.cline.termux.ui.kernel.kernelGraph
import dev.cline.termux.ui.kernel.onboardingReliabilityDestination
import dev.cline.termux.ui.onboarding.onboardingGraph
import dev.cline.termux.ui.theme.AppearancePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The onboarding facts the root decides on (KS §0.2, §0.8; DataStore `onboarding.completedAt`). */
interface OnboardingStore {
    /** `onboarding.completedAt`; throws when the DataStore cannot be read. */
    suspend fun completedAt(): Long?

    /** Writes `onboarding.completedAt = now` (KS §1 "Finishing … (1)"). */
    suspend fun complete()

    /** Settings → "New user experience" → "Replay": clears `completedAt`. */
    suspend fun reset()
}

/**
 * The activity-scoped root state (docs/07 §6.4, KS §0.2): the one DataStore read that picks
 * the start destination and the first theme (the splash stays up only for it), and the deep
 * link inbox fed by `MainActivity.onCreate` / `onNewIntent`.
 */
class RootViewModel(
    private val savedState: SavedStateHandle,
    private val onboarding: OnboardingStore,
    appearance: Flow<AppearancePrefs>,
    private val termuxPresence: () -> TermuxPresence,
    private val addKnownSession: suspend (String) -> Unit,
    private val whatsNew: WhatsNewTrigger = WhatsNewTrigger.None,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    /**
     * Builds the graph members whose first use blocks (docs/07 §8: the Hub client's client id),
     * off Main and behind the splash, so no composable builds them on Main.
     */
    private val warmUp: suspend () -> Unit = {},
) : ViewModel() {
    private val _start = MutableStateFlow<RootRoute?>(null)

    /** `onboarding` | `kernel/install-termux` | `shell`; null until the read finished. */
    val start: StateFlow<RootRoute?> = _start.asStateFlow()

    private val _appearance = MutableStateFlow<AppearancePrefs?>(null)

    /** The theme choice; null until the first read (the splash covers that instant). */
    val appearance: StateFlow<AppearancePrefs?> = _appearance.asStateFlow()

    private val _deepLink = MutableStateFlow(DeepLinks.decode(savedState[KEY_DEEP_LINK]))

    /** A link the shell has not taken yet. */
    val deepLink: StateFlow<DeepLink?> = _deepLink.asStateFlow()

    /** `installSplashScreen().setKeepOnScreenCondition { !root.ready }` (docs/07 §6.4). Thread-safe. */
    val ready: Boolean get() = _start.value != null && _appearance.value != null

    private var onboardingComplete: Boolean? = null

    init {
        viewModelScope.launch {
            appearance.catch { emit(AppearancePrefs()) }.collect { _appearance.value = it }
        }
        viewModelScope.launch {
            // An unreadable or hung DataStore never traps the splash (desktop lib/onboarding.ts:
            // "never trap the user in the first-run flow"); the defaults apply.
            delay(readTimeoutMs)
            if (_appearance.value == null) _appearance.value = AppearancePrefs()
        }
        // The read keeps running past the splash timeout so a slow store can still correct the start.
        val read = viewModelScope.async(io) {
            try {
                ReadResult(onboarding.completedAt())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReadResult(null, failed = true)
            }
        }
        val warm = viewModelScope.async(io) {
            try {
                warmUp()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A member that fails to build fails again at its first real use, where it is handled.
            }
        }
        viewModelScope.launch {
            // A hung read behaves like an unreadable store (shell or Install Termux), so an
            // onboarded user is never sent through onboarding and keeps a pending deep link.
            val inTime = withTimeoutOrNull(readTimeoutMs) { read.await() }
            withTimeoutOrNull(readTimeoutMs) { warm.await() }
            val presence = runCatching { termuxPresence() }.getOrDefault(TermuxPresence.Missing)
            applyStart(startFor(inTime?.completedAt, inTime?.failed ?: true, presence))
            if (inTime == null) {
                // The read finished late: a store that really has no `completedAt` is a first run.
                val late = read.await()
                if (late.completedAt == null && !late.failed) applyStart(OnboardingGraph)
            }
        }
    }

    private fun applyStart(route: RootRoute) {
        onboardingComplete = route != OnboardingGraph
        if (route == OnboardingGraph) dropDeepLink()
        _start.value = route
    }

    private data class ReadResult(val completedAt: Long?, val failed: Boolean = false)

    /**
     * A deep link from the launching intent. Dropped while onboarding is incomplete (docs/07
     * §6.4); otherwise the session joins `KnownSessions` and the link waits for the shell.
     */
    fun onDeepLink(link: DeepLink?) {
        if (link == null || onboardingComplete == false) return
        savedState[KEY_DEEP_LINK] = DeepLinks.encode(link)
        _deepLink.value = link
        if (link is DeepLink.Session) {
            viewModelScope.launch { runCatching { withContext(io) { addKnownSession(link.sessionId) } } }
        }
    }

    /** The shell took [link]. */
    fun clearDeepLink(link: DeepLink) {
        if (_deepLink.value == link) dropDeepLink()
    }

    private fun dropDeepLink() {
        savedState.remove<String>(KEY_DEEP_LINK)
        _deepLink.value = null
    }

    /**
     * KS §1 "Finishing": (1) `onboarding.completedAt`, (2) the current What's new entry is
     * marked seen (first-run users see onboarding instead). The navigation (3) is the caller's.
     */
    fun completeOnboarding(): Job {
        onboardingComplete = true
        return viewModelScope.launch {
            withContext(io) {
                runCatching { onboarding.complete() }
                runCatching { whatsNew.pendingId()?.let { whatsNew.markSeen(it) } }
            }
        }
    }

    /** Settings "Replay": the next start shows onboarding again. */
    fun resetOnboarding(): Job {
        onboardingComplete = false
        dropDeepLink()
        return viewModelScope.launch { withContext(io) { runCatching { onboarding.reset() } } }
    }

    companion object {
        private const val KEY_DEEP_LINK = "root.pendingDeepLink"
        const val READ_TIMEOUT_MS = 3_000L

        /**
         * KS §0.2: no `completedAt` → onboarding; otherwise Termux missing or disabled → Install
         * Termux; otherwise the shell. An unreadable DataStore (the read threw or timed out)
         * counts as onboarded.
         */
        fun startFor(completedAt: Long?, readFailed: Boolean, termux: TermuxPresence): RootRoute = when {
            completedAt == null && !readFailed -> OnboardingGraph
            termux is TermuxPresence.Missing || termux is TermuxPresence.Disabled -> KernelInstallTermux
            else -> Shell
        }

        fun factory(graph: AppGraph, whatsNew: WhatsNewTrigger = WhatsNewTrigger.None): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val prefs = graph.prefs
                RootViewModel(
                    savedState = createSavedStateHandle(),
                    onboarding = object : OnboardingStore {
                        override suspend fun completedAt(): Long? = prefs.onboardingCompletedAt()
                        override suspend fun complete() {
                            prefs.setOnboardingCompleted()
                        }
                        override suspend fun reset() {
                            prefs.setOnboardingCompleted(null)
                        }
                    },
                    appearance = prefs.appearance,
                    termuxPresence = { graph.termux.isTermuxInstalled() },
                    addKnownSession = { graph.knownSessions.add(it) },
                    whatsNew = whatsNew,
                    warmUp = { graph.warmUp() },
                )
            }
        }
    }
}

/**
 * Root-level navigation (KS §0.2, A.3, B.3, C.3, D.3) for the onboarding, kernel and setup
 * screens and for the shell's own replacement rules. Screens get it from [LocalRootNavigator].
 */
@Stable
class RootNavigator(val nav: NavHostController, private val root: RootViewModel) {
    /** A screen pushed on top (A → B, pushed repair, reliability, setup/connect → setup/done, onboarding steps). */
    fun push(route: RootRoute) {
        nav.navigate(route) { launchSingleTop = true }
    }

    /** Pops one screen; never the last one, which would leave the root NavHost empty (see [ShellNavigator.back]). */
    fun back(): Boolean = nav.previousBackStackEntry != null && nav.popBackStack()

    /**
     * Done → "Start building" or Skip on the first run (KS §1): writes `completedAt`, marks
     * What's new seen, clears the onboarding stack and opens the shell at Home.
     */
    fun finishOnboarding() {
        root.completeOnboarding()
        nav.navigate(Shell) {
            popUpTo<OnboardingGraph> { inclusive = true }
            launchSingleTop = true
        }
    }

    /**
     * The coordinator reports a blocked prerequisite after onboarding: [route] replaces the
     * shell (blocking repair, Install Termux). The shell's stack is saved so [returnToShell]
     * brings the user back to where they were (KS A.3 "the destination saved before").
     */
    fun replaceShell(route: RootRoute) {
        nav.navigate(route) {
            popUpTo<Shell> {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
        }
    }

    /** Blocking repair / Install Termux succeeded: back to the shell, its saved stack restored. */
    fun returnToShell() {
        val current = nav.currentBackStackEntry ?: return
        nav.navigate(Shell) {
            popUpTo(current.destination.id) { inclusive = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** Settings → "New user experience" → "Replay" (KS A.3): clears `completedAt` and opens A0. */
    fun replayOnboarding() {
        root.resetOnboarding()
        nav.navigate(OnboardingGraph) {
            popUpTo<Shell> { inclusive = true }
            launchSingleTop = true
        }
    }
}

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator?> { null }

/**
 * The app's root NavHost (docs/07 §6.2, KS §0.2): the onboarding graph, the kernel and setup
 * screens, and the shell. Renders nothing until [RootViewModel.start] is known (the splash is
 * still up, and the graph's blocking members were built off Main behind it).
 */
@Composable
fun RootNavHost(
    root: RootViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    shellSlots: ShellSlots = dev.cline.termux.ui.AppShellSlots,
    whatsNew: WhatsNewTrigger = WhatsNewTrigger.None,
) {
    val start by root.start.collectAsStateWithLifecycle()
    val startRoute = start ?: return
    val navigator = remember(navController, root) { RootNavigator(navController, root) }
    val graph = LocalContext.current.appGraph
    val slidePx = with(LocalDensity.current) { NavTransitions.SLIDE_DP.dp.roundToPx() }
    CompositionLocalProvider(LocalRootNavigator provides navigator) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = modifier,
            enterTransition = {
                with(NavTransitions) { enter(rootKind(initialState.rootRoute(), targetState.rootRoute()), forward = true, slidePx) }
            },
            exitTransition = {
                with(NavTransitions) { exit(rootKind(initialState.rootRoute(), targetState.rootRoute()), forward = true, slidePx) }
            },
            popEnterTransition = {
                with(NavTransitions) { enter(rootKind(targetState.rootRoute(), initialState.rootRoute()), forward = false, slidePx) }
            },
            popExitTransition = {
                with(NavTransitions) { exit(rootKind(targetState.rootRoute(), initialState.rootRoute()), forward = false, slidePx) }
            },
        ) {
            rootDestinations(navigator, graph)
            composable<Shell> { ShellDestination(root, navigator, shellSlots, whatsNew) }
        }
    }
}

/**
 * The onboarding graph (KS §0.2: welcome → kernel → reliability → connect → done) and the
 * root-level kernel and setup screens: Install Termux, Kernel repair, the standalone
 * reliability checklist, setup/connect and setup/done.
 */
fun NavGraphBuilder.rootDestinations(navigator: RootNavigator, graph: AppGraph) {
    onboardingGraph(navigator, graph, reliability = { onboardingReliabilityDestination(navigator, graph) })
    kernelGraph(navigator, graph)
}

/**
 * The shell destination: the shell's ViewModel (scoped to this entry), the deep-link hand-off
 * from the root inbox, the replacement rule (KS A.3, B.3) and the [AppShell].
 */
@Composable
private fun ShellDestination(root: RootViewModel, navigator: RootNavigator, slots: ShellSlots, whatsNew: WhatsNewTrigger) {
    val graph = LocalContext.current.appGraph
    val shell: AppShellViewModel = viewModel(factory = AppShellViewModel.factory(graph, whatsNew))
    val link by root.deepLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        link?.let {
            shell.offerDeepLink(it)
            root.clearDeepLink(it)
        }
    }
    val replacement by shell.replacement.collectAsStateWithLifecycle()
    LaunchedEffect(replacement) { replacement?.let(navigator::replaceShell) }
    AppShell(viewModel = shell, onOpenRoot = navigator::push, slots = slots)
}
