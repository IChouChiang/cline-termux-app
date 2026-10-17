package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.RunPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The reducer's "facts" (docs/07 §4.1, D1): everything about a session except its rendered
 * messages. `SessionFactsReducer` produces it from Hub events; `ChatProjector` builds the
 * messages from the same events. The former `Transcript.kt` types fold in here: the old
 * `TranscriptItem` list is gone (messages are `ChatMessage`), its tool/execution bookkeeping
 * lives on as [ToolRecord] / [CommandBuffer].
 */

/** Session status in the kernel's words (`session.status`, `snapshot.status`, `session.list` rows). */
enum class SessionStatus(val wire: String) {
    Idle("idle"),
    Starting("starting"),
    Running("running"),
    Stopping("stopping"),
    Pending("pending"),
    Completed("completed"),
    Cancelled("cancelled"),
    Failed("failed"),
    Error("error"),
    Unknown("unknown"),
    ;

    val isRunning: Boolean get() = this == Running || this == Pending || this == Starting || this == Stopping

    companion object {
        /** `aborted` (session records) and `cancelled` (snapshots) are one state (docs/08 `05`). */
        fun parse(raw: String?): SessionStatus = when (raw?.trim()?.lowercase()) {
            "idle", "active" -> Idle
            "starting", "provisioning" -> Starting
            "running" -> Running
            "stopping" -> Stopping
            "pending" -> Pending
            "completed", "expired" -> Completed
            "cancelled", "canceled", "aborted" -> Cancelled
            "failed" -> Failed
            "error" -> Error
            else -> Unknown
        }
    }
}

/** The mode a prompt was sent in (`<user_input mode="…">`). */
enum class PromptMode(val wire: String) {
    Act("act"), Plan("plan"), Yolo("yolo");

    companion object {
        fun parse(raw: String?): PromptMode = when (raw?.trim()?.lowercase()) {
            "plan" -> Plan
            "yolo" -> Yolo
            else -> Act
        }
    }
}

/** The run the session is in (docs/07 §4.1 `activeRun`). */
@Immutable
data class RunRef(
    val requestId: String?,
    val runId: String?,
    val startedAt: Long,
    val clientId: String?,
    /** `requestId` belongs to one of this client's direct sends (an open `TurnRecord`). */
    val ours: Boolean,
)

@Immutable
data class UsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val totalCost: Double = 0.0,
) {
    val total: Long get() = inputTokens + outputTokens

    companion object {
        fun parse(o: JsonObject?): UsageTotals? = o?.let {
            UsageTotals(
                inputTokens = it.long("inputTokens") ?: 0,
                outputTokens = it.long("outputTokens") ?: 0,
                cacheReadTokens = it.long("cacheReadTokens") ?: 0,
                cacheWriteTokens = it.long("cacheWriteTokens") ?: 0,
                totalCost = it.double("totalCost") ?: 0.0,
            )
        }
    }
}

/** An `approval.requested` without its `approval.resolved` (deduped by [approvalId]). */
@Immutable
data class PendingApproval(
    val approvalId: String,
    val toolCallId: String?,
    val toolName: String,
    /** `inputJson` parsed (it is a JSON string on the wire, docs/08), else `input`. */
    val input: JsonElement?,
    val iteration: Int?,
    val agentId: String?,
    val conversationId: String?,
    val policy: JsonObject?,
    val requestedAt: Long,
    val sequence: Long?,
)

/** What an open question answers: the agent's `ask_question`, or the run's mistake-limit decision (G2). */
enum class QuestionKind { AskQuestion, MistakeLimit }

/**
 * `ConsecutiveMistakeLimitContext` (`@cline/shared` agents/types.ts), the payload `context` of
 * a `mistake_limit.decide` request. The sheet builds the desktop's question from it.
 */
