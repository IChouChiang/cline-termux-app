package dev.cline.termux.ui.changes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.ChatChanges
import dev.cline.termux.ui.navigation.ShellNavigator

/**
 * Registers the pushed Changes route `chat/{sessionId}/changes` (CT A.3) in the shell graph,
 * through `chatGraph`. Back (arrow or system) pops; the
 * screen also pops itself when the summary drops to zero or the session is deleted.
 */
fun NavGraphBuilder.changesGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<ChatChanges> { entry ->
        val route = entry.toRoute<ChatChanges>()
        ChangesRoute(sessionId = route.sessionId, graph = graph, onBack = { nav.back() })
    }
}

/**
 * The supporting pane's "Open full screen" (CT A.4): pushes the two-pane route over the chat.
 * The caller closes the pane first (`LocalShellChat.current?.closeSupporting`).
 */
fun ShellNavigator.openChangesFullScreen(sessionId: String) {
    nav.navigate(ChatChanges(sessionId))
}
