package dev.cline.termux.data.tools

import dev.cline.termux.core.kernel.hub.SwitchToActModeTool
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.classifyTool
import dev.cline.termux.ui.icons.normalizeToolName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin port of sdk/packages/ui/components/agent-chat/tool-summary/index.ts and team.ts:
 * the framework-free presentation of one tool call (label, styled label parts, per-item
 * details, diff counts, extracted output). `ToolKind`, `normalizeToolName` and
 * `classifyTool` are the ports already in ui/icons/ToolIcons.kt. Labels are typed
 * [ToolLabel]s chosen exactly as the shared module chooses its English strings; the wording
 * lives in string resources (DS §8.2, ui/chat/rows/ToolLabelText.kt).
 */

/** `ToolSummaryPayload`. [input] may be structured or a JSON string; [result] is the raw tool result. */
data class ToolSummaryPayload(
    val toolName: String,
    val input: JsonElement? = null,
    val result: JsonElement? = null,
    val isError: Boolean = false,
    val inProgress: Boolean = false,
)

/**
 * `ToolAggregate`: lets consecutive calls merge into grouped counts ("Read 3 files"). [key]
 * is the merge identity; [noun] names the counted phrase (the desktop's noun and verbs).
 */
data class ToolAggregate(
    val key: String,
    val count: Int,
    val noun: AggregateNoun,
)

/** `ToolSummaryItem`. */
sealed interface ToolSummaryItem {
    data class File(
        val path: String,
        val displayPath: String,
        val startLine: Int? = null,
        val endLine: Int? = null,
        val language: String? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        /** Unified diff text, when reconstructable. */
        val diff: String? = null,
        /** Absent for created files. */
        val oldText: String? = null,
        val newText: String? = null,
        /** Texts are fragments of the file: hide absolute line numbers. */
        val fragment: Boolean? = null,
        /** Multi-hunk changes (apply_patch): render one diff per hunk. Preferred over old/new text. */
        val hunks: List<DiffHunk>? = null,
    ) : ToolSummaryItem

    data class Command(val command: String) : ToolSummaryItem
    data class Query(val query: String) : ToolSummaryItem
    data class Url(val url: String) : ToolSummaryItem
    data class Text(val text: String) : ToolSummaryItem
}

/** `ToolSummary`. */
data class ToolSummary(
    /** Normalized tool name (aliases applied). */
    val toolName: String,
    val kind: ToolKind,
    val label: ToolLabel,
    val aggregate: ToolAggregate? = null,
    val items: List<ToolSummaryItem> = emptyList(),
    val details: List<String> = emptyList(),
    val diff: DiffCounts? = null,
    val outputText: String? = null,
    val outputMedia: List<ToolOutputMedia> = emptyList(),
    val errorText: String? = null,
)

enum class PathStyle { Shortened, Basename, Full }

/** `ToolSummaryOptions`; [maxInlineChars] is unlimited by default (layout ellipsizes). */
data class ToolSummaryOptions(
    val pathStyle: PathStyle = PathStyle.Shortened,
    val maxOutputChars: Int = 64 * 1024,
    val maxInlineChars: Int = Int.MAX_VALUE,
)

private fun truncate(text: String, maxLen: Int): String {
    val collapsed = collapseWhitespace(text)
    if (collapsed.length <= maxLen) return collapsed
    return "${takeGraphemePrefix(collapsed, maxOf(1, maxLen - 1)).trimEnd()}…"
}

private val MCP_PREFIX = Regex("^mcp[-_]+", RegexOption.IGNORE_CASE)
private val SEPARATORS = Regex("[-_]+")

private fun humanizeToolName(toolName: String): String {
    val cleaned = toolName.replace(MCP_PREFIX, "").replace(SEPARATORS, " ").trim()
    if (cleaned.isEmpty()) return toolName
    return cleaned.substring(0, 1).uppercase() + cleaned.substring(1)
}

private fun formatPath(path: String, style: PathStyle): String = when (style) {
    PathStyle.Basename -> displayFileName(path)
    PathStyle.Full -> path
    PathStyle.Shortened -> shortenPath(path)
}

