package dev.cline.termux.ui.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.prefs.SidebarSortMode
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.sessions.INITIAL_VISIBLE_THREAD_COUNT
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.ListSource
import dev.cline.termux.data.sessions.ScheduleRunLabel
import dev.cline.termux.data.sessions.SessionRow
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.SidebarListRow
import dev.cline.termux.data.sessions.getSessionSourceLabel
import dev.cline.termux.data.sessions.groupScheduledThreads
import dev.cline.termux.data.sessions.groupThreadsByProject
import dev.cline.termux.data.sessions.scheduleGroupKey
import dev.cline.termux.data.sessions.scheduleRunLabel
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.normalizeWorkspacePath

/*
 * The drawer / list-pane body as a pure function (design/screens/sessions.md §2.5, an exact
 * port of agent-sidebar.tsx): filters, Pinned / Scheduled / Tasks categories, project groups,
 * schedule folding and the paging windows. The ViewModel feeds it; tests assert it directly.
 */

/** The drawer's status filter (desktop "Status": All / Running; in memory, resets per launch). */
enum class StatusFilter { All, Running }

/** One "Source" option: the raw `metadata.source` and its `getSessionSourceLabel`. */
@Immutable
data class SourceOption(val source: String, val label: String)

enum class Category { Pinned, Scheduled, Tasks }

/** What a "Show more" button does (§2.5). */
sealed interface ShowMoreKind {
    /** Tasks: +30 rows, then the page-fill loop fetches older history. */
    data object Tasks : ShowMoreKind

    /** Scheduled: +30 group rows, no fetch. */
    data object Scheduled : ShowMoreKind

    /** "Show more in <label>": +30 rows of one project, no fetch. */
    data class Project(val id: String, val label: ProjectLabel) : ShowMoreKind

    /** Project mode's final "Show more": `loadOlderSessions()` directly. */
    data object History : ShowMoreKind
}

@Immutable
data class ShowMore(val kind: ShowMoreKind, val loading: Boolean)

/** A project group's label: the chat workspace, the Termux home, the "Other" bucket, or a unique trailing path. */
sealed interface ProjectLabel {
    /** The kernel's chat workspace (sessions it imported there). */
    data object Chat : ProjectLabel

    /**
     * The Termux home, where a session starts with no folder chosen (decided A22): "Home", by
     * its path, since the recent-workspaces catalog leaves the home out.
     */
    data object Home : ProjectLabel
    data object Other : ProjectLabel
    data class Path(val text: String) : ProjectLabel
}

sealed interface DrawerRow {
    val key: String

    /** A session row; [runLabel] is set for runs nested in a schedule group ([groupLabel] names it). */
    @Immutable
    data class Session(
        val row: SessionRow,
        val visual: SessionVisual,
        val runLabel: ScheduleRunLabel? = null,
        val groupLabel: String? = null,
    ) : DrawerRow {
        val summary: SessionSummary get() = row.summary
        override val key: String get() = "s:" + row.id
    }

    /** `ScheduleGroupRow`: one row per schedule with its runs nested. */
    @Immutable
    data class ScheduleGroup(
        val id: String,
        val label: String,
        val running: Boolean,
        val unread: Boolean,
        val expanded: Boolean,
        val containsActive: Boolean,
        val runs: List<Session>,
    ) : DrawerRow {
        val runCount: Int get() = runs.size
        override val key: String get() = "g:$id"
    }
}

@Immutable
data class CategorySection(val category: Category, val count: Int, val rows: List<DrawerRow>, val showMore: ShowMore?)

@Immutable
data class ProjectSection(val id: String, val label: ProjectLabel, val rows: List<DrawerRow>, val showMore: ShowMore?)

sealed interface DrawerBody {
    /** "Loading session history..." (before the first reply, no cache). */
    data object Loading : DrawerBody

    /** "No sessions found in history." */
    data object Empty : DrawerBody

    /** Nothing pinned or scheduled: the Tasks rows without a header. */
    @Immutable
    data class Flat(val rows: List<DrawerRow>, val showMore: ShowMore?) : DrawerBody

    @Immutable
    data class Categories(val sections: List<CategorySection>) : DrawerBody

    @Immutable
    data class Projects(val groups: List<ProjectSection>, val showMore: ShowMore?) : DrawerBody
}

