package dev.cline.termux.ui.chat

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.changes.changesGraph
import dev.cline.termux.ui.navigation.Chat
import dev.cline.termux.ui.navigation.ChildChat
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.tool.toolGraph

/**
 * The chat routes (home-chat.md §2.9, docs/07 §6.5), registered from `shellDestinations()`:
 *
 * - `chat/{sessionId?}?sheet=` ([Chat]): the fixed start destination. Home when nothing is
 *   selected; a top-level session is a selection in this entry's `SavedStateHandle`
 *   (`ShellNavigator.openSession` writes it, deep links included, `?sheet=` by its wire string).
 * - `chat/{id}?child=true` ([ChildChat]): a pushed subagent session; back pops it.
 * - `chat/{id}/changes` and `chat/{id}/tool/{toolCallId}`: the pushed Changes and Tool detail
 *   routes ([changesGraph], [toolGraph]); on ≥ 1200 × ≥ 480 windows the navigator shows them in
 *   the chat's supporting pane instead.
 */
fun NavGraphBuilder.chatGraph(nav: ShellNavigator, graph: AppGraph) {
    composable<Chat> { entry -> ChatRoute(entry, nav, graph) }
    composable<ChildChat> { entry -> ChatRoute(entry, nav, graph, childSessionId = entry.toRoute<ChildChat>().sessionId) }
    changesGraph(nav, graph)
    toolGraph(nav, graph)
}
