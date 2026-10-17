package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.RunHeartbeat
import dev.cline.termux.data.chat.RunPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the pure reducers need besides the event: this client's identity and turns, and the time. */
data class ReduceContext(
    val clientId: String,
    /** requestIds of this client's open direct-send `TurnRecord`s. */
    val ownRequestIds: Set<String> = emptySet(),
    /** Receive time (epoch ms), used when the envelope carries no `timestamp`. */
    val now: Long,
)

/**
 * Pure reducer, Hub event → [SessionFacts] (docs/07 §4.2 column "Facts", CA §0.2 run phase).
 * Replaces the former `SessionReducer`: its tool/execution bookkeeping, approval marking,
 * usage and status handling are kept; the transcript moved to `ChatProjector` (D1, D14).
 * Events at or below `facts.lastSequence` are ignored, so a replay is idempotent.
 */
object SessionFactsReducer {

    const val REJECTED_MARKER = "This tool call was rejected by the user"
    private const val REJECTED_MARKER_SHORT = "rejected by the user"

    /** Seeds or refreshes facts from a `session.create` / `session.get` / `session.list` record. */
    fun fromRecord(sessionId: String, session: JsonObject?, snapshot: JsonObject?, base: SessionFacts = SessionFacts(sessionId)): SessionFacts =
        applyRecord(base, session, snapshot)

    fun reduce(facts: SessionFacts, event: HubEventEnvelope, ctx: ReduceContext): SessionFacts {
        val seq = event.sequence
        if (seq != null && seq <= facts.lastSequence) return facts
        val p = event.payload ?: EMPTY_OBJECT
        val at = event.timestamp ?: ctx.now
        val next = when (event.event) {
            HubEvents.SESSION_CREATED, HubEvents.SESSION_FORKED, HubEvents.SESSION_ATTACHED ->
                applyRecord(facts, p.obj("session"), p.obj("snapshot"))
            HubEvents.SESSION_UPDATED -> queuedRunSettled(facts.status, applyRecord(facts, p.obj("session"), p.obj("snapshot")))
            HubEvents.SESSION_PENDING_PROMPTS -> facts.copy(pendingPrompts = parsePrompts(p.arr("prompts")))
            HubEvents.SESSION_PENDING_PROMPT_SUBMITTED -> promptSubmitted(facts, p, at)
            HubEvents.RUN_STARTED -> runStarted(facts, p, ctx, at)
            HubEvents.RUN_ENQUEUED -> p.str("requestId")?.let { facts.copy(queuedRequestIds = facts.queuedRequestIds + it) } ?: facts
            HubEvents.ITERATION_STARTED -> facts.copy(
                currentIteration = p.int("iteration") ?: facts.currentIteration,
                runPhase = if (facts.runPhase in IDLE_LIKE_PHASES || facts.runPhase == RunPhase.Starting) RunPhase.Running else facts.runPhase,
            )
            HubEvents.TOOL_STARTED -> toolStarted(facts, p, at)
            HubEvents.TOOL_UPDATED -> toolUpdated(facts, p)
            HubEvents.TOOL_FINISHED -> toolFinished(facts, p, at)
            HubEvents.APPROVAL_REQUESTED -> approvalRequested(facts, p, at, seq)
            HubEvents.APPROVAL_RESOLVED -> approvalResolved(facts, p)
            HubEvents.CAPABILITY_REQUESTED -> capabilityRequested(facts, p, ctx, at)
            HubEvents.CAPABILITY_RESOLVED -> capabilityResolved(facts, p)
            HubEvents.USAGE_UPDATED -> usageUpdated(facts, p)
            HubEvents.RUN_COMPLETED -> runCompleted(facts, p, at)
            HubEvents.RUN_FAILED -> runEnded(facts, p.obj("snapshot"), SessionStatus.Failed, ToolStatus.Cancelled, at)
            HubEvents.RUN_ABORTED -> runEnded(facts, p.obj("snapshot"), SessionStatus.Cancelled, ToolStatus.Cancelled, at)
            HubEvents.RUN_INTERRUPTED -> runEnded(facts, null, SessionStatus.Idle, ToolStatus.Interrupted, at)
            HubEvents.RUN_HEARTBEAT -> RunHeartbeat.of(event)?.let { heartbeat(facts, it, ctx, at) } ?: facts
            else -> facts
        }
        val beat = event.event == HubEvents.RUN_HEARTBEAT
        return next.copy(
            lastSequence = seq ?: next.lastSequence,
            lastEventAt = at,
            // A4: every other event of the session is activity, and activity ends a stall episode.
            lastActivityAt = if (beat) next.lastActivityAt else at,
            stall = if (beat) next.stall else null,
        )
    }