/** ` (10–80)` / ` (10+)` label suffix for a partial-file read. */
private fun lineRangeLabelSuffix(startLine: Int?, endLine: Int?): String = when {
    startLine != null && endLine != null -> " ($startLine–$endLine)"
    startLine != null -> " ($startLine+)"
    endLine != null -> " (1–$endLine)"
    else -> ""
}

/** `:10-80` detail suffix for a partial-file read. */
private fun lineRangeDetailSuffix(startLine: Int?, endLine: Int?): String = when {
    startLine != null && endLine != null -> ":$startLine-$endLine"
    startLine != null -> ":$startLine+"
    endLine != null -> ":1-$endLine"
    else -> ""
}

/** `capText`: cap extracted text with the "[output truncated]" marker. */
fun capText(text: String?, maxChars: Int): String? {
    if (text == null) return null
    if (text.length <= maxChars) return text
    return "${text.substring(0, maxChars)}\n\n[output truncated]"
}

private fun extractErrorText(result: JsonElement?, maxChars: Int): String? {
    val normalized = normalizeJsonValue(result)
    normalized.asRecord()?.get("error").jsString()?.let { return capText(it, maxChars) }
    return capText(extractOutputText(normalized), maxChars)
}

/** The in-progress or completed tense of a phrase pair. */
private fun tense(inProgress: Boolean, progress: ToolPhrase, completed: ToolPhrase) = if (inProgress) progress else completed

private fun phrase(inProgress: Boolean, progress: ToolPhrase, completed: ToolPhrase, arg: String? = null): ToolLabel =
    ToolLabel.Phrase(tense(inProgress, progress, completed), arg)

private fun fallbackLabel(kind: ToolKind, toolName: String, inProgress: Boolean): ToolLabel = when (kind) {
    ToolKind.Read -> phrase(inProgress, ToolPhrase.ReadingFile, ToolPhrase.ReadFile)
    ToolKind.Edit -> phrase(inProgress, ToolPhrase.EditingFile, ToolPhrase.EditedFile)
    ToolKind.Command -> phrase(inProgress, ToolPhrase.RunningCommand, ToolPhrase.RanCommand)
    ToolKind.Search -> phrase(inProgress, ToolPhrase.Searching, ToolPhrase.Searched)
    ToolKind.Web -> phrase(inProgress, ToolPhrase.FetchingWebContent, ToolPhrase.FetchedWebContent)
    ToolKind.Spawn -> phrase(inProgress, ToolPhrase.SpawningAgent, ToolPhrase.SpawnedAgent)
    ToolKind.Skill -> phrase(inProgress, ToolPhrase.UsingSkill, ToolPhrase.UsedSkill)
    ToolKind.Question -> phrase(inProgress, ToolPhrase.AskingAQuestion, ToolPhrase.AskedAQuestion)
    ToolKind.Mcp -> phrase(inProgress, ToolPhrase.CallingMcpTool, ToolPhrase.CalledMcpTool)
    // Unknown tools keep their humanized name as data (DS §8.2.8).
    else -> if (inProgress) ToolLabel.Phrase(ToolPhrase.RunningTool, humanizeToolName(toolName)) else ToolLabel.Text(humanizeToolName(toolName))
}

private val READ_AGGREGATE = ToolAggregate("read-files", 1, AggregateNoun.ReadFiles)
private val EDIT_AGGREGATE = ToolAggregate("edited-files", 1, AggregateNoun.EditedFiles)
private val COMMAND_AGGREGATE = ToolAggregate("commands", 1, AggregateNoun.Commands)
private val SEARCH_AGGREGATE = ToolAggregate("searches", 1, AggregateNoun.Searches)
private val WEB_AGGREGATE = ToolAggregate("links", 1, AggregateNoun.Links)
private val SPAWN_AGGREGATE = ToolAggregate("spawned-agents", 1, AggregateNoun.SpawnedAgents)

private fun aggregateLabel(aggregate: ToolAggregate, count: Int, inProgress: Boolean): ToolLabel =
    ToolLabel.Aggregate(aggregate.noun, count, inProgress)

