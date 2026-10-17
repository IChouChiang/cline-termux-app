package dev.cline.termux.ui.workspace

import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.workspace.InMemoryWorkspacePrefs
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.model.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class WorkspacePickerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val home = "/data/data/com.termux/files/home"
    private val termux = FakeTermuxExecutor()
    private val prefs = InMemoryWorkspacePrefs()
    private val store = WorkspaceStore(termux, prefs)
    private val block = MutableStateFlow<KernelBlock?>(null)

    private class Harness(val vm: WorkspacePickerViewModel, val effects: MutableList<WorkspacePickerEffect>) {
        val state get() = vm.uiState.value
    }

    private fun TestScope.harness(): Harness {
        val vm = WorkspacePickerViewModel(store, block)
        val effects = mutableListOf<WorkspacePickerEffect>()
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        return Harness(vm, effects)
    }

    private fun validFolder(path: String) {
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.success("$path\n") }
        termux.on(ClineCommands.Labels.IS_DIRECTORY) { TermuxResult.success("$path\n") }
    }

    @Test fun `first run starts on chat with the empty text and stored recents show at once`() = runTest {
        val h = harness()
        assertEquals(WorkspaceChoice.Chat, h.state.active)
        assertTrue(h.state.recents.isEmpty())
        prefs.recentsFlow.value = listOf("$home/projects/app", "$home/.cline/data/workspaces/chat", home)
        // The chat workspace and the home directory are filtered out.
        assertEquals(listOf("~/projects/app"), h.state.recents.map { it.display })
    }

    @Test fun `a recent is validated adopted and closes`() = runTest {
        prefs.recentsFlow.value = listOf("$home/projects/app")
        validFolder("$home/projects/app")
        val h = harness()
        h.vm.select("$home/projects/app")
        settle()
        assertEquals(WorkspaceChoice.Folder("$home/projects/app"), prefs.activeFlow.value)
        assertEquals(listOf(WorkspacePickerEffect.Close), h.effects)
        assertTrue(h.state.recents.single().isActive)
        assertNull(h.state.validating)
    }

    @Test fun `tapping the active folder only closes`() = runTest {
        prefs.activeFlow.value = WorkspaceChoice.Folder("$home/projects/app")
        val h = harness()
        h.vm.select("$home/projects/app/")
        settle()
        assertTrue(termux.commands.isEmpty())
        assertEquals(listOf(WorkspacePickerEffect.Close), h.effects)
    }

    @Test fun `a typed path that does not exist shows the error and the sheet stays`() = runTest {
        termux.on(ClineCommands.Labels.REALPATH) { TermuxResult.failure(1, "realpath: no such file") }
        val h = harness()
        h.vm.setQuery("~/does-not-exist")
        assertTrue(h.state.pathLike)
        h.vm.openTyped()
        settle()
        assertEquals("~/does-not-exist", h.state.error)
        assertTrue(h.effects.isEmpty())
        assertEquals(WorkspaceChoice.Chat, prefs.activeFlow.value)
        // Typing again clears the error (desktop setError(null) on change).
        h.vm.setQuery("~/does-not-exis")
        assertNull(h.state.error)
    }

    @Test fun `non path text is not opened`() = runTest {
        val h = harness()
        h.vm.setQuery("projects")
        assertFalse(h.state.pathLike)
        h.vm.openTyped()
        settle()
        assertTrue(termux.commands.isEmpty())
    }

    @Test fun `while a recent validates the other actions are ignored`() = runTest {
        prefs.recentsFlow.value = listOf("$home/a", "$home/b")
        termux.latencyMs = 1_000
        validFolder("$home/a")
        val h = harness()
        h.vm.select("$home/a")
        assertEquals("$home/a", h.state.validating)
        assertTrue(h.state.busy)
        h.vm.justChat()
        h.vm.openBrowser()
        h.vm.select("$home/b")
        settle()
        assertEquals(WorkspaceChoice.Folder("$home/a"), prefs.activeFlow.value)
        assertEquals(listOf(WorkspacePickerEffect.Close), h.effects)
    }

    @Test fun `just chat and open folder`() = runTest {
        prefs.activeFlow.value = WorkspaceChoice.Folder("$home/projects/x")
        val h = harness()
        h.vm.openBrowser()
        settle()
        assertEquals(WorkspacePickerEffect.OpenBrowser("$home/projects/x"), h.effects.single())
        h.vm.justChat()
        settle()
        assertEquals(WorkspaceChoice.Chat, prefs.activeFlow.value)
        assertEquals(WorkspacePickerEffect.Close, h.effects.last())
        h.vm.openBrowser()
        settle()
        assertEquals(WorkspacePickerEffect.OpenBrowser(home), h.effects.last())
    }

    @Test fun `Termux failures show the blocked card and retry runs the validation again`() = runTest {
        termux.never(ClineCommands.Labels.REALPATH)
        val h = harness()
        h.vm.select("/sdcard/Code")
        settle()
        assertEquals(KernelBlock.TimedOut, h.state.blocked)
        validFolder("/storage/emulated/0/Code")
        h.vm.retry()
        settle()
        assertNull(h.state.blocked)
        assertEquals(WorkspaceChoice.Folder("/storage/emulated/0/Code"), prefs.activeFlow.value)
    }

    @Test fun `the kernel gate blocks folder work but just chat stays`() = runTest {
        val h = harness()
        block.value = KernelBlock.PermissionMissing
        assertEquals(KernelBlock.PermissionMissing, h.state.blocked)
        h.vm.justChat()
        settle()
        assertEquals(listOf(WorkspacePickerEffect.Close), h.effects)
        termux.permitted = false
        assertTrue(runCatching { termux.run(ClineCommands.realpath("/x")) }.exceptionOrNull() is TermuxUnavailable.PermissionMissing)
    }

    @Test fun `gate mapping only blocks on RUN_COMMAND prerequisites`() {
        assertEquals(KernelBlock.TermuxMissing, WorkspacePickerViewModel.blockOf(KernelGate.Blocked(CheckId.Termux)))
        assertEquals(KernelBlock.PermissionMissing, WorkspacePickerViewModel.blockOf(KernelGate.Blocked(CheckId.Permission)))
        assertEquals(KernelBlock.ExternalAppsBlocked, WorkspacePickerViewModel.blockOf(KernelGate.Blocked(CheckId.ExternalApps)))
        assertEquals(KernelBlock.PermissionMissing, WorkspacePickerViewModel.blockOf(KernelGate.DegradedNoCommands))
        assertNull(WorkspacePickerViewModel.blockOf(KernelGate.Blocked(CheckId.Kernel)))
        assertNull(WorkspacePickerViewModel.blockOf(KernelGate.Ready))
        assertNull(WorkspacePickerViewModel.blockOf(KernelGate.Down(KernelGate.DownVariant.Stopped, null)))
    }

    @Test fun `opening refreshes session workspaces and keeps stored ones while the hub is down`() = runTest {
        prefs.recentsFlow.value = listOf("$home/notes")
        val h = harness()
        h.vm.onOpened()
        settle()
        assertEquals(listOf("$home/notes"), h.state.recents.map { it.path })
        h.vm.setQuery("NOT")
        assertEquals(1, h.state.recents.size)
        h.vm.setQuery("zzz")
        assertTrue(h.state.recents.isEmpty())
    }
}
