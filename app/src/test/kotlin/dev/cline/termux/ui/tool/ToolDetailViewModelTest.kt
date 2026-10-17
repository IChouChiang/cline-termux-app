package dev.cline.termux.ui.tool

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.data.tools.ExecutionView
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.ToolCallEntry
import dev.cline.termux.data.tools.ToolCallView
import dev.cline.termux.data.tools.ToolLookup
import dev.cline.termux.data.tools.ToolOrigin
import dev.cline.termux.ui.changes.KernelConnectionVisual
import dev.cline.termux.ui.navigation.ShellLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolDetailViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val lookup = MutableStateFlow<ToolLookup>(ToolLookup.Loading)
    private val kernel = MutableStateFlow(KernelConnectionVisual.Connected)
    private val wrap = MutableStateFlow(true)
    private val wrapWrites = mutableListOf<Boolean>()
    private var proceedReply: suspend () -> JsonObject = { buildJsonObject { put("detachedCount", 1) } }
    private var proceedCalls = 0
    private var abortResult: suspend () -> Result<Unit> = { Result.success(Unit) }
    private var abortCalls = 0
    private var reloads = 0
    private var now = 1_000L

    private val input = buildJsonObject { putJsonArray("commands") { add("seq 1 90"); add("echo b") } }

    private fun exec(index: Int, out: String, running: Boolean = true, detachable: Boolean = true, err: String = "", success: Boolean? = null) = ExecutionView(
        executionId = "e$index",
        commandIndex = index,
        query = if (index == 0) "seq 1 90" else "echo b",
        output = TerminalBuffer.parse(out + err),
        stdout = TerminalBuffer.parse(out),
        stderr = TerminalBuffer.parse(err),
        hasStderr = err.isNotEmpty(),
        inlineOutput = out,
        inlineTruncated = false,
        streamed = out.isNotEmpty() || err.isNotEmpty(),
        gapped = false,
        fromResult = false,
        truncated = false,
        kernelDroppedChars = 0,
        detachable = detachable,
        success = success,
        result = null,
        running = running,
    )

    private fun found(status: ToolStatus, executions: List<ExecutionView>, runActive: Boolean = true, live: Boolean = true) = ToolLookup.Found(
        ToolCallView(
            ToolCallEntry(
                ToolRecord(
                    toolCallId = "call_1",
                    toolName = "run_commands",
                    input = input,
                    status = status,
                    iteration = 1,
                    requestId = "r1",
                    startedAt = 1_000,
                ),
                ToolOrigin.Live,
            ),
            executions,
            runActive,
            live,
        ),
    )

    private val running get() = found(ToolStatus.Running, listOf(exec(0, "line 1\n"), exec(1, "", detachable = false)))

    private fun TestScope.newVm(handle: SavedStateHandle = SavedStateHandle()): Pair<ToolDetailViewModel, MutableList<ToolDetailEffect>> {
        val vm = ToolDetailViewModel(
            sessionId = "s1",
            toolCallId = "call_1",
            savedState = handle,
            lookup = lookup,
            kernel = kernel,
            wrapLines = wrap,
            setWrapLines = { wrapWrites += it; wrap.value = it },
            proceed = { proceedCalls++; proceedReply() },
            abort = { abortCalls++; abortResult() },
            reload = { reloads++ },
            startKernel = {},
            nowMs = { now },
            compute = UnconfinedTestDispatcher(testScheduler),
            warn = {},
        )
        val effects = mutableListOf<ToolDetailEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        runCurrent()
        return vm to effects
    }

    @Test
    fun `loading, then the live tool with a ticking duration`() = runTest {
        val (vm, _) = newVm()
        assertTrue(vm.state.value.loading)
        now = 3_400
        lookup.value = running
        runCurrent()
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(ToolDetailStatus.Running, state.status)
        assertEquals(ToolLayout.Command, state.layout)
        assertEquals(2, state.commands.size)
        assertEquals(2_400L, state.durationMs)
        now = 4_400
        advanceTimeBy(1_001)
        assertEquals(3_400L, vm.state.value.durationMs)
        assertTrue(vm.state.value.canProceed)
        assertTrue(vm.state.value.canAbort)
    }

    @Test
    fun `loading with the kernel down shows the offline state, then the tool once it arrives`() = runTest {
        kernel.value = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Stopped, null))
        val (vm, effects) = newVm()
        var state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.kernelUnavailable)
        assertFalse(state.found)
        assertFalse(state.actionsEnabled)
        // Reconnecting is not down: the progress bar returns (the strip shows above it).
        kernel.value = KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false))
        state = vm.state.value
        assertTrue(state.loading)
        assertFalse(state.kernelUnavailable)
        kernel.value = KernelConnectionVisual.Connected
        lookup.value = running
        state = vm.state.value
        assertTrue(state.found)
        assertFalse(state.kernelUnavailable)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the edit layout copies paths resolved against the session cwd`() = runTest {
        val vm = ToolDetailViewModel(
            sessionId = "s1",
            toolCallId = "call_1",
            savedState = SavedStateHandle(),
            lookup = lookup,
            kernel = kernel,
            wrapLines = wrap,
            setWrapLines = {},
            proceed = { buildJsonObject {} },
            abort = { Result.success(Unit) },
            reload = {},
            startKernel = {},
            cwd = { "/data/data/com.termux/files/home/project/" },
            compute = UnconfinedTestDispatcher(testScheduler),
            warn = {},
        )
        assertEquals("/data/data/com.termux/files/home/project/src/Main.kt", vm.absolutePath("./src/Main.kt"))
        assertEquals("/tmp/a.txt", vm.absolutePath("/tmp/a.txt"))
    }

    @Test
    fun `not found and history failure states, retry reloads`() = runTest {
        val (vm, _) = newVm()
        lookup.value = ToolLookup.NotFound
        assertTrue(vm.state.value.notFound)
        lookup.value = ToolLookup.HistoryFailed("disk I/O error")
        assertEquals("disk I/O error", vm.state.value.loadError)
        vm.onRetry()
        assertEquals(1, reloads)
    }

    @Test
    fun `proceed detaches, then the button hides`() = runTest {
        lookup.value = running
        val (vm, _) = newVm()
        val gate = CompletableDeferred<Unit>()
        proceedReply = { gate.await(); buildJsonObject { put("detachedCount", 1) } }
        vm.onProceed()
        assertEquals(ProceedState.Pending, vm.state.value.proceed)
        vm.onProceed()
        assertEquals(1, proceedCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ProceedState.Detached, vm.state.value.proceed)
        assertFalse(vm.state.value.canProceed)
    }

    @Test
    fun `proceed with nothing detached shows the finished-before message and hides the button`() = runTest {
        lookup.value = running
        val (vm, effects) = newVm()
        proceedReply = { buildJsonObject { put("detachedCount", 0) } }
        vm.onProceed()
        assertEquals(ProceedState.Error(ProceedError.Finished), vm.state.value.proceed)
        assertFalse(vm.state.value.canProceed)
        assertEquals(ToolDetailEffect.ProceedFailed(ProceedState.Error(ProceedError.Finished)), effects.single())
    }

    @Test
    fun `proceed errors - unsupported verbatim, closed socket re-evaluates, others generic`() = runTest {
        lookup.value = running
        val (vm, _) = newVm()
        proceedReply = { throw HubCommandException(HubCommands.RUN_PROCEED_WHILE_RUNNING, HubErrorCodes.UNSUPPORTED_COMMAND, "This runtime does not support proceeding while commands are running.") }
        vm.onProceed()
        assertEquals(
            ProceedState.Error(ProceedError.Unsupported, "This runtime does not support proceeding while commands are running."),
            vm.state.value.proceed,
        )

        proceedReply = { throw HubConnectionClosedException("closed") }
        val (vm2, _) = newVm()
        vm2.onProceed()
        assertEquals(ProceedState.Idle, vm2.state.value.proceed)
        assertTrue(vm2.state.value.canProceed)

        proceedReply = { throw HubCommandException(HubCommands.RUN_PROCEED_WHILE_RUNNING, "invalid_session_id", "bad") }
        val (vm3, _) = newVm()
        vm3.onProceed()
        assertEquals(ProceedState.Error(ProceedError.Generic), vm3.state.value.proceed)
    }

    @Test
    fun `actions are disabled while the kernel is down`() = runTest {
        lookup.value = running
        kernel.value = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Asleep, null))
        val (vm, _) = newVm()
        assertFalse(vm.state.value.actionsEnabled)
        vm.onProceed()
        vm.onStopAgent()
        assertEquals(0, proceedCalls)
        assertEquals(0, abortCalls)
    }

    @Test
    fun `a socket drop keeps the action bar, disabled, while the tool still runs`() = runTest {
        lookup.value = running
        val (vm, _) = newVm()
        assertTrue(vm.state.value.showActionBar)
        // The thread stops being live when the socket drops; the bar stays with disabled buttons (B.5).
        kernel.value = KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false))
        lookup.value = found(ToolStatus.Running, listOf(exec(0, "x\n")), live = false)
        assertTrue(vm.state.value.canAbort)
        assertTrue(vm.state.value.showActionBar)
        assertFalse(vm.state.value.actionsEnabled)
        vm.onStopAgent()
        assertEquals(0, abortCalls)
    }

    @Test
    fun `stop agent stays pending until the run ends, a failure re-enables it`() = runTest {
        lookup.value = running
        val (vm, _) = newVm()
        vm.onStopAgent()
        assertEquals(1, abortCalls)
        assertTrue(vm.state.value.abortPending)
        vm.onStopAgent()
        assertEquals(1, abortCalls)

        lookup.value = found(ToolStatus.Cancelled, listOf(exec(0, "line 1\n", running = false)), runActive = false)
        assertFalse(vm.state.value.abortPending)
        assertFalse(vm.state.value.canAbort)
        assertEquals(ToolDetailStatus.NoResult, vm.state.value.status)

        lookup.value = running
        abortResult = { Result.failure(IllegalStateException()) }
        vm.onStopAgent()
        assertFalse(vm.state.value.abortPending)
    }

    @Test
    fun `finishing announces once`() = runTest {
        lookup.value = running
        val (_, effects) = newVm()
        lookup.value = found(ToolStatus.Finished(null), listOf(exec(0, "line 1\n", running = false, success = true)), runActive = false)
        lookup.value = found(ToolStatus.Finished(null), listOf(exec(0, "line 1\nmore\n", running = false, success = true)), runActive = false)
        assertEquals(listOf<ToolDetailEffect>(ToolDetailEffect.Announce(ToolDetailStatus.Completed)), effects)
    }

    @Test
    fun `failing announces failed`() = runTest {
        lookup.value = running
        val (_, effects) = newVm()
        lookup.value = found(ToolStatus.Finished("exit 3"), listOf(exec(0, "line 1\n", running = false, success = false)), runActive = false)
        assertEquals(ToolDetailEffect.Announce(ToolDetailStatus.Failed), effects.single())
    }

    @Test
    fun `selected command and stream filter are saved, the filter needs stderr`() = runTest {
        lookup.value = found(ToolStatus.Running, listOf(exec(0, "out\n", err = "err\n"), exec(1, "b\n")))
        val handle = SavedStateHandle()
        val (vm, _) = newVm(handle)
        assertTrue(vm.state.value.showFilter)
        vm.onFilter(StreamFilter.Stderr)
        assertEquals(StreamFilter.Stderr, vm.state.value.effectiveFilter)
        assertEquals("err", vm.state.value.outputCopyText())
        vm.onSelectCommand(1)
        assertFalse(vm.state.value.showFilter)
        assertEquals(StreamFilter.All, vm.state.value.effectiveFilter)
        assertEquals("b", vm.state.value.outputCopyText())

        val (restored, _) = newVm(handle)
        assertEquals(1, restored.state.value.selectedCommand)
        assertEquals(StreamFilter.Stderr, restored.state.value.streamFilter)
    }

    @Test
    fun `share text prefixes each command with its query`() = runTest {
        lookup.value = found(ToolStatus.Finished(null), listOf(exec(0, "a\n", running = false, success = true), exec(1, "b\n", running = false, success = true)), runActive = false)
        val (vm, _) = newVm()
        assertEquals("$ seq 1 90\na\n\n$ echo b\nb", vm.state.value.shareText())
    }

    @Test
    fun `copied target clears after 1600 ms and wrap toggles the preference`() = runTest {
        lookup.value = running
        val (vm, _) = newVm()
        vm.onCopied(CopyTarget.Command)
        assertEquals(CopyTarget.Command, vm.state.value.copied)
        advanceTimeBy(1_601)
        assertNull(vm.state.value.copied)

        vm.onToggleWrap()
        assertEquals(listOf(false), wrapWrites)
        assertFalse(vm.state.value.wrapLines)
    }

    @Test
    fun `history tools of a session that is not live offer neither proceed nor stop`() = runTest {
        lookup.value = found(ToolStatus.Running, listOf(exec(0, "x\n")), live = false)
        val (vm, _) = newVm()
        assertFalse(vm.state.value.canProceed)
        assertFalse(vm.state.value.canAbort)
        assertFalse(vm.state.value.showActionBar)
    }

    @Test
    fun `window follows B4 - height rule first, then 840dp`() {
        assertEquals(ToolWindow.HeightCompact, ToolWindow.forPushed(ShellLayout(891, 412)))
        assertEquals(ToolWindow.Compact, ToolWindow.forPushed(ShellLayout(412, 915)))
        assertEquals(ToolWindow.Expanded, ToolWindow.forPushed(ShellLayout(1244, 778)))
    }
}
