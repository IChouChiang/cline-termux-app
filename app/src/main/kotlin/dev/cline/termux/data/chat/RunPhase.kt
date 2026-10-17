package dev.cline.termux.data.chat

/**
 * The one run-phase derivation (composer-approvals.md §0.2): desktop `ChatSessionStatus`
 * mapped onto Hub truth. `SessionFactsReducer` maintains it from events and the store's own
 * actions; no composable computes it.
 */
enum class RunPhase {
    /** Home (welcome variant): no session id yet. */
    NoSession,
    Idle,

    /** A direct send (or the first send's `session.create`) is out and its `run.started` has not arrived. */
    Starting,
    Running,

    /** `snapshot.status == "pending"`, or an open approval / question for this session. */
    AwaitingUser,

    /** Stop pressed and `run.abort` sent; left on the terminal `run.*` event. */
    Stopping,
    ;

    /** Desktop `isBusy`. */
    val isBusy: Boolean get() = this == Starting || this == Running || this == AwaitingUser || this == Stopping

    /** Desktop `canAbort`; [runningAgents] comes from `team.progress` (agents roster). */
    fun canAbort(runningAgents: Int = 0): Boolean =
        this == Running || this == AwaitingUser || this == Stopping || runningAgents > 0
}