/** Fallback summaries still carry an aggregate so consecutive calls merge ("Spawned 3 agents"). */
private val FALLBACK_AGGREGATES: Map<ToolKind, ToolAggregate> = mapOf(
    ToolKind.Command to COMMAND_AGGREGATE,
    ToolKind.Edit to EDIT_AGGREGATE,
    ToolKind.Read to READ_AGGREGATE,
    ToolKind.Search to SEARCH_AGGREGATE,
    ToolKind.Spawn to SPAWN_AGGREGATE,
    ToolKind.Web to WEB_AGGREGATE,
)

private fun ToolSummary.labeled(label: ToolLabel): ToolSummary = copy(label = label)

/**
 * `buildToolSummary`: single-item calls carry their specifics inline (`Read file app.tsx
 * (10–80)`, `Ran command bun test`); multi-item calls aggregate (`Read 3 files`) with one
 * detail line per item. Never throws.
 */
fun buildToolSummary(payload: ToolSummaryPayload, options: ToolSummaryOptions = ToolSummaryOptions()): ToolSummary {
    val toolName = normalizeToolName(payload.toolName.ifEmpty { "tool" })
    val kind = classifyTool(toolName)
    val inProgress = payload.inProgress
    val isError = payload.isError
    val input = normalizeJsonValue(payload.input)
    val base = ToolSummary(
        toolName = toolName,
        kind = kind,
        label = fallbackLabel(kind, toolName, inProgress),
        aggregate = FALLBACK_AGGREGATES[kind],
        outputMedia = extractOutputMedia(payload.result),
        errorText = if (isError) extractErrorText(payload.result, options.maxOutputChars) else null,
    )

    if (kind == ToolKind.Team) {
        teamSummary(toolName, input, payload.result, inProgress, isError)?.let { team ->
            return base.labeled(team.label).copy(
                aggregate = team.aggregate,
                details = team.details,
                items = team.details.map { ToolSummaryItem.Text(it) },
            )
        }
    }

    // The app's own client tool (A11 row 4; CLI `tool-output.tsx` shows no output for it): the action
    // as its label, the success text kept from the row (an error still shows through errorText).
    if (toolName == SwitchToActModeTool.NAME) return base.labeled(ToolLabel.Phrase(ToolPhrase.SwitchToActMode))

    if (toolName == "read_files") {
        parseReadFilesInput(input)?.let { info ->
            val single = info.files.singleOrNull()
            val label = if (single != null) {
                phrase(
                    inProgress,
                    ToolPhrase.ReadingFileNamed,
                    ToolPhrase.ReadFileNamed,
                    "${displayFileName(single.path)}${lineRangeLabelSuffix(single.startLine, single.endLine)}",
                )
            } else {
                aggregateLabel(READ_AGGREGATE, info.files.size, inProgress)
            }
            return base.labeled(label).copy(
                aggregate = READ_AGGREGATE.copy(count = info.files.size),
                items = info.files.map {
                    ToolSummaryItem.File(
                        path = it.path,
                        displayPath = formatPath(it.path, options.pathStyle),
                        startLine = it.startLine,
                        endLine = it.endLine,
                        language = detectLanguage(it.path),
                    )
                },
                details = info.files.map { "${formatPath(it.path, options.pathStyle)}${lineRangeDetailSuffix(it.startLine, it.endLine)}" },
            )
        }
    }

    if (toolName == "run_commands") {
        parseRunCommandsInput(input)?.let { info ->
            val commands = info.commands.map { it.trim() }
            val label = if (commands.size == 1) {
                phrase(inProgress, ToolPhrase.RunningCommandNamed, ToolPhrase.RanCommandNamed, truncate(commands[0], options.maxInlineChars))
            } else {
                aggregateLabel(COMMAND_AGGREGATE, commands.size, inProgress)
            }
            return base.labeled(label).copy(
                aggregate = COMMAND_AGGREGATE.copy(count = commands.size),
                items = commands.map { ToolSummaryItem.Command(it) },
                // The label may be ellipsized, so the expanded panel carries the full commands.
                details = commands,
                outputText = if (isError) null else capText(extractOutputText(payload.result), options.maxOutputChars),
            )
        }
    }

    if (toolName == "search_codebase") {
        parseSearchInput(input)?.let { info ->
            val label = if (info.queries.size == 1) {
                phrase(inProgress, ToolPhrase.SearchingNamed, ToolPhrase.SearchedNamed, truncate(info.queries[0], options.maxInlineChars))
            } else {
                aggregateLabel(SEARCH_AGGREGATE, info.queries.size, inProgress)
            }
            return base.labeled(label).copy(
                aggregate = SEARCH_AGGREGATE.copy(count = info.queries.size),
                items = info.queries.map { ToolSummaryItem.Query(it) },
                details = info.queries,
            )
        }
    }

    if (toolName == "fetch_web_content") {
        parseWebFetchInput(input)?.let { info ->
            val label = if (info.urls.size == 1) {
                phrase(inProgress, ToolPhrase.FetchingNamed, ToolPhrase.FetchedNamed, truncate(info.urls[0], options.maxInlineChars))
            } else {
                aggregateLabel(WEB_AGGREGATE, info.urls.size, inProgress)
            }
            return base.labeled(label).copy(
                aggregate = WEB_AGGREGATE.copy(count = info.urls.size),
                items = info.urls.map { ToolSummaryItem.Url(it) },
                details = info.urls,
            )
        }
    }

    if (toolName == "apply_patch") {
        val info = parseApplyPatchInput(input)
            ?: return base.labeled(phrase(inProgress, ToolPhrase.ApplyingPatch, ToolPhrase.AppliedPatch))
                .copy(aggregate = EDIT_AGGREGATE)
        fun actionPhrase(action: PatchAction) = when (action) {
            PatchAction.Add -> tense(inProgress, ToolPhrase.CreatingFileNamed, ToolPhrase.CreatedFileNamed)
            PatchAction.Delete -> tense(inProgress, ToolPhrase.DeletingFileNamed, ToolPhrase.DeletedFileNamed)
            PatchAction.Update -> tense(inProgress, ToolPhrase.EditingFileNamed, ToolPhrase.EditedFileNamed)
        }
        // Renames display as `old → new`.
        fun fileLabel(file: ApplyPatchFile) =
            file.movedTo?.let { "${displayFileName(file.path)} → ${displayFileName(it)}" } ?: displayFileName(file.path)
        fun filePathDetail(file: ApplyPatchFile) =
            file.movedTo?.let { "${formatPath(file.path, options.pathStyle)} → ${formatPath(it, options.pathStyle)}" }
                ?: formatPath(file.path, options.pathStyle)
        val single = info.files.singleOrNull()
        val label = if (single != null) {
            ToolLabel.Phrase(actionPhrase(single.action), fileLabel(single))
        } else {
            aggregateLabel(EDIT_AGGREGATE, info.files.size, inProgress)
        }
        return base.labeled(label).copy(
            aggregate = EDIT_AGGREGATE.copy(count = info.files.size),
            diff = DiffCounts(info.additions, info.deletions),
            items = info.files.map { file ->
                val target = file.movedTo ?: file.path
                val singleHunk = file.action != PatchAction.Delete && file.hunks.size == 1
                ToolSummaryItem.File(
                    path = target,
                    displayPath = formatPath(target, options.pathStyle),
                    language = detectLanguage(target),
                    additions = file.additions,
                    deletions = file.deletions,
                    diff = file.diff,
                    // Deletions have no rich diff; single-hunk changes expose plain texts,
                    // multi-hunk changes must render per hunk.
                    oldText = if (singleHunk && file.action != PatchAction.Add) file.hunks[0].oldText else null,
                    newText = if (singleHunk) file.hunks[0].newText else null,
                    hunks = if (file.action != PatchAction.Delete && file.hunks.size > 1) file.hunks else null,
                    fragment = file.action != PatchAction.Add,
                )
            },
            details = info.files.map { file ->
                if (file.action == PatchAction.Delete) {
                    "Deleted ${filePathDetail(file)}"
                } else {
                    "${filePathDetail(file)} +${file.additions} -${file.deletions}"
                }
            },
        )
    }

    if (toolName == "editor") {
        val info = parseEditorInput(input)
        val inputRecord = input.asRecord()
        val path = info?.path ?: inputRecord?.get("path").jsString()
        // The editor schema has no `command`; derive the action from the shape.
        val command = inputRecord?.get("command").jsString()
            ?: when {
                inputRecord != null && !inputRecord["insert_line"].isNullish -> "insert"
                inputRecord?.get("old_text").jsString() != null -> "str_replace"
                inputRecord?.get("new_text").jsString() != null -> "create"
                else -> "edit"
            }
        val verb = when (command) {
            "create" -> tense(inProgress, ToolPhrase.CreatingFileNamed, ToolPhrase.CreatedFileNamed)
            "insert" -> tense(inProgress, ToolPhrase.InsertingIntoNamed, ToolPhrase.InsertedIntoNamed)
            else -> tense(inProgress, ToolPhrase.EditingFileNamed, ToolPhrase.EditedFileNamed)
        }
        var diff: DiffCounts?
        var diffText: String? = null
        if (info != null && (info.oldText != null || command == "create")) {
            // str_replace texts are fragments; fragment mode keeps the header honest.
            diffText = makeUnifiedDiff(info.oldText ?: "", info.newText, info.path, 3, fragment = command != "create")
            diff = countDiffLines(diffText)
        } else {
            diff = parseEditorResultDiffCounts(payload.result)
        }
        if (!path.isNullOrEmpty()) {
            return base.labeled(ToolLabel.Phrase(verb, displayFileName(path))).copy(
                aggregate = EDIT_AGGREGATE,
                diff = diff,
                items = listOf(
                    ToolSummaryItem.File(
                        path = path,
                        displayPath = formatPath(path, options.pathStyle),
                        language = detectLanguage(path),
                        additions = diff?.additions,
                        deletions = diff?.deletions,
                        diff = diffText,
                        oldText = info?.oldText,
                        newText = info?.newText,
                        fragment = command != "create",
                    ),
                ),
                // The label carries the basename; the panel leads with the fuller path.
                details = listOf(formatPath(path, options.pathStyle)),
            )
        }
    }

    if (toolName == "spawn_agent" || toolName.startsWith("subagent_")) {
        parseSpawnAgentInput(input)?.let { info ->
            return base.labeled(
                phrase(inProgress, ToolPhrase.SpawningAgentTask, ToolPhrase.SpawnedAgentTask, truncate(info.task, options.maxInlineChars)),
            ).copy(
                aggregate = SPAWN_AGGREGATE,
                items = listOf(ToolSummaryItem.Text(info.task)),
                details = listOf(info.task),
            )
        }
    }

    if (kind == ToolKind.Skill) {
        val record = input.asRecord()
        val skill = record?.get("skill").jsString() ?: ""
        val args = record?.get("args").jsString() ?: ""
        if (skill.isNotEmpty()) {
            val detail = if (args.isNotEmpty()) "$skill $args" else skill
            return base.labeled(
                phrase(inProgress, ToolPhrase.UsingSkillNamed, ToolPhrase.UsedSkillNamed, truncate(skill, options.maxInlineChars)),
            ).copy(items = listOf(ToolSummaryItem.Text(detail)), details = listOf(detail))
        }
    }

    if (kind == ToolKind.Question) {
        parseAskQuestionInput(input)?.let { info ->
            return base.labeled(
                phrase(inProgress, ToolPhrase.AskingQuestionText, ToolPhrase.AskedQuestionText, truncate(info.question, options.maxInlineChars)),
            ).copy(
                items = listOf(ToolSummaryItem.Text(info.question)) + info.options.map { ToolSummaryItem.Text(it) },
                details = info.options,
                outputText = if (isError) null else capText(extractOutputText(payload.result), options.maxOutputChars),
            )
        }
    }

    // Unknown / MCP tools: humanized name, plus whatever text the result holds.
    val normalizedResult = normalizeJsonValue(payload.result)
    val query = normalizedResult.asRecord()?.get("query").jsString() ?: ""
    val withQuery = if (query.isNotEmpty()) base.labeled(ToolLabel.Text(truncate(query, options.maxInlineChars))) else base
    return withQuery.copy(
        outputText = if (isError) null else capText(extractOutputText(normalizedResult), options.maxOutputChars),
    )
}

