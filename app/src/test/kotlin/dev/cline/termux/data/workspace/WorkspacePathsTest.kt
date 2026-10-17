package dev.cline.termux.data.workspace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from apps/examples/desktop-app/webview/lib/workspace-paths.test.ts (every case; the
 * window storage cases run against the pure serialize/parse pair), plus the Android rules of
 * SP S9.3.
 */
class WorkspacePathsTest {
    /** The desktop module's state: no registered home, no task worktree root. */
    private val desktop = WorkspacePathRules(hostHome = "", extraExcludedPaths = emptyList())

    private fun selection(raw: String, env: String = "local") = desktop.parseWorkspaceSelectionStorage(raw, env)

    @Test fun `recognizes typed folder paths for manual entry`() {
        assertTrue(looksLikeFolderPath("/home/user/projects"))
        assertTrue(looksLikeFolderPath(" /home/user/projects/ "))
        assertTrue(looksLikeFolderPath("~"))
        assertTrue(looksLikeFolderPath("~/documents"))
        assertTrue(looksLikeFolderPath("C:\\Users\\me\\code"))
        assertTrue(looksLikeFolderPath("D:/projects"))
        assertFalse(looksLikeFolderPath("my-project"))
        assertFalse(looksLikeFolderPath("search text"))
        assertFalse(looksLikeFolderPath(""))
    }

    @Test fun `normalizes trailing separators and Windows path casing`() {
        assertEquals("/workspace/cline", normalizeWorkspacePath(" /workspace/cline/ "))
        assertEquals("c:\\users\\saoud\\cline", normalizeWorkspacePath("C:\\Users\\Saoud\\Cline\\"))
        assertEquals("/", normalizeWorkspacePath("/"))
    }

    @Test fun `detects absolute file paths across platforms`() {
        assertTrue(isAbsoluteFilePath("/Users/renee/cline/docs/a.mdx"))
        assertTrue(isAbsoluteFilePath("C:\\Users\\renee\\a.mdx"))
        assertTrue(isAbsoluteFilePath("C:/Users/renee/a.mdx"))
        assertTrue(isAbsoluteFilePath("\\\\server\\share\\a.mdx"))
        assertFalse(isAbsoluteFilePath("docs/a.mdx"))
        assertFalse(isAbsoluteFilePath("./docs/a.mdx"))
    }

    @Test fun `resolves relative diff paths against the session cwd`() {
        assertEquals("/Users/renee/cline/docs/a.mdx", resolveWorkspaceFilePath("docs/a.mdx", "/Users/renee/cline"))
        assertEquals("/Users/renee/cline/docs/a.mdx", resolveWorkspaceFilePath("./docs/a.mdx", "/Users/renee/cline/"))
        assertEquals("/Users/renee/cline/docs/a.mdx", resolveWorkspaceFilePath("/Users/renee/cline/docs/a.mdx", "/elsewhere"))
        assertEquals("docs/a.mdx", resolveWorkspaceFilePath("docs/a.mdx", null))
        assertEquals("C:\\Users\\renee\\docs\\a.mdx", resolveWorkspaceFilePath("docs\\a.mdx", "C:\\Users\\renee"))
    }

    @Test fun `retains known projects when discovery returns an incomplete subset`() {
        val known = listOf("/projects/a", "/projects/b", "/projects/c", "/projects/d")
        val afterFirstPick = mergeWorkspacePaths(known, listOf("/projects/e", "/projects/a/"))
        val afterSecondPick = mergeWorkspacePaths(afterFirstPick, listOf("/projects/f", "/projects/b"))
        assertEquals(listOf("/projects/a", "/projects/b", "/projects/c", "/projects/d", "/projects/e"), afterFirstPick)
        assertEquals(listOf("/projects/a", "/projects/b", "/projects/c", "/projects/d", "/projects/e", "/projects/f"), afterSecondPick)
    }

    @Test fun `keeps the first-seen order so earlier groups rank first`() {
        assertEquals(
            listOf("/projects/zulu", "/projects/mike", "/projects/alpha"),
            mergeWorkspacePaths(listOf("/projects/zulu", "/projects/mike"), listOf("/projects/alpha", "/projects/zulu/")),
        )
    }

