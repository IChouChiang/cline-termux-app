package dev.cline.termux.data.agents

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.tools.asRecord
import dev.cline.termux.data.tools.isNullish
import dev.cline.termux.data.tools.isTruthy
import dev.cline.termux.data.tools.jsString
import dev.cline.termux.data.tools.nonEmptyString
import dev.cline.termux.data.tools.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Header-level accounting of the child agents a session has started: Kotlin port of
 * apps/examples/desktop-app/webview/lib/session-agents.ts. There is no dedicated subagent
 * event on the wire, so every rule is derived from the spawning tool calls, which stays
 * correct for live turns, materialized run results and hydrated history alike.
 */

/** `SessionAgentRunState`. [Unresolved]: dispatched, no observed outcome (a crash or reload). */
enum class SessionAgentRunState { Running, Completed, Failed, Cancelled, Unresolved }

/** `SessionAgentActivity`. */
data class SessionAgentActivity(
    val total: Int = 0,
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val unresolved: Int = 0,
) {
    fun plus(state: SessionAgentRunState): SessionAgentActivity = when (state) {
        SessionAgentRunState.Running -> copy(running = running + 1)
        SessionAgentRunState.Completed -> copy(completed = completed + 1)
        SessionAgentRunState.Failed -> copy(failed = failed + 1)
        SessionAgentRunState.Cancelled -> copy(cancelled = cancelled + 1)
        SessionAgentRunState.Unresolved -> copy(unresolved = unresolved + 1)
    }

    companion object {
        val EMPTY = SessionAgentActivity()
    }
}

/** `spawn_agent` runs the sub-agent inside its own execute(); older transcripts use `subagent_<name>`. */
private val SUBAGENT_RUN_TOOL_NAMES = setOf("spawn_agent", "spawn-agent", "spawn_agent_tool")
private const val TEAM_RUN_TOOL_NAME = "team_run_task"

/** Tools whose results report the current status of previously dispatched runs. */
private val TEAM_RUN_REPORT_TOOL_NAMES = setOf("team_await_runs", "team_list_runs", "team_cancel_run")

private fun normalizeAgentToolName(toolName: String?): String = toolName?.trim()?.lowercase() ?: ""

private fun isSubagentRunTool(toolName: String): Boolean =
    toolName in SUBAGENT_RUN_TOOL_NAMES || toolName.startsWith("subagent_")

/** `isAgentRunToolName`. */
fun isAgentRunToolName(toolName: String?): Boolean {
    val normalized = normalizeAgentToolName(toolName)
    return isSubagentRunTool(normalized) || normalized == TEAM_RUN_TOOL_NAME
}

/** Hydrated `tool_result` blocks carry JSON as text: reparse when it starts like JSON. */
private fun normalizeResult(result: JsonElement?): JsonElement? {
    val text = result.jsString() ?: return result
    val trimmed = text.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return result
    return parseJsonOrNull(trimmed) ?: result
}

private class AgentToolPayload(val toolName: String?, val input: JsonElement?, val result: JsonElement?, val isError: Boolean)

private fun parsePayload(content: String): AgentToolPayload? {
    val parsed = parseJsonOrNull(content) ?: return null
    val record = parsed as? JsonObject
    return AgentToolPayload(
        toolName = record?.get("toolName").jsString(),
        input = record?.get("input"),
        result = record?.get("result"),
        isError = record?.get("isError").isTruthy,
    ).takeIf { parsed.isTruthy }
}

/** Mirrors the tool row's in-progress rule so the header agrees with the transcript. */
private fun isToolCallInProgress(hookEventName: String?, payload: AgentToolPayload?): Boolean {
    if (hookEventName == "tool_call_start" || hookEventName == "history_tool_use") return true
    return payload != null && payload.result.isNullish && !payload.isError
}

private fun mapTeamRunStatus(status: String?): SessionAgentRunState = when (status) {
    "queued", "running" -> SessionAgentRunState.Running
    "completed" -> SessionAgentRunState.Completed
    "failed" -> SessionAgentRunState.Failed
    "cancelled", "interrupted" -> SessionAgentRunState.Cancelled
    else -> SessionAgentRunState.Running
}

/** A sub-agent that hit its abort signal reports `finishReason: "aborted"`. */
private fun subagentStateFromResult(result: JsonElement?): SessionAgentRunState =
    if (normalizeResult(result).asRecord()?.get("finishReason").nonEmptyString() == "aborted") {
        SessionAgentRunState.Cancelled
    } else {
        SessionAgentRunState.Completed
    }

private data class RunReport(val id: String, val status: String?)

private fun collectRunReports(result: JsonElement?): List<RunReport> {
    val normalized = normalizeResult(result)
    val entries = if (normalized is JsonArray) normalized.toList() else listOf(normalized)
    return entries.mapNotNull { entry ->
        val record = entry.asRecord() ?: return@mapNotNull null
        // team_list_runs/team_await_runs report `id`; team_cancel_run reports `runId`.
        val id = record["id"].nonEmptyString() ?: record["runId"].nonEmptyString() ?: return@mapNotNull null
        RunReport(id, record["status"].nonEmptyString())
    }
}

/**
 * Fold a session's tool messages into per-run states. Sub-agent runs and sync teammate runs
 * are keyed by their tool message id; an async teammate run is keyed by its `runId` and
 * refined by later team_await_runs / team_list_runs / team_cancel_run results.
 */
