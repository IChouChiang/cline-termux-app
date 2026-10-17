package dev.cline.termux.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.ui.kernel.KernelRepairScreen
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.OnboardingConnect
import dev.cline.termux.ui.navigation.OnboardingDone
import dev.cline.termux.ui.navigation.OnboardingGraph
import dev.cline.termux.ui.navigation.OnboardingKernel
import dev.cline.termux.ui.navigation.OnboardingReliability
import dev.cline.termux.ui.navigation.OnboardingWelcome
import dev.cline.termux.ui.navigation.RootNavigator
import dev.cline.termux.ui.navigation.SetupConnect
import dev.cline.termux.ui.navigation.SetupDone
import kotlinx.coroutines.launch

/**
 * The onboarding destinations of `RootNavHost` (docs/07 §6.2, onboarding-kernel.md §0.2, §1):
 * the `onboarding` graph (`welcome → kernel → [reliability] → connect → done`), and the
 * root-level `kernel/install-termux`, `kernel/repair?focus=&pushed=`, `setup/connect` and
 * `setup/done`.
 *
 * [reliability] registers `composable<OnboardingReliability>` inside the onboarding graph (the
 * One UI checklist, KS D, is its own package). When it is null, "Continue" on Kernel setup goes
 * straight to Connect. `kernel/reliability` (standalone D) is registered by that package.
 */
fun NavGraphBuilder.onboardingGraph(
    nav: RootNavigator,
    graph: AppGraph,
    reliability: (NavGraphBuilder.() -> Unit)? = null,
) {
    val reliabilityAvailable = reliability != null
    navigation<OnboardingGraph>(startDestination = OnboardingWelcome) {
        composable<OnboardingWelcome> {
            val termuxInstalled = remember { graph.termux.isTermuxInstalled() is dev.cline.termux.core.kernel.termux.TermuxPresence.Installed }
            WelcomeScreen(termuxInstalled = termuxInstalled, onGetStarted = { nav.push(OnboardingKernel) })
        }
        composable<OnboardingKernel> {
            val vm: KernelSetupViewModel = viewModel(factory = kernelSetupFactory(graph, LocalContext.current, SetupVariant.FirstRun, reliabilityAvailable))
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        KernelSetupEvent.ToReliability -> nav.push(OnboardingReliability)
                        KernelSetupEvent.ToConnect -> nav.push(OnboardingConnect)
                        KernelSetupEvent.ToInstallTermux -> nav.push(KernelInstallTermux)
                        is KernelSetupEvent.RepairResolved -> Unit
                    }
                }
            }
            KernelSetupScreen(vm, onBack = { nav.back() })
        }
        reliability?.invoke(this)
        composable<OnboardingConnect> { ConnectRoute(nav, graph, firstRun = true) }
        composable<OnboardingDone> { DoneRoute(nav, graph, firstRun = true) }
    }
    composable<KernelInstallTermux> {
        val pushed = remember { nav.nav.previousBackStackEntry != null }
        val vm: InstallTermuxViewModel = viewModel(
            factory = viewModelFactory { initializer { InstallTermuxViewModel(SetupKernel.of(graph), pushedFromSetup = pushed) } },
        )
        LaunchedEffect(vm) {
            vm.events.collect {
                // B.3: back to A (or the repair screen) when pushed, else back to the shell.
                if (pushed) nav.back() else nav.returnToShell()
            }
        }
        InstallTermuxScreen(vm, onBack = if (pushed) ({ nav.back() }) else null)
    }
    composable<KernelRepair> { entry -> KernelRepairScreen(entry.toRoute<KernelRepair>(), nav, graph) }
    composable<SetupConnect> { ConnectRoute(nav, graph, firstRun = false) }
    composable<SetupDone> { DoneRoute(nav, graph, firstRun = false) }
}

/** The checklist's ViewModel factory (first run and both repair variants). */
fun kernelSetupFactory(graph: AppGraph, context: Context, variant: SetupVariant, reliabilityAvailable: Boolean) = viewModelFactory {
    initializer {
        KernelSetupViewModel(
            kernel = SetupKernel.of(graph),
            diagnosticsUseCase = graph.kernelDiagnostics,
            prefs = OnboardingPrefs.of(graph.prefs),
            reliability = ReliabilityProbe.of(context.applicationContext),
            savedState = createSavedStateHandle(),
            variant = variant,
            reliabilityAvailable = reliabilityAvailable,
        )
    }
}

/** The SavedStateHandle key Connect leaves for Done (survives process death with the back stack). */
internal const val KEY_CONNECTION = "onboarding.connection"

@Composable
private fun ConnectRoute(nav: RootNavigator, graph: AppGraph, firstRun: Boolean) {
    val context = LocalContext.current
    val vm: ConnectModelViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ConnectModelViewModel(
                    kernel = SetupKernel.of(graph),
                    setup = ProviderSetup.of(graph, context.applicationContext),
                    signIn = ClineSignIn.of(graph.signIn),
                )
            }
        },
    )
    ConnectModelScreen(
        viewModel = vm,
        onBack = { nav.back() },
        onSkip = { if (firstRun) finishOnboarding(nav, graph) else nav.back() },
        onOpenKernelSetup = { focus: CheckId? -> nav.push(KernelRepair.of(focus, pushed = true)) },
        onDone = { connection ->
            nav.nav.currentBackStackEntry?.savedStateHandle?.set(KEY_CONNECTION, connection.encode())
            nav.push(if (firstRun) OnboardingDone else SetupDone)
        },
    )
}

@Composable
private fun DoneRoute(nav: RootNavigator, graph: AppGraph, firstRun: Boolean) {
    val connection = remember {
        OnboardingConnection.decode(nav.nav.previousBackStackEntry?.savedStateHandle?.get<String>(KEY_CONNECTION))
            ?: OnboardingConnection.Cline()
    }
    SetupDoneScreen(
        connection = connection,
        onBack = null,
        onStartBuilding = {
            if (firstRun) {
                finishOnboarding(nav, graph)
            } else {
                // Re-entry: pop to the entry point; Home re-reads providers and hides the setup notice.
                if (!nav.nav.popBackStack<SetupConnect>(inclusive = true)) nav.back()
            }
        },
    )
}

/**
 * KS §1 "Finishing", desktop `completeOnboarding`: `onboarding.completedAt`, the current What's
 * new release marked seen (first-run users see onboarding instead), the onboarding stack cleared
 * and Home opened with a fresh thread.
 */
fun finishOnboarding(nav: RootNavigator, graph: AppGraph) {
    graph.appScope.launch { runCatching { graph.whatsNew.markCurrentSeen() } }
    nav.finishOnboarding()
}
