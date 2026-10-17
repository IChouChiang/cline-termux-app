package dev.cline.termux.data.notifications

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.LiveProjection
import dev.cline.termux.data.session.MistakeLimitContext
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.PendingApproval
import dev.cline.termux.data.session.PendingQuestion
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.data.session.QueuedPrompt
import dev.cline.termux.data.session.RunRef
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus

/**
 * Session states as the store would hold them after the corresponding Hub events, and the
 * [NotifyFacts] it publishes for them ([notify], [factsOf]).
 */
object NotificationStates {
    const val T0 = 1_790_000_000_000L

    /** What `SessionStore.notifyFacts` publishes for this state. */
    fun SessionState.notify(): NotifyFacts = NotifyFacts.of(this)

    /** The keyed map the notifier and the keep-alive receive. */
    fun factsOf(vararg states: SessionState): Map<String, NotifyFacts> = states.associate { it.sessionId to it.notify() }

    fun idle(id: String, title: String? = "Fix the login redirect", seq: Long = 1, at: Long = T0) = SessionState(
        sessionId = id,
        facts = SessionFacts(sessionId = id, status = SessionStatus.Idle, runPhase = RunPhase.Idle, title = title, lastSequence = seq, lastEventAt = at),
    )

    /** After `run.started`. */
    fun running(id: String, runId: String = "run-1", startedAt: Long = T0, title: String? = "Fix the login redirect", seq: Long = 2) = SessionState(
        sessionId = id,
        facts = SessionFacts(
            sessionId = id,
            status = SessionStatus.Running,
            runPhase = RunPhase.Running,
            activeRun = RunRef(requestId = "req-$runId", runId = runId, startedAt = startedAt, clientId = "android-test", ours = true),
            title = title,
            lastSequence = seq,
            lastEventAt = startedAt,
        ),
    )

    fun SessionState.withApproval(approvalId: String, toolName: String = "run_commands", at: Long = T0): SessionState = copy(
        facts = facts.copy(
            runPhase = RunPhase.AwaitingUser,
            approvals = facts.approvals + (approvalId to approval(approvalId, toolName, at)),
            lastSequence = facts.lastSequence + 1,
            lastEventAt = at,
        ),
    )

    fun SessionState.withoutApproval(approvalId: String): SessionState = copy(
        facts = facts.copy(approvals = facts.approvals - approvalId, runPhase = RunPhase.Running, lastSequence = facts.lastSequence + 1),
    )

    fun SessionState.withQuestion(requestId: String, question: String, at: Long = T0): SessionState = copy(
        facts = facts.copy(
            runPhase = RunPhase.AwaitingUser,
            questions = facts.questions + (requestId to PendingQuestion(requestId, question, listOf("A", "B"), null, 1, at)),
            lastSequence = facts.lastSequence + 1,
            lastEventAt = at,
        ),
    )

    /** A `mistake_limit.decide` request as the reducer keeps it (G2): no question text, the request's context. */
    fun SessionState.withMistakeLimit(requestId: String, details: String?, at: Long = T0): SessionState {
        val question = PendingQuestion(
            requestId = requestId,
            question = "",
            options = MistakeLimitPrompt.OPTIONS,
            agentId = null,
            iteration = 3,
            requestedAt = at,
            kind = QuestionKind.MistakeLimit,
            mistakeLimit = MistakeLimitContext(3, 6, 6, "tool_execution_failed", details),
        )
        return copy(
            facts = facts.copy(
                runPhase = RunPhase.AwaitingUser,
                questions = facts.questions + (requestId to question),
                lastSequence = facts.lastSequence + 1,
                lastEventAt = at,
            ),
        )
    }

    fun SessionState.withoutQuestion(requestId: String): SessionState = copy(
        facts = facts.copy(questions = facts.questions - requestId, runPhase = RunPhase.Running, lastSequence = facts.lastSequence + 1),
    )

    fun SessionState.withQueued(count: Int): SessionState = copy(
        facts = facts.copy(pendingPrompts = (1..count).map { QueuedPrompt("p$it", "next $it", "queue", 0) }),
    )

    fun SessionState.withPhase(phase: RunPhase): SessionState = copy(facts = facts.copy(runPhase = phase))

    /** After `run.completed {reason}` / `run.failed` / `run.aborted` at [at]. */
    fun SessionState.ended(status: SessionStatus, at: Long, error: String? = null): SessionState = copy(
        facts = facts.copy(
            status = status,
            runPhase = RunPhase.Idle,
            activeRun = null,
            approvals = emptyMap(),
            questions = emptyMap(),
            lastSequence = facts.lastSequence + 1,
            lastEventAt = at,
        ),
        projection = if (error == null) projection else LiveProjection(
            messages = projection.messages + ChatMessage(id = "err:${facts.lastSequence}", sessionId = sessionId, role = Role.Error, content = error, createdAt = at),
        ),
    )

    fun approval(approvalId: String, toolName: String, at: Long) = PendingApproval(
        approvalId = approvalId,
        toolCallId = "call-$approvalId",
        toolName = toolName,
        input = null,
        iteration = 1,
        agentId = null,
        conversationId = null,
        policy = null,
        requestedAt = at,
        sequence = null,
    )
}
