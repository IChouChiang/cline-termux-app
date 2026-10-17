package dev.cline.termux.data.workspace

import dev.cline.termux.data.tools.jsLocaleCompare
import dev.cline.termux.data.tools.jsString
import dev.cline.termux.data.tools.parseJsDate
import dev.cline.termux.data.tools.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Kotlin port of apps/examples/desktop-app/webview/lib/workspace-paths.ts (+ its tests),
 * sdk/packages/shared/src/storage/chat-workspace-paths.ts (`isChatWorkspacePath`) and the
 * `formatWorkspacePath` helper of workspace-selector.tsx, with the Android rules of
 * design/screens/settings-providers.md S9.3: the Termux home replaces the host home, and
 * `~/storage/shared` itself is excluded like the desktop's Desktop folder.
 *
 * The desktop keeps the registered host home and task worktree root in module globals;
 * here they are constructor parameters of [WorkspacePathRules] so every function stays pure.
 */

/** Termux's `$HOME`. RUN_COMMAND arguments get no `~` expansion, so the app expands it. */
const val TERMUX_HOME = "/data/data/com.termux/files/home"

/** `~/storage/shared`, the Android shared storage root `termux-setup-storage` links. */
const val TERMUX_SHARED_STORAGE = "$TERMUX_HOME/storage/shared"

const val LOCAL_WORKSPACE_ENVIRONMENT_ID = "local"

/** `WorkspaceSelectionStorage`. */
data class WorkspaceSelectionStorage(val lastWorkspace: String = "", val workspaces: List<String> = emptyList())

/** `WorkspacePathSource`: the fields of a session record the catalog reads. */
data class WorkspacePathSource(
    val cwd: String? = null,
    val workspaceRoot: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val origin: String? = null,
    val environmentId: String = LOCAL_WORKSPACE_ENVIRONMENT_ID,
)

private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")
private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
private val TRAILING_SEPARATORS = Regex("[\\\\/]+$")
private val WINDOWS_ABSOLUTE_PATH_PATTERN = Regex("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\)")
private val POSIX_HOME_OR_DESKTOP_PATTERN = Regex("^(?:/Users/[^/]+|/home/[^/]+|/root)(?:/Desktop)?$")
private val WINDOWS_HOME_OR_DESKTOP_PATTERN = Regex("^[a-z]:[\\\\/]users[\\\\/][^\\\\/]+(?:[\\\\/]desktop)?$", RegexOption.IGNORE_CASE)
private val DESKTOP_SUFFIX = Regex("^[\\\\/]desktop$", RegexOption.IGNORE_CASE)
private val TASK_WORKTREE_SHAPE = Regex("^[\\\\/][^\\\\/]+[\\\\/][^\\\\/]+$")
private val SEPARATORS = Regex("[\\\\/]")

/** `isChatWorkspacePath`: the shared `.cline/data/workspaces/chat` directory itself. */
fun isChatWorkspacePath(path: String): Boolean {
    val normalized = path.trim()
    val isWindowsAbsolute = WINDOWS_DRIVE.containsMatchIn(normalized) || normalized.startsWith("\\\\")
    val isPosixAbsolute = normalized.startsWith("/")
    if (!isWindowsAbsolute && !isPosixAbsolute) return false
    val segments = normalized.split(if (isWindowsAbsolute) Regex("[\\\\/]+") else Regex("/+")).filter { it.isNotEmpty() }
    fun at(fromEnd: Int) = segments.getOrNull(segments.size - fromEnd) ?: ""
    return at(4) == ".cline" && at(3) == "data" && at(2) == "workspaces" && at(1) == "chat"
}

/** `looksLikeFolderPath`: typed/pasted folder paths double as manual path entry. */
fun looksLikeFolderPath(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("/") || trimmed == "~" || trimmed.startsWith("~/") || WINDOWS_DRIVE.containsMatchIn(trimmed)
}

/** `normalizeWorkspacePath`: no trailing separators, Windows drive paths lower-cased. */
fun normalizeWorkspacePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ""
    val withoutTrailing = trimmed.replace(TRAILING_SEPARATORS, "")
    val normalized = withoutTrailing.ifEmpty { trimmed.substring(0, 1) }
    return if (WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) normalized.lowercase() else normalized
}

/** `mergeWorkspacePaths`: dedupe across groups, first spelling and first position win. */
fun mergeWorkspacePaths(vararg pathGroups: List<String>): List<String> {
    val byNormalized = LinkedHashMap<String, String>()
    for (paths in pathGroups) {
        for (path in paths) {
            val trimmed = path.trim()
            val normalized = normalizeWorkspacePath(trimmed)
            if (normalized.isNotEmpty() && normalized !in byNormalized) byNormalized[normalized] = trimmed
        }
    }
    return byNormalized.values.toList()
}

