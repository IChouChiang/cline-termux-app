package dev.cline.termux.data.workspace

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.sessions.SessionSummary
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/*
 * The Home workspace selection (settings-providers.md S9.3; docs/07 §4.5): DataStore
 * `workspace.active` (`Folder(path)` | `Chat`) and `workspace.recents` (MRU, ≤ 20, desktop
 * `workspace-selection.v2`), folder validation through RUN_COMMAND (`realpath -e`, then
 * `find -maxdepth 0 -type d`; the desktop's `validate_workspace_directory`), and the
 * session-derived recents (`workspacePathsFromSessions` over `session.list`).
 */

/** The persistence the store needs; [AppPreferences] in the app. */
interface WorkspacePrefs {
    val active: Flow<WorkspaceChoice>
    suspend fun active(): WorkspaceChoice
    val recents: Flow<List<String>>
    suspend fun recents(): List<String>

    /** Sets the choice; a folder also moves to the head of the recents (≤ 20). */
    suspend fun setActive(choice: WorkspaceChoice)
    suspend fun removeRecent(path: String)

    companion object {
        fun of(prefs: AppPreferences): WorkspacePrefs = object : WorkspacePrefs {
            override val active: Flow<WorkspaceChoice> get() = prefs.workspace
            override suspend fun active(): WorkspaceChoice = prefs.workspace()
            override val recents: Flow<List<String>> get() = prefs.workspaceRecents
            override suspend fun recents(): List<String> = prefs.workspaceRecents()
            override suspend fun setActive(choice: WorkspaceChoice) {
                prefs.setWorkspace(choice)
            }
            override suspend fun removeRecent(path: String) {
                prefs.removeWorkspaceRecent(path)
            }
        }
    }
}

/** Result of [WorkspaceStore.validate]. */
sealed interface WorkspaceValidation {
    /** [path] is the canonical directory (`realpath -e`), the form that gets stored. */
    data class Valid(val path: String) : WorkspaceValidation

    /** Not a directory, or it does not exist: "Couldn't open "<path>". Check that the folder exists and try again." */
    data class Invalid(val path: String) : WorkspaceValidation

    /** Termux could not run the check (§0.5 `KernelBlockedCard`). */
    data class Blocked(val error: TermuxUnavailable) : WorkspaceValidation
}

/** One row of the "Workspaces" section. */
data class WorkspaceRow(val path: String, val display: String, val isActive: Boolean)