@Immutable
data class MistakeLimitContext(
    val iteration: Int?,
    val consecutiveMistakes: Int?,
    val maxConsecutiveMistakes: Int?,
    /** `api_error` | `invalid_tool_call` | `tool_execution_failed`. */
    val reason: String?,
    val details: String?,
)

/**
 * An open `capability.requested` targeted at this client that the user answers: a
 * `tool_executor.askQuestion` ([QuestionKind.AskQuestion]) or a `mistake_limit.decide`
 * ([QuestionKind.MistakeLimit], with [mistakeLimit] and the two desktop options, the question
 * text built by the sheet from string resources).
 */
@Immutable
data class PendingQuestion(
    val requestId: String,
    val question: String,
    /** `args[1]`, distinct, order kept (composer-approvals.md C4.6). */
    val options: List<String>,
    val agentId: String?,
    val iteration: Int?,
    val requestedAt: Long,
    val kind: QuestionKind = QuestionKind.AskQuestion,
    val mistakeLimit: MistakeLimitContext? = null,
)

/** A `session.pending_prompts` entry (composer-approvals.md C2). */
@Immutable
data class QueuedPrompt(
    val id: String,
    val prompt: String,
    /** `queue` | `steer`. */
    val delivery: String,
    val attachmentCount: Int,
) {
    val steer: Boolean get() = delivery == "steer"
}

/** One entry of `snapshot.checkpoint.history` (changes-tools-checkpoints.md C.4). */
@Immutable
data class CheckpointEntry(
    val ref: String,
    val createdAt: Long,
    val runCount: Int,
    /** `stash` | `commit` when known. */
    val kind: String? = null,
)

enum class OutputStream { Stdout, Stderr }

@Immutable
data class OutputChunk(val stream: OutputStream, val text: String)

/**
 * One streamed command execution inside a `run_commands` tool call (CT §1.4). [chunks] is the
 * interleaved arrival-order buffer, head-capped at [MAX_CHARS] (the detail screen's cap).
 *
 * The buffer is append-only (`SessionFactsReducer.appendChunk`): [chunks] is a persistent list
 * of pieces of at most [PIECE_CHARS] characters that later chunks never copy, and [length] is
 * the running total, so an append costs the new text plus one bounded piece, not the output so
 * far. The reducer runs on the single `kernelDispatcher` (docs/07 §8) that every session shares.
 */
@Immutable
data class CommandBuffer(
    val executionId: String,
    val commandIndex: Int,
    val query: String,
    val chunks: List<OutputChunk> = persistentListOf(),
    /** Total characters held in [chunks]; always passed together with a new [chunks]. */
    val length: Int = chunks.sumOf { it.text.length },
    /** Characters dropped by the app-side head cap. */
    val headDroppedChars: Long = 0,
    /** Sum of `update.droppedChars` (kernel-side coalescing drops). */
    val kernelDroppedChars: Long = 0,
    val truncated: Boolean = false,
    val detachable: Boolean = false,
    /** The first chunk seen was not the empty announce: the replay began mid-stream (CT B.3). */
    val gapped: Boolean = false,
    /** From `tool.finished.output[commandIndex]`. */
    val success: Boolean? = null,
    val result: String? = null,
) {
    fun text(stream: OutputStream? = null): String =
        chunks.filter { stream == null || it.stream == stream }.joinToString("") { it.text }

    companion object {
        const val MAX_CHARS = 1_048_576

        /** The largest piece an append creates or tops up: bounds the characters one chunk copies. */
        const val PIECE_CHARS = 4_096
    }
}

sealed interface ToolStatus {
    data object Running : ToolStatus
    data object AwaitingApproval : ToolStatus
    data class Finished(val error: String?) : ToolStatus {
        val ok: Boolean get() = error == null
    }
    data object Rejected : ToolStatus

    /** The run ended (abort, failure, cancelled approval) while the tool had not finished. */
    data object Cancelled : ToolStatus

    /** The Hub restarted under the run (`run.interrupted`, derived interruption). */
    data object Interrupted : ToolStatus
}

