package dev.cline.termux.ui.sessions

import dev.cline.termux.data.prefs.SidebarSortMode
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.ListSource
import dev.cline.termux.data.sessions.ScheduleInfo
import dev.cline.termux.data.sessions.ScheduleRunLabel
import dev.cline.termux.data.sessions.SessionFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** sessions.md §2.5 content rules (agent-sidebar.tsx) through [DrawerBodies]. */
class SessionsDrawerModelTest {
    private fun inputs(
        rows: List<dev.cline.termux.data.sessions.SessionRow>,
        sort: SidebarSortMode = SidebarSortMode.Time,
        load: ListLoad = ListLoad.Loaded,
        source: ListSource = ListSource.Live,
        status: StatusFilter = StatusFilter.All,
        sourceFilter: String? = null,
        mayHaveMore: Boolean = false,
        visible: VisibleCounts = VisibleCounts(),
        active: String? = null,
        toggles: Map<String, Boolean> = emptyMap(),
    ) = DrawerInputs(rows, source, load, sort, status, sourceFilter, visible, mayHaveMore, loadingMore = false, activeSessionId = active, groupToggles = toggles)

    private fun scheduled(id: String, activityAt: Long, scheduleId: String = "sch1", run: Int? = null) =
        Rows.row(id, activityAt) { it.copy(schedule = ScheduleInfo(scheduleId, "Daily standup", run)) }

    @Test fun `loading before the first reply, empty only after a definitive one`() {
        assertEquals(DrawerBody.Loading, DrawerBodies.build(inputs(emptyList(), load = ListLoad.Initial, source = ListSource.None)))
        assertEquals(DrawerBody.Empty, DrawerBodies.build(inputs(emptyList(), load = ListLoad.Loaded)))
    }

    @Test fun `nothing pinned or scheduled is a flat list without headers`() {
        val body = DrawerBodies.build(inputs(Rows.tasks(3))) as DrawerBody.Flat
        assertEquals(listOf("t0", "t1", "t2"), body.rows.map { (it as DrawerRow.Session).summary.id })
        assertNull(body.showMore)
    }

    @Test fun `pinned and scheduled categories with desktop counts`() {
        val rows = listOf(
            Rows.row("p1", 10_000) { it.copy(pinned = true) },
            scheduled("r1", 9_000, run = 2),
            scheduled("r2", 8_000, run = 1),
            scheduled("x1", 7_000, scheduleId = "sch2"),
        ) + Rows.tasks(3)
        val body = DrawerBodies.build(inputs(rows)) as DrawerBody.Categories
        assertEquals(listOf(Category.Pinned, Category.Scheduled, Category.Tasks), body.sections.map { it.category })
        assertEquals(1, body.sections[0].count)
        // Scheduled counts groups after folding, not runs (count={scheduledRows.length}).
        assertEquals(2, body.sections[1].count)
        val group = body.sections[1].rows.first() as DrawerRow.ScheduleGroup
        assertEquals(2, group.runCount)
        assertEquals(ScheduleRunLabel.Numbered(2), group.runs.first().runLabel)
        assertEquals("Daily standup", group.runs.first().groupLabel)
        assertEquals(3, body.sections[2].count)
    }

    @Test fun `a pinned scheduled run stays under Pinned`() {
        val rows = listOf(scheduled("r1", 9_000).let { it.copy(summary = it.summary.copy(pinned = true)) })
        val body = DrawerBodies.build(inputs(rows)) as DrawerBody.Categories
        assertEquals(listOf(Category.Pinned), body.sections.map { it.category })
    }

    @Test fun `tasks window of 30 with show more, and show more while history may have more`() {
        val body = DrawerBodies.build(inputs(Rows.tasks(45))) as DrawerBody.Flat
        assertEquals(30, body.rows.size)
        assertEquals(ShowMoreKind.Tasks, body.showMore?.kind)
        val fewer = DrawerBodies.build(inputs(Rows.tasks(5), mayHaveMore = true)) as DrawerBody.Flat
        assertEquals(ShowMoreKind.Tasks, fewer.showMore?.kind)
        val running = DrawerBodies.build(inputs(Rows.tasks(5).map { it.copy(summary = it.summary.copy(kernelStatus = KernelSessionStatus.Running)) }, mayHaveMore = true, status = StatusFilter.Running)) as DrawerBody.Flat
        assertNull("history paging is not offered while filtering Running", running.showMore)
    }