    @Test fun `orders the catalog by the most recent session in each workspace`() {
        val paths = desktop.workspacePathsFromSessions(
            listOf(
                WorkspacePathSource(workspaceRoot = "/projects/old", startedAt = "2026-01-05T00:00:00Z"),
                WorkspacePathSource(workspaceRoot = "/projects/active", startedAt = "2026-02-01T00:00:00Z", endedAt = "2026-02-01T01:00:00Z"),
                WorkspacePathSource(workspaceRoot = "/projects/old", startedAt = "2026-03-01T00:00:00Z"),
                WorkspacePathSource(workspaceRoot = "/projects/mid", startedAt = "2026-02-15T00:00:00Z"),
                WorkspacePathSource(workspaceRoot = "/projects/undated"),
            ),
        )
        assertEquals(listOf("/projects/old", "/projects/mid", "/projects/active", "/projects/undated"), paths)
    }

    @Test fun `excludes cloud sessions so workspace never pollutes local recents`() {
        val paths = desktop.workspacePathsFromSessions(
            listOf(
                WorkspacePathSource(workspaceRoot = "/projects/local", startedAt = "2026-02-01T00:00:00Z"),
                WorkspacePathSource(workspaceRoot = "/workspace", cwd = "/workspace", origin = "cloud", startedAt = "2026-03-01T00:00:00Z"),
            ),
        )
        assertEquals(listOf("/projects/local"), paths)
    }

    @Test fun `builds the project catalog from every loaded history workspace`() {
        val sessions = List(25) { WorkspacePathSource(workspaceRoot = "/projects/project-${(it + 1).toString().padStart(2, '0')}") } +
            WorkspacePathSource(workspaceRoot = "/projects/project-01/")
        val paths = desktop.workspacePathsFromSessions(sessions)
        assertEquals(25, paths.size)
        assertTrue("/projects/project-25" in paths)
    }

    @Test fun `restores the selected project and catalog across thread remounts`() {
        assertEquals(
            WorkspaceSelectionStorage("/projects/selected/", listOf("/projects/one", "/projects/selected")),
            selection("""{"environments":{"local":{"lastWorkspace":"/projects/selected/","workspaces":["/projects/one","/projects/selected"]}}}"""),
        )
        assertEquals(WorkspaceSelectionStorage(), selection("not json"))
    }

    @Test fun `does not interpret path-only v1 data as an environment selection`() {
        assertEquals(WorkspaceSelectionStorage(), selection("""{"lastWorkspace":"/projects/legacy","workspaces":["/projects/legacy"]}"""))
    }

    @Test fun `reads and writes each environment without replacing the others`() {
        var raw: String? = null
        raw = desktop.serializeWorkspaceSelectionStorage(raw, "local", WorkspaceSelectionStorage("/Users/dev/local-app", listOf("/Users/dev/local-app")))
        raw = desktop.serializeWorkspaceSelectionStorage(raw, "pi-host", WorkspaceSelectionStorage("/home/pi/remote-app", listOf("/home/pi/other-app", "/home/pi/remote-app")))
        assertEquals(WorkspaceSelectionStorage("/Users/dev/local-app", listOf("/Users/dev/local-app")), selection(raw, "local"))
        assertEquals(WorkspaceSelectionStorage("/home/pi/remote-app", listOf("/home/pi/other-app", "/home/pi/remote-app")), selection(raw, "pi-host"))
        assertEquals(
            Json.parseToJsonElement(
                """{"environments":{"local":{"lastWorkspace":"/Users/dev/local-app","workspaces":["/Users/dev/local-app"]},
                "pi-host":{"lastWorkspace":"/home/pi/remote-app","workspaces":["/home/pi/other-app","/home/pi/remote-app"]}}}""",
            ),
            Json.parseToJsonElement(raw),
        )
    }

    @Test fun `excludes cline-internal paths from the workspace catalog`() {
        assertTrue(desktop.isExcludedWorkspacePath("/Users/beatrix/.cline/worktrees/5e0b3/sdk-wip"))
        assertTrue(desktop.isExcludedWorkspacePath("/Users/beatrix/.cline/plugins/_installed/git/github.com/example-plugin"))
        assertTrue(desktop.isExcludedWorkspacePath("C:\\Users\\Saoud\\.cline\\worktrees\\abc"))
    }

