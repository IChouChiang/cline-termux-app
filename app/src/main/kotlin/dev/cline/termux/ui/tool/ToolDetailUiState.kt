package dev.cline.termux.ui.tool

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.data.tools.ToolFileDiff
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolOutputMedia
import dev.cline.termux.ui.changes.KernelConnectionVisual
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.ToolSymbol

/** The CT B.5 rows (the screen-level states Loading / Not found / load error are flags on the UiState). */
enum class ToolDetailStatus {
    WaitingForOutput,
    Running,
    AwaitingApproval,
    Completed,
    Failed,
    Rejected,

    /** "Still running in the background": completed with the kernel's detach notice. */
    Detached,

    /** In progress but the run is no longer active (desktop "A missing result can outlive its run"). */
    NoResult,

    /** `run.interrupted {reason:"hub_restart"}` while running. */
    Interrupted;

    /** The tool still runs (spinner, ticking duration, bottom bar). */
    val running: Boolean get() = this == WaitingForOutput || this == Running
}

/** B.4: the body layout chosen from the tool summary. */
enum class ToolLayout { Command, Edit, Generic }

/** B.4 stream filter: "All" (interleaved, desktop), "stdout", "stderr". */
enum class StreamFilter { All, Stdout, Stderr }

/** Why "Proceed while running" did not detach (B.6 copy). */
enum class ProceedError {
    /** "Could not detach the running command." */
    Generic,

    /** `detachedCount < 1`: "The command finished before it could be detached." */
    Finished,

    /** `unsupported_command`: the kernel's sentence, verbatim. */
    Unsupported,
}

/** B.8 `proceed: ActionState`. */
@Immutable
sealed interface ProceedState {
    data object Idle : ProceedState
    data object Pending : ProceedState

    /** Detached at least one execution: the button disappears until the tool finishes. */
    data object Detached : ProceedState

    /** [message] is the kernel text for [ProceedError.Unsupported], when the reply carried one. */
    data class Error(val error: ProceedError, val message: String? = null) : ProceedState
}

/** What a copy action targets; the matching icon/label shows "Copied …" for 1600 ms. */
enum class CopyTarget { Output, Command, Input, LogPath }

/** One command execution of the command layout (B.8 `CommandView`). */
@Immutable
data class CommandView(
    val index: Int,
    val query: String,
    /** stdout and stderr interleaved in arrival order (the desktop's one buffer). */
    val output: TerminalSnapshot,
    val stdout: TerminalSnapshot,
    val stderr: TerminalSnapshot,
    val hasStderr: Boolean,
    /** Streamed chunks arrived (they always win; an errored tool without them shows only the error, CT §1.4). */
    val streamed: Boolean,
    val success: Boolean?,
    val running: Boolean,
    /** The replay lost the head of this execution (B.3). */
    val gapped: Boolean,
) {
    fun lines(filter: StreamFilter): TerminalSnapshot = when (filter) {
        StreamFilter.All -> output
        StreamFilter.Stdout -> stdout
        StreamFilter.Stderr -> stderr
    }

    /** First line of the query, for tabs and list rows. */
    val queryLine: String get() = query.lineSequence().firstOrNull().orEmpty()
}

/** Edit layout: one file of this call with its diff blocks (B.8 `ToolFileItemView`). */
@Immutable
data class ToolFileItemView(
    val path: String,
    val displayPath: String,
    val additions: Int,
    val deletions: Int,
    val diffs: List<ToolFileDiff>,
)

/** CT B.8 `ToolDetailUiState`. */
@Immutable
data class ToolDetailUiState(
    val sessionId: String,
    val toolCallId: String,
    val kernel: KernelConnectionVisual = KernelConnectionVisual.Connected,
    val loading: Boolean = true,
    /** B.5 "Offline / kernel down": the tool is not loaded yet and the kernel is down or blocked. */
    val kernelUnavailable: Boolean = false,
    val notFound: Boolean = false,
    /** `session.messages` failed and the tool is not in the live store (kernel text). */
    val loadError: String? = null,
    val toolName: String = "",
    val kind: ToolKind = ToolKind.Other,
    val icon: ToolSymbol = ToolSymbol.Build,
    val label: ToolLabel = ToolLabel.Text(""),
    val status: ToolDetailStatus = ToolDetailStatus.Running,
    /** Null = unknown (history without timings). */
    val durationMs: Long? = null,
    val layout: ToolLayout = ToolLayout.Generic,
    val commands: List<CommandView> = emptyList(),
    val selectedCommand: Int = 0,
    val streamFilter: StreamFilter = StreamFilter.All,
    val wrapLines: Boolean = true,
    /** An errored command without streamed output shows only its Error section (CT §1.4). */
    val showTerminal: Boolean = true,
    val fileItems: List<ToolFileItemView> = emptyList(),
    val details: List<String> = emptyList(),
    val outputText: String? = null,
    val submitMarkdown: String? = null,
    val images: List<ChatImage> = emptyList(),
    val otherMedia: List<ToolOutputMedia> = emptyList(),
    /** Always computed; shown in the body only in debug builds, copyable from the overflow always. */
    val inputJson: String = "",
    val errorText: String? = null,
    val detachedLogPath: String? = null,
    /** "Proceed while running" is offered (desktop `canProceed`). */
    val canProceed: Boolean = false,
    val proceed: ProceedState = ProceedState.Idle,
    /** "Stop agent" is offered (a live run is in flight for this tool). */
    val canAbort: Boolean = false,
    val abortPending: Boolean = false,
    /** The session is attached on this connection (B.5 "History (not live)" otherwise). */
    val sessionLive: Boolean = false,
    val copied: CopyTarget? = null,
) {
    /** The tool resolved: the title, the bar actions and a body layout show. */
    val found: Boolean get() = !loading && !kernelUnavailable && !notFound && loadError == null

    val selected: CommandView? get() = commands.getOrNull(selectedCommand) ?: commands.firstOrNull()

    /** Any stderr chunk exists for the selected command: the stream filter shows (B.4). */
    val showFilter: Boolean get() = selected?.hasStderr == true

    /** The effective filter (a filter is meaningless without stderr). */
    val effectiveFilter: StreamFilter get() = if (showFilter) streamFilter else StreamFilter.All

    /** Buttons are enabled only while the Hub answers (B.5 "Offline / kernel down"). */
    val actionsEnabled: Boolean get() = kernel.connected

    /** The bottom bar (or the height-compact bar actions) is shown. */
    val showActionBar: Boolean get() = canAbort || (canProceed && proceed != ProceedState.Detached)

    val hasOutput: Boolean
        get() = when (layout) {
            ToolLayout.Command -> commands.any { !it.output.isEmpty }
            else -> !outputText.isNullOrEmpty() || !submitMarkdown.isNullOrEmpty()
        }

    /** The text "Copy output" copies: the selected command's filtered buffer, or the generic output. */
    fun outputCopyText(): String = when (layout) {
        ToolLayout.Command -> selected?.lines(effectiveFilter)?.copyText().orEmpty()
        else -> outputText ?: submitMarkdown.orEmpty()
    }

    /** "Share output": `$ <query>\n<output>` per command (B.7), or the generic output. */
    fun shareText(): String = when (layout) {
        ToolLayout.Command -> commands.joinToString("\n\n") { "$ ${it.query}\n${it.output.copyText()}" }
        else -> outputText ?: submitMarkdown.orEmpty()
    }
}
