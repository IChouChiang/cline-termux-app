package dev.cline.termux.ui.tool

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.data.tools.ExecutionView
import dev.cline.termux.data.tools.RUN_COMMANDS_TOOL
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.data.tools.ToolCallEntry
import dev.cline.termux.data.tools.ToolCallView
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolOrigin
import dev.cline.termux.data.tools.ToolOutputMedia
import dev.cline.termux.data.tools.ToolPayload
import dev.cline.termux.data.tools.ToolPresentation
import dev.cline.termux.data.tools.ToolSummaryItem
import dev.cline.termux.data.tools.ToolSummaryPayload
import dev.cline.termux.data.tools.buildToolRowModel
import dev.cline.termux.data.tools.buildToolSummary
import dev.cline.termux.data.tools.extractRunCommandOutput
import dev.cline.termux.data.tools.formatToolValue
import dev.cline.termux.data.tools.withExecutedCommands
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.ToolSymbol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The part of [ToolDetailUiState] derived purely from one store [ToolCallView] (CT B.4, B.5,
 * B.8), independent of the screen's own selections. Pure and JVM-tested.
 */
@Immutable
data class ToolDetailContent(
    val toolName: String,
    val kind: ToolKind,
    val icon: ToolSymbol,
    val label: ToolLabel,
    val status: ToolDetailStatus,
    /** Known duration (run result or envelope timestamps); null while running or unknown. */
    val durationMs: Long?,
    /** Start time for the ticking "Running · Ns" duration of a live running tool; null otherwise. */
    val runningSince: Long?,
    val layout: ToolLayout,
    val commands: List<CommandView>,
    val showTerminal: Boolean,
    val fileItems: List<ToolFileItemView>,
    val details: List<String>,
    val outputText: String?,
    val submitMarkdown: String?,
    val images: List<ChatImage>,
    val otherMedia: List<ToolOutputMedia>,
    val inputJson: String,
    val errorText: String?,
    val detachedLogPath: String?,
    /** Desktop `canProceed` without the kernel gate: running command, a detachable execution, session live. */
    val proceedOffered: Boolean,
    /** A live run is in flight for this tool, so "Stop agent" applies. */
    val abortOffered: Boolean,
    val sessionLive: Boolean,
)

object ToolDetailModel {
    /** The kernel's detach notice (bash.ts:887); the path is what "Copy log path" copies. */
    private val DETACH_NOTICE = Regex("\\[Command is still running\\. Output will continue in (.+?)]")

    /**
     * Builds the screen content of [view] for [sessionId]. [parse] turns a history output text
     * into a terminal snapshot (injected so tests and the ViewModel can cache it).
     */
    fun build(
        sessionId: String,
        view: ToolCallView,
        parse: (String) -> TerminalSnapshot = { TerminalBuffer.parse(it) },
    ): ToolDetailContent {
        val entry = view.entry
        val record = entry.record
        val isError = entry.isError
        val inProgress = entry.inProgress
        // Trial row 18: the result as presented, with the input's commands for the kernel's echo.
        val output = if (record.toolName == RUN_COMMANDS_TOOL) withExecutedCommands(record.output, record.input) else record.output
        val summary = buildToolSummary(
            ToolSummaryPayload(
                toolName = record.toolName,
                input = record.input,
                result = output,
                isError = isError,
                inProgress = inProgress,
            ),
        )
        val presentation = ToolPresentation(
            message = ChatMessage(
                id = "t:${record.toolCallId}",
                sessionId = sessionId,
                role = Role.Tool,
                content = "",
                createdAt = record.startedAt,
                meta = Meta(toolName = record.toolName, toolCallId = record.toolCallId),
            ),
            payload = ToolPayload(toolName = record.toolName, input = record.input, result = output, isError = isError),
            toolName = record.toolName,
            inProgress = inProgress,
            summary = summary,
        )
        val row = buildToolRowModel(presentation, isRunActive = view.runActive, proceedAvailable = false, showInputPreview = false)
        val isCommand = summary.kind == ToolKind.Command

        val commands = if (isCommand) commandViews(view.executions, summary.items, output, isError, summary.outputText, parse) else emptyList()
        val streamedAny = commands.any { it.streamed }
        val detachedPath = detachedLogPath(view.executions, record.output, commands)
        val status = status(entry, isCommand, commands, view.runActive, detachedPath)

        val fileItems = row.fileDiffs
            .groupBy { it.item.path }
            .map { (path, diffs) ->
                val item = diffs.first().item
                ToolFileItemView(path, item.displayPath, item.additions ?: 0, item.deletions ?: 0, diffs)
            }
        val layout = when {
            isCommand -> ToolLayout.Command
            fileItems.isNotEmpty() -> ToolLayout.Edit
            else -> ToolLayout.Generic
        }
        val errorText = row.errorText?.takeIf { it.isNotBlank() }
            ?: if (isError) record.error?.takeIf { it.isNotBlank() } else null
        val live = entry.origin == ToolOrigin.Live
        val running = status.running
        val proceedOffered = running && isCommand && view.executions.any { it.detachable && it.running } && view.sessionLive
        val abortOffered = view.sessionLive && view.runActive &&
            (running || status == ToolDetailStatus.AwaitingApproval)
        return ToolDetailContent(
            toolName = record.toolName,
            kind = summary.kind,
            icon = row.icon,
            label = row.label,
            status = status,
            durationMs = if (running) null else entry.durationMs,
            runningSince = if (running && live) record.startedAt else null,
            layout = layout,
            commands = commands,
            // An errored tool without streamed output shows only its Error section (CT §1.4).
            showTerminal = !(isError && !streamedAny),
            fileItems = fileItems,
            details = row.details,
            outputText = if (!isCommand && !row.isSubmit) row.outputText?.takeIf { it.isNotEmpty() } else null,
            submitMarkdown = row.submitText.takeIf { row.isSubmit && it.isNotEmpty() },
            images = row.outputImages,
            otherMedia = row.otherOutputMedia,
            inputJson = formatToolValue(record.input),
            errorText = errorText,
            detachedLogPath = detachedPath,
            proceedOffered = proceedOffered,
            abortOffered = abortOffered,
            sessionLive = view.sessionLive,
        )
    }