    @Test fun `recognizes task worktrees only under the root the sidecar reports`() {
        assertFalse(desktop.isTaskWorktreePath("/Users/beatrix/.cline/worktrees/5e0b3/sdk-wip"))
        val custom = WorkspacePathRules(hostHome = "", taskWorktreeRoot = "/srv/cline-dir/worktrees/")
        assertTrue(custom.isTaskWorktreePath("/srv/cline-dir/worktrees/5e0b3/sdk-wip"))
        assertFalse(custom.isTaskWorktreePath("/Users/beatrix/.cline/worktrees/5e0b3/sdk-wip"))
        assertFalse(custom.isTaskWorktreePath("/srv/cline-dir/worktrees/5e0b3"))
        assertFalse(custom.isTaskWorktreePath("/srv/cline-dir/worktrees/5e0b3/sdk-wip/packages"))
        assertFalse(custom.isTaskWorktreePath("/srv/cline-dir/worktrees-old/5e0b3/x"))
        assertFalse(custom.isTaskWorktreePath("/projects/example/.cline/worktrees/app"))
        assertFalse(custom.isTaskWorktreePath(""))
        val windows = WorkspacePathRules(hostHome = "", taskWorktreeRoot = "C:\\Users\\Saoud\\.cline\\worktrees")
        assertTrue(windows.isTaskWorktreePath("C:\\Users\\Saoud\\.cline\\worktrees\\abc12\\app\\"))
        assertFalse(windows.isTaskWorktreePath("C:\\Users\\Saoud\\projects\\app"))
    }

    @Test fun `excludes the SDK chat workspace from discovery and stored selections`() {
        val temporary = "/home/host/.cline/data/workspaces/chat"
        assertTrue(desktop.isExcludedWorkspacePath(temporary))
        assertEquals(
            listOf("/projects/app"),
            desktop.workspacePathsFromSessions(listOf(WorkspacePathSource(workspaceRoot = temporary), WorkspacePathSource(workspaceRoot = "/projects/app"))),
        )
        assertEquals(
            WorkspaceSelectionStorage("", listOf("/projects/app")),
            selection("""{"environments":{"local":{"lastWorkspace":"$temporary","workspaces":["$temporary","/projects/app"]}}}"""),
        )
    }

    @Test fun `excludes a non-standard home and its Desktop but keeps projects inside them`() {
        val rules = WorkspacePathRules(hostHome = "/srv/homes/bea/", extraExcludedPaths = emptyList())
        assertTrue(rules.isExcludedWorkspacePath("/srv/homes/bea"))
        assertTrue(rules.isExcludedWorkspacePath("/srv/homes/bea/Desktop"))
        assertFalse(rules.isExcludedWorkspacePath("/srv/homes/bea/projects/app"))
        assertFalse(rules.isExcludedWorkspacePath("/srv/homes/beatrix"))
    }

    @Test fun `matches Windows homes case-insensitively`() {
        val rules = WorkspacePathRules(hostHome = "D:\\Homes\\Bea", extraExcludedPaths = emptyList())
        assertTrue(rules.isExcludedWorkspacePath("d:\\homes\\bea\\"))
        assertTrue(rules.isExcludedWorkspacePath("D:\\Homes\\Bea\\Desktop"))
        assertFalse(rules.isExcludedWorkspacePath("D:\\Homes\\Bea\\cline"))
    }

    @Test fun `excludes home and Desktop directories but keeps projects inside them`() {
        assertTrue(desktop.isExcludedWorkspacePath("/Users/beatrix"))
        assertTrue(desktop.isExcludedWorkspacePath("/Users/beatrix/Desktop/"))
        assertTrue(desktop.isExcludedWorkspacePath("/home/beatrix"))
        assertTrue(desktop.isExcludedWorkspacePath("/root"))
        assertTrue(desktop.isExcludedWorkspacePath("C:\\Users\\Saoud"))
        assertTrue(desktop.isExcludedWorkspacePath("C:\\Users\\Saoud\\Desktop"))
        assertFalse(desktop.isExcludedWorkspacePath("/Users/beatrix/dev/cline"))
        assertFalse(desktop.isExcludedWorkspacePath("/Users/beatrix/Desktop/my-app"))
        assertFalse(desktop.isExcludedWorkspacePath("/home/beatrix/projects"))
        assertFalse(desktop.isExcludedWorkspacePath("/workspace/cline"))
        assertFalse(desktop.isExcludedWorkspacePath("C:\\Users\\Saoud\\Cline"))
    }

