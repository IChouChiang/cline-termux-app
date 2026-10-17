package dev.cline.termux.data.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from apps/examples/desktop-app/webview/lib/sidebar-session-organization.test.ts (every case). */
class SessionGroupingTest {
    private fun thread(
        id: String,
        workspacePath: String,
        title: String = id,
        schedule: ScheduleInfo? = null,
        createdAt: Long = 0,
    ) = SessionSummary(
        id = id,
        title = title,
        hasCustomTitle = false,
        workspaceRoot = workspacePath,
        cwd = workspacePath,
        kernelStatus = KernelSessionStatus.Completed,
        provider = "cline",
        model = "test-model",
        source = "android",
        pinned = false,
        schedule = schedule,
        createdAt = createdAt,
        activityAt = createdAt,
    )

    private fun rowLabel(row: SidebarListRow): String = when (row) {
        is SidebarListRow.Thread -> row.thread.id
        is SidebarListRow.Schedule -> "${row.label}[${row.threads.joinToString(",") { it.id }}]"
    }

    @Test fun `groups every loaded thread before applying per-project visibility`() {
        val threads = List(12) { thread("alpha-${it + 1}", "/work/acme/repo") } + thread("beta-1", "/work/other/repo")
        val groups = groupThreadsByProject(threads)
        assertEquals(listOf("acme/repo", "other/repo"), groups.map { it.label })
        assertEquals(12, groups[0].threads.size)
        assertEquals(listOf("beta-1"), groups[1].threads.map { it.id })
    }

    @Test fun `uses the repository directory instead of the full workspace path`() {
        assertEquals("cline", groupThreadsByProject(listOf(thread("a", "/Users/saoud/code/cline/")))[0].label)
        assertEquals("cline", groupThreadsByProject(listOf(thread("b", "C:\\Users\\saoud\\code\\cline\\")))[0].label)
    }

    @Test fun `labels chat workspace groups as Chat`() {
        val path = "/home/host/.cline/data/workspaces/chat"
        assertEquals("Chat", groupThreadsByProject(listOf(thread("temp", path)))[0].label)
    }

    @Test fun `groups blank workspaces under Other`() {
        val groups = groupThreadsByProject(listOf(thread("x", "")))
        assertEquals("__other__", groups[0].id)
        assertEquals("Other", groups[0].label)
    }

    @Test fun `folds scheduled runs into one group per schedule at the newest run's position`() {
        fun scheduled(id: String, run: Int) =
            thread(id, "/work/acme/repo", title = "Report today's date", schedule = ScheduleInfo("sched_daily", "Daily date report", run))
        val rows = groupScheduledThreads(
            listOf(
                thread("task-1", "/work/acme/repo"),
                scheduled("run-3", 3),
                thread("task-2", "/work/acme/repo"),
                scheduled("run-2", 2),
                thread("other-1", "/work/acme/repo", schedule = ScheduleInfo("sched_other", "Other schedule")),
                scheduled("run-1", 1),
            ),
        )
        assertEquals(listOf("task-1", "Daily date report[run-3,run-2,run-1]", "task-2", "Other schedule[other-1]"), rows.map(::rowLabel))
        assertEquals("schedule:sched_daily", (rows[1] as SidebarListRow.Schedule).id)
    }

    @Test fun `groups un-stamped scheduled runs by their shared title`() {
        fun legacy(id: String) = thread(id, "/work/acme/repo", title = "Report today's date to the user.", schedule = ScheduleInfo())
        val rows = groupScheduledThreads(
            listOf(
                legacy("legacy-2"),
                thread("named-1", "/work/acme/repo", title = "Report today's date to the user.", schedule = ScheduleInfo("sched_daily", "Daily date report")),
                legacy("legacy-1"),
            ),
        )
        // Runs with a schedule id never merge into the title-keyed group.
        assertEquals(
            listOf("title:report today's date to the user.:2", "schedule:sched_daily:1"),
            rows.map { (it as SidebarListRow.Schedule).let { group -> "${group.id}:${group.threads.size}" } },
        )
        assertEquals("Report today's date to the user.", (rows[0] as SidebarListRow.Schedule).label)
    }

    @Test fun `labels runs by number and falls back to the start time`() {
        assertEquals(ScheduleRunLabel.Numbered(7), scheduleRunLabel(thread("a", "/ws", schedule = ScheduleInfo(runNumber = 7))))
        val startedAt = 1_788_204_700_834L
        assertEquals(ScheduleRunLabel.StartedAt(startedAt), scheduleRunLabel(thread("b", "/ws", schedule = ScheduleInfo(), createdAt = startedAt)))
        assertEquals(ScheduleRunLabel.Unknown, scheduleRunLabel(thread("c", "/ws", schedule = ScheduleInfo())))
    }

    @Test fun `non-scheduled threads have no group key`() {
        assertEquals(null, scheduleGroupKey(thread("a", "/ws")))
        assertEquals(30, INITIAL_VISIBLE_THREAD_COUNT)
    }
}