/** A tool call of the current run (feeds `ToolCallStore` / `SessionDiffStore`, D1). */
@Immutable
data class ToolRecord(
    val toolCallId: String,
    val toolName: String,
    val input: JsonElement?,
    val status: ToolStatus,
    val iteration: Int?,
    val requestId: String?,
    val startedAt: Long,
    val endedAt: Long? = null,
    val output: JsonElement? = null,
    val error: String? = null,
    val executions: List<CommandBuffer> = emptyList(),
    val approvalId: String? = null,
    val durationMs: Long? = null,
)

/**
 * A4: the kernel's run sent a live `run.heartbeat` after a whole heartbeat interval in which the
 * session produced no other event, while nobody owed it an answer and no tool was executing.
 * [since] is the timestamp of the heartbeat that opened this episode (later silent heartbeats keep
 * it), so a "Keep waiting" dismissal is per episode; [requestId] is the heartbeat's run.
 */
@Immutable
data class StallFact(val since: Long, val requestId: String?)

/** The reducer output (docs/07 §4.1). */
@Immutable
data class SessionFacts(
    val sessionId: String,
    val status: SessionStatus = SessionStatus.Unknown,
    val runPhase: RunPhase = RunPhase.Idle,
    val activeRun: RunRef? = null,
    val title: String? = null,
    val pinned: Boolean = false,
    /** Raw `session.metadata` of the latest record (spread on `session.update`, HF). */
    val metadata: JsonObject = JsonObject(emptyMap()),
    val workspaceRoot: String? = null,
    val cwd: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val mode: String = PromptMode.Act.wire,
    val thinking: Boolean? = null,
    val reasoningEffort: String? = null,
    /** `metadata.source` (`android`, `cli`, …); a resume keeps it (HC §1.3.6). */
    val source: String? = null,
    val parentSessionId: String? = null,
    val approvals: Map<String, PendingApproval> = emptyMap(),
    val questions: Map<String, PendingQuestion> = emptyMap(),
    val tools: Map<String, ToolRecord> = emptyMap(),
    val usage: UsageTotals? = null,
    val aggregateUsage: UsageTotals? = null,
    val currentIteration: Int? = null,
    val checkpointHistory: List<CheckpointEntry> = emptyList(),
    val pendingPrompts: List<QueuedPrompt> = emptyList(),
    /** `pending_prompts_unavailable` on this connection: busy sends go out directly (CA C2.6). */
    val queueUnavailable: Boolean = false,
    /** requestIds sent with a `delivery` or announced by `run.enqueued`: their `run.started` opens nothing. */
    val queuedRequestIds: Set<String> = emptySet(),
    /** `snapshot.endedAt` of the latest snapshot (shutdown projections, docs/08). */
    val endedAt: Long? = null,
    val lastSequence: Long = 0,
    val lastEventAt: Long? = null,
    /** Timestamp of the last event of this session other than `run.heartbeat` (A4). */
    val lastActivityAt: Long? = null,
    /** Timestamp of the current run's last live `run.heartbeat`; null between runs (A4). */
    val lastHeartbeatAt: Long? = null,
    /** The run has gone silent (A4 stall notice); cleared by any other event and by the run's end. */
    val stall: StallFact? = null,
    /**
     * The desktop's `mistakeRecovery.continuedThroughIteration` for the current run: after the
     * user chose to continue at the mistake limit, a request whose iteration is at or below it
     * is answered "continue" without asking again (G2). Cleared when a run starts.
     */
    val mistakeContinuedThroughIteration: Int? = null,
) {
    /** Usage the composer ring and details sheet show (HC §3.8: `aggregateUsage` first). */
    val displayUsage: UsageTotals? get() = aggregateUsage ?: usage
    val hasPendingInput: Boolean get() = approvals.isNotEmpty() || questions.isNotEmpty()
}