private fun collectAgentRunStates(messages: List<ChatMessage>): LinkedHashMap<String, SessionAgentRunState> {
    val states = LinkedHashMap<String, SessionAgentRunState>()
    val asyncRunIds = mutableSetOf<String>()
    for (message in messages) {
        if (message.role != Role.Tool) continue
        val metaToolName = normalizeAgentToolName(message.meta.toolName)
        val isRunTool = isSubagentRunTool(metaToolName) || metaToolName == TEAM_RUN_TOOL_NAME
        val isReportTool = metaToolName in TEAM_RUN_REPORT_TOOL_NAMES
        // Only messages that can matter are parsed; a missing meta name is recovered from the payload.
        if (metaToolName.isNotEmpty() && !isRunTool && !isReportTool) continue
        val payload = parsePayload(message.content)
        val toolName = metaToolName.ifEmpty { normalizeAgentToolName(payload?.toolName) }
        if (toolName.isEmpty()) continue

        if (toolName in TEAM_RUN_REPORT_TOOL_NAMES) {
            for (report in collectRunReports(payload?.result)) {
                if (report.id in asyncRunIds) states[report.id] = mapTeamRunStatus(report.status)
            }
            continue
        }
        val isSubagent = isSubagentRunTool(toolName)
        if (!isSubagent && toolName != TEAM_RUN_TOOL_NAME) continue

        if (isToolCallInProgress(message.meta.hookEventName, payload)) {
            states[message.id] = SessionAgentRunState.Running
            continue
        }
        if (payload?.isError == true) {
            states[message.id] = SessionAgentRunState.Failed
            continue
        }
        if (isSubagent) {
            states[message.id] = subagentStateFromResult(payload?.result)
            continue
        }
        // team_run_task: only async mode outlives its tool call; sync `status` is unreliable.
        val result = normalizeResult(payload?.result).asRecord()
        val requestedMode = payload?.input.asRecord()?.get("runMode").nonEmptyString()
        val mode = result?.get("mode").nonEmptyString() ?: requestedMode
        val runId = result?.get("runId").nonEmptyString()
        if (mode == "async" && runId != null) {
            asyncRunIds += runId
            states[runId] = mapTeamRunStatus(result?.get("status").nonEmptyString())
            // The run is tracked under runId; counting the dispatching call too would double count.
            states.remove(message.id)
            continue
        }
        states[message.id] = SessionAgentRunState.Completed
    }
    return states
}

/**
 * `buildSessionAgentActivity`: child agents bucketed by outcome. [sessionActive] separates a
 * run still working from one recorded without an outcome (hydrated `tool_use` blocks).
 */
fun buildSessionAgentActivity(messages: List<ChatMessage>, sessionActive: Boolean = false): SessionAgentActivity {
    val states = collectAgentRunStates(messages)
    if (states.isEmpty()) return SessionAgentActivity.EMPTY
    var activity = SessionAgentActivity(total = states.size)
    for (state in states.values) {
        activity = if (state == SessionAgentRunState.Running) {
            activity.plus(if (sessionActive) SessionAgentRunState.Running else SessionAgentRunState.Unresolved)
        } else {
            activity.plus(state)
        }
    }
    return activity
}

/** `SessionAgentEntry`: one child agent from `list_session_agents` (the persisted roster). */
data class SessionAgentEntry(
    val sessionId: String,
    val agentId: String,
    val parentAgentId: String? = null,
    val kind: Kind,
    val status: String,
    /** The task the agent was given (its first prompt). */
    val prompt: String? = null,
    /** Most recent step, for an at-a-glance progress line. */
    val lastAction: LastAction? = null,
    val teamName: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val hasMessages: Boolean,
) {
    enum class Kind { Subagent, Teamtask }
}

/**
 * A roster row's latest step (sidecar `deriveLastAction`), kept typed so the sheet resolves
 * the "Running %1$s" copy from resources and shows the kernel's tool name as data.
 */
sealed interface LastAction {
    /** The latest block was a `tool_use`: [name] is the raw tool name. */
    data class Tool(val name: String) : LastAction

    /** The latest assistant text, whitespace-collapsed and truncated at 160 + "...". */
    data class Text(val text: String) : LastAction
}

/** `agentEntryState`: persisted session statuses map onto the same buckets. */
fun agentEntryState(status: String): SessionAgentRunState = when (status.trim().lowercase()) {
    "running", "pending" -> SessionAgentRunState.Running
    "completed" -> SessionAgentRunState.Completed
    "failed" -> SessionAgentRunState.Failed
    "cancelled" -> SessionAgentRunState.Cancelled
    else -> SessionAgentRunState.Unresolved
}

/** `summarizeAgentEntries`. */
fun summarizeAgentEntries(entries: List<SessionAgentEntry>): SessionAgentActivity =
    entries.fold(SessionAgentActivity(total = entries.size)) { activity, entry -> activity.plus(agentEntryState(entry.status)) }

/**
 * `mergeAgentActivity`: the roster knows identities and true statuses but lags persistence;
 * the tally sees a spawn immediately. The larger total wins without double counting.
 */
fun mergeAgentActivity(
    entries: List<SessionAgentEntry>,
    derived: SessionAgentActivity,
    sessionActive: Boolean = false,
): SessionAgentActivity {
    if (entries.isEmpty()) return derived
    val roster = summarizeAgentEntries(entries)
    if (derived.total <= roster.total) return roster
    // Extra spawns the roster has not caught up with are still in flight.
    val pending = derived.total - roster.total
    return roster.copy(
        total = derived.total,
        running = roster.running + if (sessionActive) pending else 0,
        unresolved = roster.unresolved + if (sessionActive) 0 else pending,
    )
}