class WorkspaceStore(
    private val termux: TermuxExecutor,
    private val prefs: WorkspacePrefs,
    private val rules: WorkspacePathRules = WorkspacePathRules.Termux,
    private val sessionSource: SessionWorkspaceSource = SessionWorkspaceSource.None,
) {
    /** Reads `session.list {limit: 200, rootOnly: true}` when the Hub is connected; null otherwise. */
    fun interface SessionWorkspaceSource {
        suspend fun sessions(): List<SessionSummary>?

        companion object {
            val None = SessionWorkspaceSource { null }
        }
    }

    private val sessionPaths = MutableStateFlow<List<String>>(emptyList())

    val active: Flow<WorkspaceChoice> get() = prefs.active
    val recents: Flow<List<String>> get() = prefs.recents

    /** Distinct `workspaceRoot || cwd` of the sessions, most recent activity first (filtered). */
    val sessionWorkspaces: StateFlow<List<String>> = sessionPaths.asStateFlow()

    suspend fun activeNow(): WorkspaceChoice = prefs.active()

    /**
     * The S9.3 merge: the active folder, the stored recents, then the session workspaces,
     * deduped by `normalizeWorkspacePath` and passed through `filterWorkspacePaths`.
     */
    val catalog: Flow<List<String>>
        get() = combine(prefs.active, prefs.recents, sessionPaths) { active, recents, sessions ->
            mergedRecents(active, recents, sessions, rules)
        }

    /** `recentsFromSessions` (docs/07 §4.5): fold a `session.list` projection into the catalog. */
    fun recentsFromSessions(sessions: List<SessionSummary>) {
        sessionPaths.value = workspacePathsFromSummaries(sessions, rules)
    }

    /** Refreshes the session workspaces through [sessionSource]; keeps the last list when the Hub is not connected. */
    suspend fun refreshSessionWorkspaces() {
        val sessions = runCatching { sessionSource.sessions() }.getOrNull() ?: return
        recentsFromSessions(sessions)
    }

    /**
     * Validates [path] in Termux (`~` expanded by the app): `realpath -e` must exit 0 with the
     * canonical path, and `find <canonical> -maxdepth 0 -type d` must print it.
     */
    suspend fun validate(path: String): WorkspaceValidation {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return WorkspaceValidation.Invalid(path)
        val expanded = expandHomePath(trimmed)
        return try {
            val real = termux.run(ClineCommands.realpath(expanded))
            val canonical = real.stdoutLines.firstOrNull { it.isNotBlank() }?.trim()
            if (!real.ok || canonical.isNullOrEmpty()) return WorkspaceValidation.Invalid(trimmed)
            val dir = termux.run(ClineCommands.isDirectory(canonical))
            if (dir.ok && dir.stdout.isNotBlank()) WorkspaceValidation.Valid(canonical) else WorkspaceValidation.Invalid(trimmed)
        } catch (e: TermuxUnavailable) {
            WorkspaceValidation.Blocked(e)
        }
    }

    /** Validate → adopt (S9.4 recents, "Open folder", "Use this folder"); the active one only closes. */
    suspend fun open(path: String): WorkspaceValidation {
        val result = validate(path)
        if (result is WorkspaceValidation.Valid) adopt(result.path)
        return result
    }

    /** A validated folder becomes the Home selection and moves to the front of the recents. */
    suspend fun adopt(canonicalPath: String) {
        prefs.setActive(WorkspaceChoice.Folder(canonicalPath))
    }

    /** "Just chat": no folder chosen; the next first send starts in the Termux home (decided A22). */
    suspend fun selectChat() {
        prefs.setActive(WorkspaceChoice.Chat)
    }

    suspend fun removeRecent(path: String) = prefs.removeRecent(path)

    companion object {
        const val MAX_RECENTS = AppPreferences.MAX_RECENTS

        /** S9.3 merge order: active (if a folder), stored recents, session workspaces; then filtered. */
        fun mergedRecents(
            active: WorkspaceChoice,
            recents: List<String>,
            sessionPaths: List<String>,
            rules: WorkspacePathRules = WorkspacePathRules.Termux,
        ): List<String> = rules.filterWorkspacePaths(
            mergeWorkspacePaths(listOfNotNull(active.pathOrNull), recents.take(MAX_RECENTS), sessionPaths),
        )

        /** S9.3 rows: [paths] filtered by [query] (substring, case-insensitive) with their `~` display form. */
        fun rows(paths: List<String>, active: WorkspaceChoice, query: String): List<WorkspaceRow> {
            val needle = query.trim()
            val activePath = active.pathOrNull?.let(::normalizeWorkspacePath)
            return paths
                .filter { needle.isEmpty() || it.contains(needle, ignoreCase = true) || formatWorkspacePath(it).contains(needle, ignoreCase = true) }
                .map { WorkspaceRow(path = it, display = formatWorkspacePath(it), isActive = normalizeWorkspacePath(it) == activePath) }
        }

        /** `workspacePathsFromSessions` over session summaries (activity = `max(updatedAt, createdAt)`). */
        fun workspacePathsFromSummaries(sessions: List<SessionSummary>, rules: WorkspacePathRules = WorkspacePathRules.Termux): List<String> =
            rules.workspacePathsFromSessions(
                sessions.map { summary ->
                    WorkspacePathSource(
                        cwd = summary.cwd,
                        workspaceRoot = summary.workspaceRoot,
                        startedAt = summary.activityAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                    )
                },
            )
    }
}
