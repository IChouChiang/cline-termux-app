package dev.cline.termux.ui.tool

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.tools.DiffHunk
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.data.tools.ToolFileDiff
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import dev.cline.termux.data.tools.ToolSummaryItem
import dev.cline.termux.ui.changes.KernelConnectionVisual
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.ToolSymbol

/* Previews of every CT B.5 state and B.4 layout, light and dark. */

private val StdoutText = "bun test v1.4.2\n\u001b[32m✓\u001b[0m parser > parses SGR [2.1ms]\n\u001b[31m✗\u001b[0m diff > counts lines\n12 pass  1 fail"
private val StderrText = "\u001b[33mwarning:\u001b[0m deprecated flag --watch\n"

private fun snapshot(text: String): TerminalSnapshot = TerminalBuffer.parse(text)

private fun command(index: Int, query: String, out: String, err: String = "", running: Boolean = false, success: Boolean? = true) = CommandView(
    index = index,
    query = query,
    output = snapshot(out + err),
    stdout = snapshot(out),
    stderr = snapshot(err),
    hasStderr = err.isNotEmpty(),
    streamed = out.isNotEmpty() || err.isNotEmpty(),
    success = success,
    running = running,
    gapped = false,
)

private fun commandState(
    status: ToolDetailStatus,
    commands: List<CommandView>,
    canProceed: Boolean = false,
    canAbort: Boolean = false,
    proceed: ProceedState = ProceedState.Idle,
    errorText: String? = null,
    kernel: KernelConnectionVisual = KernelConnectionVisual.Connected,
    detached: String? = null,
) = ToolDetailUiState(
    sessionId = "s1",
    toolCallId = "call_1",
    kernel = kernel,
    loading = false,
    toolName = "run_commands",
    kind = ToolKind.Command,
    icon = ToolSymbol.Terminal,
    label = ToolLabel.Phrase(ToolPhrase.RanCommandNamed, "bun test"),
    status = status,
    durationMs = 12_000,
    layout = ToolLayout.Command,
    commands = commands,
    canProceed = canProceed,
    canAbort = canAbort,
    proceed = proceed,
    errorText = errorText,
    detachedLogPath = detached,
    sessionLive = true,
    showTerminal = errorText == null || commands.any { it.streamed },
)

@Composable
private fun Screen(state: ToolDetailUiState, window: ToolWindow = ToolWindow.Compact) {
    ComponentPreview(padding = false) {
        ToolDetailScreen(state, window, ToolDetailActions(), onBack = {}, onClose = if (window == ToolWindow.Pane) ({}) else null)
    }
}

