package dev.cline.termux.ui.sessions

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.Search
import dev.cline.termux.ui.navigation.Sessions
import dev.cline.termux.ui.navigation.ShellNavigator

/**
 * The sessions routes of the shell NavHost (docs/07 §6.2): `sessions` (S2, pushed) and
 * `search` (S3 full-screen variant; expanded windows use [SessionSearchDocked] in the list
 * pane instead). Registered from `shellDestinations`.
 */
fun NavGraphBuilder.sessionsGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<Sessions> { SessionsScreen(nav, graph) }
    composable<Search> { SessionSearchScreen(nav, graph) }
}