/** `isAbsoluteFilePath`. */
fun isAbsoluteFilePath(path: String): Boolean = path.startsWith("/") || WINDOWS_ABSOLUTE_PATH_PATTERN.containsMatchIn(path)

/** `resolveWorkspaceFilePath`: join a relative diff path to the session cwd in the cwd's separator style. */
fun resolveWorkspaceFilePath(path: String, cwd: String?): String {
    val trimmed = path.trim()
    val base = (cwd ?: "").trim()
    if (trimmed.isEmpty() || base.isEmpty() || isAbsoluteFilePath(trimmed)) return trimmed
    val separator = if ('\\' in base && '/' !in base) "\\" else "/"
    return "${base.replace(TRAILING_SEPARATORS, "")}$separator${trimmed.removePrefix("./")}"
}

/**
 * `formatWorkspacePath` (workspace-selector.tsx) for display: the Termux home becomes `~`
 * *(Android)*, and the desktop's `/Users/<u>`, `/home/<u>`, `C:\Users\<u>` rules stay for
 * sessions recorded elsewhere.
 */
fun formatWorkspacePath(path: String, home: String = TERMUX_HOME): String {
    if (home.isNotEmpty()) {
        if (path == home || path == "$home/") return "~"
        if (path.startsWith("$home/")) return "~/${path.substring(home.length + 1)}"
    }
    Regex("^/Users/[^/]+/(.*)$").find(path)?.let { m -> return if (m.groupValues[1].isNotEmpty()) "~/${m.groupValues[1]}" else "~" }
    Regex("^/home/[^/]+/(.*)$").find(path)?.let { m -> return if (m.groupValues[1].isNotEmpty()) "~/${m.groupValues[1]}" else "~" }
    Regex("^[A-Za-z]:\\\\Users\\\\[^\\\\]+\\\\(.*)$").find(path)?.let { m ->
        val tail = m.groupValues[1].replace("\\", "/")
        return if (tail.isNotEmpty()) "~/$tail" else "~"
    }
    return path
}

/** Expand `~` and `~/…` against the Termux home before a RUN_COMMAND (SP S9.3). */
fun expandHomePath(path: String, home: String = TERMUX_HOME): String {
    val trimmed = path.trim()
    return when {
        trimmed == "~" -> home
        trimmed.startsWith("~/") -> "$home/${trimmed.substring(2)}"
        else -> trimmed
    }
}

/** Display name of a workspace folder (`workspaceName`): last segment, "workspace" when empty. */
fun workspaceName(path: String): String {
    val trimmed = path.trim().replace(TRAILING_SEPARATORS, "")
    if (trimmed.isEmpty()) return "workspace"
    return trimmed.split(SEPARATORS).lastOrNull()?.takeIf { it.isNotEmpty() } ?: "workspace"
}

/**
 * The stateful parts of workspace-paths.ts as pure rules: [hostHome] is the desktop's
 * `registerHostHomeDirectory` (the Termux home on Android), [taskWorktreeRoot] its
 * `registerTaskWorktreeRoot`, [extraExcludedPaths] the Android-only exclusions.
 */
