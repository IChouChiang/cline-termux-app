package dev.cline.termux.ui.sessions

import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.SessionRow
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.basenamePath

/** S2 sort menu: "Newest first" (default) / "Oldest first"; not persisted (desktop). */
enum class SortDirection { Newest, Oldest }

/**
 * The Sessions screen's local search, facets and sort (sessions.md §3.4), ported from
 * `sessions-view.tsx` (`sessionFilterDetails`, the searchable join, `sortTimestamp`).
 */
object SessionsScreenModel {
    /** §3.4 `facetStatus`: the desktop's normalised vocabulary (`normalizeDiscoveredStatus` through `thread.status`). */
    fun facetStatus(status: KernelSessionStatus): String = when (status) {
        KernelSessionStatus.Running -> "running"
        KernelSessionStatus.Completed -> "completed"
        KernelSessionStatus.Aborted, KernelSessionStatus.Cancelled -> "cancelled"
        KernelSessionStatus.Failed -> "failed"
        KernelSessionStatus.Idle, KernelSessionStatus.Pending, KernelSessionStatus.Unknown -> "idle"
    }

    /** `sessionFilterDetails` (no cloud sessions on Android). */
    fun facets(summary: SessionSummary): List<String> {
        val workspacePath = summary.workspaceRoot.ifEmpty { summary.cwd }
        val workspace = if (workspacePath.isNotEmpty()) basenamePath(workspacePath) else ""
        return listOfNotNull(
            "pinned:yes".takeIf { summary.pinned },
            workspace.takeIf { it.isNotEmpty() }?.let { "workspace:$it" },
            "status:" + facetStatus(summary.kernelStatus),
            summary.provider.takeIf { it.isNotEmpty() }?.let { "provider:$it" },
            summary.model.takeIf { it.isNotEmpty() }?.let { "model:$it" },
        )
    }

    /** Every facet of the loaded rows, distinct, sorted like `localeCompare`. */
    fun facetOptions(rows: List<SessionRow>): List<String> =
        rows.flatMap { facets(it.summary) }.distinct().sortedWith { a, b ->
            val c = a.compareTo(b, ignoreCase = true)
            if (c != 0) c else a.compareTo(b)
        }

    /** The searchable join: title, workspace name, provider, model, workspace root, cwd. */
    fun matchesQuery(summary: SessionSummary, query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return true
        val workspacePath = summary.workspaceRoot.ifEmpty { summary.cwd }
        val searchable = listOf(
            summary.title,
            if (workspacePath.isNotEmpty()) basenamePath(workspacePath) else "",
            summary.provider,
            summary.model,
            summary.workspaceRoot,
            summary.cwd,
        ).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
        return normalized in searchable
    }

    /** OR semantics (desktop): a row shows when any of its facets is selected. */
    fun matchesFacets(summary: SessionSummary, selected: Set<String>): Boolean =
        selected.isEmpty() || facets(summary).any { it in selected }

    fun apply(rows: List<SessionRow>, query: String, selected: Set<String>, sort: SortDirection): List<SessionRow> {
        val filtered = rows.filter { matchesFacets(it.summary, selected) && matchesQuery(it.summary, query) }
        return when (sort) {
            SortDirection.Newest -> filtered.sortedByDescending { it.summary.activityAt }
            SortDirection.Oldest -> filtered.sortedBy { it.summary.activityAt }
        }
    }

    /** `requiresCompleteHistory`; opening the filter sheet also asks for it (§3.4). */
    fun needsCompleteHistory(query: String, selected: Set<String>, sort: SortDirection, filterSheetOpen: Boolean): Boolean =
        query.isNotBlank() || selected.isNotEmpty() || sort == SortDirection.Oldest || filterSheetOpen

    /** §3.5: load older when the last visible index is within this many rows of the end. */
    const val PREFETCH_DISTANCE = 5

    fun shouldLoadOlder(lastVisibleIndex: Int, totalRows: Int, mayHaveMore: Boolean, needsComplete: Boolean, loading: Boolean, halted: Boolean): Boolean =
        mayHaveMore && !needsComplete && !loading && !halted && lastVisibleIndex >= totalRows - 1 - PREFETCH_DISTANCE
}
