package dev.cline.termux.ui.workspace

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.InMemoryWorkspacePrefs
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.TERMUX_SHARED_STORAGE
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.model.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderBrowserViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val termux = FakeTermuxExecutor()
    private val prefs = InMemoryWorkspacePrefs()
    private val store = WorkspaceStore(termux, prefs)
    private val folders = HashMap<String, List<String>>()

    init {
        termux.on(ClineCommands.Labels.LIST_DIRECTORIES) { command ->
            val dir = command.args.first()
            val children = folders[dir] ?: return@on TermuxResult.failure(1, "find: ‘$dir’: No such file or directory")
            TermuxResult.success(children.joinToString("\n") { "$dir/$it" } + "\n")
        }
        termux.on(ClineCommands.Labels.HAS_SHARED_STORAGE) { TermuxResult.failure(1, "find: storage: No such file or directory") }
        folders[TERMUX_HOME] = listOf("projects", "Notes", "apps")
        folders["$TERMUX_HOME/projects"] = listOf("x", "cline-termux-app")
        folders["$TERMUX_HOME/projects/x"] = emptyList()
        folders[TERMUX_HOME.substringBeforeLast('/')] = listOf("home", "usr")
    }

    private class Harness(val vm: FolderBrowserViewModel, val effects: MutableList<FolderBrowserEffect>, val saved: SavedStateHandle) {
        val state get() = vm.uiState.value
    }

    private fun TestScope.harness(start: String? = TERMUX_HOME, saved: SavedStateHandle = SavedStateHandle()): Harness {
        val vm = FolderBrowserViewModel(saved, start, store, DirectorySource.of(termux))
        val effects = mutableListOf<FolderBrowserEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        return Harness(vm, effects, saved)
    }

    @Test fun `the start folder lists subfolders sorted case-insensitively`() = runTest {
        val h = harness()
        settle()
        assertEquals(listOf("apps", "Notes", "projects"), h.state.entries.map { it.name })
        assertTrue(h.state.atHome)
        assertFalse(h.state.loading)
        assertEquals(SharedStorageState.NotSetUp, h.state.sharedStorage)
        assertTrue(h.state.showStorageSetup)
        assertEquals(listOf("$TERMUX_HOME", "-maxdepth", "1", "-mindepth", "1", "-type", "d", "-not", "-name", ".*"), termux.commandsLabelled(ClineCommands.Labels.LIST_DIRECTORIES).first().args)
    }

    @Test fun `browsing pushes history and back walks it to the start`() = runTest {
        val h = harness()
        settle()
        h.vm.open(h.state.entries.first { it.name == "projects" })
        settle()
        h.vm.open(h.state.entries.first { it.name == "x" })
        settle()
        assertEquals(listOf(TERMUX_HOME, "$TERMUX_HOME/projects", "$TERMUX_HOME/projects/x"), h.state.history)
        assertTrue(h.state.entries.isEmpty())
        assertTrue(h.vm.back())
        settle()
        assertEquals("$TERMUX_HOME/projects", h.state.path)
        assertTrue(h.vm.back())
        settle()
        assertEquals(TERMUX_HOME, h.state.path)
        assertFalse(h.state.canGoBack)
        assertFalse(h.vm.back())
    }

    @Test fun `home and up push their target while refresh does not`() = runTest {
        val h = harness(start = "~/projects/x")
        settle()
        assertEquals("$TERMUX_HOME/projects/x", h.state.path)
        h.vm.up()
        settle()
        assertEquals("$TERMUX_HOME/projects", h.state.path)
        h.vm.refresh()
        settle()
        assertEquals(2, h.state.history.size)
        h.vm.home()
        settle()
        assertEquals(3, h.state.history.size)
        assertTrue(h.state.atHome)
        h.vm.up()
        settle()
        assertEquals(TERMUX_HOME.substringBeforeLast('/'), h.state.path)
    }

    @Test fun `history survives process death through the saved state`() = runTest {
        val saved = SavedStateHandle()
        val h = harness(saved = saved)
        settle()
        h.vm.open(h.state.entries.first { it.name == "projects" })
        settle()
        val restored = harness(start = TERMUX_HOME, saved = saved)
        settle()
        assertEquals("$TERMUX_HOME/projects", restored.state.path)
        assertEquals(2, restored.state.history.size)
    }

    @Test fun `parent is client side and null only at the root`() {
        assertNull(FolderBrowserViewModel.parentOf("/"))
        assertEquals("/", FolderBrowserViewModel.parentOf("/data"))
        assertEquals("/data/data", FolderBrowserViewModel.parentOf("/data/data/com.termux"))
        assertEquals("$TERMUX_HOME/projects", FolderBrowserViewModel.normalize("~/projects/"))
        assertEquals("/", FolderBrowserViewModel.normalize("/"))
    }

    @Test fun `a listing error shows the stderr line and disables use`() = runTest {
        val h = harness(start = "$TERMUX_SHARED_STORAGE/Android")
        settle()
        assertTrue(h.state.listFailed)
        assertEquals("find: ‘$TERMUX_SHARED_STORAGE/Android’: No such file or directory", h.state.error)
        assertFalse(h.state.canUse)
    }

    @Test fun `permission denied inside shared storage offers the setup row`() = runTest {
        termux.on(ClineCommands.Labels.HAS_SHARED_STORAGE) { TermuxResult.success("$TERMUX_HOME/storage\n") }
        termux.on(ClineCommands.Labels.LIST_DIRECTORIES) { TermuxResult.failure(1, "find: ‘$TERMUX_SHARED_STORAGE’: Permission denied") }
        val h = harness(start = TERMUX_SHARED_STORAGE)
        settle()
        assertEquals(SharedStorageState.Available, h.state.sharedStorage)
        assertTrue(h.state.inSharedStorage)
        assertTrue(h.state.showStorageSetup)
    }

    @Test fun `more than 500 folders are truncated`() = runTest {
        folders[TERMUX_HOME] = (1..600).map { "d$it" }
        val h = harness()
        settle()
        assertEquals(500, h.state.entries.size)
        assertTrue(h.state.truncated)
    }

    @Test fun `use this folder validates adopts and finishes`() = runTest {
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.success("$TERMUX_HOME/projects/x\n") }
        termux.on(ClineCommands.Labels.IS_DIRECTORY) { TermuxResult.success("$TERMUX_HOME/projects/x\n") }
        val h = harness(start = "$TERMUX_HOME/projects/x")
        settle()
        h.vm.useThisFolder()
        settle()
        assertEquals(listOf(FolderBrowserEffect.Done("$TERMUX_HOME/projects/x")), h.effects)
        assertEquals(WorkspaceChoice.Folder("$TERMUX_HOME/projects/x"), prefs.activeFlow.value)
    }

    @Test fun `shared storage setup brings Termux forward and re-checks after it exits`() = runTest {
        val h = harness()
        settle()
        h.vm.setUpSharedStorage()
        settle()
        assertEquals(SharedStorageState.SettingUp, h.state.sharedStorage)
        assertEquals(listOf(FolderBrowserEffect.BringTermuxForward), h.effects)
        val handle = termux.detached.single()
        assertEquals(ClineCommands.Labels.SETUP_STORAGE, handle.command.label)
        // The user granted storage in Termux; the command exits 0.
        termux.on(ClineCommands.Labels.HAS_SHARED_STORAGE) { TermuxResult.success("$TERMUX_HOME/storage\n") }
        handle.exitWith(TermuxResult.success(""))
        settle()
        assertEquals(SharedStorageState.Available, h.state.sharedStorage)
        assertFalse(h.state.showStorageSetup)
    }

    @Test fun `a failed storage setup shows its stderr`() = runTest {
        val h = harness()
        settle()
        h.vm.setUpSharedStorage()
        termux.detached.single().exitWith(TermuxResult.failure(1, "termux-setup-storage: permission denied"))
        settle()
        assertEquals(SharedStorageState.NotSetUp, h.state.sharedStorage)
        assertEquals("termux-setup-storage: permission denied", h.state.storageError)
    }

    @Test fun `Termux failures show the blocked card`() = runTest {
        termux.throwing(ClineCommands.Labels.LIST_DIRECTORIES, TermuxUnavailable.ExternalAppsBlocked("allow-external-apps"))
        val h = harness()
        settle()
        assertEquals(KernelBlock.ExternalAppsBlocked, h.state.blocked)
        assertFalse(h.state.canUse)
    }
}