    // ---- the store's own actions (not events) ---------------------------------------------

    /** A direct send went out: `Starting` until its `run.started` (CA §0.2). */
    fun onDirectSend(facts: SessionFacts): SessionFacts =
        if (facts.runPhase == RunPhase.Stopping) facts else facts.copy(runPhase = RunPhase.Starting)

    /** A send with `delivery` (queue / steer): its `run.started` must not open a turn (CA §0.2). */
    fun onQueuedSend(facts: SessionFacts, requestId: String): SessionFacts =
        facts.copy(queuedRequestIds = facts.queuedRequestIds + requestId)

    /** The send's reply failed before the turn ran (e.g. `session_not_found`): undo `Starting` and the run it may have opened. */
    fun onSendRejected(facts: SessionFacts, requestId: String): SessionFacts {
        val run = facts.activeRun?.takeIf { it.requestId == requestId }
        if (run == null && facts.runPhase != RunPhase.Starting) return facts
        return facts.copy(
            activeRun = if (run != null) null else facts.activeRun,
            runPhase = if (run != null || facts.runPhase == RunPhase.Starting) RunPhase.Idle else facts.runPhase,
            status = if (run != null && facts.status == SessionStatus.Running) SessionStatus.Idle else facts.status,
        )
    }

    /** Stop pressed and `run.abort` sent; a stall notice gives way to Stop's own timeout (A4). */
    fun onAbortRequested(facts: SessionFacts): SessionFacts =
        if (facts.runPhase.isBusy) facts.copy(runPhase = RunPhase.Stopping, stall = null) else facts

    /** `run.abort` failed: back to what the status says. */
    fun onAbortFailed(facts: SessionFacts): SessionFacts =
        if (facts.runPhase != RunPhase.Stopping) facts else facts.copy(runPhase = phaseForStatus(RunPhase.Running, facts.status, facts.hasPendingInput))

    fun onQueueUnavailable(facts: SessionFacts, unavailable: Boolean): SessionFacts = facts.copy(queueUnavailable = unavailable)

    /**
     * A turn was interrupted by a Hub restart (CA §0.2, HC §1.3.4), or a genuine `run.interrupted`.
     * Approvals and questions held for the old daemon are dropped (CA §0.5: the Hub resolved them
     * on shutdown without an event). So is its prompt queue, which lived only in that daemon
     * (a new daemon answers `session.pending_prompts` with an empty list).
     */
    fun markInterrupted(facts: SessionFacts): SessionFacts = facts.copy(
        runPhase = RunPhase.Idle,
        status = if (facts.status.isRunning || facts.status == SessionStatus.Unknown) SessionStatus.Idle else facts.status,
        activeRun = null,
        pendingPrompts = emptyList(),
        approvals = emptyMap(),
        questions = emptyMap(),
        currentIteration = null,
        tools = settleTools(facts.tools, ToolStatus.Interrupted, null),
        lastHeartbeatAt = null,
        stall = null,
    )

    /** A new socket: the queue capability is re-learned per connection (CA C2.6). */
    fun onReconnected(facts: SessionFacts): SessionFacts = facts.copy(queueUnavailable = false)

    /**
     * `session.get` reports the loop settled although the stream never delivered the run's
     * terminal `run.*` (it fell outside a truncated replay, SS §1.2.1 item 2): the run ends as
     * the record says. Approvals and questions belong to a run that no longer exists, so they
     * go too (a running Hub re-issues live ones on subscribe). A direct send of this
     * connection that is still `Starting` keeps its phase until its own `run.started`.
     */
    fun onVerifiedSettled(facts: SessionFacts, at: Long): SessionFacts = facts.copy(
        status = if (facts.status.isRunning || facts.status == SessionStatus.Unknown) SessionStatus.Idle else facts.status,
        runPhase = if (facts.runPhase == RunPhase.Starting) RunPhase.Starting else RunPhase.Idle,
        activeRun = null,
        approvals = emptyMap(),
        questions = emptyMap(),
        currentIteration = null,
        tools = settleTools(facts.tools, ToolStatus.Cancelled, at),
        lastHeartbeatAt = null,
        stall = null,
    )