/** The paging windows (`INITIAL_VISIBLE_THREAD_COUNT` = 30 each). */
@Immutable
data class VisibleCounts(
    val tasks: Int = INITIAL_VISIBLE_THREAD_COUNT,
    val scheduled: Int = INITIAL_VISIBLE_THREAD_COUNT,
    val projects: Map<String, Int> = emptyMap(),
) {
    fun project(id: String): Int = projects[id] ?: INITIAL_VISIBLE_THREAD_COUNT
}

/** Everything [DrawerBodies.build] reads. */
data class DrawerInputs(
    val rows: List<SessionRow>,
    val source: ListSource,
    val load: ListLoad,
    val sortMode: SidebarSortMode,
    val statusFilter: StatusFilter = StatusFilter.All,
    val sourceFilter: String? = null,
    val visible: VisibleCounts = VisibleCounts(),
    val mayHaveMore: Boolean = false,
    val loadingMore: Boolean = false,
    val activeSessionId: String? = null,
    /** User toggles of schedule groups (process lifetime); absent = the default rule. */
    val groupToggles: Map<String, Boolean> = emptyMap(),
)

object DrawerBodies {
    /** §2.5 item 1: the source filter, then `Running` keeps running sessions only. */
    fun filtered(rows: List<SessionRow>, statusFilter: StatusFilter, sourceFilter: String?): List<SessionRow> =
        rows.filter { sourceFilter == null || it.summary.source == sourceFilter }
            .filter { statusFilter == StatusFilter.All || it.summary.kernelStatus == KernelSessionStatus.Running }

