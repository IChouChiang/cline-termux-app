package dev.cline.termux.data.agents

import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.idempotentRead
import dev.cline.termux.data.customize.outcomeOf
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import java.time.Instant
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The agents roster (customize-and-rest.md §6.7, §6.8; sidecar `listSessionAgents`): the
 * children of a session from `session.list {limit: 200, rootOnly: false}` (records whose
 * `metadata.parentSessionId` is the parent and that carry `metadata.agentId`), re-requested
 * once with `limit: 1000` when the first page was full and found fewer children than the
 * tally expects (OQ C-6); each row's last action from `session.messages` with the ported
 * `deriveLastAction`, cached per `(sessionId, updatedAt)` and re-fetched for running rows.
 *
 * [parentActivity] emits whenever the parent session's state changes in `SessionStore`
 * (`team.progress`, `run.*`); the repository never subscribes to the socket (docs/07 D15).
 */
class AgentsRepository(
    private val hub: HubClient,
    private val parentActivity: (parentSessionId: String) -> Flow<Any?>,
    private val timing: Timing = Timing(),
) {
    data class Timing(val activityDebounceMs: Long = 750, val runningPollMs: Long = 5_000)

    /** A child row (CR §6.7). [updatedAt] keys the last-action cache. */
    data class Child(val entry: SessionAgentEntry, val updatedAt: Long?, val createdAt: Long?)

    data class Roster(
        val children: List<Child>,
        /** The last refresh failed; [children] is the last good list (desktop keeps it). */
        val error: RepoFailure?,
        val loading: Boolean,
    ) {
        val entries: List<SessionAgentEntry> get() = children.map { it.entry }
        val hasRunning: Boolean get() = entries.any { agentEntryState(it.status) == SessionAgentRunState.Running }
    }

    private val cacheMutex = Mutex()
    private val lastActions = HashMap<Pair<String, Long?>, LastAction?>()

    /** One load: children, then last actions in parallel. */
    suspend fun load(parentSessionId: String, expectedTotal: Int = 0): Outcome<List<Child>> = outcomeOf {
        val children = fetchChildren(parentSessionId, expectedTotal)
        coroutineScope {
            children.map { child -> async { child.copy(entry = child.entry.copy(lastAction = lastAction(child))) } }.awaitAll()
        }
    }

    /**
     * The roster while collected: an initial load, then a refresh on parent activity
     * (debounced 750 ms) and every 5 s while any row is running. A failed refresh keeps the
     * previous rows and reports the error. [expectedTotal] is the merged tally's total.
     */
    @OptIn(FlowPreview::class)
    fun observe(parentSessionId: String, expectedTotal: () -> Int): Flow<Roster> = channelFlow {
        val state = MutableStateFlow(Roster(emptyList(), error = null, loading = true))
        val refreshMutex = Mutex()
        suspend fun refresh() = refreshMutex.withLock {
            state.value = when (val out = load(parentSessionId, expectedTotal())) {
                is Outcome.Ok -> Roster(out.value, error = null, loading = false)
                is Outcome.Failed -> state.value.copy(error = out.failure, loading = false)
            }
        }
        launch { state.collect { send(it) } }
        refresh()
        launch { parentActivity(parentSessionId).drop(1).debounce(timing.activityDebounceMs).collect { refresh() } }
        launch {
            while (true) {
                delay(timing.runningPollMs)
                if (state.value.hasRunning) refresh()
            }
        }
    }

    private suspend fun fetchChildren(parentSessionId: String, expectedTotal: Int): List<Child> {
        val first = list(FIRST_LIMIT)
        var children = childrenOf(first, parentSessionId)
        if (first.size >= FIRST_LIMIT && children.size < expectedTotal) children = childrenOf(list(FULL_LIMIT), parentSessionId)
        return children.sortedBy { it.createdAt ?: Long.MAX_VALUE }
    }

    private suspend fun list(limit: Int): List<JsonObject> =
        HubPayloads.sessionsOf(hub.idempotentRead { hub.command(HubCommands.SESSION_LIST, HubPayloads.sessionList(limit = limit, rootOnly = false)) })

    private suspend fun lastAction(child: Child): LastAction? {
        val running = agentEntryState(child.entry.status) == SessionAgentRunState.Running
        val key = child.entry.sessionId to child.updatedAt
        if (!running) cacheMutex.withLock { if (key in lastActions) return lastActions[key] }
        val action = runCatching {
            val reply = hub.idempotentRead { hub.command(HubCommands.SESSION_MESSAGES, HubPayloads.sessionMessages(child.entry.sessionId), child.entry.sessionId) }
            deriveLastAction(HubPayloads.messagesOf(reply))
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        cacheMutex.withLock { lastActions[key] = action }
        return action
    }

    companion object {
        const val FIRST_LIMIT = 200
        const val FULL_LIMIT = 1000
        const val LAST_ACTION_LIMIT = 160
        private const val TEAM_TASK_MARKER = "__teamtask__"

        /** Sidecar `listChildren` + `agentId` filter, mapped to [SessionAgentEntry]. */
        fun childrenOf(records: List<JsonObject>, parentSessionId: String): List<Child> = records.mapNotNull { record ->
            val metadata = record["metadata"] as? JsonObject ?: return@mapNotNull null
            if (metadata["parentSessionId"].jsString() != parentSessionId) return@mapNotNull null
            val agentId = metadata["agentId"].jsString() ?: return@mapNotNull null
            val sessionId = record["sessionId"].jsString() ?: return@mapNotNull null
            val createdAt = record["createdAt"].jsNumber()?.toLong()
            val updatedAt = record["updatedAt"].jsNumber()?.toLong()
            val status = record["status"].jsString() ?: "idle"
            val runtime = record["runtimeSession"] as? JsonObject
            val team = (runtime?.get("team") as? JsonObject)?.get("teamId").jsString()?.takeIf { it.isNotBlank() }
            Child(
                entry = SessionAgentEntry(
                    sessionId = sessionId,
                    agentId = agentId,
                    parentAgentId = metadata["parentAgentId"].jsString(),
                    kind = if (sessionId.contains(TEAM_TASK_MARKER)) SessionAgentEntry.Kind.Teamtask else SessionAgentEntry.Kind.Subagent,
                    status = status,
                    prompt = metadata["prompt"].jsString(),
                    teamName = team,
                    provider = metadata["provider"].jsString(),
                    model = metadata["model"].jsString(),
                    startedAt = createdAt?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
                    endedAt = if (agentEntryState(status) == SessionAgentRunState.Running) null else updatedAt?.let { Instant.ofEpochMilli(it).toString() },
                    hasMessages = metadata["messagesPath"].jsString() != null,
                ),
                updatedAt = updatedAt,
                createdAt = createdAt,
            )
        }

        /**
         * Sidecar `deriveLastAction`: latest assistant message; latest `tool_use` →
         * [LastAction.Tool] (the sheet renders "Running <name>"), else its text.
         */
        fun deriveLastAction(messages: JsonArray): LastAction? {
            for (index in messages.indices.reversed()) {
                val message = messages[index] as? JsonObject ?: continue
                if (message["role"].jsString() != "assistant") continue
                val content = message["content"]
                val text = content.jsString()
                if (text != null) {
                    truncate(text).takeIf { it.isNotEmpty() }?.let { return LastAction.Text(it) }
                    continue
                }
                val blocks = content as? JsonArray ?: continue
                for (blockIndex in blocks.indices.reversed()) {
                    val block = blocks[blockIndex] as? JsonObject ?: continue
                    val type = block["type"].jsString()
                    val name = block["name"].jsString()
                    if (type == "tool_use" && name != null) return LastAction.Tool(name)
                    val blockText = block["text"].jsString()
                    if (type == "text" && blockText != null) truncate(blockText).takeIf { it.isNotEmpty() }?.let { return LastAction.Text(it) }
                }
            }
            return null
        }

        /**
         * Sidecar `truncate`: whitespace collapsed, cut at 160 UTF-16 units + "...", on the
         * last grapheme boundary within the budget (DS §8.2.7).
         */
        fun truncate(value: String, limit: Int = LAST_ACTION_LIMIT): String {
            val collapsed = value.replace(Regex("\\s+"), " ").trim()
            return if (collapsed.length > limit) "${takeGraphemePrefix(collapsed, limit)}..." else collapsed
        }
    }
}