    @Test fun `filters excluded paths out of session-derived workspaces`() {
        val paths = desktop.workspacePathsFromSessions(
            listOf(
                WorkspacePathSource(workspaceRoot = "/projects/app"),
                WorkspacePathSource(workspaceRoot = "/Users/beatrix/.cline/worktrees/97815/sdk-wip"),
                WorkspacePathSource(cwd = "/Users/beatrix/Desktop"),
                WorkspacePathSource(cwd = "/Users/beatrix"),
                WorkspacePathSource(cwd = "/projects/tool"),
            ),
        )
        assertEquals(listOf("/projects/app", "/projects/tool"), paths)
    }

    @Test fun `scrubs excluded paths from the stored catalog while keeping the selection`() {
        assertEquals(
            WorkspaceSelectionStorage("/Users/beatrix/Desktop", listOf("/projects/one")),
            selection(
                """{"environments":{"local":{"lastWorkspace":"/Users/beatrix/Desktop","workspaces":["/projects/one","/Users/beatrix/.cline/worktrees/5e0b3/sdk-wip","/Users/beatrix"]}}}""",
            ),
        )
        assertEquals(listOf("/projects/one"), desktop.filterWorkspacePaths(listOf("/projects/one", "/Users/beatrix/Desktop")))
    }

    @Test fun `scopes stored and session-derived workspaces by environment`() {
        val raw = """{"environments":{"local":{"lastWorkspace":"/Users/dev/local-app","workspaces":["/Users/dev/local-app"]},"pi-host":{"lastWorkspace":"/home/pi/remote-app","workspaces":["/home/pi/remote-app"]}}}"""
        assertEquals(listOf("/Users/dev/local-app"), selection(raw, "local").workspaces)
        assertEquals(listOf("/home/pi/remote-app"), selection(raw, "pi-host").workspaces)
        assertEquals(
            listOf("/home/pi/remote-app"),
            desktop.workspacePathsFromSessions(
                listOf(
                    WorkspacePathSource(workspaceRoot = "/Users/dev/local-app", environmentId = "local"),
                    WorkspacePathSource(workspaceRoot = "/home/pi/remote-app", environmentId = "pi-host"),
                    WorkspacePathSource(workspaceRoot = "/home/other/app", environmentId = "other-host"),
                ),
                "pi-host",
            ),
        )
    }

    // Android (SP S9.3)

    @Test fun `the Termux rules exclude the home and shared storage itself`() {
        val termux = WorkspacePathRules.Termux
        assertTrue(termux.isExcludedWorkspacePath(TERMUX_HOME))
        assertTrue(termux.isExcludedWorkspacePath("$TERMUX_HOME/storage/shared/"))
        assertTrue(termux.isExcludedWorkspacePath("$TERMUX_HOME/.cline/data/workspaces/chat"))
        assertFalse(termux.isExcludedWorkspacePath("$TERMUX_HOME/cline-termux-app"))
        assertFalse(termux.isExcludedWorkspacePath("$TERMUX_HOME/storage/shared/Documents/notes"))
    }

    @Test fun `formats and expands the Termux home`() {
        assertEquals("~", formatWorkspacePath(TERMUX_HOME))
        assertEquals("~/projects/app", formatWorkspacePath("$TERMUX_HOME/projects/app"))
        assertEquals("~/code", formatWorkspacePath("/Users/bea/code"))
        assertEquals("~/x/y", formatWorkspacePath("C:\\Users\\bea\\x\\y"))
        assertEquals("/srv/app", formatWorkspacePath("/srv/app"))
        assertEquals(TERMUX_HOME, expandHomePath("~"))
        assertEquals("$TERMUX_HOME/app", expandHomePath(" ~/app "))
        assertEquals("/srv", expandHomePath("/srv"))
    }

    @Test fun `chat workspace detection follows the data-dir layout`() {
        assertTrue(isChatWorkspacePath("/home/host/.cline/data/workspaces/chat/"))
        assertTrue(isChatWorkspacePath("C:\\Users\\me\\.cline\\data\\workspaces\\chat"))
        assertFalse(isChatWorkspacePath("/home/host/.cline/data/workspaces/chat/project"))
        assertFalse(isChatWorkspacePath(".cline/data/workspaces/chat"))
        assertEquals("app", workspaceName("/a/app/"))
        assertEquals("workspace", workspaceName(""))
    }
}
