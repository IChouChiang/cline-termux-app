package dev.cline.termux.data.workspace

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory `workspace.active` / `workspace.recents` with the AppPreferences MRU rule. */
class InMemoryWorkspacePrefs : WorkspacePrefs {
    val activeFlow = MutableStateFlow<WorkspaceChoice>(WorkspaceChoice.Chat)
    val recentsFlow = MutableStateFlow<List<String>>(emptyList())
    override val active: Flow<WorkspaceChoice> get() = activeFlow
    override suspend fun active(): WorkspaceChoice = activeFlow.value
    override val recents: Flow<List<String>> get() = recentsFlow
    override suspend fun recents(): List<String> = recentsFlow.value
    override suspend fun setActive(choice: WorkspaceChoice) {
        activeFlow.value = choice
        if (choice is WorkspaceChoice.Folder) {
            recentsFlow.value = listOf(choice.path) + (recentsFlow.value - choice.path).take(WorkspaceStore.MAX_RECENTS - 1)
        }
    }
    override suspend fun removeRecent(path: String) {
        recentsFlow.value = recentsFlow.value - path
    }
}

/** SP S9.3: validation, adoption, MRU, the recents merge and the session workspaces. */
class WorkspaceStoreTest {
    private val home = "/data/data/com.termux/files/home"
    private val termux = FakeTermuxExecutor()
    private val prefs = InMemoryWorkspacePrefs()
    private val store = WorkspaceStore(termux, prefs)

    @Test fun `a folder is validated with realpath then find and stored canonical`() = runTest {
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.success("$home/projects/app\n") }
        termux.on(ClineCommands.Labels.IS_DIRECTORY) { TermuxResult.success("$home/projects/app\n") }
        val result = store.open("~/projects/app/")
        assertEquals(WorkspaceValidation.Valid("$home/projects/app"), result)
        assertEquals(listOf("-e", "$home/projects/app/"), termux.commandsLabelled(ClineCommands.Labels.REALPATH).single().args)
        assertEquals(listOf("$home/projects/app", "-maxdepth", "0", "-type", "d"), termux.commandsLabelled(ClineCommands.Labels.IS_DIRECTORY).single().args)
        assertEquals(WorkspaceChoice.Folder("$home/projects/app"), store.activeNow())
        assertEquals(listOf("$home/projects/app"), prefs.recentsFlow.value)
    }

    @Test fun `a missing path or a file is invalid and changes nothing`() = runTest {
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.failure(1, "realpath: /x: No such file or directory") }
        assertEquals(WorkspaceValidation.Invalid("/x"), store.open("/x"))
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.success("$home/notes.txt\n") }
        termux.on(ClineCommands.Labels.IS_DIRECTORY) { TermuxResult.success("") }
        assertEquals(WorkspaceValidation.Invalid("~/notes.txt"), store.open("~/notes.txt"))
        assertEquals(WorkspaceChoice.Chat, store.activeNow())
        assertEquals(WorkspaceValidation.Invalid(" "), store.validate(" "))
    }

    @Test fun `Termux failures surface as blocked`() = runTest {
        termux.presence = TermuxPresence.Missing
        assertTrue((store.validate("/sdcard") as WorkspaceValidation.Blocked).error is TermuxUnavailable.NotInstalled)
    }

    @Test fun `recents are an MRU of at most 20`() = runTest {
        repeat(22) { store.adopt("/p/$it") }
        store.adopt("/p/5")
        val recents = prefs.recentsFlow.value
        assertEquals(20, recents.size)
        assertEquals("/p/5", recents.first())
        assertEquals("/p/21", recents[1])
        store.selectChat()
        assertEquals(WorkspaceChoice.Chat, store.activeNow())
        assertEquals(20, prefs.recentsFlow.value.size)
    }

    @Test fun `the catalog merges active, recents and sessions, then filters`() = runTest {
        prefs.activeFlow.value = WorkspaceChoice.Folder("$home/a")
        prefs.recentsFlow.value = listOf("$home/b", "$home/a/", home, "$home/.cline/data/workspaces/chat", "$home/storage/shared")
        store.recentsFromSessions(
            listOf(
                summary("s1", "$home/c", activityAt = 2_000),
                summary("s2", "$home/b", activityAt = 3_000),
                summary("s3", "$home/.cline/worktrees/x/repo", activityAt = 4_000),
                summary("s4", "$home/d", activityAt = 1_000),
            ),
        )
        assertEquals(listOf("$home/b", "$home/c", "$home/d"), store.sessionWorkspaces.value)
        assertEquals(listOf("$home/a", "$home/b", "$home/c", "$home/d"), store.catalog.first())
    }

    @Test fun `session workspaces refresh only when the source answers`() = runTest {
        var answer: List<SessionSummary>? = listOf(summary("s1", "$home/x", activityAt = 1))
        val withSource = WorkspaceStore(termux, prefs, sessionSource = { answer })
        withSource.refreshSessionWorkspaces()
        assertEquals(listOf("$home/x"), withSource.sessionWorkspaces.value)
        answer = null
        withSource.refreshSessionWorkspaces()
        assertEquals(listOf("$home/x"), withSource.sessionWorkspaces.value)
    }

    @Test fun `rows filter by substring and show the home as tilde`() {
        val rows = WorkspaceStore.rows(listOf("$home/Projects/App", "/sdcard/x"), WorkspaceChoice.Folder("$home/Projects/App"), "app")
        assertEquals(listOf(WorkspaceRow("$home/Projects/App", "~/Projects/App", isActive = true)), rows)
        assertEquals(2, WorkspaceStore.rows(listOf("$home/Projects/App", "/sdcard/x"), WorkspaceChoice.Chat, "").size)
        assertEquals(1, WorkspaceStore.rows(listOf("$home/Projects/App", "/sdcard/x"), WorkspaceChoice.Chat, "~/proj").size)
    }

    private fun summary(id: String, root: String, activityAt: Long) = SessionSummary(
        id = id, title = id, hasCustomTitle = false, workspaceRoot = root, cwd = root,
        kernelStatus = KernelSessionStatus.parse("completed"), provider = "", model = "", source = "cli",
        pinned = false, createdAt = activityAt, activityAt = activityAt,
    )
}
