package dev.cline.termux.ui.account

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.Account
import dev.cline.termux.ui.navigation.ShellNavigator

/**
 * The Account destination (`account?tab=`, customize-and-rest.md §5.7): a pushed page. The
 * route's `tab` argument lands in the entry's `SavedStateHandle` under
 * [AccountViewModel.KEY_TAB]; a deep link to Usage while signed out still shows the
 * signed-out card on Overview. Registered from `shellDestinations`; the expanded Settings
 * scaffold also shows [AccountRoute] in its detail pane (`inPane = true`).
 */
fun NavGraphBuilder.accountGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<Account> { AccountRoute(nav, graph) }
}
