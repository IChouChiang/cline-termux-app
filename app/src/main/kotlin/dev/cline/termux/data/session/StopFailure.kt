package dev.cline.termux.data.session

import dev.cline.termux.data.chat.RunPhase

/**
 * A Stop that did not take (trial row 11): its `run.abort` failed, or no terminal `run.*` event
 * came within the Hub's command timeout (A4). It is about the [run] that was going at that moment
 * (null: a run this client never saw start, such as one opened mid-run), so "The agent did not
 * stop. Try again." stands only while that run does. The composer's and the approval sheet's Stop
 * share this rule.
 */
data class StopFailure(val run: RunRef?) {
    /**
     * The run Stop could not end is over: nothing of the session is left to stop (the composer's
     * own Stop rule, [RunPhase.canAbort] with the session's [runningAgents]), or another run has
     * taken its place, which also covers a run that ended and a queued prompt that started between
     * two readings of the session.
     */
    fun isOver(phase: RunPhase, activeRun: RunRef?, runningAgents: Int = 0): Boolean =
        !phase.canAbort(runningAgents) || (activeRun != null && activeRun != run)

    companion object {
        /** The failure of a Stop of the run [facts] show now. */
        fun of(facts: SessionFacts): StopFailure = StopFailure(facts.activeRun)
    }
}
