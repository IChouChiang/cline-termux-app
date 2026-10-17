package dev.cline.termux.ui.kernel

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.KernelReliability
import dev.cline.termux.ui.navigation.KernelSettings
import dev.cline.termux.ui.navigation.OnboardingConnect
import dev.cline.termux.ui.navigation.OnboardingReliability
import dev.cline.termux.ui.navigation.RootNavigator
import dev.cline.termux.ui.navigation.ShellNavigator

/**
 * The kernel-status lane's root destinations: `kernel/reliability` (the standalone D
 * checklist, pushed over the shell from the status sheet, the KernelStatePanel's "Why did this
 * happen?" or settings). Registered from `rootDestinations`.
 */
fun NavGraphBuilder.kernelGraph(nav: RootNavigator, graph: AppGraph) {
    composable<KernelReliability> {
        val context = LocalContext.current
        val vm: ReliabilityViewModel = viewModel(factory = ReliabilityViewModel.factory(graph, ReliabilityVariant.Standalone, context.applicationContext))
        ReliabilityRoute(viewModel = vm, onBack = { nav.back() }, onContinue = { nav.back() })
    }
}

/**
 * `onboarding/reliability` (D.3/D.4), registered inside `navigation<OnboardingGraph>`. The
 * step skips itself (replacing its own entry with `onboarding/connect`) when it was shown
 * before or nothing verifiable is missing; "Continue" / "Skip for now" push `onboarding/connect`.
 */
fun NavGraphBuilder.onboardingReliabilityDestination(nav: RootNavigator, graph: AppGraph) {
    composable<OnboardingReliability> {
        val context = LocalContext.current
        val vm: ReliabilityViewModel = viewModel(factory = ReliabilityViewModel.factory(graph, ReliabilityVariant.Onboarding, context.applicationContext))
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.skipOnboarding) {
            if (state.skipOnboarding == true) {
                nav.nav.navigate(OnboardingConnect) {
                    popUpTo<OnboardingReliability> { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        if (state.skipOnboarding == false) {
            ReliabilityRoute(viewModel = vm, onBack = { nav.back() }, onContinue = { nav.push(OnboardingConnect) })
        }
    }
}

/** The settings `kernel` page (SP S1 "Kernel" row), registered from `shellDestinations`. */
fun NavGraphBuilder.kernelSettingsDestination(navigator: ShellNavigator) {
    composable<KernelSettings> { KernelSettingsScreen(navigator) }
}
