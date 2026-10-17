package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents

/**
 * Client-side interruption derivation (composer-approvals.md §0.2, home-chat.md §1.3.4).
 * The Hub publishes `run.interrupted` only for `run.enqueue` runs, so the app's own
 * `session.send_input` turns are marked interrupted when their record is open and either
 * (a) the reconnect found a new daemon lifetime, evaluated once the replay has drained to
 * `hub.status.eventLog.lastSequence`, or (b) the stream carries the session's shutdown
 * projection. Pure: `SessionStore` applies the verdicts.
 */
object InterruptionDetector {

    /**
     * (b): `run.completed {reason:"disposed"}`, or `session.updated` with `snapshot.status`
     * cancelled/completed and `endedAt` set. A non-interactive session (`snapshot.interactive:
     * false`, a one-shot `cline "<prompt>"` or a scheduled run) ends itself after its run, and
     * the Hub publishes that ending `session.updated` *before* the turn's `run.completed
     * {reason:"completed"}`, so for those the update is the normal end, not a disposal; a
     * restart under them is still caught by (a) and by `run.interrupted`.
     */
    fun isShutdownProjection(event: HubEventEnvelope): Boolean {
        val p = event.payload ?: return false
        return when (event.event) {
            HubEvents.RUN_COMPLETED -> p.str("reason") == "disposed"
            HubEvents.SESSION_UPDATED -> {
                val snapshot = p.obj("snapshot") ?: return false
                if (snapshot.bool("interactive") == false) return false
                val status = SessionStatus.parse(snapshot.str("status"))
                (status == SessionStatus.Cancelled || status == SessionStatus.Completed) && snapshot["endedAt"].epochMillis() != null
            }
            else -> false
        }
    }

    /** (a): open turns taken by another daemon lifetime than the one now connected. Unknown keys are not judged. */
    fun interruptedByRestart(openTurns: List<TurnRecord>, currentLifetimeKey: String?): List<TurnRecord> {
        if (currentLifetimeKey == null) return emptyList()
        return openTurns.filter { it.open && it.hubLifetimeKey != null && it.hubLifetimeKey != currentLifetimeKey }
    }

    /**
     * Our direct sends that never produced a `run.started` although the same daemon is still up
     * and the replay has drained: the frame never reached the Hub (socket dropped while
     * writing). The event stream is the truth (HC §3.12 step 7), so these are returned to the
     * composer instead of being retried.
     */
    fun notDelivered(openTurns: List<TurnRecord>, currentLifetimeKey: String?): List<TurnRecord> {
        if (currentLifetimeKey == null) return emptyList()
        return openTurns.filter { it.open && it.ours && !it.started && it.hubLifetimeKey == currentLifetimeKey }
    }

    /**
     * The turn is judged by [interruptedByRestart] or [notDelivered] after a reconnect, so a
     * `session.get` verification (a replay gap) must leave it to them: it ends as interrupted
     * or returns to the composer, never as a plain "ended" run.
     */
    fun judgedOnReconnect(turn: TurnRecord, currentLifetimeKey: String?): Boolean =
        interruptedByRestart(listOf(turn), currentLifetimeKey).isNotEmpty() || notDelivered(listOf(turn), currentLifetimeKey).isNotEmpty()
}