/** Input of [buildGroupedToolLabel]: a summary's label and aggregate plus its row's progress. */
data class GroupedToolLabelInput(val label: ToolLabel, val aggregate: ToolAggregate? = null, val inProgress: Boolean = false)

/**
 * `buildGroupedToolLabel`: adjacent same-key aggregates merge their counts ("Read 3
 * files"); other labels pass through. The segments are joined in the UI with the
 * `chat_tool_label_group` separator resource ("%1$s · %2$s").
 */
fun buildGroupedToolLabel(summaries: List<GroupedToolLabelInput>): List<ToolLabel> {
    if (summaries.size == 1) return listOf(summaries[0].label)
    data class Segment(val label: ToolLabel?, val aggregate: ToolAggregate?, val inProgress: Boolean)
    val segments = mutableListOf<Segment>()
    for (summary in summaries) {
        val aggregate = summary.aggregate
        if (aggregate == null) {
            segments += Segment(summary.label, null, false)
            continue
        }
        val previous = segments.lastOrNull()
        if (previous?.aggregate != null && previous.aggregate.key == aggregate.key) {
            segments[segments.lastIndex] = Segment(
                null,
                previous.aggregate.copy(count = previous.aggregate.count + aggregate.count),
                previous.inProgress || summary.inProgress,
            )
            continue
        }
        segments += Segment(null, aggregate, summary.inProgress)
    }
    return segments.mapNotNull { segment ->
        val aggregate = segment.aggregate ?: return@mapNotNull segment.label
        ToolLabel.Aggregate(aggregate.noun, aggregate.count, segment.inProgress)
    }
}