    /** Distinct sources of the loaded rows, sorted by label (desktop source facet). */
    fun sourceOptions(rows: List<SessionRow>): List<SourceOption> =
        rows.map { it.summary.source.trim() }.filter { it.isNotEmpty() }.distinct()
            .map { SourceOption(it, getSessionSourceLabel(it)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** Time mode's Tasks rows (not pinned, not scheduled), for the page-fill loop. */
    fun taskRows(inputs: DrawerInputs): List<SessionRow> =
        filtered(inputs.rows, inputs.statusFilter, inputs.sourceFilter).filter { !it.summary.pinned && !it.summary.isScheduled }

    fun build(inputs: DrawerInputs): DrawerBody {
        if (inputs.rows.isEmpty() && inputs.load == ListLoad.Initial && inputs.source != ListSource.Cache) return DrawerBody.Loading
        val filtered = filtered(inputs.rows, inputs.statusFilter, inputs.sourceFilter)
        return when (inputs.sortMode) {
            SidebarSortMode.Time -> timeBody(inputs, filtered)
            SidebarSortMode.Project -> projectBody(inputs, filtered)
        }
    }

    private fun timeBody(inputs: DrawerInputs, filtered: List<SessionRow>): DrawerBody {
        if (filtered.isEmpty()) return DrawerBody.Empty
        val pinned = filtered.filter { it.summary.pinned }
        val scheduled = filtered.filter { !it.summary.pinned && it.summary.isScheduled }
        val tasks = filtered.filter { !it.summary.pinned && !it.summary.isScheduled }
        val taskWindow = tasks.take(inputs.visible.tasks).map { session(it) }
        val tasksMore = (tasks.size > inputs.visible.tasks || (inputs.statusFilter == StatusFilter.All && inputs.mayHaveMore))
            .takeIf { it }?.let { ShowMore(ShowMoreKind.Tasks, inputs.loadingMore) }
        if (pinned.isEmpty() && scheduled.isEmpty()) return DrawerBody.Flat(taskWindow, tasksMore)
        val sections = buildList {
            if (pinned.isNotEmpty()) add(CategorySection(Category.Pinned, pinned.size, pinned.map { session(it) }, null))
            if (scheduled.isNotEmpty()) {
                val grouped = folded(scheduled, inputs)
                val more = if (grouped.size > inputs.visible.scheduled) ShowMore(ShowMoreKind.Scheduled, false) else null
                add(CategorySection(Category.Scheduled, grouped.size, grouped.take(inputs.visible.scheduled), more))
            }
            if (tasks.isNotEmpty() || tasksMore != null) add(CategorySection(Category.Tasks, tasks.size, taskWindow, tasksMore))
        }
        return DrawerBody.Categories(sections)
    }

    private fun projectBody(inputs: DrawerInputs, filtered: List<SessionRow>): DrawerBody {
        val byId = filtered.associateBy { it.id }
        val pinnedFirst = filtered.filter { it.summary.pinned } + filtered.filterNot { it.summary.pinned }
        val groups = groupThreadsByProject(pinnedFirst.map { it.summary })
        if (groups.isEmpty()) return DrawerBody.Empty
        val sections = groups.map { group ->
            val label = when {
                isChatLabel(group.workspacePath) -> ProjectLabel.Chat
                normalizeWorkspacePath(group.workspacePath) == TERMUX_HOME -> ProjectLabel.Home
                group.workspacePath.isBlank() -> ProjectLabel.Other
                else -> ProjectLabel.Path(group.label)
            }
            val rows = folded(group.threads.mapNotNull { byId[it.id] }, inputs)
            val window = inputs.visible.project(group.id)
            ProjectSection(
                id = group.id,
                label = label,
                rows = rows.take(window),
                showMore = if (rows.size > window) ShowMore(ShowMoreKind.Project(group.id, label), false) else null,
            )
        }
        val more = if (inputs.statusFilter == StatusFilter.All && inputs.mayHaveMore) ShowMore(ShowMoreKind.History, inputs.loadingMore) else null
        return DrawerBody.Projects(sections, more)
    }

    /** `groupScheduledThreads` over rows, with the §2.4 expansion rule. */
    private fun folded(rows: List<SessionRow>, inputs: DrawerInputs): List<DrawerRow> {
        val byId = rows.associateBy { it.id }
        return groupScheduledThreads(rows.map { it.summary }).map { entry ->
            when (entry) {
                is SidebarListRow.Thread -> session(byId.getValue(entry.thread.id))
                is SidebarListRow.Schedule -> {
                    val runs = entry.threads.map { byId.getValue(it.id) }
                    val containsActive = runs.any { it.id == inputs.activeSessionId }
                    DrawerRow.ScheduleGroup(
                        id = entry.id,
                        label = entry.label,
                        running = runs.any { it.summary.kernelStatus == KernelSessionStatus.Running },
                        unread = runs.any { it.flags.unread },
                        expanded = inputs.groupToggles[entry.id] ?: containsActive,
                        containsActive = containsActive,
                        runs = runs.map { session(it, scheduleRunLabel(it.summary), entry.label) },
                    )
                }
            }
        }
    }

    private fun session(row: SessionRow, runLabel: ScheduleRunLabel? = null, groupLabel: String? = null) =
        DrawerRow.Session(row, row.visual, runLabel, groupLabel)

    private fun isChatLabel(path: String): Boolean = dev.cline.termux.data.workspace.isChatWorkspacePath(path)

    /** The schedule group key of a session (for "opening a run drops the stored choice", §2.4). */
    fun groupKeyOf(summary: SessionSummary): String? = scheduleGroupKey(summary)
}

/**
 * §2.7 "Running / streaming": rows must not jump under the finger. Membership changes apply at
 * once; a pure reorder of the same ids is adopted at most once every [intervalMs].
 */
class StableOrder(private val intervalMs: Long = RESORT_INTERVAL_MS) {
    private var order: List<String> = emptyList()
    private var sortedAt: Long = Long.MIN_VALUE / 4

    /** The rows in the order to show at [nowMs]; [pendingResortAt] says when a held reorder becomes due. */
    fun apply(rows: List<SessionRow>, nowMs: Long): List<SessionRow> {
        val ids = rows.map { it.id }
        val sameMembers = ids.size == order.size && ids.toSet() == order.toSet()
        if (!sameMembers || ids == order || nowMs - sortedAt >= intervalMs) {
            if (ids != order) sortedAt = nowMs
            order = ids
            pendingResortAt = null
            return rows
        }
        pendingResortAt = sortedAt + intervalMs
        val byId = rows.associateBy { it.id }
        return order.map { byId.getValue(it) }
    }

    var pendingResortAt: Long? = null
        private set

    companion object {
        const val RESORT_INTERVAL_MS = 5_000L
    }
}
