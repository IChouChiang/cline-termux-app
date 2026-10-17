package dev.cline.termux.data.tools

import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.ToolSymbol
import dev.cline.termux.ui.icons.getToolNameIcon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin port of apps/examples/desktop-app/webview/components/views/chat/messages/
 * tool-summaries.ts (the app layer between a tool ChatMessage and buildToolSummary), the
 * row rules of tool-message-block.tsx `ToolCallRow` (status, label override, command
 * output source, expandability, proceed) and lib/command-output.ts.
 * design/screens/home-chat.md §3.4.4 and changes-tools-checkpoints.md §1.4.
 *
 * Two departures, both presentation (P1 rule 2): the desktop's auto-open rule is dropped, so
 * every row starts folded (A23); and a `$ <command>` line prints the command from the tool
 * call's input, not the kernel's 200-character echo in the result (trial row 18).
 */

/** `ToolPayload`: the tool message's content `{toolName, input, result?, isError?}`. */
data class ToolPayload(
    val toolName: String? = null,
    val input: JsonElement? = null,
    /** [JsonNull] when the payload says `"result": null`; Kotlin null when absent. */
    val result: JsonElement? = null,
    val isError: Boolean = false,
)

/** `parseToolPayload`: the message content as a payload, or null when it is not JSON. */
fun parseToolPayload(raw: String): ToolPayload? {
    val parsed = parseJsonOrNull(raw) ?: return null
    if (!parsed.isTruthy) return null
    val record = parsed as? JsonObject ?: return ToolPayload()
    return ToolPayload(
        toolName = record["toolName"].jsString(),
        input = record["input"],
        result = record["result"],
        isError = record["isError"].isTruthy,
    )
}

/** `formatToolValue`: strings pass, `{error}` surfaces the error, others pretty-print; null/undefined is "". */
fun formatToolValue(value: JsonElement?): String {
    val normalized = normalizeJsonValue(value)
    if (normalized.isNullish) return ""
    normalized.jsString()?.let { return it }
    normalized.asRecord()?.get("error").jsString()?.let { return it }
    return jsonStringify(normalized!!, "  ")
}

/**
 * `extractRunCommandOutput`: the text of a finished run_commands result (history path). One
 * record prints its output; several prefix each with `$ <query>`. A presented result's `query`
 * is already the call input's command, not the kernel's capped echo ([withExecutedCommands],
 * trial row 18).
 */
