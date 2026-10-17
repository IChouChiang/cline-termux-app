package dev.cline.termux.data.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.workspace.isChatWorkspacePath
import dev.cline.termux.data.workspace.normalizeWorkspacePath

/*
 * Ports of apps/examples/desktop-app/webview/lib/sidebar-session-organization.ts
 * (`groupThreadsByProject`, `groupScheduledThreads`, `scheduleGroupKey`) over
 * [SessionSummary] rows (design/screens/sessions.md §1.1, §2.5).
 */

/** `INITIAL_VISIBLE_THREAD_COUNT`: one page of drawer rows. */
const val INITIAL_VISIBLE_THREAD_COUNT = 30

/** `SidebarProjectGroup`. */
@Immutable
data class ProjectGroup(
    val id: String,
    val label: String,
    val workspacePath: String,
    val threads: List<SessionSummary>,
)

/**
 * `groupThreadsByProject`: one group per normalized workspace path in first-seen order;
 * the chat workspace reads "Chat", others the shortest unique trailing path, blank "Other".
 */
fun groupThreadsByProject(threads: List<SessionSummary>): List<ProjectGroup> {
    val groups = LinkedHashMap<String, Pair<String, MutableList<SessionSummary>>>()
    for (thread in threads) {
        val workspacePath = thread.workspaceRoot.trim()
        val projectId = normalizeWorkspacePath(workspacePath).ifEmpty { "__other__" }
        val current = groups[projectId]
        if (current != null) current.second += thread else groups[projectId] = workspacePath to mutableListOf(thread)
    }
    val workspacePaths = groups.values.map { it.first }
    return groups.map { (id, group) ->
        ProjectGroup(
            id = id,
            label = if (isChatWorkspacePath(group.first)) "Chat" else uniqueWorkspaceLabel(group.first, workspacePaths),
            workspacePath = group.first,
            threads = group.second.toList(),
        )
    }
}

/** `SidebarListRow`. */
sealed interface SidebarListRow {
    @Immutable
    data class Thread(val thread: SessionSummary) : SidebarListRow

    /** One row per schedule; [id] is the schedule id key or the shared-title key. */
    @Immutable
    data class Schedule(val id: String, val label: String, val threads: List<SessionSummary>) : SidebarListRow
}

/**
 * `scheduleGroupKey`: runs stamped with (or linked to) a schedule id group by it; older
 * runs without one group by their shared title. Null for non-scheduled threads.
 */
fun scheduleGroupKey(thread: SessionSummary): String? {
    val schedule = thread.schedule ?: return null
    val scheduleId = schedule.scheduleId?.trim().orEmpty()
    if (scheduleId.isNotEmpty()) return "schedule:$scheduleId"
    val title = normalizeTitle(thread.title).trim().lowercase()
    return if (title.isNotEmpty()) "title:$title" else null
}

private fun scheduleGroupLabel(thread: SessionSummary): String =
    thread.schedule?.scheduleName?.trim()?.ifEmpty { null } ?: normalizeTitle(thread.title).trim()

/**
 * `groupScheduledThreads`: every scheduled thread folds into one row per schedule at the
 * position of its first (newest) run; other threads pass through.
 */
fun groupScheduledThreads(threads: List<SessionSummary>): List<SidebarListRow> {
    val order = mutableListOf<Any>()
    val groups = LinkedHashMap<String, Pair<StringBuilder, MutableList<SessionSummary>>>()
    for (thread in threads) {
        val key = scheduleGroupKey(thread)
        if (key == null) {
            order += SidebarListRow.Thread(thread)
            continue
        }
        val existing = groups[key]
        if (existing != null) {
            existing.second += thread
            if (existing.first.isEmpty()) existing.first.append(scheduleGroupLabel(thread))
            continue
        }
        groups[key] = StringBuilder(scheduleGroupLabel(thread)) to mutableListOf(thread)
        order += key
    }
    return order.map { entry ->
        when (entry) {
            is SidebarListRow -> entry
            else -> {
                val key = entry as String
                val (label, runs) = groups.getValue(key)
                SidebarListRow.Schedule(key, label.toString(), runs.toList())
            }
        }
    }
}

/** `scheduleRunLabel` for a row: "Run N", else its creation time. */
fun scheduleRunLabel(thread: SessionSummary): ScheduleRunLabel =
    scheduleRunLabel(thread.schedule?.runNumber, thread.createdAt.takeIf { it > 0 })