    // ---- records -----------------------------------------------------------------------------

    private fun applyRecord(facts: SessionFacts, session: JsonObject?, snapshot: JsonObject?): SessionFacts {
        var f = facts
        snapshot?.let { s ->
            val model = s.obj("model")
            val workspace = s.obj("workspace")
            val checkpoint = s.obj("checkpoint")
            f = f.copy(
                status = s.str("status")?.let(SessionStatus::parse)?.takeIf { it != SessionStatus.Unknown } ?: f.status,
                providerId = model?.str("providerId") ?: f.providerId,
                modelId = model?.str("modelId") ?: f.modelId,
                workspaceRoot = workspace?.str("root") ?: f.workspaceRoot,
                cwd = workspace?.str("cwd") ?: f.cwd,
                usage = UsageTotals.parse(s.obj("usage")) ?: f.usage,
                aggregateUsage = UsageTotals.parse(s.obj("aggregateUsage")) ?: f.aggregateUsage,
                checkpointHistory = checkpoint?.let { parseCheckpoints(it.arr("history")) } ?: f.checkpointHistory,
                endedAt = s["endedAt"].epochMillis(),
                metadata = if (session == null && f.metadata.isEmpty()) s.obj("metadata") ?: f.metadata else f.metadata,
            )
        }
        session?.let { r ->
            val metadata = r.obj("metadata")
            val runtime = r.obj("runtimeOptions")
            f = f.copy(
                status = if (snapshot == null) r.str("status")?.let(SessionStatus::parse)?.takeIf { it != SessionStatus.Unknown } ?: f.status else f.status,
                workspaceRoot = r.str("workspaceRoot") ?: f.workspaceRoot,
                cwd = r.str("cwd") ?: f.cwd,
                metadata = metadata ?: f.metadata,
                title = metadata?.str("title") ?: f.title,
                pinned = metadata?.bool("pinned") ?: f.pinned,
                providerId = metadata?.str("provider") ?: f.providerId,
                modelId = metadata?.str("model") ?: f.modelId,
                mode = metadata?.str("mode") ?: runtime?.str("mode") ?: f.mode,
                thinking = metadata?.bool("thinking") ?: f.thinking,
                reasoningEffort = metadata?.str("reasoningEffort") ?: f.reasoningEffort,
                source = metadata?.str("source") ?: f.source,
                // The Hub writes a subagent's parent into `metadata` (§6.4 "metadata.parentSessionId").
                parentSessionId = metadata?.str("parentSessionId")?.takeIf { it.isNotBlank() }
                    ?: r.str("parentSessionId")?.takeIf { it.isNotBlank() } ?: f.parentSessionId,
                usage = UsageTotals.parse(r.obj("usage")) ?: f.usage,
                aggregateUsage = UsageTotals.parse(r.obj("aggregateUsage")) ?: f.aggregateUsage,
            )
        }
        return f.copy(runPhase = phaseForStatus(f.runPhase, f.status, f.hasPendingInput))
    }

    /** Run phase after a status change (CA §0.2 table). */
    internal fun phaseForStatus(current: RunPhase, status: SessionStatus, hasPendingInput: Boolean): RunPhase = when {
        current == RunPhase.Stopping -> RunPhase.Stopping
        status == SessionStatus.Pending -> RunPhase.AwaitingUser
        status == SessionStatus.Running -> if (hasPendingInput) RunPhase.AwaitingUser else RunPhase.Running
        status in IDLE_STATUSES -> when (current) {
            // The direct send's `run.started` has not arrived yet; a stale idle must not cancel it.
            RunPhase.Starting -> RunPhase.Starting
            else -> RunPhase.Idle
        }
        else -> current
    }

