package dev.cline.termux.ui.customize

import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.data.workspace.WorkspaceValidation
import kotlinx.coroutines.flow.Flow

/*
 * The seams the Customize and MCP editor ViewModels depend on, so they are constructed
 * directly with fakes in tests (docs/07 §6.5, §10.1). The app adapters are below.
 */

/**
 * Customize's workspace scope (CR §1.6): DataStore `customize.workspaceRoot`, defaulting to
 * the workspace of the most recently active session, else Termux's home. Choosing a folder
 * validates it in Termux first (SP S9.3) but never touches the Home selection.
 */
interface CustomizeWorkspace {
    /** The stored scope, else the most recent session workspace, else [TERMUX_HOME]. */
    suspend fun initialRoot(): String

    suspend fun store(path: String)

    /** The S9.3 recents (active folder, stored recents, session workspaces), filtered. */
    val candidates: Flow<List<String>>

    suspend fun validate(path: String): WorkspaceValidation
}

/** The app's [CustomizeWorkspace] over [AppPreferences] and the [WorkspaceStore]. */
class AppCustomizeWorkspace(
    private val prefs: AppPreferences,
    private val workspaces: WorkspaceStore,
) : CustomizeWorkspace {
    override suspend fun initialRoot(): String {
        prefs.customizeWorkspaceRoot()?.takeIf { it.isNotBlank() }?.let { return it }
        workspaces.refreshSessionWorkspaces()
        return workspaces.sessionWorkspaces.value.firstOrNull()
            ?: workspaces.activeNow().pathOrNull
            ?: TERMUX_HOME
    }

    override suspend fun store(path: String) {
        prefs.setCustomizeWorkspaceRoot(path)
    }

    override val candidates: Flow<List<String>> get() = workspaces.catalog

    override suspend fun validate(path: String): WorkspaceValidation = workspaces.validate(path)
}
