package dev.cline.termux.ui.changes

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.diff.DiffStoreState
import dev.cline.termux.data.diff.SessionDiffHunk
import dev.cline.termux.data.diff.SessionDiffState
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.data.diff.SessionFileDiff
import dev.cline.termux.data.diff.UnifiedDiffText
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.ui.navigation.ShellLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class ChangesViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sessionId = "1791064689782_kck3p"
    private val cwd = "/data/data/com.termux/files/home/ws/demo"
    private val hello = SessionFileDiff("hello.py", 2, 0, listOf(SessionDiffHunk(1, 1, "", "print(\"hello\")\nprint(\"hi\")\n")))
    private val notes = SessionFileDiff("notes.txt", 0, 1, listOf(SessionDiffHunk(2, 2, "b\n", "")))
    private val deleted = SessionFileDiff("gone.txt", 0, 0, emptyList())

    private val diff = MutableStateFlow<DiffStoreState>(DiffStoreState.Loading)
    private val session = MutableStateFlow(SessionState(sessionId))
    private val kernel = MutableStateFlow(KernelConnectionVisual.Connected)
    private val removed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var retries = 0
    private var starts = 0
    private var now = 10_000L

    private fun ready(vararg files: SessionFileDiff, at: Long = 1L, changed: Set<String> = emptySet()) = DiffStoreState.Ready(
        diff = SessionDiffState(files.toList(), SessionDiffSummary(files.sumOf { it.additions }, files.sumOf { it.deletions })),
        cwd = cwd,
        updatedAt = at,
        changedPaths = changed,
    )

    private fun TestScope.newVm(handle: SavedStateHandle = SavedStateHandle()): Pair<ChangesViewModel, MutableList<ChangesEffect>> {
        val vm = ChangesViewModel(
            sessionId = sessionId,
            savedState = handle,
            diff = diff,
            session = session,
            kernel = kernel,
            removedSessions = removed,
            retrySeed = { retries++ },
            startKernel = { starts++ },
            noHunksNote = "No hunk details available.",
            nowMs = { now },
            compute = UnconfinedTestDispatcher(testScheduler),
            warn = {},
        )
        val effects = mutableListOf<ChangesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        runCurrent()
        return vm to effects
    }

    @Test
    fun `loading then populated with every file expanded by default`() = runTest {
        val (vm, _) = newVm()
        assertEquals(ChangesUiState.Content.Loading, vm.state.value.content)
        assertFalse(vm.state.value.hasFiles)

        diff.value = ready(hello, notes)
        val state = vm.state.value
        val content = state.content as ChangesUiState.Content.Ready
        assertEquals(listOf("hello.py", "notes.txt"), content.files.map { it.path })
        assertEquals(SessionDiffSummary(2, 1), content.summary)
        assertEquals(setOf("hello.py", "notes.txt"), state.expanded)
        assertTrue(state.anyExpanded)
        assertEquals("hello.py", state.selectedFile?.path)
    }

    @Test
    fun `toggles survive a recreated ViewModel through the saved state handle`() = runTest {
        diff.value = ready(hello, notes)
        val handle = SavedStateHandle()
        val (vm, _) = newVm(handle)
        vm.onToggle("notes.txt")
        vm.onSelect("notes.txt")
        vm.onShowMore(HunkKey("hello.py", 0))
        assertEquals(setOf("hello.py"), vm.state.value.expanded)

        val (restored, _) = newVm(handle)
        assertEquals(setOf("notes.txt"), restored.state.value.collapsed)
        assertEquals("notes.txt", restored.state.value.selectedPath)
        assertEquals(800, restored.state.value.shownRows(HunkKey("hello.py", 0)))
        assertEquals(400, restored.state.value.shownRows(HunkKey("hello.py", 1)))
    }

    @Test
    fun `collapse all and expand all flip every file and the action`() = runTest {
        diff.value = ready(hello, notes)
        val (vm, _) = newVm()
        vm.onCollapseAll()
        assertEquals(emptySet<String>(), vm.state.value.expanded)
        assertFalse(vm.state.value.anyExpanded)
        vm.onExpandAll()
        assertEquals(setOf("hello.py", "notes.txt"), vm.state.value.expanded)
    }

    @Test
    fun `a file that appears later opens expanded`() = runTest {
        diff.value = ready(hello)
        val (vm, _) = newVm()
        vm.onToggle("hello.py")
        diff.value = ready(hello, notes, at = 2)
        assertEquals(setOf("notes.txt"), vm.state.value.expanded)
    }

    @Test
    fun `kernel down with nothing cached is Unavailable, cached content stays readable`() = runTest {
        kernel.value = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.StartFailed, null))
        val (vm, _) = newVm()
        assertEquals(ChangesUiState.Content.Unavailable, vm.state.value.content)
        diff.value = DiffStoreState.Failed("hub_connection_closed")
        assertEquals(ChangesUiState.Content.Unavailable, vm.state.value.content)

        diff.value = ready(hello)
        assertTrue(vm.state.value.content is ChangesUiState.Content.Ready)
        assertFalse(vm.state.value.kernel.connected)
    }

    @Test
    fun `a failed seed shows the kernel message and Retry re-seeds`() = runTest {
        val (vm, _) = newVm()
        diff.value = DiffStoreState.Failed("No messages found for session x")
        assertEquals(ChangesUiState.Content.Failed("No messages found for session x"), vm.state.value.content)
        vm.onRetry()
        assertEquals(1, retries)
    }

    @Test
    fun `reconnecting after a failed seed re-seeds once`() = runTest {
        kernel.value = KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false))
        val (vm, _) = newVm()
        diff.value = DiffStoreState.Failed("hub_connection_closed")
        assertEquals(ChangesUiState.Content.Loading, vm.state.value.content)
        assertEquals(0, retries)
        kernel.value = KernelConnectionVisual.Connected
        assertEquals(1, retries)
        kernel.value = KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Draining, null, false))
        assertEquals(1, retries)
    }

    @Test
    fun `summary dropping to zero closes, a fresh seed to zero shows Empty`() = runTest {
        diff.value = ready()
        val (vm, effects) = newVm()
        val empty = vm.state.value.content as ChangesUiState.Content.Ready
        assertTrue(empty.files.isEmpty())
        assertTrue(effects.none { it == ChangesEffect.Close })

        diff.value = ready(hello, at = 2)
        diff.value = ready(at = 3)
        assertEquals(1, effects.count { it == ChangesEffect.Close })
    }

    @Test
    fun `deleting the session closes the screen, other sessions do not`() = runTest {
        val (_, effects) = newVm()
        removed.emit("other")
        assertTrue(effects.isEmpty())
        removed.emit(sessionId)
        assertEquals(listOf<ChangesEffect>(ChangesEffect.Close), effects)
    }

    @Test
    fun `copy resolves the absolute termux path and shows the check for 1600 ms after success`() = runTest {
        diff.value = ready(hello)
        val (vm, effects) = newVm()
        vm.onCopyPath("hello.py")
        assertEquals(ChangesEffect.Copy("hello.py", "$cwd/hello.py"), effects.single())
        assertNull(vm.state.value.copiedPath)

        vm.onCopied("hello.py")
        assertEquals("hello.py", vm.state.value.copiedPath)
        advanceTimeBy(1_599)
        assertEquals("hello.py", vm.state.value.copiedPath)
        advanceTimeBy(2)
        assertNull(vm.state.value.copiedPath)
    }

    @Test
    fun `copy of an unknown path does nothing`() = runTest {
        diff.value = ready(hello)
        val (vm, effects) = newVm()
        vm.onCopyPath("nope.txt")
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `share builds unified diff text with the localised no-hunks note`() = runTest {
        diff.value = ready(hello, deleted)
        session.value = SessionState(sessionId, facts = SessionFacts(sessionId, title = "Demo"))
        val (vm, effects) = newVm()
        vm.onShareAll()
        val share = effects.single() as ChangesEffect.Share
        assertEquals(UnifiedDiffText.forSession(listOf(hello, deleted), "No hunk details available."), share.text)
        assertTrue(share.text.contains("# No hunk details available."))
        assertEquals("$sessionId.diff", share.fileName)
        assertEquals("Demo", share.sessionTitle)

        effects.clear()
        vm.onShareFile("hello.py")
        val single = effects.single() as ChangesEffect.Share
        assertTrue(single.text.startsWith("--- a/hello.py\n+++ b/hello.py\n@@ -1,0 +1,2 @@"))
    }

    @Test
    fun `untitled sessions share with the fallback title`() = runTest {
        diff.value = ready(hello)
        session.value = SessionState(sessionId, facts = SessionFacts(sessionId, title = "  "))
        val (vm, effects) = newVm()
        vm.onShareAll()
        assertNull((effects.single() as ChangesEffect.Share).sessionTitle)
    }

    @Test
    fun `updates are announced at most once every five seconds`() = runTest {
        diff.value = ready(hello)
        val (_, effects) = newVm()
        assertTrue(effects.isEmpty())

        diff.value = ready(hello, notes, at = 2, changed = setOf("notes.txt"))
        assertEquals(ChangesEffect.Announce(2), effects.last())
        now += 1_000
        diff.value = ready(hello, notes, at = 3, changed = setOf("hello.py"))
        assertEquals(1, effects.count { it is ChangesEffect.Announce })
        now += 5_000
        diff.value = ready(hello, notes, at = 4, changed = setOf("hello.py"))
        assertEquals(2, effects.count { it is ChangesEffect.Announce })
    }

    @Test
    fun `busy follows running and pending, title follows the record`() = runTest {
        val (vm, _) = newVm()
        assertFalse(vm.state.value.isSessionBusy)
        session.value = SessionState(sessionId, facts = SessionFacts(sessionId, status = SessionStatus.Running, title = "T"))
        assertTrue(vm.state.value.isSessionBusy)
        assertEquals("T", vm.state.value.sessionTitle)
        session.value = SessionState(sessionId, facts = SessionFacts(sessionId, status = SessionStatus.Pending))
        assertTrue(vm.state.value.isSessionBusy)
        session.value = SessionState(sessionId, facts = SessionFacts(sessionId, status = SessionStatus.Completed))
        assertFalse(vm.state.value.isSessionBusy)
    }

    @Test
    fun `a new pane instance starts with every file expanded, the same instance keeps its toggles`() = runTest {
        diff.value = ready(hello, notes)
        val (vm, _) = newVm()
        vm.bindInstance("a")
        vm.onToggle("hello.py")
        vm.bindInstance("a")
        assertEquals(setOf("hello.py"), vm.state.value.collapsed)
        vm.bindInstance("b")
        assertEquals(emptySet<String>(), vm.state.value.collapsed)
    }

    @Test
    fun `start kernel outside the shell asks the coordinator`() = runTest {
        val (vm, _) = newVm()
        vm.onStartKernel()
        assertEquals(1, starts)
    }

    @Test
    fun `shown-lines encoding round-trips paths with separators`() {
        val shown = mapOf(HunkKey("a|b/c.kt", 2) to 1200, HunkKey("x", 0) to 800)
        assertEquals(shown, ChangesViewModel.decodeShown(ChangesViewModel.encodeShown(shown)))
        assertEquals(emptyMap<HunkKey, Int>(), ChangesViewModel.decodeShown(listOf("bad", "1|x")))
    }

    @Test
    fun `layout follows A4 - height rule first, then the 840dp two-pane width`() {
        assertEquals(ChangesLayout.HeightCompact, ChangesLayout.forPushed(ShellLayout(891, 412)))
        assertEquals(ChangesLayout.Accordion, ChangesLayout.forPushed(ShellLayout(412, 915)))
        assertEquals(ChangesLayout.Accordion, ChangesLayout.forPushed(ShellLayout(778, 1200)))
        assertEquals(ChangesLayout.TwoPane, ChangesLayout.forPushed(ShellLayout(1244, 778)))
        assertEquals(ChangesLayout.TwoPane, ChangesLayout.forPushed(ShellLayout(900, 600)))
    }

    @Test
    fun `kernel visual - connected, down and the strip action`() {
        assertTrue(KernelConnectionVisual(KernelGate.Ready).connected)
        assertTrue(KernelConnectionVisual(KernelGate.DegradedNoCommands).connected)
        assertTrue(KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Draining, null, false)).connected)
        assertFalse(KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Connecting, null, false)).connected)
        assertFalse(KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Connecting, null, false)).down)
        val asleep = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Asleep, null))
        assertTrue(asleep.down)
        assertEquals(dev.cline.termux.ui.kernel.KernelAction.StartKernel, asleep.action)
        assertNull(KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false)).action)
    }
}