fun extractRunCommandOutput(value: JsonElement?): String {
    val normalized = normalizeJsonValue(value)
    normalized.jsString()?.let { return it }
    val records = if (normalized is JsonArray) normalized.mapNotNull { it.asRecord() } else listOfNotNull(normalized.asRecord())
    val includeCommand = records.size > 1
    return records.map { record ->
        val output = recordString(record, "result").ifEmpty { recordString(record, "error") }
        if (output.isEmpty()) return@map ""
        val query = recordString(record, "query")
        if (includeCommand && query.isNotEmpty()) "$ $query\n$output" else output
    }.filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** `extractSubmitSummaryText`: the final answer in a `submit_and_exit` call's input. */
fun extractSubmitSummaryText(payload: ToolPayload?): String =
    recordString(normalizeJsonValue(payload?.input).asRecord(), "summary")

/** `ToolPresentation`. */
data class ToolPresentation(
    val message: ChatMessage,
    val payload: ToolPayload?,
    val toolName: String,
    val inProgress: Boolean,
    val summary: ToolSummary,
)

/** Hook events that mark a call as still running (`tool_call_start`, hydrated `history_tool_use`). */
fun isToolCallInProgress(hookEventName: String?, payload: ToolPayload?): Boolean =
    hookEventName == "tool_call_start" ||
        hookEventName == "history_tool_use" ||
        (payload != null && payload.result.isNullish && !payload.isError)

/**
 * `buildToolPresentation`. A run_commands result is presented with the call input's commands
 * in place of the kernel's capped `query` echo (trial row 18, [withExecutedCommands]), so its
 * `$ <query>` lines and the JSON fallback of a call without output never show the echo note.
 */
fun buildToolPresentation(message: ChatMessage): ToolPresentation {
    val parsed = parseToolPayload(message.content)
    val toolName = message.meta.toolName?.takeIf { it.isNotEmpty() }
        ?: parsed?.toolName?.takeIf { it.isNotEmpty() }
        ?: "tool"
    val payload = if (toolName == RUN_COMMANDS_TOOL && parsed != null) {
        parsed.copy(result = withExecutedCommands(parsed.result, parsed.input))
    } else {
        parsed
    }
    val inProgress = isToolCallInProgress(message.meta.hookEventName, payload)
    val summary = buildToolSummary(
        ToolSummaryPayload(
            toolName = toolName,
            input = payload?.input,
            result = payload?.result,
            isError = payload?.isError == true,
            inProgress = inProgress,
        ),
    )
    return ToolPresentation(message, payload, toolName, inProgress, summary)
}

// ---------------------------------------------------------------- lib/command-output.ts

/** `MAX_LIVE_COMMAND_OUTPUT_CHARS`: the inline row's cap. One definition: [CommandOutputCap]. */
const val MAX_LIVE_COMMAND_OUTPUT_CHARS = CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS

/** `COMMAND_OUTPUT_TRUNCATION_MARKER` (starts with an ANSI reset). */
const val COMMAND_OUTPUT_TRUNCATION_MARKER = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER

data class CappedOutput(val output: String, val truncated: Boolean)

/** `appendCappedCommandOutput`: keep the tail, prefix the marker once the cap is exceeded ([CommandOutputCap.appendCapped]). */
fun appendCappedCommandOutput(current: String, chunk: String, maxChars: Int = MAX_LIVE_COMMAND_OUTPUT_CHARS): CappedOutput =
    CommandOutputCap.appendCapped(current, chunk, maxChars).let { CappedOutput(it.output, it.truncated) }

// ---------------------------------------------------------------- ToolCallRow rules

/**
 * `ToolActivityStatus`. Rows use Running, Success and Error, plus [Interrupted]; Pending exists
 * for parity.
 */
enum class ToolActivityStatus {
    Pending, Running, Success, Error,

    /**
     * A21 (app addition): in progress, but its turn has finished (the run ended, or a later user
     * message started another turn). The desktop keeps such a call in progress.
     */
    Interrupted;

    /** While in flight the spinner takes the icon's slot (`ToolActivityTrigger`). */
    val inFlight: Boolean get() = this == Pending || this == Running
}

/** One diff block of the expanded row: a rich per-hunk diff, or a text-only unified diff. */
data class ToolFileDiff(
    val key: String,
    val item: ToolSummaryItem.File,
    /** Null for a text-only diff ([item].diff). */
    val hunk: DiffHunk?,
) {
    val isRich: Boolean get() = hunk != null
}

/** Everything a tool row renders, decided by the desktop's `ToolCallRow` rules. */
data class ToolRowModel(
    val presentation: ToolPresentation,
    val status: ToolActivityStatus,
    /** The row is in progress and its run is active (shimmer, spinner, proceed). */
    val isRunning: Boolean,
    /** In progress although its turn has finished: drawn as "Interrupted" (A21). */
    val isInterrupted: Boolean,
    val hasError: Boolean,
    /** The summary's label; `submit_and_exit` uses the scheduled-task milestone phrase. */
    val label: ToolLabel,
    /** Kind icon (name override first); error rows draw the error icon instead. */
    val icon: ToolSymbol,
    /** `+N` badge; null when zero or unknown. */
    val additions: Int?,
    /** `-N` badge; null when zero or unknown. */
    val deletions: Int?,
    val isCommand: Boolean,
    val isSubmit: Boolean,
    /** Markdown body of `submit_and_exit`, empty otherwise. */
    val submitText: String,
    /** Command output capped at 48 000 chars, empty when none. */
    val commandOutput: String,
    val details: List<String>,
    val fileDiffs: List<ToolFileDiff>,
    val outputImages: List<ChatImage>,
    val otherOutputMedia: List<ToolOutputMedia>,
    /** Non-command output text (the summary's), shown in a mono block. */
    val outputText: String?,
    val errorText: String?,
    /** Debug-only "Input" block; empty when not requested. */
    val inputPreview: String,
    val canProceed: Boolean,
    /** Any body section exists; a non-expandable row has no click, ripple or chevron. */
    val expandable: Boolean,
)

private val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/**
 * The `ToolCallRow` rules. [isRunActive] = the row sits after the last user message and the
 * session is busy ("a missing result can outlive its run": without it an in-progress row is
 * Interrupted, A21). [proceedAvailable] = the host wired `run.proceed_while_running`.
 * [showInputPreview] = debug builds' "Input" block.
 */
fun buildToolRowModel(
    message: ChatMessage,
    isRunActive: Boolean,
    proceedAvailable: Boolean = true,
    showInputPreview: Boolean = false,
): ToolRowModel = buildToolRowModel(buildToolPresentation(message), isRunActive, proceedAvailable, showInputPreview)

fun buildToolRowModel(
    presentation: ToolPresentation,
    isRunActive: Boolean,
    proceedAvailable: Boolean = true,
    showInputPreview: Boolean = false,
): ToolRowModel {
    val (message, payload, toolName, inProgress, summary) = presentation
    val isRunning = inProgress && isRunActive
    val isCommand = summary.kind == ToolKind.Command
    val isSubmit = summary.toolName == "submit_and_exit"
    val hasError = payload?.isError == true
    // A21 (trial row 9): a finished turn has no running call. The desktop draws a call whose
    // result never came (a cancelling hook ended the run, a Hub restart) in progress forever.
    val isInterrupted = inProgress && !isRunActive && !hasError
    val submitText = if (isSubmit) extractSubmitSummaryText(payload).ifEmpty { summary.outputText.orEmpty() } else ""
    val baseLabel = if (isSubmit) {
        ToolLabel.Phrase(
            when {
                inProgress -> ToolPhrase.CompletingScheduledTask
                hasError -> ToolPhrase.ScheduledTaskFailed
                else -> ToolPhrase.ScheduledTaskCompleted
            },
        )
    } else {
        summary.label
    }
    val label = if (isInterrupted) ToolLabel.Interrupted(baseLabel) else baseLabel
    // Streamed chunks always win; otherwise the finished result, dropped for errors.
    val commandOutputSource = if (isCommand) {
        message.meta.toolOutput?.takeIf { it.isNotEmpty() }
            ?: if (hasError) "" else extractRunCommandOutput(payload?.result).ifEmpty { summary.outputText.orEmpty() }
    } else {
        ""
    }
    val commandOutput = if (commandOutputSource.isNotEmpty()) appendCappedCommandOutput("", commandOutputSource).output else ""
    val canProceed = isRunning && isCommand && message.meta.toolDetachable && message.sessionId.isNotEmpty() && proceedAvailable
    val fileDiffs = summary.items.withIndex().flatMap { (index, item) ->
        if (item !is ToolSummaryItem.File) return@flatMap emptyList()
        val hunks = item.hunks ?: item.newText?.let { listOf(DiffHunk(item.oldText, it)) }
        when {
            hunks != null -> hunks.mapIndexed { hunkIndex, hunk -> ToolFileDiff("${message.id}_diff_${index}_$hunkIndex", item, hunk) }
            item.diff != null -> listOf(ToolFileDiff("${message.id}_diff_$index", item, null))
            else -> emptyList()
        }
    }
    val outputImages = summary.outputMedia.withIndex().mapNotNull { (index, media) ->
        if (media.modality != MediaModality.Image || media.mediaType !in IMAGE_MEDIA_TYPES || media.data.isEmpty()) return@mapNotNull null
        ChatImage(id = "${message.id}_tool_image_$index", mediaType = media.mediaType, data = media.data)
    }
    val otherOutputMedia = summary.outputMedia.filter { it.modality != MediaModality.Image }
    val inputPreview = if (showInputPreview && payload != null) formatToolValue(payload.input) else ""
    val expandable = summary.details.isNotEmpty() ||
        fileDiffs.isNotEmpty() ||
        submitText.isNotEmpty() ||
        (if (isCommand) commandOutput.isNotEmpty() else !summary.outputText.isNullOrEmpty()) ||
        summary.outputMedia.isNotEmpty() ||
        !summary.errorText.isNullOrEmpty() ||
        inputPreview.isNotEmpty() ||
        canProceed
    return ToolRowModel(
        presentation = presentation,
        status = when {
            hasError -> ToolActivityStatus.Error
            isRunning -> ToolActivityStatus.Running
            isInterrupted -> ToolActivityStatus.Interrupted
            else -> ToolActivityStatus.Success
        },
        isRunning = isRunning,
        isInterrupted = isInterrupted,
        hasError = hasError,
        label = label,
        icon = getToolNameIcon(toolName),
        additions = summary.diff?.additions?.takeIf { it != 0 },
        deletions = summary.diff?.deletions?.takeIf { it != 0 },
        isCommand = isCommand,
        isSubmit = isSubmit,
        submitText = submitText,
        commandOutput = commandOutput,
        details = summary.details,
        fileDiffs = fileDiffs,
        outputImages = outputImages,
        otherOutputMedia = otherOutputMedia,
        outputText = summary.outputText,
        errorText = summary.errorText,
        inputPreview = inputPreview,
        canProceed = canProceed,
        expandable = expandable,
    )
}