@LightDarkPreviews
@Composable
private fun ToolRunningPreview() {
    Screen(
        commandState(
            ToolDetailStatus.Running,
            listOf(command(0, "bun test", StdoutText, StderrText, running = true, success = null), command(1, "bun lint", "")),
            canProceed = true,
            canAbort = true,
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolWaitingPreview() {
    Screen(commandState(ToolDetailStatus.WaitingForOutput, listOf(command(0, "seq 1 90", "", running = true, success = null)), canAbort = true, proceed = ProceedState.Pending, canProceed = true))
}

@LightDarkPreviews
@Composable
private fun ToolFailedPreview() {
    Screen(commandState(ToolDetailStatus.Failed, listOf(command(0, "seq 1 50; exit 3", (1..50).joinToString("\n"), success = false)), errorText = "Command exited with code 3"))
}

@LightDarkPreviews
@Composable
private fun ToolDetachedPreview() {
    val notice = "[Command is still running. Output will continue in /data/data/com.termux/files/home/.cline/cmd-1.log]"
    Screen(commandState(ToolDetailStatus.Detached, listOf(command(0, "for i in 1..90", "line 1\nline 2\n$notice")), detached = "/data/data/com.termux/files/home/.cline/cmd-1.log"))
}

@LightDarkPreviews
@Composable
private fun ToolInterruptedOfflinePreview() {
    Screen(
        commandState(
            ToolDetailStatus.Interrupted,
            listOf(command(0, "bun test", StdoutText, success = null)),
            kernel = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.StartFailed, null)),
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolNoResultPreview() {
    Screen(commandState(ToolDetailStatus.NoResult, listOf(command(0, "sleep 100", "", success = null))))
}

@LightDarkPreviews
@Composable
private fun ToolEmptyOutputPreview() {
    Screen(commandState(ToolDetailStatus.Completed, listOf(command(0, "true", ""))))
}

@LightDarkPreviews
@Composable
private fun ToolEditPreview() {
    val item = ToolSummaryItem.File(path = "app/src/main/kotlin/dev/cline/Main.kt", displayPath = "Main.kt", additions = 2, deletions = 1, fragment = true)
    Screen(
        ToolDetailUiState(
            sessionId = "s1",
            toolCallId = "call_2",
            loading = false,
            toolName = "editor",
            kind = ToolKind.Edit,
            icon = ToolSymbol.Edit,
            label = ToolLabel.Phrase(ToolPhrase.EditedFileNamed, "Main.kt"),
            status = ToolDetailStatus.Completed,
            durationMs = 400,
            layout = ToolLayout.Edit,
            details = listOf("app/src/main/kotlin/dev/cline/Main.kt"),
            fileItems = listOf(
                ToolFileItemView(item.path, item.displayPath, 2, 1, listOf(ToolFileDiff("d0", item, DiffHunk("val x = 1\n", "val x = 2\nval y = 3\n")))),
            ),
            sessionLive = true,
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolGenericSubmitPreview() {
    Screen(
        ToolDetailUiState(
            sessionId = "s1",
            toolCallId = "call_3",
            loading = false,
            toolName = "submit_and_exit",
            kind = ToolKind.Other,
            icon = ToolSymbol.ExitToApp,
            label = ToolLabel.Phrase(ToolPhrase.ScheduledTaskCompleted),
            status = ToolDetailStatus.Completed,
            durationMs = 1_000,
            layout = ToolLayout.Generic,
            submitMarkdown = "## Summary\n\n- Updated **3** files\n- All tests pass",
            inputJson = "{\n  \"summary\": \"…\"\n}",
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolRejectedPreview() {
    Screen(
        ToolDetailUiState(
            sessionId = "s1",
            toolCallId = "call_4",
            loading = false,
            toolName = "read_files",
            kind = ToolKind.Read,
            icon = ToolSymbol.FileCopy,
            label = ToolLabel.Phrase(ToolPhrase.ReadFileNamed, "secrets.txt"),
            status = ToolDetailStatus.Rejected,
            layout = ToolLayout.Generic,
            details = listOf("/data/data/com.termux/files/home/secrets.txt"),
            errorText = "This tool call was rejected by the user and not executed.",
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolNotFoundPreview() {
    Screen(ToolDetailUiState(sessionId = "s1", toolCallId = "gone", loading = false, notFound = true))
}

@LightDarkPreviews
@Composable
private fun ToolLoadingPreview() {
    Screen(ToolDetailUiState(sessionId = "s1", toolCallId = "call_1", loading = true))
}

@LightDarkPreviews
@Composable
private fun ToolAwaitingApprovalPreview() {
    Screen(
        commandState(
            ToolDetailStatus.AwaitingApproval,
            listOf(command(0, "rm -rf build && bun run build", "", running = false, success = null)),
            canAbort = true,
        ),
    )
}

private val DownKernel = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Stopped, null))

@LightDarkPreviews
@Composable
private fun ToolLoadErrorPreview() {
    Screen(ToolDetailUiState(sessionId = "s1", toolCallId = "call_1", loading = false, loadError = "database is locked"))
}

@LightDarkPreviews
@Composable
private fun ToolLoadErrorKernelDownPreview() {
    Screen(ToolDetailUiState(sessionId = "s1", toolCallId = "call_1", kernel = DownKernel, loading = false, loadError = "Hub connection closed"))
}

@LightDarkPreviews
@Composable
private fun ToolLoadingKernelDownPreview() {
    Screen(ToolDetailUiState(sessionId = "s1", toolCallId = "call_1", kernel = DownKernel, loading = false, kernelUnavailable = true))
}

@LightDarkPreviews
@Composable
private fun ToolLoadingReconnectingPreview() {
    Screen(
        ToolDetailUiState(
            sessionId = "s1",
            toolCallId = "call_1",
            kernel = KernelConnectionVisual(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), 25463),
            loading = true,
        ),
    )
}

@LightDarkPreviews
@Composable
private fun ToolPanePreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.width(400.dp)) {
            ToolDetailScreen(
                commandState(ToolDetailStatus.Running, listOf(command(0, "bun test", StdoutText, running = true, success = null)), canAbort = true, canProceed = true),
                ToolWindow.Pane,
                ToolDetailActions(),
                onBack = null,
                onClose = {},
            )
        }
    }
}

@Preview(name = "Height-compact", widthDp = 891, heightDp = 412)
@Preview(name = "Height-compact dark", widthDp = 891, heightDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun ToolHeightCompactPreview() {
    Screen(
        commandState(
            ToolDetailStatus.Running,
            listOf(command(0, "bun test", StdoutText, running = true, success = null), command(1, "bun lint", "ok")),
            canProceed = true,
            canAbort = true,
        ),
        ToolWindow.HeightCompact,
    )
}

@Preview(name = "Expanded", widthDp = 1000, heightDp = 700)
@Preview(name = "Expanded dark", widthDp = 1000, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun ToolExpandedPreview() {
    Screen(
        commandState(
            ToolDetailStatus.Running,
            listOf(command(0, "bun test", StdoutText, StderrText, running = true, success = null), command(1, "bun lint", "ok")),
            canProceed = true,
            canAbort = true,
        ),
        ToolWindow.Expanded,
    )
}
