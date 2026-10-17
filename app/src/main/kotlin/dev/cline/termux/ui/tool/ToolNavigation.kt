package dev.cline.termux.ui.tool

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.ChatTool
import dev.cline.termux.ui.navigation.ShellNavigator

/**
 * Registers the pushed Tool detail route `chat/{sessionId}/tool/{toolCallId}` (CT B.3) in the
 * shell graph through `chatGraph`. Back pops to the chat;
 * "View all changes" opens Changes for the same session through the navigator (pushed here,
 * since the route is not the chat).
 */
fun NavGraphBuilder.toolGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<ChatTool> { entry ->
        val route = entry.toRoute<ChatTool>()
        ToolDetailRoute(
            sessionId = route.sessionId,
            toolCallId = route.toolCallId,
            graph = graph,
            onBack = { nav.back() },
            onViewAllChanges = { nav.openChanges(route.sessionId) },
        )
    }
}
