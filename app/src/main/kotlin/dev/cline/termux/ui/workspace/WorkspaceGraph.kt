package dev.cline.termux.ui.workspace

import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WorkspaceBrowse
import dev.cline.termux.ui.navigation.editor

/**
 * Registers `workspace/browse?start=` (SP S9.2, docs/07 §6.2): a full-screen `composable()`
 * on compact/medium and a `dialog()` on expanded, per the shell's latched [editorsAsDialogs].
 * Registered from `shellDestinations`.
 */
fun NavGraphBuilder.workspaceGraph(navigator: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean) {
    editor<WorkspaceBrowse>(editorsAsDialogs) { asDialog ->
        FolderBrowserRoute(start = toRoute<WorkspaceBrowse>().start, asDialog = asDialog, navigator = navigator, graph = graph)
    }
}