// ---------------------------------------------------------------- team.ts

/** `TeamSummaryResult`. */
data class TeamSummaryResult(val label: ToolLabel, val details: List<String>, val aggregate: ToolAggregate? = null)

private val TEAM_FAILURE_LABELS: Map<String, ToolPhrase> = mapOf(
    "team_attach_outcome_fragment" to ToolPhrase.FailedAttachOutcomeFragment,
    "team_await_runs" to ToolPhrase.FailedAwaitRuns,
    "team_broadcast" to ToolPhrase.FailedBroadcast,
    "team_cancel_run" to ToolPhrase.FailedCancelRun,
    "team_cleanup" to ToolPhrase.FailedCleanup,
    "team_create_outcome" to ToolPhrase.FailedCreateOutcome,
    "team_finalize_outcome" to ToolPhrase.FailedFinalizeOutcome,
    "team_list_outcomes" to ToolPhrase.FailedListOutcomes,
    "team_list_runs" to ToolPhrase.FailedListRuns,
    "team_mission_log" to ToolPhrase.FailedMissionLog,
    "team_read_mailbox" to ToolPhrase.FailedReadMailbox,
    "team_review_outcome_fragment" to ToolPhrase.FailedReviewOutcomeFragment,
    "team_run_task" to ToolPhrase.FailedRunTask,
    "team_send_message" to ToolPhrase.FailedSendMessage,
    "team_shutdown_teammate" to ToolPhrase.FailedShutdownTeammate,
    "team_spawn_teammate" to ToolPhrase.FailedSpawnTeammate,
    "team_status" to ToolPhrase.FailedTeamStatus,
    "team_task" to ToolPhrase.FailedTeamTask,
)