    private val IDLE_STATUSES = setOf(SessionStatus.Idle, SessionStatus.Completed, SessionStatus.Cancelled, SessionStatus.Failed, SessionStatus.Error)
    private val IDLE_LIKE_PHASES = setOf(RunPhase.Idle, RunPhase.NoSession)

    private fun parseCheckpoints(history: JsonArray?): List<CheckpointEntry> = history.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val ref = o.str("ref")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val createdAt = o["createdAt"].epochMillis() ?: return@mapNotNull null
        val runCount = o.int("runCount") ?: return@mapNotNull null
        CheckpointEntry(ref, createdAt, runCount, o.str("kind")?.takeIf { it == "stash" || it == "commit" })
    }

    private fun parsePrompts(prompts: JsonArray?): List<QueuedPrompt> = prompts.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        QueuedPrompt(id, o.str("prompt").orEmpty(), o.str("delivery") ?: "queue", o.int("attachmentCount") ?: 0)
    }

    // ---- runs -------------------------------------------------------------------------------

    private fun promptSubmitted(facts: SessionFacts, p: JsonObject, at: Long): SessionFacts {
        val prompt = p.obj("prompt")
        val id = prompt?.str("id") ?: p.str("promptId")
        val remaining = if (id == null) facts.pendingPrompts else facts.pendingPrompts.filterNot { it.id == id }
        // The queued prompt starts running; without an active run it becomes one (keyed `q:<id>`).
        val run = facts.activeRun ?: id?.let { RunRef(requestId = queuedRunKey(it), runId = null, startedAt = at, clientId = null, ours = false) }
        return facts.copy(
            pendingPrompts = remaining,
            activeRun = run,
            runPhase = if (facts.runPhase == RunPhase.Stopping || facts.runPhase == RunPhase.AwaitingUser) facts.runPhase else RunPhase.Running,
        )
    }

    /**
     * A drained queued prompt never gets a `run.*` event: its run ends when the session's
     * status goes idle-like (the Hub publishes `session.updated` when the loop settles). A Stop
     * of such a run ends here too: `Stopping` would otherwise wait for a terminal that never comes.
     * The drained run may carry no `activeRun` at all (the previous run's late `run.completed`
     * cleared it), so a running -> settled change with no run left to end releases `Stopping` too.
     */
    private fun queuedRunSettled(previous: SessionStatus, facts: SessionFacts): SessionFacts {
        if (facts.status !in IDLE_STATUSES) return facts
        val run = facts.activeRun?.requestId
        val settledWithoutRun = run == null && previous.isRunning
        if (!settledWithoutRun && (run == null || !isQueuedRunKey(run))) return facts
        val phase = if (facts.runPhase == RunPhase.Stopping) RunPhase.Idle else facts.runPhase
        return facts.copy(activeRun = null, currentIteration = if (run == null) facts.currentIteration else null, runPhase = phase)
    }

    /** The pseudo request id of a queued prompt that runs without its own `run.started`. */
    fun queuedRunKey(promptId: String): String = "$QUEUED_RUN_PREFIX$promptId"

    fun isQueuedRunKey(requestId: String): Boolean = requestId.startsWith(QUEUED_RUN_PREFIX)

    private const val QUEUED_RUN_PREFIX = "q:"

    private fun runStarted(facts: SessionFacts, p: JsonObject, ctx: ReduceContext, at: Long): SessionFacts {
        val requestId = p.str("requestId")
        // Queued sends publish run.started at enqueue time (CA §0.2): ignore for phase and turns.
        if (requestId != null && requestId in facts.queuedRequestIds) return facts
        return facts.copy(
            activeRun = RunRef(
                requestId = requestId,
                runId = p.str("runId"),
                startedAt = at,
                clientId = p.str("clientId"),
                ours = requestId != null && requestId in ctx.ownRequestIds,
            ),
            runPhase = if (facts.runPhase == RunPhase.Stopping) RunPhase.Stopping else RunPhase.Running,
            status = SessionStatus.Running,
            tools = emptyMap(),
            currentIteration = null,
            lastHeartbeatAt = null,
            // The desktop keeps a mistake-limit answer only within the run it was given in.
            mistakeContinuedThroughIteration = null,
        )
    }

    private fun runCompleted(facts: SessionFacts, p: JsonObject, at: Long): SessionFacts {
        val completed = p.str("reason") == "completed"
        // A run.completed with another reason (e.g. "disposed" on shutdown) ends the turn but is not success.
        val ended = runEnded(facts, p.obj("snapshot"), if (completed) SessionStatus.Completed else SessionStatus.Idle, ToolStatus.Cancelled, at)
        val toolCalls = p.obj("result")?.arr("toolCalls") ?: return ended
        return ended.copy(tools = refineDurations(ended.tools, toolCalls))
    }

    private fun runEnded(facts: SessionFacts, snapshot: JsonObject?, status: SessionStatus, unfinished: ToolStatus, at: Long): SessionFacts {
        val withSnapshot = if (snapshot != null) applyRecord(facts, null, snapshot) else facts
        return withSnapshot.copy(
            status = status,
            runPhase = RunPhase.Idle,
            activeRun = null,
            approvals = emptyMap(),
            questions = emptyMap(),
            currentIteration = null,
            tools = settleTools(facts.tools, unfinished, at),
            lastHeartbeatAt = null,
        )
    }

    // ---- heartbeats (A4) ------------------------------------------------------------------------

    /**
     * A live heartbeat is a stall when the run is active (Starting or Running: not waiting for
     * the user, not stopping), nobody owes it an answer (no approval or question pending), no
     * tool call is executing (a long command is the kernel working), and no other event of this
     * session arrived since the window began: the previous heartbeat, or, for the run's first
     * one, the run's start and its opening burst ([RUN_OPENING_MS]). Without a known start (the
     * session was opened mid-run) the first heartbeat only opens the window. A heartbeat older
     * than [HEARTBEAT_LIVE_MS] is a replay and says nothing about now. An episode keeps the time
     * of the heartbeat that opened it while later ones stay silent.
     */
    private fun heartbeat(facts: SessionFacts, beat: RunHeartbeat, ctx: ReduceContext, at: Long): SessionFacts {
        if (ctx.now - at > HEARTBEAT_LIVE_MS) return facts
        val windowStart = facts.lastHeartbeatAt ?: facts.activeRun?.startedAt?.plus(RUN_OPENING_MS)
        val silent = windowStart != null && (facts.lastActivityAt ?: Long.MIN_VALUE) <= windowStart
        val active = facts.runPhase == RunPhase.Running || facts.runPhase == RunPhase.Starting
        val executing = facts.tools.values.any { it.status == ToolStatus.Running }
        val stalled = silent && active && !facts.hasPendingInput && !executing
        return facts.copy(
            lastHeartbeatAt = at,
            stall = if (stalled) facts.stall ?: StallFact(since = at, requestId = beat.requestId) else null,
        )
    }

    /** Heartbeats older than this (Hub clock against the receive time) are replayed, not live (A4). */
    const val HEARTBEAT_LIVE_MS = 45_000L

    /**
     * The Hub's opening burst of a run belongs to its start, not to "the kernel responded":
     * `run.started`, the `session.updated` pair and the first `iteration.started` arrive within
     * milliseconds of each other (fixture 02), so the first heartbeat of a model that never
     * answers is silent (A4: "the first heartbeat with no session event since the run began").
     */
    const val RUN_OPENING_MS = 2_000L

    private fun settleTools(tools: Map<String, ToolRecord>, to: ToolStatus, at: Long?): Map<String, ToolRecord> = tools.mapValues { (_, t) ->
        if (t.status == ToolStatus.Running || t.status == ToolStatus.AwaitingApproval) t.copy(status = to, endedAt = t.endedAt ?: at) else t
    }

    private fun refineDurations(tools: Map<String, ToolRecord>, calls: JsonArray): Map<String, ToolRecord> {
        if (tools.isEmpty()) return tools
        val next = tools.toMutableMap()
        for (element in calls) {
            val call = element as? JsonObject ?: continue
            val id = call.str("id") ?: continue
            val record = next[id] ?: continue
            next[id] = record.copy(
                startedAt = call["startedAt"].epochMillis() ?: record.startedAt,
                endedAt = call["endedAt"].epochMillis() ?: record.endedAt,
                durationMs = call.long("durationMs") ?: record.durationMs,
            )
        }
        return next
    }

    // ---- tools ------------------------------------------------------------------------------

    private fun toolStarted(facts: SessionFacts, p: JsonObject, at: Long): SessionFacts {
        val id = p.str("toolCallId") ?: return facts // hook-derived duplicates carry no id
        val existing = facts.tools[id]
        val record = existing?.copy(
            toolName = p.str("toolName") ?: existing.toolName,
            input = p["input"] ?: existing.input,
            status = if (existing.status == ToolStatus.AwaitingApproval) existing.status else ToolStatus.Running,
        ) ?: ToolRecord(
            toolCallId = id,
            toolName = p.str("toolName") ?: "tool",
            input = p["input"],
            status = ToolStatus.Running,
            iteration = facts.currentIteration,
            requestId = facts.activeRun?.requestId,
            startedAt = at,
        )
        return facts.copy(tools = facts.tools + (id to record))
    }

    private fun toolUpdated(facts: SessionFacts, p: JsonObject): SessionFacts {
        val id = p.str("toolCallId") ?: return facts
        val record = facts.tools[id] ?: return facts
        val update = p.obj("update") ?: return facts
        val executionId = update.str("executionId") ?: return facts
        val stream = if (update.str("stream") == "stderr") OutputStream.Stderr else OutputStream.Stdout
        val chunk = update.str("chunk").orEmpty()
        val executions = record.executions.toMutableList()
        val index = executions.indexOfFirst { it.executionId == executionId }
        val base = if (index >= 0) executions[index] else CommandBuffer(
            executionId = executionId,
            commandIndex = update.int("commandIndex") ?: executions.size,
            query = update.str("query").orEmpty(),
            // The first update announces the execution with an empty chunk (docs/08); otherwise the replay began mid-stream.
            gapped = chunk.isNotEmpty(),
        )
        val updated = appendChunk(base, OutputChunk(stream, chunk)).copy(
            detachable = update.bool("detachable") ?: base.detachable,
            truncated = base.truncated || update.bool("truncated") == true,
            kernelDroppedChars = base.kernelDroppedChars + (update.long("droppedChars") ?: 0L),
            query = base.query.ifEmpty { update.str("query").orEmpty() },
        )
        if (index >= 0) executions[index] = updated else executions += updated
        return facts.copy(tools = facts.tools + (id to record.copy(executions = executions)))
    }

    /**
     * Appends with the 1 048 576-char head cap per execution (CT §1.4), append-only: the text
     * so far is never copied. A chunk tops up the last piece only while that piece stays within
     * [CommandBuffer.PIECE_CHARS]; otherwise it is added as pieces of at most that size. The
     * head cap drops whole leading pieces and slices only the one boundary piece.
     */
    internal fun appendChunk(buffer: CommandBuffer, chunk: OutputChunk): CommandBuffer {
        val text = chunk.text
        if (text.isEmpty()) return buffer
        var pieces = buffer.chunks.toPersistentList()
        val last = pieces.lastOrNull()
        if (last != null && last.stream == chunk.stream && last.text.length + text.length <= CommandBuffer.PIECE_CHARS) {
            pieces = pieces.set(pieces.lastIndex, OutputChunk(chunk.stream, last.text + text))
        } else if (text.length <= CommandBuffer.PIECE_CHARS) {
            pieces = pieces.add(chunk)
        } else {
            var start = 0
            while (start < text.length) {
                val end = minOf(text.length, start + CommandBuffer.PIECE_CHARS)
                pieces = pieces.add(OutputChunk(chunk.stream, text.substring(start, end)))
                start = end
            }
        }
        val length = buffer.length + text.length
        if (length <= CommandBuffer.MAX_CHARS) return buffer.copy(chunks = pieces, length = length)
        var excess = length - CommandBuffer.MAX_CHARS
        var drop = 0
        while (drop < pieces.size && pieces[drop].text.length <= excess) {
            excess -= pieces[drop].text.length
            drop++
        }
        if (drop > 0) pieces = persistentListOf<OutputChunk>().addAll(pieces.subList(drop, pieces.size))
        if (excess > 0 && pieces.isNotEmpty()) {
            val head = pieces[0]
            pieces = pieces.set(0, head.copy(text = head.text.substring(excess)))
        }
        val dropped = (length - CommandBuffer.MAX_CHARS).toLong()
        return buffer.copy(chunks = pieces, length = CommandBuffer.MAX_CHARS, headDroppedChars = buffer.headDroppedChars + dropped)
    }

    private fun toolFinished(facts: SessionFacts, p: JsonObject, at: Long): SessionFacts {
        val id = p.str("toolCallId") ?: return facts
        val output = p["output"]
        val error = p.str("error") ?: extractError(output)
        val status = if (error != null && error.contains(REJECTED_MARKER_SHORT)) ToolStatus.Rejected else ToolStatus.Finished(error)
        val record = facts.tools[id] ?: ToolRecord(
            toolCallId = id,
            toolName = p.str("toolName") ?: "tool",
            input = null,
            status = status,
            iteration = facts.currentIteration,
            requestId = facts.activeRun?.requestId,
            startedAt = at,
        )
        val results = output as? JsonArray
        val executions = record.executions.map { exec ->
            val entry = results?.getOrNull(exec.commandIndex) as? JsonObject ?: return@map exec
            exec.copy(success = entry.bool("success"), result = entry.str("result") ?: exec.result)
        }
        return facts.copy(
            tools = facts.tools + (id to record.copy(status = status, output = output, error = error, endedAt = at, executions = executions)),
        )
    }

    /** `output.error`, also when the output is a JSON-encoded object string (rejected calls, docs/08). */
    fun extractError(output: JsonElement?): String? = when (output) {
        is JsonObject -> output.str("error")
        is JsonPrimitive -> output.contentOrNull?.takeIf { it.trimStart().startsWith("{") }?.let { raw ->
            runCatching { (HubJson.parseToJsonElement(raw) as? JsonObject)?.str("error") }.getOrNull()
        }
        else -> null
    }

    // ---- approvals and questions -------------------------------------------------------------

    private fun approvalRequested(facts: SessionFacts, p: JsonObject, at: Long, seq: Long?): SessionFacts {
        val approvalId = p.str("approvalId") ?: return facts
        // Re-issued on every subscribe (KC): dedupe by approvalId.
        if (approvalId in facts.approvals) return facts
        val toolCallId = p.str("toolCallId")
        val input = p.str("inputJson")?.let { runCatching { HubJson.parseToJsonElement(it) }.getOrNull() } ?: p["input"]
        val approval = PendingApproval(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = p.str("toolName") ?: "tool",
            input = input,
            iteration = p.int("iteration"),
            agentId = p.str("agentId"),
            conversationId = p.str("conversationId"),
            policy = p.obj("policy"),
            requestedAt = at,
            sequence = seq,
        )
        val tools = if (toolCallId == null) facts.tools else {
            val existing = facts.tools[toolCallId]
            facts.tools + (toolCallId to (existing?.copy(status = ToolStatus.AwaitingApproval, approvalId = approvalId) ?: ToolRecord(
                toolCallId = toolCallId,
                toolName = approval.toolName,
                input = input,
                status = ToolStatus.AwaitingApproval,
                iteration = approval.iteration ?: facts.currentIteration,
                requestId = facts.activeRun?.requestId,
                startedAt = at,
                approvalId = approvalId,
            )))
        }
        return facts.copy(
            approvals = facts.approvals + (approvalId to approval),
            tools = tools,
            status = SessionStatus.Pending,
            runPhase = if (facts.runPhase == RunPhase.Stopping) RunPhase.Stopping else RunPhase.AwaitingUser,
        )
    }

    private fun approvalResolved(facts: SessionFacts, p: JsonObject): SessionFacts {
        val approvalId = p.str("approvalId") ?: return facts
        val approval = facts.approvals[approvalId]
        val toolCallId = approval?.toolCallId ?: facts.tools.values.firstOrNull { it.approvalId == approvalId }?.toolCallId
        val status = when {
            p.bool("cancelled") == true -> ToolStatus.Cancelled
            p.bool("approved") == true -> ToolStatus.Running
            else -> ToolStatus.Rejected
        }
        val tools = toolCallId?.let { id ->
            val record = facts.tools[id] ?: return@let facts.tools
            // A tool.finished that already arrived wins over the resolution.
            if (record.status != ToolStatus.AwaitingApproval) facts.tools else facts.tools + (id to record.copy(status = status))
        } ?: facts.tools
        if (approval == null && tools === facts.tools) return facts
        return afterInputResolved(facts.copy(approvals = facts.approvals - approvalId, tools = tools))
    }

    private fun afterInputResolved(facts: SessionFacts): SessionFacts {
        if (facts.hasPendingInput) return facts
        return facts.copy(
            status = if (facts.status == SessionStatus.Pending) SessionStatus.Running else facts.status,
            runPhase = if (facts.runPhase == RunPhase.AwaitingUser) RunPhase.Running else facts.runPhase,
        )
    }

    /**
     * The user-answered capability requests targeted at this install: `tool_executor.askQuestion`
     * and `mistake_limit.decide` (G2). Another client (the TUI) answers its own; the other
     * contributions are answered without the user by `ClientContributions`. A mistake-limit
     * request the user already continued through ([MistakeLimitPrompt.autoContinues]) is
     * answered "continue" there and opens no question here.
     */
    private fun capabilityRequested(facts: SessionFacts, p: JsonObject, ctx: ReduceContext, at: Long): SessionFacts {
        val requestId = p.str("requestId") ?: return facts
        if (p.str("targetClientId") != ctx.clientId) return facts
        if (requestId in facts.questions) return facts
        val payload = p.obj("payload")
        val pending = when (p.str("capabilityName")) {
            HubCapabilities.ASK_QUESTION -> {
                val args = payload?.arr("args")
                val question = (args?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val options = (args?.getOrNull(1) as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .distinct()
                val context = payload?.obj("context")
                PendingQuestion(requestId, question, options, context?.str("agentId"), context?.int("iteration"), at)
            }
            HubCapabilities.MISTAKE_LIMIT -> {
                val context = MistakeLimitPrompt.context(payload)
                if (MistakeLimitPrompt.autoContinues(facts, context.iteration)) return facts
                PendingQuestion(
                    requestId = requestId,
                    question = "",
                    options = MistakeLimitPrompt.OPTIONS,
                    agentId = null,
                    iteration = context.iteration,
                    requestedAt = at,
                    kind = QuestionKind.MistakeLimit,
                    mistakeLimit = context,
                )
            }
            else -> return facts
        }
        return facts.copy(
            questions = facts.questions + (requestId to pending),
            runPhase = if (facts.runPhase == RunPhase.Stopping) RunPhase.Stopping else RunPhase.AwaitingUser,
        )
    }

    /**
     * `capability.resolved` closes the question. A mistake-limit question the user answered
     * with "continue" sets the desktop's `continuedThroughIteration`: the larger of the
     * request's iteration and the run's latest iteration (`chat-session.ts` Continue path).
     */
    private fun capabilityResolved(facts: SessionFacts, p: JsonObject): SessionFacts {
        val id = p.str("requestId") ?: return facts
        val question = facts.questions[id] ?: return facts
        val continued = question.kind == QuestionKind.MistakeLimit &&
            p.bool("ok") == true &&
            p.obj("payload")?.obj("result")?.str("action") == MistakeLimitPrompt.ACTION_CONTINUE
        val through = if (continued) {
            listOfNotNull(question.iteration, facts.currentIteration, facts.mistakeContinuedThroughIteration).maxOrNull()
        } else {
            facts.mistakeContinuedThroughIteration
        }
        return afterInputResolved(facts.copy(questions = facts.questions - id, mistakeContinuedThroughIteration = through))
    }

    // ---- usage ------------------------------------------------------------------------------

    private fun usageUpdated(facts: SessionFacts, p: JsonObject): SessionFacts {
        val kind = p.obj("agent")?.str("kind")
        // Session totals come from the lead agent only (docs/07 §4.2).
        if (kind != null && kind != "lead") return facts
        return facts.copy(
            usage = UsageTotals.parse(p.obj("usage")) ?: facts.usage,
            aggregateUsage = UsageTotals.parse(p.obj("aggregateUsage")) ?: facts.aggregateUsage,
        )
    }
}