    /** B.5 state from the store record and the run gate. */
    internal fun status(
        entry: ToolCallEntry,
        isCommand: Boolean,
        commands: List<CommandView>,
        runActive: Boolean,
        detachedPath: String?,
    ): ToolDetailStatus = when (val s = entry.record.status) {
        ToolStatus.AwaitingApproval -> if (runActive) ToolDetailStatus.AwaitingApproval else ToolDetailStatus.NoResult
        ToolStatus.Running -> when {
            !runActive -> ToolDetailStatus.NoResult
            isCommand && commands.none { !it.output.isEmpty } -> ToolDetailStatus.WaitingForOutput
            else -> ToolDetailStatus.Running
        }
        ToolStatus.Interrupted -> ToolDetailStatus.Interrupted
        ToolStatus.Cancelled -> ToolDetailStatus.NoResult
        ToolStatus.Rejected -> ToolDetailStatus.Rejected
        is ToolStatus.Finished -> when {
            !s.ok -> ToolDetailStatus.Failed
            detachedPath != null -> ToolDetailStatus.Detached
            else -> ToolDetailStatus.Completed
        }
    }

    /**
     * The command layout's executions. Live and history executions come from the store (the
     * output source precedence of CT §1.4 is applied there, and each carries the command of the
     * call's input); a call without any execution (a running call before its first announce, a
     * non-array history result) falls back to `extractRunCommandOutput` of the presented
     * result, then the summary output, dropped for errors.
     */
    private fun commandViews(
        executions: List<ExecutionView>,
        items: List<ToolSummaryItem>,
        output: JsonElement?,
        isError: Boolean,
        summaryOutput: String?,
        parse: (String) -> TerminalSnapshot,
    ): List<CommandView> {
        if (executions.isNotEmpty()) {
            return executions.map { exec ->
                CommandView(
                    index = exec.commandIndex,
                    query = exec.query,
                    output = exec.output,
                    stdout = exec.stdout,
                    stderr = exec.stderr,
                    hasStderr = exec.hasStderr,
                    streamed = exec.streamed,
                    success = exec.success,
                    running = exec.running,
                    gapped = exec.gapped,
                )
            }
        }
        val queries = items.filterIsInstance<ToolSummaryItem.Command>().map { it.command }
        val text = if (isError) "" else extractRunCommandOutput(output).ifEmpty { summaryOutput.orEmpty() }
        val snapshot = if (text.isEmpty()) TerminalSnapshot.EMPTY else parse(text)
        return listOf(
            CommandView(
                index = 0,
                query = queries.joinToString("\n"),
                output = snapshot,
                stdout = snapshot,
                stderr = TerminalSnapshot.EMPTY,
                hasStderr = false,
                streamed = false,
                success = null,
                running = false,
                gapped = false,
            ),
        )
    }

    /** The `<path>` of the kernel's detach notice in the results or the terminal lines, if any. */
    internal fun detachedLogPath(executions: List<ExecutionView>, output: JsonElement?, commands: List<CommandView>): String? {
        executions.forEach { exec -> exec.result?.let { DETACH_NOTICE.find(it) }?.let { return it.groupValues[1] } }
        textOf(output)?.let { DETACH_NOTICE.find(it) }?.let { return it.groupValues[1] }
        commands.forEach { command ->
            command.output.lines.asReversed().firstNotNullOfOrNull { line -> if (line.isNotice) DETACH_NOTICE.find(line.text) else null }
                ?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun textOf(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }
}