class WorkspacePathRules(
    hostHome: String = TERMUX_HOME,
    taskWorktreeRoot: String = "",
    extraExcludedPaths: List<String> = listOf(TERMUX_SHARED_STORAGE),
) {
    private val hostHomePath = normalizeWorkspacePath(hostHome)
    private val taskWorktreeRootPath = normalizeWorkspacePath(taskWorktreeRoot)
    private val extraExcluded = extraExcludedPaths.map(::normalizeWorkspacePath).filter { it.isNotEmpty() }.toSet()

    private fun isRegisteredHomeOrDesktop(normalized: String): Boolean {
        if (hostHomePath.isEmpty()) return false
        if (normalized == hostHomePath) return true
        return normalized.startsWith(hostHomePath) && DESKTOP_SUFFIX.containsMatchIn(normalized.substring(hostHomePath.length))
    }

    /**
     * `isExcludedWorkspacePath`: the chat workspace, anything inside a `.cline` segment, the
     * home (and its Desktop), and on Android `~/storage/shared` itself. Projects inside
     * them stay.
     */
    fun isExcludedWorkspacePath(path: String): Boolean {
        val normalized = normalizeWorkspacePath(path)
        if (normalized.isEmpty()) return false
        if (isChatWorkspacePath(normalized)) return true
        if (".cline" in normalized.split(SEPARATORS)) return true
        if (normalized in extraExcluded) return true
        return isRegisteredHomeOrDesktop(normalized) ||
            POSIX_HOME_OR_DESKTOP_PATTERN.containsMatchIn(normalized) ||
            WINDOWS_HOME_OR_DESKTOP_PATTERN.containsMatchIn(normalized)
    }

    /** `isTaskWorktreePath`: exactly `<root>/<id>/<repo>` under the reported root. */
    fun isTaskWorktreePath(path: String): Boolean {
        val normalized = normalizeWorkspacePath(path)
        if (taskWorktreeRootPath.isEmpty() || !normalized.startsWith(taskWorktreeRootPath)) return false
        return TASK_WORKTREE_SHAPE.containsMatchIn(normalized.substring(taskWorktreeRootPath.length))
    }

    /** `filterWorkspacePaths`. */
    fun filterWorkspacePaths(paths: List<String>): List<String> = paths.filterNot(::isExcludedWorkspacePath)

    /**
     * `workspacePathsFromSessions`: most recent session activity first; paths without a
     * parseable timestamp fall back to alphabetical order at the end. Cloud sessions and
     * other environments are skipped.
     */
    fun workspacePathsFromSessions(sessions: List<WorkspacePathSource>, environmentId: String = LOCAL_WORKSPACE_ENVIRONMENT_ID): List<String> {
        val scoped = sessions.filter { it.environmentId == environmentId && it.origin != "cloud" }
        val lastActivityByPath = HashMap<String, Long>()
        for (session in scoped) {
            val normalized = normalizeWorkspacePath(session.workspaceRoot?.ifEmpty { null } ?: session.cwd ?: "")
            if (normalized.isEmpty()) continue
            val activity = parseJsDate(session.endedAt ?: session.startedAt ?: "") ?: continue
            val known = lastActivityByPath[normalized]
            if (known == null || activity > known) lastActivityByPath[normalized] = activity
        }
        val merged = mergeWorkspacePaths(scoped.map { it.workspaceRoot?.ifEmpty { null } ?: it.cwd ?: "" })
        return filterWorkspacePaths(merged).sortedWith { a, b ->
            val aTime = lastActivityByPath[normalizeWorkspacePath(a)] ?: 0L
            val bTime = lastActivityByPath[normalizeWorkspacePath(b)] ?: 0L
            if (aTime == bTime) jsLocaleCompare(a, b) else bTime.compareTo(aTime)
        }
    }

    /** `parseWorkspaceSelectionStorage`: the v2 `{environments:{<id>:{lastWorkspace, workspaces}}}` shape. */
    fun parseWorkspaceSelectionStorage(raw: String?, environmentId: String = LOCAL_WORKSPACE_ENVIRONMENT_ID): WorkspaceSelectionStorage {
        if (raw.isNullOrEmpty()) return WorkspaceSelectionStorage()
        val parsed = parseJsonOrNull(raw) ?: return WorkspaceSelectionStorage()
        val selected = ((parsed as? JsonObject)?.get("environments") as? JsonObject)?.get(environmentId) as? JsonObject
        val parsedLast = selected?.get("lastWorkspace").jsString()?.trim() ?: ""
        val lastWorkspace = if (isChatWorkspacePath(parsedLast)) "" else parsedLast
        val workspaces = (selected?.get("workspaces") as? JsonArray)?.mapNotNull { it.jsString() } ?: emptyList()
        return WorkspaceSelectionStorage(lastWorkspace, filterWorkspacePaths(mergeWorkspacePaths(workspaces, listOf(lastWorkspace))))
    }

    /**
     * `writeWorkspaceSelectionToWindow` without the window: [currentRaw] is what is stored
     * now; the result replaces only [environmentId]'s entry.
     */
    fun serializeWorkspaceSelectionStorage(currentRaw: String?, environmentId: String, value: WorkspaceSelectionStorage): String {
        val current = (parseJsonOrNull(currentRaw ?: "{}") as? JsonObject)?.get("environments") as? JsonObject ?: JsonObject(emptyMap())
        val lastWorkspace = if (isChatWorkspacePath(value.lastWorkspace)) "" else value.lastWorkspace.trim()
        val workspaces = filterWorkspacePaths(mergeWorkspacePaths(value.workspaces, listOf(lastWorkspace)))
        val entry = buildJsonObject {
            put("lastWorkspace", lastWorkspace)
            put("workspaces", buildJsonArray { workspaces.forEach { add(JsonPrimitive(it)) } })
        }
        return buildJsonObject {
            put("environments", JsonObject(current + (environmentId to entry)))
        }.toString()
    }

    companion object {
        /** The app's rules: Termux home, no task worktrees, `~/storage/shared` excluded. */
        val Termux = WorkspacePathRules()
    }
}
