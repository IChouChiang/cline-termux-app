package dev.cline.termux.ui.kernel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.RootNavigator
import dev.cline.termux.ui.onboarding.KernelSetupEvent
import dev.cline.termux.ui.onboarding.KernelSetupScreen
import dev.cline.termux.ui.onboarding.KernelSetupViewModel
import dev.cline.termux.ui.onboarding.SetupVariant
import dev.cline.termux.ui.onboarding.kernelSetupFactory
import dev.cline.termux.ui.showReplacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Fix the Cline kernel" (onboarding-kernel.md A.3, A.5 "repair variant"): the setup checklist
 * focused on [route]'s row. **Blocking** (`pushed = false`) replaces the shell while a
 * prerequisite is broken: no back arrow, back leaves the app, and it returns to the shell (its
 * saved stack restored) 600 ms after the rows pass. **Pushed** (`pushed = true`) sits on top of
 * the caller: back arrow and system back pop to it, and it pops by itself once the focused row
 * passes. Both say "Cline kernel connected" when they leave with the Hub connected; the caller
 * retries its own action only on a new tap.
 */
@Composable
fun KernelRepairScreen(route: KernelRepair, nav: RootNavigator, graph: AppGraph) {
    val context = LocalContext.current
    val variant = SetupVariant.Repair(route.checkId, route.pushed)
    val vm: KernelSetupViewModel = viewModel(factory = kernelSetupFactory(graph, context, variant, reliabilityAvailable = false))
    val snackbar = LocalSnackbarHost.current
    val connected = stringResource(R.string.kernel_connected_announce)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is KernelSetupEvent.RepairResolved -> {
                    // The message outlives this screen: the app-wide host shows it over the caller.
                    if (event.connected) graph.appScope.launch(Dispatchers.Main) { snackbar.showReplacing(connected) }
                    if (route.pushed) nav.back() else nav.returnToShell()
                }
                KernelSetupEvent.ToInstallTermux -> nav.push(KernelInstallTermux)
                KernelSetupEvent.ToConnect, KernelSetupEvent.ToReliability -> Unit
            }
        }
    }
    KernelSetupScreen(vm, onBack = if (route.pushed) ({ nav.back() }) else null)
}