    @Test fun `the Tasks header shows when only show more is offered`() {
        val rows = listOf(Rows.row("p1", 10_000) { it.copy(pinned = true) })
        val body = DrawerBodies.build(inputs(rows, mayHaveMore = true)) as DrawerBody.Categories
        val tasks = body.sections.last()
        assertEquals(Category.Tasks, tasks.category)
        assertEquals(0, tasks.count)
        assertTrue(tasks.rows.isEmpty())
    }

    @Test fun `filters - source then running`() {
        val rows = listOf(
            Rows.row("a") { it.copy(source = "cli", kernelStatus = KernelSessionStatus.Running) },
            Rows.row("b") { it.copy(source = "cli") },
            Rows.row("c") { it.copy(source = "android", kernelStatus = KernelSessionStatus.Running) },
        )
        assertEquals(listOf("a", "b"), DrawerBodies.filtered(rows, StatusFilter.All, "cli").map { it.id })
        assertEquals(listOf("a"), DrawerBodies.filtered(rows, StatusFilter.Running, "cli").map { it.id })
        assertEquals(listOf("a", "c"), DrawerBodies.filtered(rows, StatusFilter.Running, null).map { it.id })
        val filteredOut = DrawerBodies.build(inputs(rows, status = StatusFilter.Running, sourceFilter = "vscode"))
        assertEquals(DrawerBody.Empty, filteredOut)
    }

    @Test fun `source options are distinct and sorted by label`() {
        val rows = listOf(
            Rows.row("a") { it.copy(source = "vscode") },
            Rows.row("b") { it.copy(source = "cli") },
            Rows.row("c") { it.copy(source = "android") },
            Rows.row("d") { it.copy(source = "cli") },
            Rows.row("e") { it.copy(source = "hub-schedule") },
            Rows.row("f") { it.copy(source = "") },
        )
        assertEquals(
            listOf(SourceOption("android", "Android"), SourceOption("cli", "CLI"), SourceOption("hub-schedule", "Hub Schedule"), SourceOption("vscode", "VS Code")),
            DrawerBodies.sourceOptions(rows),
        )
    }

    @Test fun `project mode groups pinned first with unique labels and paging`() {
        val rows = listOf(
            Rows.row("a1", 10_000) { it.copy(workspaceRoot = "/home/a/app") },
            Rows.row("b1", 9_000) { it.copy(workspaceRoot = "/home/b/app") },
            Rows.row("a2", 8_000) { it.copy(workspaceRoot = "/home/a/app", pinned = true) },
            Rows.row("o1", 7_000) { it.copy(workspaceRoot = "") },
        )
        val body = DrawerBodies.build(inputs(rows, sort = SidebarSortMode.Project, mayHaveMore = true)) as DrawerBody.Projects
        assertEquals(listOf(ProjectLabel.Path("a/app"), ProjectLabel.Path("b/app"), ProjectLabel.Other), body.groups.map { it.label })
        assertEquals(listOf("a2", "a1"), body.groups[0].rows.map { (it as DrawerRow.Session).summary.id })
        assertEquals(ShowMoreKind.History, body.showMore?.kind)

        val many = Rows.tasks(35)
        val paged = DrawerBodies.build(inputs(many, sort = SidebarSortMode.Project)) as DrawerBody.Projects
        assertEquals(30, paged.groups.single().rows.size)
        assertTrue(paged.groups.single().showMore?.kind is ShowMoreKind.Project)
        val more = DrawerBodies.build(inputs(many, sort = SidebarSortMode.Project, visible = VisibleCounts(projects = mapOf(paged.groups.single().id to 60)))) as DrawerBody.Projects
        assertEquals(35, more.groups.single().rows.size)
    }