private fun resultRecords(result: JsonElement?): List<JsonObject> {
    val normalized = normalizeJsonValue(result)
    if (normalized is JsonArray) return normalized.mapNotNull { it.asRecord() }
    return listOfNotNull(normalized.asRecord())
}

/** `recordString`: a non-empty string field, else [fallback]. */
fun recordString(record: JsonObject?, key: String, fallback: String = ""): String =
    record?.get(key).nonEmptyString() ?: fallback

private fun joinNonEmpty(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString(" ")

private fun recordsOf(value: JsonElement?): List<JsonObject>? = (value as? JsonArray)?.mapNotNull { it.asRecord() }

/** `teamSummary`: labels for the `team_*` tools, or null for an unknown one. */
fun teamSummary(
    toolName: String,
    input: JsonElement?,
    result: JsonElement?,
    inProgress: Boolean,
    isError: Boolean,
): TeamSummaryResult? {
    if (!toolName.startsWith("team_")) return null
    if (isError) {
        val failure = TEAM_FAILURE_LABELS[toolName]?.let { ToolLabel.Phrase(it) } ?: ToolLabel.Phrase(ToolPhrase.FailedTool, toolName)
        return TeamSummaryResult(failure, emptyList())
    }
    val inputRecord = normalizeJsonValue(input).asRecord()
    val records = resultRecords(result)
    val resultRecord = records.firstOrNull()
    fun aggregate(key: String, noun: AggregateNoun, details: List<String>, count: Int = 1) = TeamSummaryResult(
        label = ToolLabel.Aggregate(noun, count, inProgress),
        details = details,
        aggregate = ToolAggregate(key, count, noun),
    )
    fun label(progress: ToolPhrase, completed: ToolPhrase) = phrase(inProgress, progress, completed)
    fun counted(progress: ToolPhrase, completed: ToolCountPhrase, count: Int): ToolLabel =
        if (inProgress) ToolLabel.Phrase(progress) else ToolLabel.Count(completed, count)
    val agentId = recordString(resultRecord, "agentId", recordString(inputRecord, "agentId"))

    return when (toolName) {
        "team_spawn_teammate" ->
            aggregate("team-spawn", AggregateNoun.TeamSpawn, listOfNotNull(agentId.ifEmpty { null }))
        "team_run_task" -> {
            val mode = recordString(resultRecord, "mode", recordString(inputRecord, "runMode", "sync"))
            val status = if (inProgress) "assigning" else recordString(resultRecord, "status", "assigned")
            val line = joinNonEmpty(mode, agentId, status)
            aggregate("team-run-task", AggregateNoun.TeamRunTask, listOfNotNull(line.ifEmpty { null }))
        }
        "team_await_runs" -> TeamSummaryResult(
            label = label(ToolPhrase.WaitingForTeammates, ToolPhrase.WaitedForTeammates),
            details = records.map { joinNonEmpty(recordString(it, "agentId", recordString(it, "id")), recordString(it, "status")) },
        )
        "team_shutdown_teammate" ->
            aggregate("team-shutdown", AggregateNoun.TeamShutdown, listOfNotNull(agentId.ifEmpty { null }))
        "team_status" -> {
            val members = recordsOf(resultRecord?.get("members")) ?: emptyList()
            TeamSummaryResult(
                label = label(ToolPhrase.CheckingTeamStatus, ToolPhrase.CheckedTeamStatus),
                details = members.map { joinNonEmpty(recordString(it, "agentId"), recordString(it, "status")) },
            )
        }
        "team_task" -> {
            val action = recordString(inputRecord, "action", recordString(resultRecord, "action", "update"))
            val noun = when (action) {
                "create" -> AggregateNoun.TeamTaskCreate
                "list" -> AggregateNoun.TeamTaskList
                "claim" -> AggregateNoun.TeamTaskClaim
                "complete" -> AggregateNoun.TeamTaskComplete
                "block" -> AggregateNoun.TeamTaskBlock
                else -> AggregateNoun.TeamTaskUpdate
            }
            val tasks = recordsOf(resultRecord?.get("tasks")) ?: records
            val details = tasks.map { task ->
                joinNonEmpty(
                    recordString(task, "taskId", recordString(task, "id", recordString(inputRecord, "taskId"))),
                    recordString(task, "title", recordString(inputRecord, "title")),
                    recordString(task, "status"),
                )
            }
            aggregate("team-task-$action", noun, details, if (action == "list") tasks.size else 1)
        }
        "team_list_runs" -> TeamSummaryResult(
            label = counted(ToolPhrase.ListingTeammateRuns, ToolCountPhrase.ListedTeammateRuns, records.size),
            details = records.map { joinNonEmpty(recordString(it, "agentId"), recordString(it, "status")) },
        )
        "team_cancel_run" -> TeamSummaryResult(
            label = label(ToolPhrase.CancellingTeammateRun, ToolPhrase.CancelledTeammateRun),
            details = listOf(
                joinNonEmpty(recordString(resultRecord, "runId", recordString(inputRecord, "runId")), recordString(resultRecord, "status")),
            ).filter { it.isNotEmpty() },
        )
        "team_send_message" -> {
            val recipient = recordString(resultRecord, "toAgentId", recordString(inputRecord, "toAgentId"))
            val line = joinNonEmpty(recipient, recordString(inputRecord, "subject"))
            aggregate("team-send-message", AggregateNoun.TeamSendMessage, listOfNotNull(line.ifEmpty { null }))
        }
        "team_broadcast" -> {
            val delivered = resultRecord?.get("delivered").jsNumber()?.toInt() ?: 0
            val subject = recordString(inputRecord, "subject")
            TeamSummaryResult(
                label = counted(ToolPhrase.BroadcastingToTeammates, ToolCountPhrase.BroadcastToTeammates, delivered),
                details = listOfNotNull(subject.ifEmpty { null }),
            )
        }
        "team_read_mailbox" -> TeamSummaryResult(
            label = counted(ToolPhrase.ReadingTeamMailbox, ToolCountPhrase.ReadTeamMessages, records.size),
            details = records.map { joinNonEmpty(recordString(it, "fromAgentId"), recordString(it, "subject")) },
        )
        "team_mission_log" -> TeamSummaryResult(
            label = label(ToolPhrase.UpdatingMissionLog, ToolPhrase.UpdatedMissionLog),
            details = listOf(joinNonEmpty(recordString(inputRecord, "kind"), recordString(inputRecord, "summary"))).filter { it.isNotEmpty() },
        )
        "team_cleanup" -> TeamSummaryResult(
            label = label(ToolPhrase.CleaningUpTeam, ToolPhrase.CleanedUpTeam),
            details = listOfNotNull(recordString(resultRecord, "status").ifEmpty { null }),
        )
        "team_create_outcome" -> TeamSummaryResult(
            label = label(ToolPhrase.CreatingTeamOutcome, ToolPhrase.CreatedTeamOutcome),
            details = listOf(
                joinNonEmpty(recordString(resultRecord, "outcomeId"), recordString(inputRecord, "title"), recordString(resultRecord, "status")),
            ).filter { it.isNotEmpty() },
        )
        "team_attach_outcome_fragment" -> TeamSummaryResult(
            label = label(ToolPhrase.AttachingOutcomeFragment, ToolPhrase.AttachedOutcomeFragment),
            details = listOf(joinNonEmpty(recordString(inputRecord, "section"), recordString(resultRecord, "status"))).filter { it.isNotEmpty() },
        )
        "team_review_outcome_fragment" -> {
            val approved = inputRecord?.get("approved").jsBoolean()
            val verdict = when (approved) {
                true -> "approved"
                false -> "rejected"
                null -> recordString(resultRecord, "status")
            }
            TeamSummaryResult(
                label = label(ToolPhrase.ReviewingOutcomeFragment, ToolPhrase.ReviewedOutcomeFragment),
                details = listOf(joinNonEmpty(recordString(inputRecord, "fragmentId"), verdict)).filter { it.isNotEmpty() },
            )
        }
        "team_finalize_outcome" -> TeamSummaryResult(
            label = label(ToolPhrase.FinalizingTeamOutcome, ToolPhrase.FinalizedTeamOutcome),
            details = listOf(
                joinNonEmpty(recordString(resultRecord, "outcomeId", recordString(inputRecord, "outcomeId")), recordString(resultRecord, "status")),
            ).filter { it.isNotEmpty() },
        )
        "team_list_outcomes" -> TeamSummaryResult(
            label = counted(ToolPhrase.ListingTeamOutcomes, ToolCountPhrase.ListedTeamOutcomes, records.size),
            details = records.map { joinNonEmpty(recordString(it, "title", recordString(it, "id")), recordString(it, "status")) },
        )
        else -> null
    }
}
