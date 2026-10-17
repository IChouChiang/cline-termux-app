package dev.cline.termux.data.chat

import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionStatus

/**
 * `ChatStatus` (home-chat.md §1.4, port of the desktop `ChatSessionStatus`) from the reducer's
 * facts. The visual mapping (dot, icon, label) is only `SessionVisual.of` (DS §1.6).
 *
 * | Source | ChatStatus |
 * | `session.create` in flight, or a direct send without its `run.started` | Starting |
 * | `running` / `pending` | Running |
 * | `run.abort` sent, `run.aborted` not yet received | Stopping |
 * | `completed` / `run.completed{completed}` | Completed |
 * | `aborted` / `cancelled` / `run.aborted` | Cancelled |
 * | `failed` / `run.failed` | Failed |
 * | `idle` | Idle |
 */
object ChatStatusDerivation {
    fun of(facts: SessionFacts?, creating: Boolean = false): ChatStatus {
        if (creating) return ChatStatus.Starting
        if (facts == null) return ChatStatus.Idle
        return when (facts.runPhase) {
            RunPhase.Starting -> ChatStatus.Starting
            RunPhase.Stopping -> ChatStatus.Stopping
            // `pending` maps to Running for the transcript; the dot shows "Approval required" (§1.3.1).
            RunPhase.Running, RunPhase.AwaitingUser -> ChatStatus.Running
            RunPhase.Idle, RunPhase.NoSession -> fromStatus(facts.status)
        }
    }

    fun fromStatus(status: SessionStatus): ChatStatus = when (status) {
        SessionStatus.Starting -> ChatStatus.Starting
        SessionStatus.Running, SessionStatus.Pending -> ChatStatus.Running
        SessionStatus.Stopping -> ChatStatus.Stopping
        SessionStatus.Completed -> ChatStatus.Completed
        SessionStatus.Cancelled -> ChatStatus.Cancelled
        SessionStatus.Failed, SessionStatus.Error -> ChatStatus.Failed
        SessionStatus.Idle, SessionStatus.Unknown -> ChatStatus.Idle
    }
}

/** Shorthand for [ChatStatusDerivation.of]. */
fun SessionFacts.chatStatus(creating: Boolean = false): ChatStatus = ChatStatusDerivation.of(this, creating)