    @Test fun `project mode groups the Termux home under Home and keeps Chat for the kernel's chat workspace (A22)`() {
        val home = "/data/data/com.termux/files/home"
        val rows = listOf(
            Rows.row("h1", 10_000) { it.copy(workspaceRoot = home) },
            Rows.row("c1", 9_000) { it.copy(workspaceRoot = "$home/.cline/data/workspaces/chat") },
            Rows.row("h2", 8_000) { it.copy(workspaceRoot = "$home/") },
            Rows.row("p1", 7_000) { it.copy(workspaceRoot = "$home/projects/home") },
        )
        val body = DrawerBodies.build(inputs(rows, sort = SidebarSortMode.Project)) as DrawerBody.Projects
        // By path alone: the recent-workspaces catalog leaves the home out, so the label cannot come from it.
        assertEquals(listOf(ProjectLabel.Home, ProjectLabel.Chat, ProjectLabel.Path("projects/home")), body.groups.map { it.label })
        assertEquals(listOf("h1", "h2"), body.groups[0].rows.map { (it as DrawerRow.Session).summary.id })
    }

    @Test fun `schedule group expansion follows the active run unless toggled`() {
        val rows = listOf(scheduled("r1", 9_000), scheduled("r2", 8_000))
        fun group(active: String?, toggles: Map<String, Boolean> = emptyMap()) =
            ((DrawerBodies.build(inputs(rows, active = active, toggles = toggles)) as DrawerBody.Categories).sections.single().rows.single() as DrawerRow.ScheduleGroup)
        assertEquals(false, group(null).expanded)
        assertEquals(true, group("r2").expanded)
        assertEquals(true, group("r2").containsActive)
        assertEquals(false, group("r2", mapOf("schedule:sch1" to false)).expanded)
        assertEquals(true, group(null, mapOf("schedule:sch1" to true)).expanded)
    }

    @Test fun `schedule group dots - running over unread`() {
        val running = scheduled("r1", 9_000).let { it.copy(summary = it.summary.copy(kernelStatus = KernelSessionStatus.Running)) }
        val unread = scheduled("r2", 8_000).copy(flags = SessionFlags(unread = true))
        val g = ((DrawerBodies.build(inputs(listOf(running, unread))) as DrawerBody.Categories).sections.single().rows.single() as DrawerRow.ScheduleGroup)
        assertTrue(g.running)
        assertTrue(g.unread)
    }

    @Test fun `rows carry the one visual`() {
        val rows = listOf(Rows.row("a", flags = SessionFlags(openApprovalIds = setOf("x"))))
        val body = DrawerBodies.build(inputs(rows)) as DrawerBody.Flat
        assertEquals(SessionVisual.ApprovalRequired, (body.rows.single() as DrawerRow.Session).visual)
    }

    @Test fun `task rows exclude pinned and scheduled`() {
        val rows = listOf(Rows.row("p") { it.copy(pinned = true) }, scheduled("s", 5)) + Rows.tasks(2)
        assertEquals(listOf("t0", "t1"), DrawerBodies.taskRows(inputs(rows)).map { it.id })
    }

    @Test fun `stable order holds a pure reorder for five seconds`() {
        val order = StableOrder()
        val a = Rows.row("a", 2)
        val b = Rows.row("b", 1)
        assertEquals(listOf("a", "b"), order.apply(listOf(a, b), nowMs = 0).map { it.id })
        assertEquals(listOf("a", "b"), order.apply(listOf(b, a), nowMs = 1_000).map { it.id })
        assertEquals(5_000L, order.pendingResortAt)
        assertEquals(listOf("b", "a"), order.apply(listOf(b, a), nowMs = 5_000).map { it.id })
        // A new member applies at once.
        val c = Rows.row("c", 3)
        assertEquals(listOf("c", "b", "a"), order.apply(listOf(c, b, a), nowMs = 5_100).map { it.id })
        assertNull(order.pendingResortAt)
    }
}
