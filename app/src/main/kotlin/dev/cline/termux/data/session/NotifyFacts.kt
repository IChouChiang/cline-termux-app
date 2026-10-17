package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunPhase

/**
 * The few facts of one session that `ClineNotifier` and `KeepAlivePolicy` read (docs/07 §4.7,
 * customize-and-rest.md §11.3 and §12.2), projected from its [SessionState] by
 * [SessionStore.notifyFacts]. Watching these instead of the session's state flow keeps the
 * transcript, images and tool output out of the long-lived notification observers, and lets
 * the store evict the session (docs/07 §4.1).
 *
 * Fields that move with every streamed event are left out or reduced, so a running turn's
 * deltas do not republish the map: [eventAt] is the last event time only while no run is
 * active (the terminal time a result notification shows, or the "working" start before
 * `run.started`), and [sequenced] says only whether any event was applied.
 */
@Immutable
data class NotifyFacts(
    val sessionId: String,
    val status: SessionStatus = SessionStatus.Unknown,
    val runPhase: RunPhase = RunPhase.Idle,
    val activeRun: RunRef? = null,
    /** Kernel title, untrimmed; null when untitled. */
    val title: String? = null,
    val approvals: Map<String, PendingApproval> = emptyMap(),
    val questions: Map<String, PendingQuestion> = emptyMap(),
    /** `session.pending_prompts` is not empty (completion is suppressed, SP S3.3). */
    val hasQueuedPrompts: Boolean = false,
    /** At least one event was applied (`lastSequence > 0`); a record seeded without events never notifies. */
    val sequenced: Boolean = false,
    /** `lastEventAt` while [activeRun] is null, else null. */
    val eventAt: Long? = null,
    /** The `run.failed` error text on the error row of the current failure, when the session failed. */
    val failureDetail: String? = null,
) {
    val hasPendingInput: Boolean get() = approvals.isNotEmpty() || questions.isNotEmpty()

    companion object {
        fun of(state: SessionState): NotifyFacts {
            val f = state.facts
            val eventAt = if (f.activeRun == null) f.lastEventAt else null
            val failed = f.activeRun == null && (f.status == SessionStatus.Failed || f.status == SessionStatus.Error)
            return NotifyFacts(
                sessionId = state.sessionId,
                status = f.status,
                runPhase = f.runPhase,
                activeRun = f.activeRun,
                title = f.title,
                approvals = f.approvals,
                questions = f.questions,
                hasQueuedPrompts = f.pendingPrompts.isNotEmpty(),
                sequenced = f.lastSequence > 0,
                eventAt = eventAt,
                failureDetail = if (failed) failureDetail(state, eventAt ?: 0L) else null,
            )
        }

        /** The error row the projector added for this failure (created at or after [at]), trimmed. */
        private fun failureDetail(state: SessionState, at: Long): String? =
            state.live.lastOrNull { it.role == Role.Error && it.createdAt >= at }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
